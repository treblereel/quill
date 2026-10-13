#!/usr/bin/env python3
"""Validate Quill's packaged Java worker against a compiled/indexed Quill project on macOS."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from quill_workflow_benchmark import WorkflowClient
from quill_gradle_native_e2e import rss


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--quill', type=Path, default=Path('quill-app/target/quill'))
    parser.add_argument('--project', type=Path, default=Path.cwd())
    parser.add_argument('--exercise-edit', action='store_true', help='Change and restore the overload call in a temporary project')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != 'darwin':
        parser.error('This check uses macOS ps RSS')
    binary = args.quill.resolve(strict=True)
    root = args.project.resolve(strict=True)
    temporary_roots = [Path(tempfile.gettempdir()).resolve(), Path('/tmp').resolve()]
    if args.exercise_edit and not any(root.is_relative_to(path) for path in temporary_roots):
        parser.error('--exercise-edit requires a project inside the system temporary directory')
    relative = 'quill-core/src/main/java/org/treblereel/mcp/core/JvmSourceFiles.java'
    file = root / relative
    original = file.read_text()
    stat = file.stat()
    anchor = 'return isSourceFile(path.getFileName().toString());'
    offset = original.index(anchor) + anchor.index('isSourceFile')
    rows = []
    try:
        with WorkflowClient([str(binary)], root, 90) as client:
            def query(label, descriptor):
                result, timing = client.call('get_symbol_at_position', {
                    'path': relative, 'line': original[:offset].count('\n') + 1,
                    'column': offset - original.rfind('\n', 0, offset)})
                semantic = result['semantic_analysis']
                assert semantic['status'] == 'resolved', result
                assert semantic['backend'] == 'javac', result
                assert result['selected']['descriptor'] == descriptor, result
                memory = rss(client)
                for child in memory['direct_children']:
                    argv = subprocess.check_output(['/bin/ps', '-p', str(child['pid']), '-o', 'args='], text=True)
                    child['worker'] = ('kotlin' if 'mcp.kotlin.SemanticWorker' in argv else
                                       'java' if 'mcp.javac.JavaWorker' in argv else 'other')
                rows.append({'case': label, 'timing': timing, 'semantic_analysis': semantic,
                             'descriptor': result['selected']['descriptor'], 'rss_snapshot': memory})
                return semantic

            query('first', '(Ljava/lang/String;)Z')
            for _ in range(6):
                assert query('unchanged', '(Ljava/lang/String;)Z')['reused'] is True
            if args.exercise_edit:
                call = 'return isSourceFile(path);'
                changed = original.replace(anchor, call + ' ' * (len(anchor) - len(call)))
                assert len(changed) == len(original)
                file.write_text(changed)
                os.utime(file, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                time.sleep(.3)
                edited = query('300ms_after_equal_size_restored_mtime_edit', '(Ljava/nio/file/Path;)Z')
                assert edited['reused'] is True, edited
                assert edited['generation'] > rows[0]['semantic_analysis']['generation'], edited
                file.write_text(original)
                os.utime(file, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                time.sleep(1.5)
                restored = query('1500ms_after_restore', '(Ljava/lang/String;)Z')
                assert restored['reused'] is True and restored['generation'] > edited['generation'], restored
    finally:
        if args.exercise_edit:
            file.write_text(original)
            os.utime(file, ns=(stat.st_atime_ns, stat.st_mtime_ns))
    report = {'schema_version': 1, 'platform': 'macOS arm64', 'binary_bytes': binary.stat().st_size,
              'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
              'exercise_edit': args.exercise_edit, 'source_restored': file.read_text() == original,
              'cases': rows, 'limitations': ['One overload in quill-core; not a repository-wide acceptance benchmark',
                                           'RSS snapshots include only the MCP server and its direct children',
                                           'Delay proves background reuse, not precise warmup completion time']}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': len(rows), 'latencies_ms': [r['timing']['latency_ms'] for r in rows],
                      'rss': rows[-1]['rss_snapshot']}))


if __name__ == '__main__':
    main()
