package org.treblereel.mcp.kotlin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt;
import org.jetbrains.kotlin.cli.common.config.ContentRootsKt;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar;
import org.jetbrains.kotlin.config.*;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.diagnostics.Severity;
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil;
import org.jetbrains.kotlin.load.kotlin.MethodSignatureMappingKt;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.*;

/** Isolated, analysis-only descriptor frontend. No compiler plugins or build scripts are loaded. */
public final class SemanticWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        var request = JSON.readTree(System.in);
        ObjectNode result;
        try { result = analyze(request); }
        catch (Exception | LinkageError failure) { result = response("unavailable"); }
        JSON.writeValue(System.out, result);
    }

    static ObjectNode analyze(JsonNode request) {
        var config = new CompilerConfiguration();
        FrontendConfigurationKeysKt.setExtensionsStorage(config, new CompilerPluginRegistrar.ExtensionStorage());
        config.put(CommonConfigurationKeys.MODULE_NAME, "quill-semantic");
        config.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        config.put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS,
                new LanguageVersionSettingsImpl(LanguageVersion.KOTLIN_1_9, ApiVersion.KOTLIN_1_9));
        config.put(JVMConfigurationKeys.JDK_HOME, new File(System.getProperty("java.home")));
        for (var source : request.path("sources")) ContentRootsKt.addKotlinSourceRoot(config, source.asText());
        for (var path : request.path("classpath")) JvmContentRootsKt.addJvmClasspathRoot(config, new File(path.asText()));
        for (var path : request.path("java_roots")) JvmContentRootsKt.addJavaSourceRoot(config, new File(path.asText()));
        JvmContentRootsKt.configureJdkClasspathRoots(config);
        var disposable = Disposer.newDisposable();
        try {
            var environment = KotlinCoreEnvironment.createForProduction(disposable, config, EnvironmentConfigFiles.JVM_CONFIG_FILES);
            if (environment.getSourceFiles().stream().anyMatch(value -> PsiTreeUtil.findChildOfType(value,
                    org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement.class) != null)) return response("analysis_error");
            var analysis = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(environment.getProject(),
                    environment.getSourceFiles(), new CliBindingTrace(environment.getProject()), config, environment::createPackagePartProvider);
            var binding = analysis.getBindingContext();
            if (binding.getDiagnostics().all().stream().anyMatch(value -> value.getSeverity() == Severity.ERROR))
                return response("analysis_error");
            var file = environment.getSourceFiles().stream().filter(value ->
                    new File(value.getVirtualFilePath()).toPath().toAbsolutePath().normalize().toString()
                            .equals(request.path("file").asText())).findFirst().orElse(null);
            if (file == null) return response("unavailable");
            int offset = request.path("offset").asInt(-1);
            if (offset < 0 || offset >= file.getTextLength()) return response("unresolved");
            var reference = PsiTreeUtil.getParentOfType(file.findElementAt(offset), KtNameReferenceExpression.class, false);
            if (reference == null) return response("unresolved");
            var descriptor = binding.get(BindingContext.REFERENCE_TARGET, reference);
            if (descriptor == null) return response("unresolved");
            if (descriptor instanceof ValueParameterDescriptor || DescriptorUtils.isLocal(descriptor)) return response("local");
            var result = response("resolved");
            if (descriptor instanceof FunctionDescriptor function) {
                function = function.getOriginal();
                if (function instanceof CallableMemberDescriptor member) function = (FunctionDescriptor) DescriptorUtils.unwrapFakeOverride(member);
                String signature = MethodSignatureMappingKt.computeJvmDescriptor(function, true, false);
                if (function.isSuspend()) signature = signature.substring(0, signature.indexOf(')'))
                        + "Lkotlin/coroutines/Continuation;)Ljava/lang/Object;";
                result.put("descriptor", signature);
                result.put("kind", function instanceof ConstructorDescriptor ? "CONSTRUCTOR" : "METHOD");
                String name = DescriptorUtils.getJvmName(function);
                result.put("name", name == null ? function.getName().asString() : name);
                descriptor = function;
            } else if (descriptor instanceof ClassDescriptor cls) {
                result.put("kind", "CLASS"); result.put("name", cls.getName().asString());
            } else if (descriptor instanceof PropertyDescriptor property) {
                result.put("kind", "PROPERTY"); result.put("name", property.getName().asString());
            } else return response("unavailable");
            var containing = descriptor instanceof ClassDescriptor ? descriptor : descriptor.getContainingDeclaration();
            if (containing instanceof ClassDescriptor cls) result.put("owner", MethodSignatureMappingKt.getInternalName(cls).replace('/', '.'));
            else {
                var source = DescriptorToSourceUtils.getContainingFile(descriptor);
                if (source == null) return response("unavailable");
                result.put("owner", JvmFileClassUtil.getFileClassInternalName(source).replace('/', '.'));
            }
            var declaration = DescriptorToSourceUtils.descriptorToDeclaration(descriptor);
            if (declaration != null) {
                result.put("source_file", declaration.getContainingFile().getVirtualFile().getPath());
                result.put("source_line", 1 + (int) declaration.getContainingFile().getText()
                        .substring(0, declaration.getTextOffset()).chars().filter(c -> c == '\n').count());
            }
            return result;
        } finally { Disposer.dispose(disposable); }
    }

    private static ObjectNode response(String status) {
        var result = JSON.createObjectNode();
        result.put("status", status); result.put("backend", "kotlin_descriptor_frontend");
        result.put("language_version", "1.9");
        return result;
    }
}
