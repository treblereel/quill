# Releasing Quill

Quill releases are built from tags named `v<version>`. The release workflow runs the
complete JVM and native test suites, builds each supported platform on its native
GitHub-hosted runner, publishes SHA-256 checksums and an SPDX JSON SBOM, and records
GitHub build-provenance and SBOM attestations for every archive.

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
