# Android CI and distribution

The first spike is public MIT source with experimental APKs, not a production
release or Play listing. The Android workflow runs unit tests, lint and an APK
build for app changes. It uploads the APK, SHA-256 checksum and source receipt
as a 30-day Actions artifact. Trusted `main` builds additionally publish an
immutable `preview-N` GitHub prerelease at that build's exact source revision.

## Signing and installation

Public previews use a dedicated project key supplied through GitHub secrets
`PREVIEW_KEYSTORE_BASE64` and `PREVIEW_STORE_PASSWORD`. Untrusted PR builds never
receive those secrets and use a disposable debug identity. Signing files are
decoded into a private runner temporary directory and removed after the build.
The publication job gets repository write permission separately from the build.

Trusted previews also receive the publisher application-catalog configuration
through `CATALOG_CLIENT_ID` and `CATALOG_CLIENT_SECRET`; values stay out of Git.
They are a distributed app registration, not personal credentials or a
confidential secret in the APK. No end-user sign-in is required. PR builds
retain CSV/cached-data fallback. See [metadata configuration](book-metadata.md).

The preview application ID is `io.github.kzahel.tiptoimanager.debug`. Main CI
builds use the workflow run number as their version code, allowing later
previews to update earlier previews with the same key. Check `source.json` and
`SHA256SUMS` alongside the APK. Preview artifacts are debuggable and should be
treated as experimental software.

Local builds normally use the developer's Android debug key. They cannot
update a CI preview signed by the dedicated key, or vice versa, without an
uninstall (which clears the app's downloads, pen backups and folder grant). An authorized
maintainer can use the preview key by setting `TIPTOI_PREVIEW_SIGNING_DIR`, or
the ignored `android/local.properties` entry `previewSigningDir`, to a private
directory containing `preview.jks` and `password.secret`. The alias is `preview`.
Use `TIPTOI_VERSION_CODE` for a compatible local preview version code.

Private recovery copies of that key and password are kept outside the Git
checkout. Back them up together; never commit them or reuse another app's key.
GitHub secrets provide CI delivery, not a downloadable key backup.

## Later release decisions

Tagged stable releases, a production signing identity, an AAB and Play
publication are later decisions. Physical USB/playback acceptance remains
separate from CI. An emulator cannot establish cable, pen or provider support.

Owned workflow references remain pinned in `repos.yaml`:

- [Babytrack](https://github.com/kzahel/babytrack): Android checks and signing.
- [JSTorrent](https://github.com/kzahel/jstorrent): APK release artifacts.
- [YepControl](https://github.com/kzahel/yepcontrol): source provenance and
  separation of GitHub releases from Play publication.
