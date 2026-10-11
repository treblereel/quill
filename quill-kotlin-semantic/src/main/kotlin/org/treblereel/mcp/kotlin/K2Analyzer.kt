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

    @JvmStatic fun analyze(request: JsonNode): ObjectNode {
        val disposable = Disposer.newDisposable()
        try {
            val messages = GroupingMessageCollector(MessageCollector.NONE, false, false)
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = request.path("sources").map { it.asText() } + request.path("java_roots").map { it.asText() }
                classpath = request.path("classpath").joinToString(File.pathSeparator) { it.asText() }
                moduleName = "quill-semantic"
                jdkHome = System.getProperty("java.home")
                noStdlib = true; noReflect = true
                languageVersion = "2.4"; apiVersion = "2.4"
                disableDefaultScriptingPlugin = true
                useFirLT = false
            }
            val configuration = JvmConfigurationPipelinePhase.executePhase(ArgumentsPipelineArtifact(
                arguments, Services.EMPTY, disposable, messages, PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "quill")))
            if (messages.hasErrors()) return response("analysis_error")
            val frontend = JvmFrontendPipelinePhase.executePhase(configuration) ?: return response("analysis_error")
            if (configuration.configuration.diagnosticsCollector.hasErrors || messages.hasErrors()) return response("analysis_error")
            val outputs = frontend.frontendOutput.outputs
            val files = outputs.flatMap { it.fir }
            val file = files.singleOrNull { File(it.sourceFile?.path ?: "").absoluteFile.normalize().path == request.path("file").asText() }
                ?: return response("unavailable")
            val offset = request.path("offset").asInt(-1)
            val text = File(request.path("file").asText()).readText()
            if (offset !in text.indices) return response("unresolved")
            val psi = (file.sourceFile as? KtPsiSourceFile)?.psiFile as? KtFile ?: return response("unavailable")
            val reference = PsiTreeUtil.getParentOfType(psi.findElementAt(offset), KtNameReferenceExpression::class.java, false)
                ?: return response("unresolved")
            val session = outputs.first { file in it.fir }.session
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
        } finally { Disposer.dispose(disposable) }
    }
}
