@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class, org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package org.treblereel.mcp.kotlin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmTypeMapper
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.java.findJvmNameValue
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
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
import org.jetbrains.kotlin.util.PerformanceManagerImpl

/** Runs the compiler's K2 frontend only. FIR never reaches IR/code generation. */
object K2Analyzer {
    private val json = ObjectMapper()
    @JvmStatic fun response(status: String): ObjectNode = json.createObjectNode().apply {
        put("status", status); put("backend", "kotlin_k2_fir")
        put("language_version", "2.4"); put("compiler_version", "2.4.21")
    }

    private data class AnalyzedFile(val file: FirFile, val session: FirSession)
    private data class Snapshot(val inputs: CompilerInputs, val disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable?,
        val files: Map<String, AnalyzedFile>, val status: String?, val generation: Long)
    private var snapshot: Snapshot? = null
    private var generation = 0L

    @JvmStatic @Synchronized fun clear() {
        val previous = snapshot
        snapshot = null
        previous?.disposable?.let { Disposer.dispose(it) }
    }

    @JvmStatic @Synchronized fun analyze(request: JsonNode): ObjectNode {
        try {
            val inputs = CompilerInputs.capture(request)
            val reused = snapshot?.inputs?.fingerprint() == inputs.fingerprint()
            if (!reused) {
                clear()
                snapshot = build(request, inputs)
            }
            val current = snapshot!!
            val result = current.status?.let { response(it) } ?: resolve(current, request)
            // Source/classpath edits racing a frontend run or lookup cannot receive high confidence.
            if (CompilerInputs.capture(request).fingerprint() != inputs.fingerprint()) {
                clear()
                return response("inputs_changed")
            }
            return result.put("analysis_reused", reused).put("analysis_generation", current.generation)
        } catch (failure: Exception) {
            clear()
            throw failure
        }
    }

    private fun build(request: JsonNode, inputs: CompilerInputs): Snapshot {
        val disposable = Disposer.newDisposable()
        var retained = false
        val revision = ++generation
        try {
            val messages = GroupingMessageCollector(MessageCollector.NONE, false, false)
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = request.path("sources").map { it.asText() } + request.path("java_roots").map { it.asText() }
                classpath = request.path("classpath").joinToString(File.pathSeparator) { it.asText() }
                moduleName = "quill-semantic"
                jdkHome = inputs.jdkHome()
                kotlinHome = inputs.moduleHome()
                noStdlib = true; noReflect = true
                languageVersion = "2.4"; apiVersion = "2.4"
                disableDefaultScriptingPlugin = true
                useFirLT = false
            }
            val configuration = JvmConfigurationPipelinePhase.executePhase(ArgumentsPipelineArtifact(
                arguments, Services.EMPTY, disposable, messages, PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "quill")))
            fun invalid() = Snapshot(inputs, null, emptyMap(), "analysis_error", revision)
            if (messages.hasErrors()) return invalid()
            val frontend = JvmFrontendPipelinePhase.executePhase(configuration) ?: return invalid()
            if (configuration.configuration.diagnosticsCollector.hasErrors || messages.hasErrors()) return invalid()
            val files = frontend.frontendOutput.outputs.flatMap { output -> output.fir.map { file ->
                File(file.sourceFile?.path ?: "").absoluteFile.normalize().path to AnalyzedFile(file, output.session)
            } }.toMap()
            retained = true
            return Snapshot(inputs, disposable, files, null, revision)
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
                    is FirResolvedQualifier -> element.qualifierSymbol
                    is FirResolvedTypeRef -> element.toRegularClassSymbol(session)
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
        if (declaration is FirValueParameter || declaration.isLocal) return response("local")
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
            result.put("source_line", 1 + sourceFile.text.take(declaration.source!!.startOffset).count { it == '\n' })
        }
        return result
    }
}
