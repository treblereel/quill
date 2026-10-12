package org.treblereel.mcp.core;

/** Gradle reports the capabilities of the installed capture script, independently of Quill releases. */
public final class BuildIntegrationProtocol {
    private BuildIntegrationProtocol() {}
    public static String gradleScript() { return """
        gradle.buildFinished { result ->
            try {
                def root = gradle.rootProject.projectDir
                def values = new Properties()
                values.setProperty('manifestVersion', '1')
                values.setProperty('buildTool', 'gradle')
                values.setProperty('producer', 'quill-gradle-integration')
                values.setProperty('producerVersion', '2')
                values.setProperty('buildEventProtocol', '3')
                values.setProperty('compilerSettingsProtocol', '1')
                def capabilities = ['build_events', 'runtime_classpath', 'kotlin_compiler_settings', 'java_compiler_settings']
                values.setProperty('capability.count', capabilities.size().toString())
                capabilities.eachWithIndex { value, index -> values.setProperty('capability.' + index, value) }
                def configuration = new File(root, 'settings.gradle.kts')
                if (!configuration.isFile()) configuration = new File(root, 'settings.gradle')
                values.setProperty('configuration.path', configuration.canonicalPath)
                values.setProperty('configuration.sha256', configuration.isFile() ? java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance('SHA-256').digest(configuration.bytes)) : 'absent')
                def output = new File(root, '.quill/build-integration.properties')
                output.parentFile.mkdirs()
                def previous = new Properties()
                if (output.isFile()) { try { output.withReader('UTF-8') { previous.load(it) } } catch (IllegalArgumentException ignored) { } }
                if (previous == values) return
                def temporary = File.createTempFile('.integration-', '.tmp', output.parentFile)
                try {
                    temporary.withWriter('UTF-8') { values.store(it, 'Quill build integration capabilities') }
                    try { java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    catch (java.nio.file.AtomicMoveNotSupportedException ignored) { java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                } finally { temporary.delete() }
            } catch (Exception error) { gradle.rootProject.logger.warn('[quill] Could not capture build integration capabilities: ' + error.message) }
        }
        """; }
}
