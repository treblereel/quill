package org.treblereel.mcp.core;

/** Build-time capture only. Uses the actual Kotlin JVM task options, including task-level overrides. */
public final class KotlinGradleCapture {
    private KotlinGradleCapture() {}
    public static String script() { return """
        // quill:kotlin-capture:v1
        def quillSourceSelection = { project, task ->
            def entries = []
            def snapshot = false
            def gates
            gates = { patterns, depth ->
                if (depth > 32) throw new GradleException('Source pattern nesting limit')
                if (!patterns.includeSpecs.isEmpty() || !patterns.excludeSpecs.isEmpty()) snapshot = true
                def result = [[includes: patterns.includes.toList().sort(), excludes: patterns.excludes.toList().sort(), caseSensitive: patterns.caseSensitive]]
                if (patterns instanceof org.gradle.api.tasks.util.internal.IntersectionPatternSet) result.addAll(gates(patterns.other, depth + 1))
                result
            }
            def visit = { collection, kind ->
                collection.asFileTree.visitStructure([
                    visitCollection: { source, files -> files.each { file ->
                        entries.add([kind: kind, file: file.canonicalPath])
                    } },
                    visitFileTree: { directory, patterns, tree ->
                        entries.add([kind: kind, root: directory.canonicalPath, gates: gates(patterns, 0),
                            defaultExcludes: org.apache.tools.ant.DirectoryScanner.getDefaultExcludes().toList().sort()])
                    },
                    visitFileTreeBackedByFile: { file, tree, mirror ->
                        snapshot = true
                        entries.add([kind: kind, file: file.canonicalPath])
                    }
                ] as org.gradle.api.internal.file.FileCollectionStructureVisitor)
            }
            visit(task.sources, 'kotlin')
            if (task.hasProperty('javaSources')) visit(task.javaSources, 'java')
            def selected = [kotlin: task.sources.files.findAll { it.name.endsWith('.kt') }.collect { it.canonicalPath }.sort(),
                java: task.hasProperty('javaSources') ? task.javaSources.files.findAll { it.name.endsWith('.java') }.collect { it.canonicalPath }.sort() : []]
            // Validate the structural export against Gradle's evaluated task inputs.
            // Unusual file-tree implementations fall back to a guarded snapshot.
            if (!snapshot) {
                def replay = [kotlin: [], java: []]
                entries.each { entry ->
                    def files
                    if (entry.root != null) {
                        def tree = project.fileTree(new File(entry.root))
                        entry.gates.each { rule ->
                            tree = tree.matching {
                                setCaseSensitive(rule.caseSensitive)
                                if (!rule.includes.isEmpty()) include(rule.includes)
                                if (!rule.excludes.isEmpty()) exclude(rule.excludes)
                            }
                        }
                        files = tree.files
                    } else files = [new File(entry.file)].findAll { it.isFile() }
                    replay[entry.kind].addAll(files.findAll { it.name.endsWith(entry.kind == 'kotlin' ? '.kt' : '.java') }.collect { it.canonicalPath })
                }
                if (replay.kotlin.unique().sort() != selected.kotlin.unique().sort() || replay.java.unique().sort() != selected.java.unique().sort()) snapshot = true
            }
            def selection = [protocol: 1, entries: entries.unique(), snapshot: snapshot, selected: selected]
            if (snapshot) {
                def observed = []
                selection.entries.each { entry ->
                    if (entry.root != null) {
                        def dir = new File(entry.root)
                        if (dir.isDirectory()) project.fileTree(dir).files.each { observed.add(it.canonicalFile) }
                        else if (dir.isFile()) observed.add(dir.canonicalFile)
                    } else observed.add(new File(entry.file))
                }
                selection.guard = observed.unique().sort { it.canonicalPath }.collect { file ->
                    [path: file.canonicalPath, sha256: file.isFile() ? java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance('SHA-256').digest(file.bytes)) : 'absent']
                }
            }
            selection
        }
        def quillCaptureKotlin = { project ->
            def sets = project.extensions.findByName('sourceSets')
            (sets == null ? [] : sets.toList()).each { jvmSet ->
                def sourceSet = jvmSet.name
                def taskName = jvmSet.getCompileTaskName('kotlin')
                try {
                    def task = project.tasks.findByName(taskName)
                    if (task != null && task.hasProperty('compilerOptions')) {
                        def options = task.compilerOptions
                        def values = new Properties()
                        values.setProperty('compilerSettingsProtocol', '1')
                        values.setProperty('producerVersion', '4')
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
                        def kotlinSources = jvmSet.extensions.findByName('kotlin')
                        if (kotlinSources != null && task.hasProperty('libraries')) {
                            values.setProperty('analysisInputsProtocol', '1')
                            put('sourceRoot', kotlinSources.srcDirs.collect { it.canonicalPath }.sort())
                            put('javaRoot', jvmSet.java.srcDirs.collect { it.canonicalPath }.sort())
                            put('compileClasspath', task.libraries.files.collect { it.canonicalPath }.sort())
                            // Gradle includes declared Java output directories even for NO-SOURCE tasks.
                            def declaredOutputs = project.rootProject.allprojects.collectMany { owner ->
                                def ownerSets = owner.extensions.findByName('sourceSets')
                                (ownerSets == null ? [] : ownerSets.toList()).collectMany { set ->
                                    set.output.classesDirs.files.toList() + (set.output.resourcesDir == null ? [] : [set.output.resourcesDir])
                                }
                            }.collect { it.canonicalPath }.toSet()
                            if (!task.hasProperty('friendPaths')) values.setProperty('configurationError', 'friend_paths_unavailable')
                            def friends = task.hasProperty('friendPaths') ? task.friendPaths.files.toList() : []
                            put('friendPath', friends.collect { it.canonicalPath }.sort())
                            def optional = task.libraries.files.findAll {
                                !it.exists() && declaredOutputs.contains(it.canonicalPath)
                            }
                            put('optionalOutput', optional.collect { it.canonicalPath }.unique().sort())
                            // A planned friend JAR need not exist when compiling tests against
                            // main classes. It must never make a missing dependency JAR optional.
                            put('optionalFriendPath', friends.findAll { !it.exists() }.collect { it.canonicalPath }.sort())
                            values.setProperty('sourceSelection', groovy.json.JsonOutput.toJson(quillSourceSelection(project, task)))
                        } else values.setProperty('configurationError', 'analysis_inputs_unavailable')
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
                def sets = project.extensions.findByName('sourceSets')
                (sets == null ? [] : sets.toList()).each { sourceSet ->
                    def name = sourceSet.getCompileTaskName('kotlin')
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
