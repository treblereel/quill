@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinGlobalSourceOutOfBlockModificationEvent
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.idea.references.mainReference
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Experiment only: no symbols escape the analyze scope, no bytecode is generated. */
fun main(args: Array<String>) {
    val root = Files.createTempDirectory("quill-analysis-probe-")
    val source = root.resolve("Call.kt")
    val stdlib = Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI())
    val disposable = Disposer.newDisposable()
    val results = mutableListOf<JsonObject>()
    try {
        Files.writeString(source, "fun pick() = 1000\nfun run() = pick()")
        val started = System.nanoTime()
        val session = buildStandaloneAnalysisAPISession(disposable) {
            registerProjectExtensionPoint(com.intellij.openapi.extensions.ExtensionPointName.create("com.intellij.psi.treeChangeListener"), com.intellij.psi.PsiTreeChangeListener::class.java)
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                val library = addModule(buildKtLibraryModule {
                    libraryName = "stdlib"; platform = JvmPlatforms.defaultJvmPlatform
                    addBinaryRoot(stdlib)
                })
                addModule(buildKtSourceModule {
                    moduleName = "probe"; platform = JvmPlatforms.defaultJvmPlatform
                    addSourceRoot(source); addRegularDependency(library)
                })
            }
        }
        val setupMs = (System.nanoTime() - started) / 1_000_000.0
        val file = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>().single()
        fun query(): String = analyze(file) {
            val reference = PsiTreeUtil.collectElementsOfType(file, KtNameReferenceExpression::class.java).last()
            val symbol = reference.mainReference.resolveToSymbol() as? KaCallableSymbol
            (symbol?.returnType as? KaClassType)?.classId?.asString() ?: "unresolved"
        }
        fun record(label: String, expected: String, refresh: Boolean = false) {
            val begin = System.nanoTime()
            if (refresh) session.application.runWriteAction {
                file.virtualFile.refresh(false, false)
                PsiManager.getInstance(session.project).reloadFromDisk(file)
                session.project.messageBus.syncPublisher(KotlinModificationEvent.TOPIC)
                    .onModification(KotlinGlobalSourceOutOfBlockModificationEvent)
            }
            val actual = try { query() } catch (failure: Exception) { "error:${failure.javaClass.simpleName}" }
            results.add(buildJsonObject {
                put("case", label); put("expected", expected); put("actual", actual)
                put("correct", expected == actual); put("query_ms", (System.nanoTime() - begin) / 1_000_000.0)
            })
        }
        record("cold", "kotlin/Int")
        repeat(20) { record("unchanged", "kotlin/Int") }
        val modified = Files.getLastModifiedTime(source)
        Files.writeString(source, "fun pick() = true\nfun run() = pick()")
        Files.setLastModifiedTime(source, modified)
        record("same_size_restored_mtime_disk_only", "kotlin/Boolean")
        record("explicit_psi_reload_and_global_invalidation", "kotlin/Boolean", true)
        Files.writeString(source, "fun pick() = \"s\"\nfun run() = pick()")
        record("second_edit_with_explicit_invalidation", "kotlin/String", true)
        println(buildJsonObject {
            put("java", System.getProperty("java.version")); put("compiler", "2.4.21")
            put("session_setup_ms", setupMs)
            put("cases", JsonArray(results)); put("production_enabled", false)
        })
    } finally { Disposer.dispose(disposable); disposeGlobalStandaloneApplicationServices(); Files.deleteIfExists(source); Files.deleteIfExists(root) }
}
