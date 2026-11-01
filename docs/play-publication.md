# Play publication

Brand: Pen Library; store title: Pen Library for tiptoi. Package:
`io.github.kzahel.tiptoimanager`. The `.debug` preview remains a separate app.
The release is a free, independent USB utility for parents/adults, with no ads,
accounts, purchases or backend. Do not claim Ravensburger endorsement.

## Submission status — 1 November 2026

Production **0.1.0 (102)** was submitted through Play Console. The Console
confirmed **“13 changes sent for review”** and **“Changes in review”**. This is
not yet an approved or publicly downloadable Play listing. Managed publishing
is off: approval is configured to publish the full production rollout in all
178 available countries/regions automatically. Google’s automatic checks and
human review can still identify issues.

- [CI candidate and source receipt](https://github.com/kzahel/tiptoi-manager/releases/tag/v0.1.0-rc102)
- [Successful release workflow](https://github.com/kzahel/tiptoi-manager/actions/runs/41324251905)
- App source: `a274c9367f78e68039fa3a8ac7179ba34b809070`
- Uploaded AAB SHA-256: `5a28c66a9bd117087618e9a08b1ea5b0b5866bd7bc07dcfacf1c43116a5ecee4`
- English, German and French listings, release notes, original icon/feature
  art, and four actual demo screenshots per language submitted together.
- [Privacy policy](https://kzahel.github.io/tiptoi-manager/privacy/) and support
  website are live. No ads, advertising ID, purchases or account requirement.
- Play App Signing is enabled; the optional installer check is disabled.
  Automatic app-text translation is disabled because the app bundles its three
  supported languages.

Play reported a missing deobfuscation-file warning; this build explicitly has
`isMinifyEnabled = false`, so it has no obfuscation mapping. A pre-review check
also reported the privacy URL as missing. A fresh HTTPS GET returned the complete
policy with HTTP 200; the Console’s **Proceed anyway** path was used for that
incorrect finding. The advertising-ID declaration was then completed as **No**.
Reviewer access instructions describe the read-only demo and the USB hardware
needed for actual transfers. Google’s review remains the publication gate; do
not promote the candidate’s GitHub prerelease as evidence of Play approval.

## Candidate flow

1. Run `python3 scripts/i18n.py check`, localization-tool regression tests,
   Android unit tests and lint. Inspect actual English/German/French UI and
   preserve physical acceptance findings in `docs/acceptance.md`.
2. Push reviewed source to main. Dispatch **Android release candidate** on main
   with an unused increasing version code (first candidate: 101). Signing uses
   the repository's dedicated upload key; release secrets never enter PR jobs.
3. CI builds APK/AAB, verifies the bundle signature and publishes an immutable
   GitHub prerelease with SHA-256 sums, source commit, version and workflow URL.
   A tag or artifact alone is not Play approval.
4. For the first release, upload that exact CI AAB through Play Console. Use
   Play App Signing with a dedicated Google-generated app-signing key, distinct
   from the backed-up upload key. Never use another project's signing identity.
   Play distributes APKs signed with Google's separate app-signing key. The
   direct-download CI APK uses the upload identity; it is a separate install
   channel for the production package. Switching signing identities can require
   uninstalling, which erases local backups. Existing `.debug` previews have a
   separate package and remain untouched. Prefer Play for normal production
   updates once available.
5. Complete store listings for en-US/de-DE/fr-FR, support/privacy links, content
   declarations, rating, countries and release notes. Inspect the final overview
   and submit the authorized production release for review. Record actual
   submission/review status; do not describe an unreviewed listing as public.

The initial Console bootstrap is deliberate. The GitHub workflow does not claim
an unconfigured Publisher API account or automatic production promotion. Future
API publication should use dedicated app-scoped short-lived GitHub OIDC/WIF
credentials, main/environment bindings, exact-source CI receipts, transactional
edit validation, and fail-closed handling of ambiguous commits. Never borrow
another app's service account or widen its Play grants.

`PLAY_UPLOAD_KEYSTORE_BASE64` and `PLAY_UPLOAD_STORE_PASSWORD` are production
upload credentials. `CATALOG_CLIENT_ID` and `CATALOG_CLIENT_SECRET` configure the
publisher's application catalog registration, not personal user authentication.
Do not print any of these values. Local key backups and account/app identifiers
belong in private publisher inventory, not this public runbook.

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
