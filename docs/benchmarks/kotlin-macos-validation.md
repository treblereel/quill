# Kotlin macOS validation — 2026-10-11

Tested macOS arm64 with GraalVM CE 25.0.1, application JVM 21.0.2, and external K2
workers on JDK 17.0.2 / 21.0.2 / 25.0.1. Production code and the 68,473,032-byte
native executable are unchanged from `ece1f3d`. Tests exercising FSEvents ran outside
the execution sandbox, which permits registration but suppresses event delivery.

**Two production issues remain:** immediate saves after switching modules can fail
to trigger background warmup, and native FSEvents threads can survive closing their
subscriptions. The foreground query still reads fresh inputs in the observed save
failure. Successful standard suites do not imply that every stress scenario passed.

## Completed checks

| Check | Result |
|---|---|
| Full Maven reactor `verify -Pnative -DskipITs=false` | 943 unit + 32 integration tests, no failures/errors/skips; native build and coverage checks passed |
| Python test discovery | 189 tests passed, including shutdown-attestation failures |
| Application/runtime/watcher focused tests on JDK 21 | 63 passed, no skips |
| Full native Kotlin E2E, worker JDK 17 / 21 / 25 | 20 standalone cases per JDK; Maven/Gradle runtime preparation, metadata fallback, changed-source freshness, FIR reuse and real FSEvents warmup passed |
| Native MCP connectivity E2E | Project/workspace init, full/core/router profiles, doctor and broken-launcher negative check passed |
| Native change-loop E2E | Actual compile/failure/repair/verification contract passed; stale test expectation updated from instruction v2 to current v3 |
| Native scoped-verification E2E | Stale unrelated module advisory, stale prerequisite blocking, standard test-source compilation passed |
| Real K2 prolonged warmup | 502 sources, 100 single-save + 100 five-save bursts; all 200 cycles returned fresh descriptors and reused the completed FIR generation; one observed background attempt each |
| Native EOF after completed analysis | MCP exit 0 and existing worker gone on all three JDKs; no kill fallback |
| Native EOF with a changed-source request sent and 500 additional sources | MCP exit 0 and worker gone on all three JDKs; no kill fallback |

EOF checks cover a request sent without waiting for its reply. They do not assert
which exact compiler phase had begun when EOF arrived. Their process snapshots check
all existing descendants; parent exit alone cannot attest worker cleanup. Unit tests
reject missing workers, EOF timeouts and surviving descendants.

## FSEvents stress and the open issue

`scripts/QuillKotlinWatcherStress.java` exercises the production event adapter and
coordinator with a content-reading test worker. It does **not** run K2; the separate
502-source warmup test and native E2E cover the actual compiler.

Each event round writes 100 Kotlin files, atomically renames and deletes their
containing tree, then atomically replaces a Unicode-named sentinel while preserving
its size and modification time. The probe requires a filesystem callback to observe
the final content before proceeding. Events may coalesce; the probe does not require
one callback per filesystem operation. Spaces/Unicode paths, newly created nested
directories, rapid root replacement and closing subscriptions are exercised.

- First immediate-switch run: 500 event rounds passed; background warmup was missing
  at switch index **30** (the 31st switch).
- Repeat: 500 event rounds, 250 subscription create/replace/close cycles and 250
  immediate module switches all passed, with no event-backend fallback.
- Extended run: **2,000 event rounds** (200,000 burst source writes) and **1,000
  subscription lifecycle cycles** passed. Background warmup was then missing at
  switch index **521** (the 522nd switch).
- A control run waiting **200 ms after each module activation** passed 500 event
  rounds, 250 subscription lifecycle cycles and all 250 module switches. This is a
  test control, not a production fix or proof of a sufficient readiness bound.

The extended failure waited 10 seconds for the expected background content.
`input_detection` remained `directory_watcher_fsevents`; the event adapter reported
no error and never activated fallback. A subsequent foreground query returned the
correct `version-521`. The issue affects background readiness, while foreground
freshness recovered in the observed case.

The library starts its FSEvents run-loop thread asynchronously after registration;
there is no readiness acknowledgement in the adapter. Together with the successful
settled control, this suggests a registration/startup gap. The precise cause and fix
remain unverified. Two immediate-switch runs failed and one passed under differing
host loads/counts; these runs cannot establish a real-world failure rate.

After the extended failure closed its resources: **0 Quill threads**, **7 open file
descriptors** (baseline 7), total JVM threads **8** (baseline 7), and post-GC heap
**5.76 MB** (baseline 5.39 MB). RSS was **103 MB** versus a **127 MB** baseline.
The successful immediate-switch run also ended with zero Quill threads and unchanged
descriptors. The original counters did not distinguish native FSEvents threads from other JVM
threads. More precise checks subsequently found a native-thread lifecycle leak;
zero Quill threads and unchanged file descriptors cannot attest full cleanup.

### Native thread surviving close

The latest settled control still observed **one** `WatchService for ...` thread
remaining after its subscriptions closed. It was already present after the rapid
subscription-lifecycle loop, and remained through the subsequent 250 module switches
with 200 ms pauses (about another minute). All content/freshness checks in that run
passed, but the more precise lifecycle assertion failed.

An isolated follow-up ran **5,000 subscription create/replace/close cycles**, without
module switching. **Four native threads remained** even after an additional ten-second
shutdown allowance. Their stacks end in:

```text
com.sun.jna.Native.invokeVoid
io.methvin.watchservice ... $Proxy0.CFRunLoopRun
io.methvin.watchservice.MacOSXListeningWatchService$CFRunLoopThread.run
```

Quill threads were zero and descriptors remained at seven. This reproduces a native
run-loop lifecycle leak separately from the missed-warmup scenario. Inspection of
library startup/close ordering suggests a race between `CFRunLoopStop` and entering
`CFRunLoopRun`; the precise cause and a production repair are not yet verified.
The processes running these probes exited afterward, releasing their daemon threads.

Raw observations and thread stacks are preserved in the accompanying
[JSON report](results/kotlin-macos-validation-2026-10-11.json).

## Reproduction

Full tests (the Maven extension must already be built):

```sh
./mvnw verify -Pnative -DskipITs=false \
  -Dmaven.ext.class.path="$PWD/quill-maven-extension/target/quill-maven-extension-0.1.0.jar"
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Stress probe, with no application-source edits:

```sh
mkdir -p /tmp/quill-watcher-stress-classes
javac --release 21 -cp quill-app/target/quill-app-0.1-SNAPSHOT-all.jar \
  -d /tmp/quill-watcher-stress-classes scripts/QuillKotlinWatcherStress.java
java --enable-native-access=ALL-UNNAMED -Xmx512m \
  -cp /tmp/quill-watcher-stress-classes:quill-app/target/quill-app-0.1-SNAPSHOT-all.jar \
  org.treblereel.mcp.mcp.QuillKotlinWatcherStress 2000 1000 /tmp/quill-stress.json
```

The failing result is preserved in JSON before the probe exits nonzero. Failure is
intermittent. For the control, add `-Dquill.stress.registration-settle-ms=200` to the
Java invocation. This pause belongs to the test only and does not fix the close race.
To isolate lifecycle cleanup, add `-Dquill.stress.skip-module-switches=true` and use
counts `10 5000`; the probe allows ten seconds for native run-loop threads to exit.

Native semantic/EOF suite:

```sh
python3 scripts/quill_kotlin_native_e2e.py --fixture tests/kotlin-maven \
  --worker-java-home <jdk-home> --output /tmp/quill-native-e2e.json
```

Pending-request EOF check (requires an already prepared K2 cache; runs offline):

```sh
python3 scripts/quill_kotlin_shutdown_e2e.py --runtime-cache <prepared-cache> \
  --worker-java-home <jdk17-home> --worker-java-home <jdk21-home> \
  --worker-java-home <jdk25-home> --output /tmp/quill-pending-eof.json
```

Run actual event tests on the host where filesystem notifications are permitted.
Original logs/reports are `/private/tmp/quill-osx-*`; the durable JSON retains the
important stress failure, controls and shutdown evidence. Native/JVM latency results
from concurrent stress workloads are correctness evidence, not controlled performance
comparisons. macOS x86-64, network filesystems and multi-hour workloads were not tested.
