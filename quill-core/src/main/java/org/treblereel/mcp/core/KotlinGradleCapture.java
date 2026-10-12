package org.treblereel.mcp.core;

/** Build-time capture only. Uses the actual Kotlin JVM task options, including task-level overrides. */
public final class KotlinGradleCapture {
    private KotlinGradleCapture() {}
    public static String script() { return """
        // quill:kotlin-capture:v1
        def quillCaptureKotlin = { project ->
            ['compileKotlin': 'main', 'compileTestKotlin': 'test'].each { taskName, sourceSet ->
                try {
                    def task = project.tasks.findByName(taskName)
                    if (task != null && task.hasProperty('compilerOptions')) {
                        def options = task.compilerOptions
                        def values = new Properties()
                        ['languageVersion', 'apiVersion', 'jvmTarget'].each { key ->
                            if (options.hasProperty(key)) {
                                def value = options."$key".orNull
                                if (value != null) values.setProperty(key, key == 'jvmTarget' ? value.target : value.version)
                            }
                        }
                        def put = { prefix, items ->
                            values.setProperty(prefix + '.count', items.size().toString())
                            items.eachWithIndex { item, index -> values.setProperty(prefix + '.' + index, item.toString()) }
                        }
                        put('argument', options.freeCompilerArgs.orNull ?: [])
                        def plugins = task.hasProperty('pluginClasspath') ? task.pluginClasspath.files.collect { it.canonicalPath }.sort() : []
                        def pluginOptions = []
                        if (task.hasProperty('pluginOptions')) (task.pluginOptions.orNull ?: []).each { config ->
                            config.allOptions().each { id, entries -> entries.each { option -> pluginOptions.add('plugin:' + id + ':' + option.key + '=' + option.value) } }
                        }
                        put('pluginOption', pluginOptions)
                        // KGP installs the default scripting frontend and its dependencies
                        // even without user plugins. The worker supplies that frontend itself.
                        def builtins = ['org.jetbrains.kotlin:kotlin-script-runtime',
                            'org.jetbrains.kotlin:kotlin-scripting-common', 'org.jetbrains.kotlin:kotlin-scripting-jvm',
                            'org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable',
                            'org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable',
                            'org.jetbrains.kotlin:kotlin-stdlib', 'org.jetbrains:annotations']
                        def pluginConfiguration = project.configurations.findByName('kotlinCompilerPluginClasspath' + sourceSet.capitalize())
                        def builtinFiles = []
                        if (pluginConfiguration != null && pluginConfiguration.state.toString() == 'RESOLVED') {
                            builtinFiles = pluginConfiguration.resolvedConfiguration.resolvedArtifacts.findAll { artifact ->
                                builtins.contains(artifact.moduleVersion.id.group + ':' + artifact.name)
                            }.collect { it.file.canonicalPath }
                        }
                        if (pluginOptions.isEmpty() && plugins.every { builtinFiles.contains(it) }) plugins = []
                        put('pluginClasspath', plugins)
                        def wrapper = project.plugins.findPlugin('org.jetbrains.kotlin.jvm')
                        def version = wrapper?.class?.package?.implementationVersion
                        def buildClasspath = project.buildscript.configurations.findByName('classpath')
                        if (buildClasspath != null && buildClasspath.state.toString() == 'RESOLVED') {
                            def artifact = buildClasspath.resolvedConfiguration.resolvedArtifacts.find { it.moduleVersion.id.group == 'org.jetbrains.kotlin' && it.name == 'kotlin-gradle-plugin' }
                            if (artifact != null) version = artifact.moduleVersion.id.version
                        }
                        if (version != null) values.setProperty('compilerVersion', version)
                        def inputs = []
                        def directory = project.projectDir
                        while (directory != null) {
                            ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml'].each { name -> inputs.add(new File(directory, name)) }
                            if (directory.canonicalFile == project.rootProject.projectDir.canonicalFile) break
                            directory = directory.parentFile
                        }
                        values.setProperty('buildInput.count', inputs.size().toString())
                        inputs.eachWithIndex { input, index ->
                            values.setProperty('buildInput.path.' + index, input.canonicalPath)
                            def sha = input.isFile() ? java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance('SHA-256').digest(input.bytes)) : 'absent'
                            values.setProperty('buildInput.sha256.' + index, sha)
                        }
                        def output = new File(project.projectDir, 'build/quill-kotlin-' + sourceSet + '.properties')
                        output.parentFile.mkdirs()
                        def previous = new Properties()
                        if (output.isFile()) output.withReader('UTF-8') { reader -> previous.load(reader) }
                        if (previous == values) return
                        def temporary = File.createTempFile('.kotlin-settings-', '.tmp', output.parentFile)
                        try {
                            temporary.withWriter('UTF-8') { writer -> values.store(writer, 'Quill Kotlin compiler settings') }
                            try { java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                        } finally { temporary.delete() }
                    }
                } catch (Exception error) { project.logger.warn('[quill] Could not capture Kotlin compiler settings: ' + error.message) }
            }
        }
        // Kotlin task factories require live project services; realizing them only at
        // buildFinished fails for a discovery/help build that never schedules compilation.
        gradle.projectsEvaluated {
            gradle.rootProject.allprojects.each { project ->
                ['compileKotlin', 'compileTestKotlin'].each { name ->
                    try { project.tasks.findByName(name) }
                    catch (Exception error) { project.logger.warn('[quill] Could not prepare Kotlin settings capture: ' + error.message) }
                }
            }
        }
        gradle.buildFinished { result ->
            gradle.rootProject.allprojects.each { project -> quillCaptureKotlin(project) }
        }
        """; }
}
