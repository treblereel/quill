@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class, org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package org.treblereel.mcp.kotlin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.arguments.validateArguments
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmTypeMapper
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.java.findJvmNameValue
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.KtPsiSourceElement
import org.jetbrains.kotlin.KtPsiSourceFile
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms

/** Runs the compiler's K2 frontend only. FIR never reaches IR/code generation. */
object K2Analyzer {
    private val json = ObjectMapper()
    @JvmStatic fun response(status: String): ObjectNode = json.createObjectNode().apply {
        put("status", status); put("backend", "kotlin_k2_fir")
        put("language_version", "2.1"); put("compiler_version", "2.1.20")
    }

    private data class AnalyzedFile(val file: FirFile, val session: FirSession)
    private data class Snapshot(val inputs: CompilerInputs, val disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable?,
        val files: Map<String, AnalyzedFile>, val status: String?, val generation: Long, val language: String, val diagnostics: List<String> = emptyList())
    private data class CacheKey(val module: String, val sourceSet: String)
    private val snapshots = SnapshotCache<CacheKey, Snapshot>(2,
        { value -> value.disposable?.let { Disposer.dispose(it) } },
        { Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() >= it.maxMemory() * 3 / 4 } })
    private var generation = 0L

    // Worker-protocol opt-in for benchmarks; timings never enter snapshot identity.
    private class Timings(val enabled: Boolean) {
        private val started = if (enabled) System.nanoTime() else 0L
        private val stages = if (enabled) json.createObjectNode() else null
        fun <T> measure(name: String, action: () -> T): T {
            if (!enabled) return action()
            val start = System.nanoTime()
            try { return action() }
            finally { stages!!.put(name, stages.path(name).asDouble() + (System.nanoTime() - start) / 1_000_000.0) }
        }
        fun finish(result: ObjectNode): ObjectNode {
            stages?.let {
                it.put("total", (System.nanoTime() - started) / 1_000_000.0)
                result.set<ObjectNode>("analysis_timings_ms", it)
            }
            return result
        }
    }

    @JvmStatic @Synchronized fun clear() {
        snapshots.clear()
    }

    @JvmStatic @Synchronized fun analyze(request: JsonNode): ObjectNode {
        val timings = Timings(request.path("profile_analysis").asBoolean(false))
        var key: CacheKey? = null
        try {
            if (request.has("compiler_configuration_error")) return response(request.path("compiler_configuration_error").asText())
            if (request.path("unsupported_plugins").asText().isNotBlank()) return response("compiler_plugins_unavailable")
            if (!request.path("plugin_classpath").isEmpty || !request.path("plugin_options").isEmpty) {
                if (!request.path("allow_compiler_plugins").asBoolean(false)) return response("compiler_plugins_disabled")
                val compilerVersion = request.path("compilerVersion").asText()
                if (compilerVersion != "2.1.20" && !compilerVersion.startsWith("2.1.20-")) return response("compiler_plugin_version_mismatch")
            }
            val inputs = timings.measure("capture_before") { CompilerInputs.capture(request) }
            key = CacheKey(inputs.moduleHome(), inputs.sourceSet())
            val previous = snapshots.get(key)
            val reused = previous?.inputs?.fingerprint() == inputs.fingerprint()
            val current = if (reused) previous!! else {
                timings.measure("dispose") { snapshots.prepare(key) }
                build(request, inputs, timings).also { snapshots.put(key, it) }
            }
            snapshots.pruneExcept(key)
            val result = current.status?.let { response(it).apply { current.diagnostics.forEach { diagnostic -> withArray("diagnostics").add(diagnostic) } } }
                ?: timings.measure("lookup") { resolve(current, request) }
            // Source/classpath edits racing a frontend run or lookup cannot receive high confidence.
            if (timings.measure("capture_after") { CompilerInputs.capture(request) }.fingerprint() != inputs.fingerprint()) {
                timings.measure("dispose") { snapshots.remove(key) }
                return timings.finish(response("inputs_changed"))
            }
            return timings.finish(result.put("language_version", current.language).put("analysis_reused", reused).put("analysis_generation", current.generation)
                .put("snapshot_cache_entries", snapshots.size()).put("snapshot_cache_capacity", 2)
                .put("snapshot_cache_evictions", snapshots.evictions()))
        } catch (changed: CompilerInputs.ChangedException) {
            if (key == null) clear() else snapshots.remove(key)
            return timings.finish(response("inputs_changed"))
        } catch (failure: Exception) {
            if (key == null) clear() else snapshots.remove(key)
            throw failure
        }
    }

    private fun build(request: JsonNode, inputs: CompilerInputs, timings: Timings): Snapshot {
        val disposable = Disposer.newDisposable()
        var retained = false
        val revision = ++generation
        try {
            val errors = mutableListOf<String>()
            val messages = GroupingMessageCollector(object : MessageCollector {
                override fun clear() { errors.clear() }
                override fun hasErrors() = errors.isNotEmpty()
                override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                    if (severity.isError && errors.size < 5) errors.add(message.take(2048))
                }
            }, false, false)
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = request.path("sources").map { it.asText() } + request.path("java_roots").map { it.asText() } + request.path("java_sources").map { it.asText() }
                classpath = request.path("classpath").joinToString(File.pathSeparator) { it.asText() }
                moduleName = "quill-semantic"
                friendPaths = request.path("friend_paths").map { it.asText() }.toTypedArray()
                jdkHome = inputs.jdkHome()
                kotlinHome = inputs.moduleHome()
                noStdlib = true; noReflect = true
                languageVersion = request.path("languageVersion").asText("2.1")
                apiVersion = request.path("apiVersion").asText(languageVersion)
                jvmTarget = request.path("jvmTarget").asText("1.8")
                val extras = request.path("compiler_arguments").map { it.asText() }
                // Build topology and executable/script evaluation options are outside frontend analysis.
                val semanticValues = setOf("-language-version", "-api-version", "-jvm-target", "-opt-in", "-Xjsr305",
                    "-Xjdk-release", "-Xjvm-default", "-jvm-default", "-Xnullability-annotations", "-Xexplicit-api", "-Xannotation-default-target")
                val semanticFlags = setOf("-progressive", "-Xcontext-parameters", "-Xwhen-guards", "-Xmulti-dollar-interpolation",
                    "-Xnested-type-aliases", "-Xconsistent-data-class-copy-visibility", "-Xunrestricted-builder-inference",
                    "-Xtype-enhancement-improvements-strict-mode", "-Xenhance-type-parameter-types-to-def-not-null")
                var index = 0
                while (index < extras.size) {
                    val option = extras[index++]
                    val name = option.substringBefore('=')
                    require(name in semanticFlags || name in semanticValues || option.startsWith("-XXLanguage:"))
                    if (name in semanticValues && '=' !in option) {
                        require(index < extras.size && !extras[index].startsWith("-")); index++
                    }
                }
                val parsed = K2JVMCompilerArguments()
                parseCommandLineArguments(extras, parsed)
                require(validateArguments(parsed.errors) == null && parsed.errors?.unknownExtraFlags.isNullOrEmpty())
                require(parsed.freeArgs.isEmpty() && parsed.destination == null && parsed.classpath == null
                    && parsed.jdkHome == null && parsed.kotlinHome == null && !parsed.script && parsed.expression == null)
                parseCommandLineArguments(extras, this)
                pluginClasspaths = request.path("plugin_classpath").map { it.asText() }.toTypedArray()
                pluginOptions = request.path("plugin_options").map { it.asText() }.toTypedArray()
                disableDefaultScriptingPlugin = freeArgs.none { it.endsWith(".kts") }
                allowAnyScriptsInSourceRoots = !disableDefaultScriptingPlugin
                useFirLT = false
            }
            fun invalid(): Snapshot {
                messages.flush()
                return Snapshot(inputs, null, emptyMap(), "analysis_error", revision, arguments.languageVersion ?: "2.1", errors.toList())
            }
            val configuration = timings.measure("configuration") {
                JvmConfigurationPipelinePhase.executePhase(ArgumentsPipelineArtifact(
                    arguments, Services.EMPTY, disposable, messages, K2JVMCompiler.K2JVMCompilerPerformanceManager()))
            } ?: return invalid()
            if (messages.hasErrors()) return invalid()
            val frontend = timings.measure("frontend") { JvmFrontendPipelinePhase.executePhase(configuration) } ?: return invalid()
            if (configuration.diagnosticCollector.hasErrors || messages.hasErrors()) {
                configuration.diagnosticCollector.diagnostics.filter { it.severity == org.jetbrains.kotlin.diagnostics.Severity.ERROR }
                    .take(5).forEach { errors.add(it.toString().take(2048)) }
                return invalid()
            }
            val files = frontend.result.outputs.flatMap { output -> output.fir.map { file ->
                File(file.sourceFile?.path ?: "").absoluteFile.normalize().path to AnalyzedFile(file, output.session)
            } }.toMap()
            retained = true
            return Snapshot(inputs, disposable, files, null, revision, arguments.languageVersion ?: "2.1")
        } finally { if (!retained) Disposer.dispose(disposable) }
    }

    private fun resolve(snapshot: Snapshot, request: JsonNode): ObjectNode {
        val analyzed = snapshot.files[File(request.path("file").asText()).absoluteFile.normalize().path]
            ?: return response("unavailable")
        val file = analyzed.file
        val offset = request.path("offset").asInt(-1)
        val psi = (file.sourceFile as? KtPsiSourceFile)?.psiFile as? KtFile ?: return response("unavailable")
        if (offset !in psi.text.indices) return response("unresolved")
        val reference = PsiTreeUtil.getParentOfType(psi.findElementAt(offset), KtNameReferenceExpression::class.java, false)
            ?: return response("unresolved")
        val session = analyzed.session
        var target: FirBasedSymbol<*>? = null
        var size = Int.MAX_VALUE
        file.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                val candidate = when (element) {
                    is FirResolvedNamedReference -> element.resolvedSymbol
                    is FirResolvedQualifier -> element.symbol
                    is FirResolvedTypeRef -> element.coneType.toClassLikeSymbol(session)
                    else -> null
                }
                val source = element.source
                if (candidate != null && source != null && reference.textOffset >= source.startOffset
                    && reference.textRange.endOffset <= source.endOffset && source.endOffset - source.startOffset < size
                    && (element is FirResolvedNamedReference || candidate is FirClassLikeSymbol<*> && candidate.name.asString() == reference.getReferencedName())) {
                    size = source.endOffset - source.startOffset; target = candidate
                }
                element.acceptChildren(this)
            }
        })
        val raw = target ?: return response("unresolved")
        val symbol = if (raw is FirCallableSymbol<*>) raw.unwrapFakeOverrides() else raw
        val declaration = symbol.fir
        if (symbol is FirCallableSymbol<*> && symbol.callableId.isLocal
            || declaration is FirValueParameter
            || declaration is FirSimpleFunction && declaration.isLocal
            || declaration is FirClassLikeDeclaration && declaration.isLocal) return response("local")
        val result = response("resolved")
        if (symbol is FirFunctionSymbol<*>) {
            val function = declaration as FirFunction
            val mapper = FirJvmTypeMapper(session)
            fun descriptor(type: FirTypeRef) = mapper.mapType(type.coneType).descriptor
            val signature = buildString {
                append('(')
                function.contextParameters.forEach { append(descriptor(it.returnTypeRef)) }
                function.receiverParameter?.let { append(descriptor(it.typeRef)) }
                function.valueParameters.forEach { append(descriptor(it.returnTypeRef)) }
                if (function.isSuspend) append("Lkotlin/coroutines/Continuation;")
                append(')')
                append(if (function is FirConstructor) "V" else if (function.isSuspend) "Ljava/lang/Object;"
                    else if (function.returnTypeRef.coneType.isUnit && !function.returnTypeRef.coneType.isMarkedNullable) "V"
                    else descriptor(function.returnTypeRef))
            }
            result.put("descriptor", signature)
            result.put("kind", if (function is FirConstructor) "CONSTRUCTOR" else "METHOD")
            result.put("name", function.findJvmNameValue() ?: symbol.name.asString())
        } else if (symbol is FirClassLikeSymbol<*>) {
            result.put("kind", "CLASS"); result.put("name", symbol.name.asString())
        } else if (symbol is FirPropertySymbol) {
            result.put("kind", "PROPERTY"); result.put("name", symbol.name.asString())
        } else return response("unavailable")
        val classId = when (symbol) {
            is FirClassLikeSymbol<*> -> symbol.classId
            is FirCallableSymbol<*> -> symbol.callableId?.classId
            else -> null
        }
        val sourceFile = (declaration.source as? KtPsiSourceElement)?.psi?.containingFile as? KtFile
        if (classId != null) {
            result.put("owner", classId.packageFqName.asString().let { if (it.isEmpty()) "" else "$it." } + classId.relativeClassName.asString().replace('.', '$'))
        } else if (sourceFile != null) result.put("owner", JvmFileClassUtil.getFileClassInternalName(sourceFile).replace('/', '.'))
        else if (symbol is FirCallableSymbol<*> && symbol.containerSource is JvmPackagePartSource) {
            result.put("owner", (symbol.containerSource as JvmPackagePartSource).className.internalName.replace('/', '.'))
        } else return response("unavailable")
        if (sourceFile != null) {
            result.put("source_file", sourceFile.virtualFilePath)
            val source = declaration.source!!
            val text = sourceFile.text
            if (source.startOffset >= 0 && source.endOffset > source.startOffset && source.endOffset <= text.length) {
                result.put("source_line", 1 + text.take(source.startOffset).count { it == '\n' })
                result.put("source_column", source.startOffset - text.lastIndexOf('\n', source.startOffset - 1))
                result.put("source_end_line", 1 + text.take(source.endOffset).count { it == '\n' })
                result.put("source_end_column", source.endOffset - text.lastIndexOf('\n', source.endOffset - 1))
            }
        }
        return result
    }
}
