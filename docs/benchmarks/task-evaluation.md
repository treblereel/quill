# Paired task evaluation

Latency and payload compression do not prove that an agent completes engineering tasks more
accurately or with fewer tokens. Quill therefore keeps two benchmark layers separate:

1. `scripts/quill_benchmark.py` measures indexing, memory, MCP latency, concurrency, and payload
   size relative to the indexed source covered by a response.
2. `scripts/quill_task_benchmark.py` scores the same task suite completed by the same agent once
   with Quill and once without Quill.

The second layer measures fact accuracy, completed tasks, elapsed time, actual agent input/output
tokens, requests, and manual verification steps. It does not use Quill's `compression` field as an
estimate of agent savings.

## Capture protocol

Use the same model, reasoning level, initial checkout, task prompt, and time/token limits for both
runs. The only intended difference is whether the Quill MCP tools are available. Start a clean
agent context for every task and record one result object:

```json
{
  "id": "bean-manager-coupling",
  "observed": {
    "total_dependents": 497,
    "source_dependents": 15,
    "generated_dependents": 482
  },
  "duration_seconds": 24.8,
  "input_tokens": 4100,
  "output_tokens": 620,
  "requests": 4,
  "manual_verification_steps": 1
}
```

Store the captured tasks in two JSON documents:

```json
{
  "mode": "with_quill",
  "tasks": []
}
```

The `observed` keys must match the task's `expected` keys. Missing values are counted separately
from incorrect values. `requests` counts MCP, shell, search, and file-read operations initiated to
answer the task. A manual verification step is an explicit source inspection used to confirm a
tool or search result.

## Score the pair

The Crysknife suite is pinned to the project revision stored in
`benchmarks/crysknife-quality.json`. Score two captures with:

```bash
python3 scripts/quill_task_benchmark.py \
  --suite benchmarks/crysknife-quality.json \
  --with-quill target/benchmarks/crysknife-with-quill.json \
  --without-quill target/benchmarks/crysknife-without-quill.json \
  --output target/benchmarks/crysknife-comparison.json
```

Do not compare runs from different project revisions. Rebaseline expected facts explicitly when
the target project changes.
