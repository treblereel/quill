# Apollo paired benchmark environment

Spring Boot counterpart of the Cryostat paired harness: the same four arms, run on
[apolloconfig/apollo](https://github.com/apolloconfig/apollo) (Boot 4.1, Maven, 8 modules).

| | without Quill | with Quill |
|---|---|---|
| Codex | clean fixture, no MCP, no hooks | `.quill` index + `quill init` configs, MCP via tap, prefetch hooks |
| Claude | clean fixture, no MCP, CLAUDE.md off, hooks off | same as Codex row, Claude profile |

## Prepare (once, no provider calls)

```bash
python3 benchmarks/harness/apollo-paired/prepare_fixture.py [--force]
```

Clones `/Users/treblereel/workspace/llm/apollo` at the pinned revision into the disposable
`/tmp/quill-apollo-paired-v1/project` (the original is only read), builds it online once so
`~/.m2` is complete, proves an offline `test-compile`, runs `quill init`, and snapshots
`ready-target/` (every module's `target/`) and `ready-index/`. Codex and Claude CLIs come
from the Cryostat vault (`~/.cache/quill-cryostat-paired-v7`), so versions match that run.

Fixture adaptation shared by both arms: `apollo-portal` generates its OpenAPI interfaces
from a spec on `raw.githubusercontent.com`. The spec is pinned into `build-settings/` and
`.mvn/maven.config` (git-excluded) points `apollo.openapi.spec.url` at it, so builds work
under Claude's no-network sandbox.

## Run

```bash
python3.14 benchmarks/harness/apollo-paired/arm.py preflight codex with_quill
python3.14 benchmarks/harness/apollo-paired/arm.py ask claude without_quill "Question..."
```

`preflight` starts the client (and Quill MCP to readiness) without a model turn. `ask` is
paid. Results go to `benchmarks/results/apollo-paired/` (not in git).

Each session restores sources, `target/` dirs and file times; the clean arm also strips
the `quill-*` build receipts and is denied the Quill binary and Maven extension. Seatbelt
isolation is the Cryostat one: only the fixture, the session dir, private homes, `~/.m2`
and JDKs are readable; the original checkout and this harness are probed as unreadable.

## Questions and oracles

`questions.py` holds the prompts (public) and `oracles.py` / `not_applicable.py` the expected
facts (private: the session sandbox cannot read this directory, and the isolation probe checks
`data/oracles.json`). `python3 build_data.py` writes `data/questions.json`, `data/oracles.json`
and `data/not-applicable.json`; it reads evidence with `git show REV:path` from the fixture,
records line numbers and sha256 for every evidence regex, and fails if a regex or a class
named in a prompt does not exist at the pinned revision.

Each `AQ-NNN` mirrors Cryostat `CQ-NNN` with a Spring counterpart: 44 applicable cases (42
independent, `AQ-049` patch, `AQ-050` six linked turns) and 6 frozen N/A cases. The anchors are
the portal permission validators (`UnifiedPermissionValidator` delegating by a ThreadLocal auth
type), the `AuthConfiguration` profile/missing-bean beans, the OpenAPI release route
`POST /openapi/v1/envs/{env}/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases`
(generated `ReleaseManagementApi`, `@PreAuthorize` SpEL by bean name), `ConfigPublishListener`
(event plus executor hand-off), the DB-backed `RefreshableConfig` settings
(`emergencyPublish.supported.envs`), adminservice aspects and transactions, the manual
`DatabaseDiscoveryClient` decorator chain and the `@Lazy`-broken cycles in biz services. The
`AQ-049` reference rename and the `AQ-046` narrow tests were run on a disposable clone (12/12
`UnifiedPermissionValidatorTest`, 53/53 `ReleaseBranchInstanceControllerTest`).

```bash
python3 benchmarks/harness/apollo-paired/build_data.py
python3.14 benchmarks/harness/apollo-paired/arm.py ask codex with_quill --case AQ-031
```

`--case` sends the shared context block plus the case prompts as one session, like the
Cryostat schedule.

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

## Surviving a reboot

macOS clears `/tmp` on restart. `python3 vault.py save` APFS-clones the prepared fixture into
`~/.cache/quill-apollo-paired-v1` (no extra disk space) with a hash manifest of the Quill binary,
client CLIs, index, build output and file times; `arm.py` runs `vault.py restore` whenever
`/tmp/quill-apollo-paired-v1/fixture.json` is missing, and `vault.py check` compares the live fixture
with the vault. Re-running `prepare_fixture.py` instead would rebuild with whatever Quill and CLIs
are installed then, which no longer compares with the recorded baseline
(`benchmarks/analysis/apollo-baseline-v1/`).

Two fixture details the Quill readiness gate depends on:

- `mvnw` runs `maven-wrapper.jar`, which ignores `MAVEN_ARGS`. `--settings`/`--toolchains`
  therefore live in the git-excluded `.mvn/maven.config`; otherwise builds read the user's
  `~/.m2/settings.xml` (credentials, unreadable in the sandbox) and the Maven extension
  records it as a compilation input that never matches inside a session.
- Ignored build output outside `target/` (`.flattened-pom.xml`, wrapper jar) is recorded
  as a compilation input too. It is kept in `ready-extra/` and restored after `git clean`.
  Targets are restored with APFS clones (`cp -cRp`), since macOS `rsync` drops sub-second
  mtimes and makes classes look older than their sources.

## Quill prerequisite

The fixture needs a native Quill that treats `apollo-buildtools` (only `src/main/scripts`
and `src/main/resources`, `target/classes` with copied resources) as not class-producing.
Older binaries report it as permanently `build_required`, and the MCP readiness gate
never passes. `prepare_fixture.py` requires a clean `get_overview` and `quill doctor`, so
it fails fast on such a binary.
