@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package org.treblereel.mcp.kotlin

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelineArtifact
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.toRegularClassSymbol
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.util.PerformanceManagerImpl

/** The K2 compiler API as of Kotlin 2.4.21, the only part of the analyzer that differs per worker module. */
internal object K2Compat {
    const val LANGUAGE_VERSION = "2.4"
    const val COMPILER_VERSION = "2.4.21"

    fun configure(arguments: K2JVMCompilerArguments, disposable: Disposable, messages: GroupingMessageCollector): ConfigurationPipelineArtifact? =
        JvmConfigurationPipelinePhase.executePhase(ArgumentsPipelineArtifact(
            arguments, Services.EMPTY, disposable, messages, PerformanceManagerImpl(JvmPlatforms.defaultJvmPlatform, "quill")))

    fun errorDiagnostics(configuration: ConfigurationPipelineArtifact): List<String> =
        configuration.configuration.diagnosticsCollector.diagnostics
            .filter { it.severity == org.jetbrains.kotlin.diagnostics.Severity.ERROR }.map { it.renderMessage() }

    fun analyzedFiles(frontend: JvmFrontendPipelineArtifact): List<Pair<List<FirFile>, FirSession>> =
        frontend.frontendOutput.outputs.map { it.fir to it.session }

    fun qualifierSymbol(qualifier: FirResolvedQualifier): FirBasedSymbol<*>? = qualifier.qualifierSymbol

    fun classSymbol(type: FirResolvedTypeRef, session: FirSession): FirBasedSymbol<*>? = type.toRegularClassSymbol(session)

    fun isLocal(symbol: FirBasedSymbol<*>, declaration: FirDeclaration): Boolean =
        declaration is FirValueParameter || declaration.isLocal
}
