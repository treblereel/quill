# Quarkus and Spring Boot paired task fixtures

These suites prepare 24 base tasks: 12 on Quarkus and the same 12 on Spring Boot.
They use `quill_agent_benchmark.py` and the existing exact scorer `quill_task_benchmark.py`. Preparation
and oracle verification do not call a model, require an API key, or modify client settings.

The standalone Maven projects live in `benchmarks/framework-apps/quarkus` and
`benchmarks/framework-apps/spring`. They are deliberately outside Quill's reactor.
Both have the module graph `api -> service -> persistence` and an independent `unrelated`
module. Persistence is an in-memory receipt passthrough, with no external database,
network service, or real payment. HTTP tests bind only to loopback on a random port.

Versions are pinned to Quarkus 3.27.4, Spring Boot 3.4.5 and the Quill Maven build-capture
extension 0.1.4. The extension is declared in each application's `.mvn/extensions.xml`
and is identical in both benchmark modes. Use JDK 17 or 21,
Maven 3.9+, Python 3.10+, Git and ripgrep. These are reproducible fixture versions,
not recommendations about current framework releases. Maven may download dependencies.

## Prepare and verify locally

From the Quill repository root:

```bash
python3 scripts/quill_framework_benchmark.py prepare
python3 scripts/quill_framework_benchmark.py verify --quill quill-app/target/quill
python3 -m unittest discover -s scripts -p test_quill_framework_benchmark.py
```

`prepare` copies only application inputs into two new Git projects under
`target/benchmarks/frameworks/{quarkus,spring}`. Each gets a local commit with a fixed
author and timestamp. The two suites are written beside the projects, with their exact
`project_revision`. Existing output directories are refused; use a fresh `--output`
and the corresponding `verify --workspace` to preserve an earlier run.

The checked-in suites have an intentionally invalid revision placeholder. Do not run
them directly or bypass revision checks with `--allow-dirty`. Only the prepared suites
are ready for the paired runner.

`verify` checks input fingerprints and clean pinned revisions, executes `mvn clean verify`
on each project, checks all six real JUnit tests per framework, and checks the independent
runtime observations. It also builds the runnable Quarkus application and Spring Boot jar.
The same Maven invocation captures test dependency classpaths with the standard
`maven-dependency-plugin:3.8.1:build-classpath` goal into each module's
`target/quill-test-classpath.txt`, so index-only Quill can include test dependencies.
The successful receipt is `target/benchmarks/frameworks/oracle-verification.json`.
A retry removes a previous receipt before doing work; failed builds never produce a new
success receipt. Application inputs and Git cleanliness are checked again after the build.

The Maven extension records real build outcomes, compiler inputs and classpaths under
`.quill/` and `target/`. It does not call a model or change MCP client settings.
If extension 0.1.4 is unavailable from your Maven repositories, install the local module
before building the fixtures:

```bash
mvn -f quill-maven-extension/pom.xml -DskipTests install
```

With `--quill`, verification also refreshes each index in index-only mode and calls
`get_overview` followed by `change_session`, explicitly targeting every application and
JUnit source file across all four modules. It waits for asynchronous index preparation.
Success requires `phase=complete`, a `satisfied` phase gate, no required review items and
`verification_receipt.verified=true`. The combined evidence is written to
`target/benchmarks/frameworks/quill-verification.json`, including each project's revision,
input fingerprint and actual Quill response. Per-project responses are also saved as
`quarkus-quill-gate.json` and `spring-quill-gate.json`, including blocked guidance on failure.
Previous receipts are invalidated before a new build; a partial run cannot leave a previous
successful combined receipt in place. Omitting `--quill` runs only the local JUnit oracle
checks and does not produce a Quill gate receipt.

Adding capture changed only the fixture build configuration. The fingerprints and prepared-project revisions include
the extension configuration, so older captures remain bound to their older revisions.

To start the prepared applications after verification, run one at a time:

```bash
java -jar target/benchmarks/frameworks/quarkus/api/target/quarkus-app/quarkus-run.jar
java -jar target/benchmarks/frameworks/spring/api/target/orders-api-1.0.0.jar
```

The ordinary application uses prefix `order`; the real test contexts use prefix `test`.
The conditional-delivery question explicitly specifies the test context: Quarkus's test
build profile or Spring's active `test` profile. These are separate framework mechanisms.

## Tasks and independent gold

The current suites have `oracle_version: 2`, names ending in `v2` and task IDs such as
`quarkus-v2-event-flow`. They supersede the initial pilot prompts: all method answers
use a package-qualified declaring class and simple parameter types, and `event-flow`
explicitly asks for the concrete project wrapper implementing `OrderEvents.publish`.
It does not ask for the underlying framework API invocation. Array questions specify
lexicographic ordering; the unknown-value question specifies JSON null/boolean literals.
Expected facts and application fingerprints are unchanged. Distinct task IDs prevent
the exact scorer from treating old pilot captures as v2 answers, even at the same source revision.

The shared MCP-to-Responses adapter explicitly sets `strict: false` on Quill function
definitions and preserves the original `inputSchema`. This keeps optional arguments
omittable, including the required single-project invocation `get_overview({})`.
Source tools retain their existing strict schemas. See the
[official OpenAI documentation](https://developers.openai.com/api/docs/guides/function-calling)
for Responses' default strict normalization and the explicit opt-out.

Run offline adapter and suite regression checks with:

```bash
python3 -m unittest discover -s scripts -p test_quill_agent_benchmark.py
python3 -m unittest discover -s scripts -p test_quill_framework_benchmark.py
```

Prepare v2 into a fresh directory (for example, `prepare --output
target/benchmarks/frameworks-v2-local`) and pass that directory to `verify --workspace`.
Do not overwrite the initial pilot's prepared suites or rescore its archived results
using v2 prompts. Offline transports verify outgoing schemas and argument forwarding;
they do not establish server-side Responses behavior or model performance after the fix.

| Suffix in each suite | Expected evidence | Negative control |
| --- | --- | --- |
| `known-literal` | Numeric overload's literal prefix | Quill discovery may cost more than a file read |
| `overload-usages` | Direct source callers of `place(String)`, including JUnit | `place(long)`, similarly named independent method |
| `request-chain` | HTTP handler -> String service overload -> repository | Payment, settings and event side calls |
| `qualified-bean` | Card provider selected by qualifier | Cash provider |
| `conditional-bean` | Test delivery selected in the test context | Live delivery |
| `missing-bean` | Unsatisfied lazy `AuditPort` lookup | Application still starts successfully |
| `http-route` | POST, combined route and source handler | Class/method mapping fragments alone |
| `configuration` | Exact key's files, consumer and test value | `orders.prefix-preview` |
| `event-flow` | Event type, publishing method and subscribed listener | No direct publisher-to-listener Java call |
| `module-impact` | Modules and source callers reaching `save(String)` | Numeric-only preview and independent module |
| `impacted-tests` | Four JUnit classes exercising the save path | Numeric-only preview and independent tests |
| `dynamic-unknown` | Value cannot be inferred without a key/environment | Known keys and the absent-key runtime probe |

The gold is authored from the fixture source and independent JUnit assertions, never
from Quill responses. Every task carries source evidence and a rationale in `oracle`.
The runtime HTTP test starts the actual framework, asserts selected bean types, performs
a real POST, observes the event listener, checks test configuration and the missing lookup,
and writes actual observations to `api/target/runtime-observations.properties`.
It never reads a suite or calls Quill.

Source-only answers (callers, chain, impact and literal) are manually reviewed gold,
bound to the complete fixture input SHA-256. Fingerprints detect drift; they are not an
automatic proof of the semantic answer. Runtime checks corroborate the dynamic contracts.
The local receipt reports those two evidence kinds separately. The four affected-test
candidates are independently traced source paths, not a claim that all possible mutations
will fail all four tests. Mutation testing is outside this base suite.

Changing any source, test, POM or application configuration invalidates the fingerprint.
Review affected expected facts and evidence before explicitly updating `fixture_sha256`;
the preparation command deliberately cannot rebaseline it automatically.

Suites, rationales and the verification receipt remain outside each agent-visible project.
Application tests and build outputs remain available to both modes as ordinary evidence.
No `AGENTS.md` or Quill-specific instructions are copied into the application projects.
The benchmark runner sends only the prompt and expected key/type shape, not expected values
or oracle metadata, to the model.

## Later paired run (paid; not part of local verification)

First build and independently index each prepared project, using a local Quill binary:

```bash
quill-app/target/quill init --index-only --project target/benchmarks/frameworks/quarkus
quill-app/target/quill init --index-only --project target/benchmarks/frameworks/spring
```

Index-only mode preserves project inputs and skips MCP client configuration. Indexing
and local MCP calls do not use a model. Build capture is already configured in the pinned
fixtures; `verify --quill` produces both the independent local oracle report and the Quill
workflow receipt before any paid run. None of these 24 read-only tasks asks the model for
that receipt.
The example below **does call the model API**;
run it only when paid runs are authorized, and provide the same model/effort for both suites:

```bash
python3 scripts/quill_agent_benchmark.py \
  --suite target/benchmarks/frameworks/quarkus-orders-effectiveness.json \
  --project target/benchmarks/frameworks/quarkus \
  --quill quill-app/target/quill --quill-profile default --tool-selection all \
  --repetitions 3 --output-dir target/benchmarks/framework-captures/quarkus

python3 scripts/quill_agent_benchmark.py \
  --suite target/benchmarks/frameworks/spring-orders-effectiveness.json \
  --project target/benchmarks/frameworks/spring \
  --quill quill-app/target/quill --quill-profile default --tool-selection all \
  --repetitions 3 --output-dir target/benchmarks/framework-captures/spring
```

There are no per-task tool allowlists: both cheap controls and semantic tasks expose the
normal catalog in Quill mode. The source tools are identical in both modes. Existing runner
routing guidance remains in effect, so this measures that guided runner rather than
unprompted native-editor adoption. Existing exact fact scoring is unchanged, including
separate missing/incorrect facts; this base implementation does not add precision/recall
or a new facts-vs-format contract. See `task-evaluation.md` for capture scoring commands.

This small corpus is for protocol and correctness checks. It does not establish latency,
token savings, large-project behavior, stale-index recovery, or general DI completeness.
