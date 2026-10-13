# Quill Homebrew tap setup

The target public command is `brew install treblereel/quill/quill`. It requires a
public GitHub repository named `treblereel/homebrew-quill`, with `Formula/quill.rb`
from a published Quill release. This directory contains setup instructions and CI
for that separate repository; it is not itself a live Homebrew tap.

## Bootstrap the tap

1. Publish the embedded Maven extension version first, then a Quill native release.
   The release workflow builds and tests all platforms and verifies Homebrew
   installation on macOS ARM64/Intel and Linux ARM64/x86-64 before publication.
2. Create the public `treblereel/homebrew-quill` repository with a `main` branch.
3. Download the release's generated `quill.rb` asset into `Formula/quill.rb`.
   Copy `tap-ci.yml` from this directory to `.github/workflows/test.yml` in the tap,
   and add the project Apache-2.0 `LICENSE` and an installation README.
4. Run the tap CI and merge only after all four platform tests pass.

The formula installs the native executable and README from upstream archives.
It preserves the executable with `skip_clean` to avoid stripping its code signature.
It does not install GraalVM, Maven, a compiler, or a JDK. For Kotlin semantics users
need Java 17+ selected by `JAVA_HOME` or PATH and must run `quill init` on their
compiled Maven/Gradle project to prepare the Kotlin runtime. Formula tests use an
empty temporary project and exercise CLI version/help, MCP initialize/tools-list
and clean EOF shutdown; they do not download the Kotlin compiler.

## Automate tap updates

In the **Quill source repository**, configure:

- Actions variable `HOMEBREW_TAP_REPOSITORY=treblereel/homebrew-quill`.
- Actions secret `HOMEBREW_TAP_TOKEN`: a fine-grained token restricted to the tap
  repository with Contents and Pull requests write permissions (and Metadata read).
  The source repository's ordinary `GITHUB_TOKEN` cannot write to a second repository.

After successful release publication, the reusable `Update Homebrew Tap` workflow
verifies that the release is neither draft nor prerelease, downloads all four Unix
archives plus checksums, verifies their bytes, and regenerates the formula. It
opens or updates a `quill-v<version>` PR in the tap and enables squash auto-merge.
The tap's `main` branch requires successful `test` checks on macOS arm64, macOS
Intel, Linux x86-64, and Linux arm64; a failed check leaves the PR open. Existing
identical formulas/PRs are reused. An older release cannot downgrade the current
formula, and the same release version cannot replace existing archive checksums.

Keep all four tap checks required in branch protection and allow auto-merge in the
tap repository settings. A later release may supersede an older pending PR;
close any obsolete PR rather than merging it. Only a merged formula update makes
the new version available through `brew upgrade`.

If the variable is absent, Quill publication still produces the `quill.rb` release
asset and does not attempt to update any tap. If configured, a missing token fails
the tap-update job without undoing the already-published Quill release. Recover by
running **Actions → Update Homebrew Tap → Run workflow**, entering the published
stable tag. This also permits independent retry after GitHub/API failures.

## Generate or test locally

```sh
python3 scripts/quill_homebrew.py --version 1.0.0 --repository treblereel/quill \
  --archives /path/to/downloaded/archives --output /tmp/quill.rb
ruby -c /tmp/quill.rb
```

The archive directory must contain all four `quill-{macos,linux}-{aarch64,x86_64}.tar.gz`
files and their `.sha256` companions. Use `--previous /path/to/tap/Formula/quill.rb`
when preparing a tap update to enforce the version/checksum rules. Prerelease
formulas may be generated for testing but are never promoted by the tap workflow.

For a local temporary tap, copy the generated formula to `Formula/quill.rb`,
initialize/commit the tap Git repository, and run:

```sh
brew tap quill-local/test file:///absolute/path/to/test-tap
brew install quill-local/test/quill
brew test quill-local/test/quill
brew uninstall quill-local/test/quill
brew untap quill-local/test
```

Local archive testing may substitute the formula's GitHub release URL prefix with
a `file:///absolute/path/to/archives` prefix. Never publish that local test formula.
The release CI does this substitution only in its temporary tap before publishing
the unmodified GitHub formula. All platforms' archive hashes must still verify.

Homebrew treats a formula without bottles as a source installation, even when
`install` only copies a prebuilt executable, so it can require current Command
Line Tools on macOS. If those environment checks block local installation, this
limited harness can still execute the formula test block against an existing
binary, without claiming a successful Homebrew install:

```sh
QUILL_TEST_BINARY=/absolute/path/to/quill \
  ruby scripts/quill_homebrew_formula_test.rb /tmp/quill.rb
```

The formula's version must match that executable. Linux archives are built and
installation-tested on Ubuntu 24.04; compatibility with older glibc systems is
not established by these tests.
