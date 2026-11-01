# Google Play listing

Title: **Pen Library for tiptoi**. Independent developer branding comes first;
compatibility is explicit. No Ravensburger logo, official-app claim or bundled
publisher artwork/audio. Category: Tools. Intended audience: adults managing
family reading pens; this app is a file-management utility, not a children's
reading game. Free, no advertisements or purchases.

`{en-US,de-DE,fr-FR}/listing.json` owns localized descriptions. Each locale has
a 1024 × 500 feature graphic and four unedited 1080 × 1920 actual app captures.
Shared `icon.png` is 512 × 512. Screenshots are losslessly exported as RGB
PNG (the unused opaque alpha channel is removed); captured pixels are unchanged.
The capture/export scripts require ImageMagick (`magick`), and art export also
requires `rsvg-convert`. Validate with `python3 scripts/check_store.py`. Generated original illustration and provenance
are documented in [art](../art/README.md).

Rebuild the release APK and install it on a dedicated disposable emulator.
Never point this capture tool at a household phone or clear its storage:

```
adb -s emulator-SERIAL install -r android/app/build/outputs/apk/release/app-release.apk
python3 scripts/store_screenshots.py --serial emulator-SERIAL --locale en-US
python3 scripts/store_screenshots.py --serial emulator-SERIAL --locale de-DE
python3 scripts/store_screenshots.py --serial emulator-SERIAL --locale fr-FR
```

All sample content is visibly labeled. The demo is available to every user
from My pens and Help. File changes, downloads and cache cleanup are blocked
in demo mode. It does not claim to test hardware or playback.

Review access instructions: No login. On My pens choose Explore demo to inspect
sample pens, library, book details and shared selections. Exit demo to manage
real hardware. For real transfer testing, connect a compatible tiptoi pen as
USB mass storage using a data cable and choose its top-level folder in Android.
Physical acceptance to date covers one fourth-generation pen. Keep the app in
the foreground while copying; unplug to test audio playback on a physical book.

Privacy: https://kzahel.github.io/tiptoi-manager/privacy/
Support: https://github.com/kzahel/tiptoi-manager/issues
