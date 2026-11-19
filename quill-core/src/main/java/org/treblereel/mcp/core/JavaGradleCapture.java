package org.treblereel.mcp.core;

/** Captures JavaCompile task settings; configuration-only evidence never claims a selected compiler. */
public final class JavaGradleCapture {
    private JavaGradleCapture() {}
    public static String script() { return """
        // quill:java-capture:v2
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
                        values.setProperty('compilerSettingsProtocol', '1')
                        values.setProperty('producerVersion', '2')
                        def put = { prefix, items ->
                            values.setProperty(prefix + '.count', items.size().toString())
                            items.eachWithIndex { item, index -> values.setProperty(prefix + '.' + index, item.toString()) }
                        }
                        def options = task.options
                        values.setProperty('buildTool', 'gradle')
                        values.setProperty('sourceSet', name)
                        values.setProperty('execution', task.path)
                        values.setProperty('evidence', confirmed ? 'successful_task' : 'configured_task')
                        def rest = project.plugins.hasPlugin('io.quarkus') && project.configurations.findByName('compileClasspath')?.resolvedConfiguration?.resolvedArtifacts?.any { artifact ->
                            artifact.moduleVersion.id.group == 'io.quarkus' && artifact.name in ['quarkus-rest', 'quarkus-resteasy-reactive']
                        }
                        values.setProperty('quarkusRest', Boolean.toString(rest == true))
                        def arc = project.plugins.hasPlugin('io.quarkus') && project.configurations.findByName('compileClasspath')?.resolvedConfiguration?.resolvedArtifacts?.any { artifact -> artifact.moduleVersion.id.group == 'io.quarkus' && artifact.name == 'quarkus-arc' }
                        values.setProperty('quarkusArc', Boolean.toString(arc == true))
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
                        if (previous.getProperty('compilationProtocol') == '1') {
                            previous.each { key, value -> if (key.toString().startsWith('compilation') || key.toString().startsWith('compiled') || key == 'buildVariantSha256') values.setProperty(key.toString(), value.toString()) }
                        }
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
        """ + compilationScript() + BuildIntegrationProtocol.gradleScript(); }
    private static String compilationScript() { return """
        def quillCompilationStarts = new java.util.concurrent.ConcurrentHashMap()
        def quillTreeHash = { File root ->
            if (!root.exists()) return 'absent'
            def sha = { byte[] bytes -> java.security.MessageDigest.getInstance('SHA-256').digest(bytes) }
            if (root.isFile()) return java.util.HexFormat.of().formatHex(sha(root.bytes))
            def digest = java.security.MessageDigest.getInstance('SHA-256')
            def files = []
            root.eachFileRecurse { if (it.isFile()) files.add(it) }
            files.sort { a, b -> a.path <=> b.path }.each { file ->
                digest.update(root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char).getBytes('UTF-8'))
                digest.update((byte)0); digest.update(sha(file.bytes)); digest.update((byte)0)
            }
            java.util.HexFormat.of().formatHex(digest.digest())
        }
        def quillCompilationInputs = { task ->
            def sets = task.project.extensions.findByName('sourceSets')
            def set = sets?.find { task.name == it.compileJavaTaskName || task.name == it.getCompileTaskName('kotlin') }
            if (set == null) return null
            def values = new Properties()
            values.setProperty('compilationModule', task.project.projectDir.canonicalPath)
            values.setProperty('sourceSet', set.name)
            def languages = (set.allSource.files - set.resources.files).collect { it.name.endsWith('.java') ? 'java' : it.name.endsWith('.kt') ? 'kotlin' : null }.findAll { it != null }.unique().sort()
            values.setProperty('compilationLanguage.count', languages.size().toString())
            languages.eachWithIndex { language, index -> values.setProperty('compilationLanguage.' + index, language) }
            def roots = new TreeSet<File>({ a, b -> a.canonicalPath <=> b.canonicalPath } as Comparator)
            roots.addAll(set.allSource.srcDirs)
            roots.add(new File(task.project.projectDir, 'src/' + set.name + '/kotlin'))
            if (task instanceof org.gradle.api.tasks.compile.JavaCompile) {
                def generated = task.options.generatedSourceOutputDirectory.orNull
                if (generated != null) roots.remove(generated.asFile)
            }
            if (set.name != 'main' && sets.findByName('main') != null) roots.addAll(sets.main.output.classesDirs.files)
            def directory = task.project.projectDir
            while (directory != null) {
                ['build.gradle','build.gradle.kts','settings.gradle','settings.gradle.kts','gradle.properties','gradle/libs.versions.toml'].each { roots.add(new File(directory, it)) }
                if (directory.canonicalFile == task.project.rootProject.projectDir.canonicalFile) break
                directory = directory.parentFile
            }
            roots.add(new File(gradle.gradleUserHomeDir, 'gradle.properties'))
            values.setProperty('compilationRoot.count', roots.size().toString())
            roots.eachWithIndex { input, index ->
                values.setProperty('compilationRoot.path.' + index, input.canonicalPath)
                values.setProperty('compilationRoot.sha256.' + index, quillTreeHash(input))
            }
            values.setProperty('buildVariantSha256', java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance('SHA-256').digest(gradle.startParameter.projectProperties.toSorted().toString().getBytes('UTF-8'))))
            values
        }
        def quillCompilerTask = { task -> task instanceof org.gradle.api.tasks.compile.JavaCompile || task.class.name.contains('KotlinCompile') }
        gradle.taskGraph.beforeTask { task ->
            if (quillCompilerTask(task) && System.getProperty('quill.internal') != 'true') {
                try { def values = quillCompilationInputs(task); if (values != null) quillCompilationStarts.put(task.path, values) }
                catch (Exception error) { task.logger.warn('[quill] Could not capture compile inputs: ' + error.message) }
            }
        }
        gradle.taskGraph.afterTask { task, state ->
            if (quillCompilerTask(task) && System.getProperty('quill.internal') != 'true') {
                def before = quillCompilationStarts.remove(task.path)
                if (state.failure == null && !state.noSource && (!state.skipped || state.upToDate || state.fromCache)) try {
                    def after = quillCompilationInputs(task)
                    if (after != null) {
                        def kotlin = task.class.name.contains('KotlinCompile')
                        def outputDir = task.destinationDirectory.get().asFile
                        def file = new File(task.project.buildDir, 'quill-' + (kotlin ? 'kotlin' : 'java') + '-' + after.getProperty('sourceSet') + '.properties')
                        def values = new Properties()
                        if (file.isFile()) file.withReader('UTF-8') { values.load(it) }
                        values.putAll(after)
                        values.setProperty('compilationProtocol', '1')
                        values.setProperty('compilationEvidence', before != null && before == after ? 'matching' : 'inputs_changed_during_compile')
                        values.setProperty('buildTool', 'gradle'); values.setProperty('evidence', 'successful_task')
                        values.setProperty('compiledOutput.path', outputDir.canonicalPath)
                        values.setProperty('compiledOutput.sha256', quillTreeHash(outputDir))
                        def previous = new Properties()
                        if (file.isFile()) file.withReader('UTF-8') { previous.load(it) }
                        if (previous == values) return
                        values.setProperty('compiledAt', System.currentTimeMillis().toString())
                        file.parentFile.mkdirs()
                        def temporary = File.createTempFile('.compile-', '.tmp', file.parentFile)
                        try {
                            temporary.withWriter('UTF-8') { values.store(it, 'Quill successful compilation inputs') }
                            try { java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                        } finally { temporary.delete() }
                    }
                } catch (Exception error) { task.logger.warn('[quill] Could not confirm compile inputs: ' + error.message) }
            }
        }
        """; }

}
