# Releasing

## Strategy

Releases are immutable Git tags built from `main`. `gradle.properties` remains the source of truth for
the plugin version, and the release workflow accepts only a matching `vX.Y.Z` or
`vX.Y.Z-prerelease.N` tag. During the `0.x` series, minor versions may add or change behavior and patch
versions contain compatible fixes. After `1.0.0`, use ordinary semantic versioning.

When Marketplace publication is enabled, stable tags publish to its default channel. A prerelease
suffix selects a channel from its first identifier: `v0.3.0-beta.1` uses `beta`,
`v0.3.0-eap.1` uses `eap`, and so on. Prerelease tags also create GitHub prereleases. Users of a
non-default Marketplace channel must add that channel's repository URL to IntelliJ explicitly.

Every tag is rebuilt instead of reusing a branch artifact. The workflow runs unit tests, builds the
plugin, checks its structure, verifies binary compatibility against the pinned IntelliJ IDEA build,
signs the distribution, verifies the signature, and creates a GitHub release containing the signed
ZIP and a SHA-256 checksum. GitHub generates release notes from commits and pull requests since the
previous tag.

JetBrains requires the first Marketplace publication to be uploaded manually. For that reason,
Marketplace publication is controlled separately from GitHub Releases and is disabled unless the
`PUBLISH_TO_MARKETPLACE` repository variable is `true`.

## Repository Setup

Create a GitHub environment named `release`. Require reviewer approval for that environment if the
repository has more than one maintainer, then add these environment secrets:

- `CERTIFICATE_CHAIN`: the PEM certificate chain used to sign the plugin
- `PRIVATE_KEY`: the matching PEM private key
- `PRIVATE_KEY_PASSWORD`: the private-key password
- `PUBLISH_TOKEN`: a JetBrains Marketplace permanent token; this is needed only after enabling
  Marketplace publication

Generate and store the signing material according to the
[JetBrains plugin-signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
Never commit it. Keep an encrypted offline backup: losing the key prevents producing updates signed
with the same author certificate. Store the PEM text directly in the secrets; the workflow writes it
to permission-restricted temporary files because both signing and signature verification consume the
same file-backed certificate configuration.

Protect `main` and require the `Test, build, and verify` check before merging. Restrict tag creation
for `v*` tags to maintainers when the repository rules feature is available. These controls keep the
release workflow's tag trigger behind the same reviewed commit and CI gate used by ordinary changes.

Before the first Marketplace release:

1. Leave `PUBLISH_TO_MARKETPLACE` unset or set to `false`.
2. Publish a tag and download the signed ZIP from its GitHub release.
3. Create the plugin entry and upload that ZIP manually in JetBrains Marketplace.
4. Add `PUBLISH_TOKEN` to the `release` environment.
5. Set the repository variable `PUBLISH_TO_MARKETPLACE` to `true` for later tag releases.

## Release Procedure

1. Complete the manual checks in [DEVELOPMENT.md](DEVELOPMENT.md#release-checks).
2. Update `pluginVersion` in `gradle.properties` to the intended semantic version.
3. Ensure user-facing documentation describes the release and CI passes on `main`.
4. Create and push an annotated matching tag:

```sh
git tag -a v0.3.0 -m "Release 0.3.0"
git push origin v0.3.0
```

5. Approve the `release` environment deployment, if protected.
6. Confirm the workflow attached one signed ZIP and one checksum to the GitHub release.
7. If Marketplace publication is enabled, confirm the update reached the expected channel and passed
   JetBrains review.

Do not move or reuse a release tag. If a release fails before publication, fix the cause in a new
commit, update the version, and create a new tag. If GitHub release creation succeeded but a later
step failed, rerunning the same workflow safely replaces its release assets; do not publish duplicate
Marketplace versions.
