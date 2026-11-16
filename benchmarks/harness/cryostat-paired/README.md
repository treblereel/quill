# Cryostat paired benchmark harness

The source of the 528-session paired runs. Results stay out of git under
`benchmarks/results/`, so the code lives here and each run gets its own frozen copy:

```bash
benchmarks/harness/cryostat-paired/new_run.py <run-name> <seed> [CQ-007,CQ-015,...] [--arm with_quill] [--repeat 1]
```

A case list makes a targeted run under the same protocol. `--arm` and `--repeat` narrow it
further for a cheap diagnostic. One arm has no fresh pairs; one repeat has pairs but too few.
`protocol.json` records `run_filters`, and only an unfiltered run is acceptance evidence.

Then, from the new run directory, `python3.14 run.py prepare`, `preflight`,
`preflight-client` and `plan` make no provider calls, and neither does
`python3.14 base/test-runner.py`. `start` is the paid step, and `continue` runs only the
never-started part of a stopped schedule.

`template/run.py` overrides functions of `template/base_run.py`, which is copied to
`<run>/base/run.py`. The disposable fixture and client binaries are expected under
`/tmp/quill-cryostat-full-paired-v7`, and `mitmdump` under `~/.cache/quill`.

`data/capture-preflight/results.json` is the capture transparency test from run V14, on
synthetic traffic. `new_run.py` reuses it only while `mitmdump --version` still matches.

Provider traffic is still stored as one pair of files per frame under each session's
`wire/`, because `quill_response_reuse_audit.py` reads that layout. Packing it needs
that reader changed first.

## Runs, results and comparison (both suites)

Apollo and Cryostat runs start from one entry point and land in one layout:

```bash
python3.14 benchmarks/bench.py new SUITE NAME [--cases ...] [--clients codex,claude] [--repeats N]
python3.14 benchmarks/bench.py check SUITE NAME      # free: fixture, isolation, client preflights
python3.14 benchmarks/bench.py start SUITE NAME      # paid; writes summary.json at the end
python3.14 benchmarks/bench.py compare SUITE RUN [RUN ...] [--cases]
```

`benchmarks/results/<suite>/<run>/` holds `run.json` (what was asked, Quill commit), `run.log` (one line
per session), `sessions/<case>-<client>-<arm>-r<N>/` and `summary.json` (tokens, cost, Quill calls,
rubric verdict and missed facts per session). Runs kept in an older layout stay where they are;
`bench.py import SUITE NAME PATH` writes their `summary.json` so they compare with new ones.
Both suites run on disposable fixtures under `/tmp`, never on the original checkouts.
