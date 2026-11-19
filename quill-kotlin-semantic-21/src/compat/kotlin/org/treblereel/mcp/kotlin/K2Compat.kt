@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package org.treblereel.mcp.kotlin

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelineArtifact
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.coneType

/** The K2 compiler API as of Kotlin 2.1.20, the only part of the analyzer that differs per worker module. */
internal object K2Compat {
    const val LANGUAGE_VERSION = "2.1"
    const val COMPILER_VERSION = "2.1.20"

    fun configure(arguments: K2JVMCompilerArguments, disposable: Disposable, messages: GroupingMessageCollector): ConfigurationPipelineArtifact? =
        JvmConfigurationPipelinePhase.executePhase(ArgumentsPipelineArtifact(
            arguments, Services.EMPTY, disposable, messages, K2JVMCompiler.K2JVMCompilerPerformanceManager()))

    fun errorDiagnostics(configuration: ConfigurationPipelineArtifact): List<String> =
        configuration.diagnosticCollector.diagnostics
            .filter { it.severity == org.jetbrains.kotlin.diagnostics.Severity.ERROR }.map { it.toString() }

    fun analyzedFiles(frontend: JvmFrontendPipelineArtifact): List<Pair<List<FirFile>, FirSession>> =
        frontend.result.outputs.map { it.fir to it.session }

    fun qualifierSymbol(qualifier: FirResolvedQualifier): FirBasedSymbol<*>? = qualifier.symbol

    fun classSymbol(type: FirResolvedTypeRef, session: FirSession): FirBasedSymbol<*>? = type.coneType.toClassLikeSymbol(session)

    fun isLocal(symbol: FirBasedSymbol<*>, declaration: FirDeclaration): Boolean =
        symbol is FirCallableSymbol<*> && symbol.callableId.isLocal
            || declaration is FirValueParameter
            || declaration is FirSimpleFunction && declaration.isLocal
            || declaration is FirClassLikeDeclaration && declaration.isLocal
}
