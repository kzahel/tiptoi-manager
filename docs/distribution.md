# Android CI and distribution direction

## Current state

The repository is public source under MIT. Bootstrap GitHub Actions checks the
reference manifest and Python helper. **There is no Android implementation,
buildable APK, project signing identity or artifact publishing workflow yet.**
The owner requested a check-in before the first app spike; this document records
the intended release direction without starting that spike.

## Desired workflow

1. Ordinary pushes and pull requests run the app's relevant unit checks, Android
   lint and a debug build once the app exists. Physical USB behavior has separate
   acceptance evidence; an emulator cannot prove real pen connectivity.
2. CI produces downloadable preview APKs so the owner can install a candidate
   without maintaining a local Android build environment.
3. Version tags such as `android-v0.1.0` build and publish a signed APK on GitHub
   Releases with checksums, source revision and release notes. Stable signing
   must allow updates over previously installed previews.
4. Add an AAB and a separate Play publication path only when store distribution
   is requested. AAB artifacts alone are not directly installable previews.

Choose the application ID and signing strategy at the kickoff checkpoint. Use a
dedicated project key, record its private recovery location outside this public
repository, and store CI credentials through the selected GitHub environment.
Ordinary untrusted PR checks do not receive signing credentials. Do not reuse
another product's private key or copy its Play configuration.

## Owned release references

The manifest pins public versions of these repositories. These are examples to
adapt, not runtime dependencies or authorization to publish other products.

| Reference | Useful entry points |
| --- | --- |
| [Babytrack](https://github.com/kzahel/babytrack) | `.github/workflows/android-internal.yml`, `.github/workflows/android-publish.yml`, `docs/topics/mobile-release-signing.md` |
| [JSTorrent](https://github.com/kzahel/jstorrent) | `.github/workflows/android-release.yml`, `docs/android-release.md` |
| [YepControl](https://github.com/kzahel/yepcontrol) | `.github/workflows/android-release.yml`, `.github/workflows/android-publish.yml`, `docs/distribution/android-beta.md` |

Reuse the useful shape: meaningful checks, declared versioning, stable signing,
checksums, source provenance, and a clear difference between a downloadable APK
and Play publication. Tiptoi Manager does not inherit their relays, accounts,
background-service requirements or product-specific release machinery.
