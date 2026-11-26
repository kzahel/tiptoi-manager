# Play publication

Brand: Pen Library; store title: Pen Library for tiptoi. Package:
`io.github.kzahel.tiptoimanager`. The `.debug` preview remains a separate app.
The release is a free, independent USB utility for parents/adults, with no ads,
accounts, purchases or backend. Do not claim Ravensburger endorsement.

## First public release

Production 0.1.0 (102) was submitted on 1 November 2026. The package and
localized listing are publicly available on [Google Play](https://play.google.com/store/apps/details?id=io.github.kzahel.tiptoimanager).
The original [CI candidate and source receipt](https://github.com/kzahel/tiptoi-manager/releases/tag/v0.1.0-rc102)
record the submitted bundle and its checksum. Play App Signing uses a dedicated
Google-managed app-signing key.

## Release from Git

1. Commit the intended app version, three localized notes, and reviewed listing
   assets to `main`. Check translations, unit tests, lint, and any physical
   acceptance evidence in [acceptance](acceptance.md).
2. Tag that main commit `play-vMAJOR.MINOR.PATCH-CODE`, for example
   `play-v0.2.0-103`, and push the tag. The version name must match
   `android/app/build.gradle.kts`. Use an increasing Play version code.
   CI builds and verifies a signed AAB, runs the read-only demo on an emulator,
   captures German screens, and publishes a versioned GitHub candidate with
   checksums, source receipt and the listing archive. The same AAB goes to
   the Play internal track.
3. Check the internal track and the Android build. To submit to all production
   users, add and push `play-promote-vMAJOR.MINOR.PATCH-CODE` at the exact
   same commit. The publisher verifies the Play bundle's hash against the
   candidate and checks that the version is on internal, then submits
   production and changed listing assets in one edit. Review and delivery are
   separate from a successful submission.
4. For a listing-only revision, commit the copy and assets, then push a unique
   `play-listing-*` tag at that main commit, for example
   `play-listing-2026-11-26`. This submits localized text, artwork and
   screenshots without building a new app. The icon is shared through the
   default English locale. Other listing fields and locales are preserved.

Wait for each Play submission before pushing the next publishing tag. A
manual run of **Google Play publication** on main checks access, tracks and
listing locales without saving an edit. A manual **Android release candidate**
run makes GitHub artifacts only. Use different version codes for separate
candidates. If only an upload job fails after the GitHub candidate exists, rerun
the failed jobs to reuse the recorded bundle. If a commit response is lost,
check the Console and the access run before deciding whether to retry. Edits
fail when changes are already under review, preserving the pending review.

The direct-download release APK is signed with the upload key. Play
installs use the Play app-signing key. Switching install channels can require
uninstalling, which erases local app data. The `.debug` package is separate.

## CI access

`play-publishing` uses a dedicated app-scoped service account with short-lived
GitHub OIDC credentials. Set the GitHub environment variables
`PLAY_WIF_PROVIDER` and `PLAY_SERVICE_ACCOUNT` after provisioning. The provider
allows this repository's numeric GitHub IDs, the publishing environment
subject, and the expected workflow/ref pairs. In Play Console grant
Pen Library app access for app information, testing and production releases,
and store presence. Leave account-wide grants empty. See
[Play API setup](https://developers.google.com/android-publisher/getting_started)
and [GitHub federation](https://github.com/google-github-actions/auth#workload-identity-federation).

`PLAY_UPLOAD_KEYSTORE_BASE64` and `PLAY_UPLOAD_STORE_PASSWORD` are signing
credentials. `CATALOG_CLIENT_ID` and `CATALOG_CLIENT_SECRET` configure the
publisher's application catalog registration. Do not log their values.
Keep recovery material and account identifiers in private publisher inventory.

## Review disclosures

- No login; demo is publicly available from My pens and Help.
- Real transfers need compatible USB mass-storage hardware and a data cable.
- The current publisher catalog/audio is German; UI has three languages.
- A physical fourth-generation pen is tested; additional generations and a
  second real pen remain separate acceptance work.
- Removing a book creates a verified phone backup; uninstall/clear app storage
  erases local copies/backups. Sync only adds; it never evicts extra pen files.
- File modification dates are not last-played dates.
- Only INTERNET is requested in the manifest. USB access uses Android's explicit
  folder picker; no broad storage, location, advertising-ID or account permission.
- No telemetry SDK or first-party collection. Direct publisher requests expose
  ordinary network/request information; privacy policy explains that. Do not
  claim that the app never contacts external servers or assume publisher logs
  are ephemeral without evidence. Data safety responses must match actual
  network behavior and Google's current definitions.

Artwork and exact localized copy live in `store/`; provenance in `art/`.
Privacy/support site is deployed from `site/` using GitHub Pages.
