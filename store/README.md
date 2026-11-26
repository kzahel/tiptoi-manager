# Google Play listing

**Pen Library for tiptoi** is a free, independent Android utility for adults
managing family reading pens. The listing is available in English, German and
French. Catalog and book audio are in German.

Each locale contains its Play text, a 1024 × 500 feature graphic, and four
1080 × 1920 phone images in display order:

1. The app's guided USB connection screen.
2. Browsing the catalog with public demo titles.
3. The install action with the optional transfer-only setting.
4. Home and travel pen profiles from the public demo.

The screenshots are actual app views placed below localized captions. The demo
views are visibly labeled, and the transfer image shows the action before
copying starts. No household data or publisher covers appear. The feature
graphics reuse the original illustrated artwork documented in [art](../art/README.md).
The shared icon is 512 × 512.

Check exports with `python3 scripts/check_store.py`. Capture with Android 13+
on a dedicated disposable emulator with no pen or private data. Use Android
platform-tools and ImageMagick. The tool changes the emulator app locale and
temporarily selects Transfer only to show its install action.

```sh
adb -s emulator-SERIAL shell wm size 1080x1920
adb -s emulator-SERIAL shell wm density 420
adb -s emulator-SERIAL install -r android/app/build/outputs/apk/release/app-release.apk
python3 scripts/store_screenshots.py --serial emulator-SERIAL --locale de-DE
python3 scripts/store_screenshots.py --serial emulator-SERIAL --locale en-US
python3 scripts/store_screenshots.py --serial emulator-SERIAL --locale fr-FR
```

Optional `--font` and `--bold-font` override the DejaVu Sans caption fonts.
Use `--output` to inspect new exports outside the tracked listing. CI attaches
the checked-in listing and fresh German QA screenshots to the candidate.

After reviewing and committing an update, push the tags documented in the
[Play publication runbook](../docs/play-publication.md). CI uploads the
checked-in metadata, feature graphics and phone screenshots directly to Play.

[Support](https://kzahel.github.io/tiptoi-manager/de/) · [Privacy](https://kzahel.github.io/tiptoi-manager/privacy/)
