@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

import com.intellij.mock.MockProject
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiManagerImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinGlobalSourceOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageProviderFactory
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.cli.common.localfs.KotlinLocalFileSystem
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.idea.references.mainReference
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Experiment only. No PSI/symbols escape this process; analysis results are strings. */
fun main(args: Array<String>) {
    val cases = Json.parseToJsonElement(Files.readString(Path.of(args.single()))).jsonArray
    val root = Files.createTempDirectory("quill-analysis-probe-")
    val stdlib = Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI())
    val disposable = Disposer.newDisposable()
    val results = mutableListOf<JsonObject>()
    var applicationStarted = false
    fun sources(case: JsonObject) = case.getValue("sources").jsonObject.mapValues { it.value.jsonPrimitive.content }
    fun writeSnapshot(snapshot: Map<String, String>, restoreMtime: Boolean) {
        Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.forEach {
            if (root.relativize(it).toString() !in snapshot) Files.delete(it)
        } }
        for ((name, text) in snapshot) {
            val path = root.resolve(name).normalize()
            require(path.startsWith(root) && name.endsWith(".kt"))
            Files.createDirectories(path.parent)
            val time = if (Files.exists(path)) Files.getLastModifiedTime(path) else null
            Files.writeString(path, text)
            if (restoreMtime && time != null) Files.setLastModifiedTime(path, time)
        }
    }
    try {
        writeSnapshot(sources(cases.first().jsonObject), false)
        val started = System.nanoTime()
        applicationStarted = true
        val session = buildStandaloneAnalysisAPISession(disposable) {
            registerProjectExtensionPoint(com.intellij.openapi.extensions.ExtensionPointName.create("com.intellij.psi.treeChangeListener"), com.intellij.psi.PsiTreeChangeListener::class.java)
            val sourceScope = object : GlobalSearchScope(project) {
                override fun contains(file: VirtualFile) = Path.of(file.path).normalize().startsWith(root) && file.extension == "kt"
                override fun isSearchInModuleContent(module: Module) = true
                override fun isSearchInLibraries() = false
            }
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                val library = addModule(buildKtLibraryModule {
                    libraryName = "stdlib"; platform = JvmPlatforms.defaultJvmPlatform
                    addBinaryRoot(stdlib)
                })
                addModule(buildKtSourceModule {
                    moduleName = "probe"; platform = JvmPlatforms.defaultJvmPlatform
                    addSourceRoot(root); contentScope = sourceScope; addRegularDependency(library)
                })
            }
        }
        val setupMs = (System.nanoTime() - started) / 1_000_000.0
        val files = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
            .associateBy { root.relativize(Path.of(it.virtualFile.path)).toString() }.toMutableMap()
        val project = session.project as MockProject
        val manager = PsiManager.getInstance(project) as PsiManagerImpl
        fun <T : Any> replaceService(type: Class<T>, value: T) {
            project.picoContainer.unregisterComponent(type.name)
            project.registerService(type, value)
        }
        for (element in cases) {
            val case = element.jsonObject
            val snapshot = sources(case)
            writeSnapshot(snapshot, case["restore_mtime"]?.jsonPrimitive?.boolean ?: false)
            val begin = System.nanoTime()
            var refreshMs = 0.0
            var changedFiles = 0
            var actual = "unresolved"
            var callable = "unresolved"
            var declarationFile: String? = null
            var parameters = emptyList<String>()
            var psiMatches = false
            var failure: String? = null
            try {
                if (case["mode"]?.jsonPrimitive?.content != "disk_only") session.application.runWriteAction {
                    // Standalone local VFS refresh/reload can retain old text even when timestamps change.
                    // Replace only changed/deleted PSI views, then rebuild the immutable provider indexes.
                    for ((name, file) in files.toMap()) if (name !in snapshot) {
                        manager.fileManager.setViewProvider(file.virtualFile, null)
                        files.remove(name); changedFiles++
                    }
                    for ((name, text) in snapshot) if (files[name]?.text != text) {
                        val virtualFile = KotlinLocalFileSystem().findFileByPath(root.resolve(name).toString())!!
                        manager.fileManager.setViewProvider(virtualFile, null)
                        files[name] = manager.findFile(virtualFile) as KtFile
                        changedFiles++
                    }
                    if (changedFiles > 0) {
                        val current = files.values.toList()
                        replaceService(KotlinDeclarationProviderFactory::class.java,
                            KotlinStandaloneDeclarationProviderFactory(project, session.coreApplicationEnvironment, current))
                        replaceService(KotlinPackageProviderFactory::class.java,
                            KotlinStandalonePackageProviderFactory(project, current, emptyList()))
                        replaceService(KotlinAnnotationsResolverFactory::class.java,
                            KotlinStandaloneAnnotationsResolverFactory(project, current))
                        project.messageBus.syncPublisher(KotlinModificationEvent.TOPIC)
                            .onModification(KotlinGlobalSourceOutOfBlockModificationEvent)
                    }
                }
                refreshMs = (System.nanoTime() - begin) / 1_000_000.0
                psiMatches = files.keys == snapshot.keys && snapshot.all { (name, text) -> files[name]?.text == text }
                val file = files.getValue(case.getValue("query_file").jsonPrimitive.content)
                analyze(file) {
                    val offset = file.text.indexOf("/*query*/") + "/*query*/".length
                    val reference = PsiTreeUtil.collectElementsOfType(file, KtNameReferenceExpression::class.java)
                        .single { it.textRange.startOffset == offset }
                    val symbol = reference.mainReference.resolveToSymbol() as? KaCallableSymbol
                    actual = (symbol?.returnType as? KaClassType)?.classId?.asString() ?: "unresolved"
                    callable = symbol?.callableId?.toString() ?: "unresolved"
                    declarationFile = symbol?.psi?.containingFile?.virtualFile?.path?.let { root.relativize(Path.of(it)).toString() }
                    parameters = (symbol as? KaFunctionSymbol)?.valueParameters?.map {
                        (it.returnType as? KaClassType)?.classId?.asString() ?: "non_class_type"
                    } ?: emptyList()
                }
            } catch (error: Exception) {
                actual = "error:${error.javaClass.simpleName}"
                failure = error.stackTraceToString().take(6000).replace(root.toString(), "<probe>")
            }
            val expected = case.getValue("expected_type").jsonPrimitive.content
            val expectedParams = case.getValue("expected_parameters").jsonArray.map { it.jsonPrimitive.content }
            val negative = case["mode"]?.jsonPrimitive?.content == "disk_only"
            results.add(buildJsonObject {
                put("case", case.getValue("case")); put("expected", expected); put("actual", actual)
                put("callable", callable); if (declarationFile != null) put("declaration_file", declarationFile); put("parameters", JsonArray(parameters.map(::JsonPrimitive)))
                put("expected_parameters", JsonArray(expectedParams.map(::JsonPrimitive)))
                put("correct", expected == actual && parameters == expectedParams && psiMatches)
                put("negative_control", negative); put("psi_matches_disk", psiMatches)
                put("changed_psi_files", changedFiles); put("refresh_ms", refreshMs)
                put("query_ms", (System.nanoTime() - begin) / 1_000_000.0 - refreshMs)
                if (failure != null) put("failure", failure)
            })
        }
        println(buildJsonObject {
            put("java", System.getProperty("java.version")); put("compiler", "2.4.21")
            put("session_setup_ms", setupMs); put("cases", JsonArray(results))
            put("production_enabled", false); put("session_builds", 1)
            put("invalidation", "global_out_of_block; immutable declaration/package/annotation indexes rebuilt after edits")
        })
    } finally {
        Disposer.dispose(disposable)
        if (applicationStarted) disposeGlobalStandaloneApplicationServices()
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
