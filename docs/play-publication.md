# Play publication

Brand: Pen Library; store title: Pen Library for tiptoi. Package:
`io.github.kzahel.tiptoimanager`. The `.debug` preview remains a separate app.
The release is a free, independent USB utility for parents/adults, with no ads,
accounts, purchases or backend. Do not claim Ravensburger endorsement.

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
