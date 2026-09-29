# Releasing Quill

Quill releases are built from tags named `v<version>`. The release workflow runs the
complete JVM and native test suites, builds each supported platform on its native
GitHub-hosted runner, publishes SHA-256 checksums and an SPDX JSON SBOM, and records
GitHub build-provenance and SBOM attestations for every archive.

## Prepare a release from GitHub Actions

Add a repository secret named `RELEASE_TOKEN`. Use a fine-grained personal access token or
GitHub App token that can write repository contents and whose pushes are allowed to trigger
workflows. If the default branch is protected, allow that identity to push the Maven release
commits and tag.

Then open **Actions → Prepare Release → Run workflow**, select the default branch, and enter:

- the release version without a `v` prefix, for example `0.1.0`;
- the next development version ending in `-SNAPSHOT`, for example `0.2.0-SNAPSHOT`.

The preparation workflow runs the complete JVM verification through Maven Release Plugin,
commits the release versions, creates and pushes `v<version>`, and commits the next development
versions. The pushed tag starts the existing Release workflow. A dedicated `RELEASE_TOKEN` is
required because tags pushed with the workflow's default `GITHUB_TOKEN` do not start another
workflow run.

The Maven release workflow does not run `release:perform` and does not deploy Maven artifacts.
The GitHub release contains only platform-specific Quill native archives, their SHA-256 checksum
files, and the SPDX SBOM. The shaded JAR is used inside CI only as input for SBOM generation.

For emergency or local preparation, the equivalent command is:

```bash
./mvnw release:clean release:prepare \
  -DreleaseVersion=0.1.0 \
  -DdevelopmentVersion=0.2.0-SNAPSHOT \
  -Dtag=v0.1.0 \
  -Darguments="-DskipITs=false"
```

## Supported native archives

- Linux x86-64 and AArch64
- macOS Apple Silicon and Intel
- Windows x86-64

The release version comes from the tag. Create the tag only from a clean commit that
has passed CI; the workflow rejects a tag that GitHub cannot verify for the release.

## Platform signing

Signing is optional while the project is under development. Set the repository
variable `REQUIRE_PLATFORM_SIGNING=true` before a production release to make missing
or incomplete credentials fail the build instead of producing unsigned binaries.

For macOS, configure these Actions secrets:

- `APPLE_CERTIFICATE_BASE64`: base64-encoded Developer ID Application `.p12`
- `APPLE_CERTIFICATE_PASSWORD`: password for that `.p12`
- `APPLE_SIGNING_IDENTITY`: full Developer ID Application identity
- `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`: notarytool credentials

For Windows, configure:

- `WINDOWS_CERTIFICATE_BASE64`: base64-encoded Authenticode `.pfx`
- `WINDOWS_CERTIFICATE_PASSWORD`: password for that `.pfx`

Credentials are imported only into the ephemeral runner. The workflow signs before
creating the archive, verifies the signature, and submits signed macOS executables to
Apple's notarization service.

## Verifying a download

First verify the adjacent checksum file:

```bash
shasum -a 256 -c quill-linux-x86_64.tar.gz.sha256
```

On Windows:

```powershell
$expected = (Get-Content quill-windows-x86_64.zip.sha256).Split()[0]
$actual = (Get-FileHash quill-windows-x86_64.zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "Checksum mismatch" }
```

For public repositories, GitHub's cryptographic provenance can also be checked with:

```bash
gh attestation verify quill-linux-x86_64.tar.gz --repo OWNER/REPOSITORY
gh attestation verify quill-linux-x86_64.tar.gz --repo OWNER/REPOSITORY \
  --predicate-type https://spdx.dev/Document/v2.3
```

Artifact attestations prove which workflow and commit produced a download; they do
not replace vulnerability review. Pull requests additionally run GitHub dependency
review and reject newly introduced dependencies with moderate-or-higher known
vulnerabilities.
