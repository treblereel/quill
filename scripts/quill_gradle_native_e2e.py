#!/usr/bin/env python3
"""Validate packaged Gradle K2 navigation, source-set reuse and warmup on macOS."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from quill_workflow_benchmark import WorkflowClient
from quill_kotlin_native_e2e import assert_clean_shutdown


def rss(client):
    pid = client.client.process.pid
    output = subprocess.check_output(['/bin/ps', '-axo', 'pid=,ppid=,rss='], text=True)
    stats = {int(p): (int(parent), int(k) * 1024)
             for p, parent, k in (line.split() for line in output.splitlines())}
    return {'server_bytes': stats[pid][1], 'direct_children': [
        {'pid': p, 'rss_bytes': size} for p, (parent, size) in stats.items() if parent == pid]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--quill', type=Path, default=Path('quill-app/target/quill'))
    parser.add_argument('--java-jar', type=Path)
    parser.add_argument('--task-override', action='store_true')
    parser.add_argument('--explicit-file', action='store_true', help='Use task.setSource(file) instead of a file tree')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != 'darwin':
        parser.error('This validation expects the macOS FSEvents watcher and ps RSS format')
    binary = (args.java_jar or args.quill).resolve(strict=True)
    command = ['java', '-jar', str(binary)] if args.java_jar else [str(binary)]
    rows = []
    with tempfile.TemporaryDirectory(prefix='quill-native-gradle-ü-') as directory:
        root = Path(directory).resolve()

        def write(path, text):
            file = root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text)
            return file

        write('settings.gradle.kts', 'rootProject.name = "native-selection"')
        write('build.gradle.kts', '''plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
val integrationTest by sourceSets.creating
kotlin.sourceSets.named("integrationTest") {
 kotlin.setSrcDirs(listOf("custom ü")); kotlin.exclude("**/Ignored.kt")
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIntegrationTestKotlin") {
 compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
''')
        main_file = write('src/main/kotlin/Main.kt',
                          'package mainprobe\nfun pick() = 1\nfun run() = pick()\n')
        custom = write('custom ü/Custom.kt',
                       'package customprobe\nfun pick() = "ok"\nfun run() = pick()\n')
        write('custom ü/Ignored.kt', 'invalid Kotlin excluded by Gradle')
        if args.task_override:
            build = root / 'build.gradle.kts'
            build.write_text(build.read_text() + '''
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIntegrationTestKotlin") {
 setSource(fileTree("override ü") { include("**/*.kt"); exclude("**/Ignored.kt") })
}
''')
            (root / 'custom ü').rename(root / 'override ü')
            custom = root / 'override ü/Custom.kt'
        if args.explicit_file:
            build = root / 'build.gradle.kts'
            build.write_text(build.read_text() + '\ntasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIntegrationTestKotlin") { setSource(file("' + str(custom.relative_to(root)) + '")) }\n')
        source_dir = custom.parent.relative_to(root)
        subprocess.run(['gradle', 'classes', 'integrationTestClasses', '--quiet'], cwd=root,
                       check=True, capture_output=True, text=True, timeout=180)
        init = subprocess.run(command + ['init', '--no-allow-quill-tools', '--project', str(root)],
                              check=True, capture_output=True, text=True, timeout=180)
        assert 'runtime ready' in init.stderr, init.stderr
        assert 'sourceSelection' in (root / 'build/quill-kotlin-integrationTest.properties').read_text()
        with WorkflowClient(command, root, 90) as client:
            def query(file, needle, descriptor, label):
                text = file.read_text()
                offset = text.rindex(needle)
                result, timing = client.call('get_symbol_at_position', {
                    'path': str(file.relative_to(root)), 'line': text[:offset].count('\n') + 1,
                    'column': offset - text.rfind('\n', 0, offset)})
                semantic = result['semantic_analysis']
                assert semantic['status'] == 'resolved', result
                assert semantic['descriptor'] == descriptor, result
                if 'selected' in result:
                    assert result['selected']['jvm_descriptor'] == descriptor, result
                rows.append({'case': label, 'timing': timing, 'semantic_analysis': semantic,
                             'resolution': result['resolution'], 'confidence': result['confidence'],
                             'rss_snapshot': rss(client)})
                return semantic

            query(main_file, 'pick()', '()I', 'main')
            query(custom, 'pick()', '()Ljava/lang/String;', 'integrationTest')
            assert query(main_file, 'pick()', '()I', 'main_revisited')['analysis_reused'] is True
            assert query(custom, 'pick()', '()Ljava/lang/String;',
                         'integrationTest_revisited')['analysis_reused'] is True
            write(source_dir / 'Added.kt', 'package customprobe\nfun added() = true\n')
            if args.explicit_file:
                custom.write_text('package customprobe\nfun added() = true\nfun run() = added()\n')
                write(source_dir / 'Added.kt', 'invalid Kotlin not in the explicit task source list')
            else:
                custom.write_text('package customprobe\nfun run() = added()\n')
            time.sleep(1.5)
            warmed = query(custom, 'added()', '()Z', 'created_source_background_warmup')
            assert warmed['analysis_reused'] is True, warmed
            assert warmed['input_detection'] == 'directory_watcher_fsevents', warmed
            write(source_dir / 'Ignored.kt', 'still invalid excluded by Gradle')
            time.sleep(.4)
            excluded = query(custom, 'added()', '()Z', 'excluded_file_changed')
            assert excluded['analysis_generation'] == warmed['analysis_generation'], excluded
            shutdown = assert_clean_shutdown(client)
    report = {'schema_version': 1, 'platform': 'macOS arm64', 'task_override': args.task_override, 'explicit_file': args.explicit_file,
              'distribution': 'jvm' if args.java_jar else 'native', 'eof_shutdown': shutdown,
              'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(), 'cases': rows,
              'limitations': ['Small single-module Gradle fixture; RSS snapshots after requests, not long-session peaks',
                              'The 1.5 second allowance proves background reuse, not exact readiness time',
                              'Predicate snapshots and task-level inputs also covered by JVM Gradle integration tests',
                              'A newly declared target has a compiler descriptor but no indexed selected symbol until rebuild and update']}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': len(rows), 'latencies_ms': [r['timing']['latency_ms'] for r in rows],
                      'rss': rows[-1]['rss_snapshot']}))


if __name__ == '__main__':
    main()
