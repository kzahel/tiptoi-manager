# Localization

English is the source/fallback in `android/app/src/main/res/values/strings.xml`.
German and French are bundled offline, including when Android App Bundles are
used. Help offers an app-language selector; Android 13+ also exposes it in
system Settings. This changes the interface, not publisher audio language.

The Android resource adapter, exact-source hashes, placeholder/plural checks,
atomic imports and terminology tooling are adapted from the MIT-licensed
[kzahel/web-server-chrome](https://github.com/kzahel/web-server-chrome) pipeline
(copyright Kyle Graehl). CI never calls a translation service or model.

```
python3 scripts/i18n.py check
python3 -m unittest discover -s scripts -p 'test_localization*.py'
python3 scripts/localization_android.py export de --out-dir .local/i18n/de
python3 scripts/localization_android.py validate-response de --job JOB --response RESPONSE
python3 scripts/localization_android.py import de --job JOB --response RESPONSE \
  --translator ACTUAL_TRANSLATOR_SESSION --reviewer ACTUAL_DISTINCT_REVIEW_SESSION
```

Independent imports retain the upstream strict review gate. The first release
was translated and checked in one authoring session, without independent
translation workers. Its separate `localization/author-checks/` ledger records
that fact, source/translation/terminology hashes, and the real authoring
campaign identity. It does not invent independent reviewers or mark these
resources agent-reviewed. `i18n.py check` accepts either current independent
review or current single-session author checks, reporting each separately.
The strict adapter `check` continues to report resources without independent
review. A later independent campaign can replace that provenance honestly.

After actually reviewing an intentional edit (never just to silence CI):

```
python3 scripts/i18n.py record-author-check --locale de --author ACTUAL_SESSION
```

Translate complete messages; preserve positional argument types/indices, URLs,
filenames, product identifiers and trademark spelling. Never translate persisted
wire keys or publisher HTML field names. `localization/terminology/` owns each
language's register and terms. Real file dates are not playback history. Pen
backups remain distinct from reusable downloads and transfer-only staging.
After changes, build/lint the app and inspect affected screens in each locale,
including dialogs, large font and narrow display. Do not put household data in
public screenshots; use the visible, read-only sample library.
