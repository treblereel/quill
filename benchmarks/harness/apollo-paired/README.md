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

Questions and oracles (the CQ→Apollo mapping) are not here yet.

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
