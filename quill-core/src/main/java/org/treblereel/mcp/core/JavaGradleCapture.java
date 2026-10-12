package org.treblereel.mcp.core;

/** Captures JavaCompile task settings; configuration-only evidence never claims a selected compiler. */
public final class JavaGradleCapture {
    private JavaGradleCapture() {}
    public static String script() { return """
        // quill:java-capture:v1
        def quillCaptureJava = { project, task, confirmed ->
                    try {
                        if (task.state.failure != null) return
                        def sourceSets = project.extensions.findByName('sourceSets')
                        def sourceSet = sourceSets?.find { it.compileJavaTaskName == task.name }
                        def name = sourceSet == null ? 'task-' + task.name : sourceSet.name
                        if (!(name ==~ /[A-Za-z0-9._-]+/)) return
                        def captureFile = new File(project.projectDir, 'build/quill-java-' + name + '.properties')
                        if (!confirmed && captureFile.isFile()) return
                        def values = new Properties()
                        def put = { prefix, items ->
                            values.setProperty(prefix + '.count', items.size().toString())
                            items.eachWithIndex { item, index -> values.setProperty(prefix + '.' + index, item.toString()) }
                        }
                        def options = task.options
                        values.setProperty('buildTool', 'gradle')
                        values.setProperty('sourceSet', name)
                        values.setProperty('execution', task.path)
                        values.setProperty('evidence', confirmed ? 'successful_task' : 'configured_task')
                        values.setProperty('compilerId', 'javac')
                        def release = options.release.orNull
                        if (release != null) values.setProperty('release', release.toString())
                        values.setProperty('source', task.sourceCompatibility)
                        values.setProperty('target', task.targetCompatibility)
                        values.setProperty('fork', options.fork.toString())
                        if (options.encoding != null) values.setProperty('encoding', options.encoding)
                        put('argument', options.allCompilerArgs)
                        values.setProperty('enablePreview', options.allCompilerArgs.contains('--enable-preview').toString())
                        values.setProperty('useModulePath', task.modularity.inferModulePath.get().toString())
                        values.setProperty('modulePathEvidence', 'compiler_inference_or_arguments')
                        put('compileClasspath', task.classpath.files.collect { it.canonicalPath })
                        put('processorPath', options.annotationProcessorPath == null ? [] : options.annotationProcessorPath.files.collect { it.canonicalPath })
                        values.setProperty('processorPathEvidence', 'resolved_files')
                        put('sourceRoot', sourceSet == null ? task.source.files.findAll { it.name.endsWith('.java') }.collect { it.parentFile.canonicalPath }.unique() : sourceSet.java.srcDirs.collect { it.canonicalPath })
                        def generated = options.generatedSourceOutputDirectory.orNull
                        if (generated != null) values.setProperty('generatedSourcesDirectory', generated.asFile.canonicalPath)
                        def inputs = []
                        def directory = project.projectDir
                        while (directory != null) {
                            ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml'].each { entry -> inputs.add(new File(directory, entry)) }
                            if (directory.canonicalFile == project.rootProject.projectDir.canonicalFile) break
                            directory = directory.parentFile
                        }
                        def gradleUserProperties = new File(gradle.gradleUserHomeDir, 'gradle.properties')
                        inputs.add(gradleUserProperties)
                        if (sourceSet != null) sourceSet.java.srcDirs.each { inputs.add(new File(it, 'module-info.java')) }
                        if (confirmed) {
                            def compiler = task.javaCompiler.orNull
                            if (compiler != null) {
                                def metadata = compiler.metadata
                                values.setProperty('jdkHome', metadata.installationPath.asFile.canonicalPath)
                                values.setProperty('jdkVersion', metadata.javaRuntimeVersion)
                                values.setProperty('jdkVendor', metadata.vendor)
                                values.setProperty('jdkSelection', 'task_java_compiler')
                                values.setProperty('compilerExecutable', compiler.executablePath.asFile.canonicalPath)
                                inputs.add(new File(metadata.installationPath.asFile, 'release'))
                            } else values.setProperty('jdkSelection', 'unavailable')
                        } else {
                            values.setProperty('jdkSelection', 'not_executed')
                            def javaExtension = project.extensions.findByName('java')
                            def language = javaExtension?.toolchain?.languageVersion?.orNull
                            if (language != null) values.setProperty('requestedJdkVersion', language.toString())
                        }
                        if (options.fork && options.forkOptions.executable != null) {
                            values.setProperty('compilerExecutable', options.forkOptions.executable)
                            values.setProperty('jdkSelection', 'explicit_executable')
                            values.remove('jdkHome'); values.remove('jdkVersion'); values.remove('jdkVendor')
                            def executable = project.file(options.forkOptions.executable)
                            def home = executable.parentFile?.parentFile
                            def releaseFile = home == null ? null : new File(home, 'release')
                            if (releaseFile != null && releaseFile.isFile()) {
                                def props = new Properties(); releaseFile.withReader('UTF-8') { props.load(it) }
                                values.setProperty('jdkHome', home.canonicalPath)
                                if (props.getProperty('JAVA_VERSION') != null) values.setProperty('jdkVersion', props.getProperty('JAVA_VERSION').replace('"', ''))
                                inputs.add(releaseFile)
                            }
                        }
                        values.setProperty('buildInput.count', inputs.size().toString())
                        inputs.eachWithIndex { input, index ->
                            values.setProperty('buildInput.path.' + index, input.canonicalPath)
                            def sha = input.isFile() ? java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance('SHA-256').digest(input.bytes)) : 'absent'
                            values.setProperty('buildInput.sha256.' + index, sha)
                        }
                        def output = new File(project.projectDir, 'build/quill-java-' + name + '.properties')
                        output.parentFile.mkdirs()
                        def previous = new Properties()
                        if (output.isFile()) output.withReader('UTF-8') { previous.load(it) }
                        if (previous == values) return
                        def temporary = File.createTempFile('.java-settings-', '.tmp', output.parentFile)
                        try {
                            temporary.withWriter('UTF-8') { values.store(it, 'Quill Java compiler settings') }
                            try { java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                        } finally { temporary.delete() }
                    } catch (Exception error) { project.logger.warn('[quill] Could not capture Java compiler settings for ' + task.path + ': ' + error.message) }
        }
        gradle.projectsEvaluated {
            gradle.rootProject.allprojects.each { project ->
                project.tasks.withType(org.gradle.api.tasks.compile.JavaCompile).toList().each { task ->
                    // Realize task factories while project services are live; discovery captures under its task lock.
                }
            }
        }
        // Project services remain live here; buildFinished is too late to resolve lazy Java task providers.
        gradle.taskGraph.afterTask { task, state ->
            if (task instanceof org.gradle.api.tasks.compile.JavaCompile && state.failure == null)
                quillCaptureJava(task.project, task, true)
        }
        """; }
}
