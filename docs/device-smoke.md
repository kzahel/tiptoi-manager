# Opt-in USB smoke runner

`UsbSmokeInstrumentation` is in the separate Android test APK, not the manager
APK. It invokes the real application's storage, catalog, cache and install
operations without touching the foreground UI. Grant the pen folder normally
through the manager first. Use an explicitly selected, authorized phone; tests
do not discover or retarget another device.

Build and install matching app/test APKs with the same signing configuration:

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s YOUR_PHONE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_PHONE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Read-only inventory by default (no network or pen writes):

```sh
adb -s YOUR_PHONE shell am instrument -w -r \
  io.github.kzahel.tiptoimanager.debug.test/io.github.kzahel.tiptoimanager.UsbSmokeInstrumentation
```

An explicitly authorized standard-book test can add these arguments before the
instrumentation component:

```text
-e refreshCatalog true -e downloadProductId PRODUCT_ID -e installProductId PRODUCT_ID
```

Omit `installProductId` for a download-only test. The runner refuses installs
without an identical download selection, ambiguous multi-file product IDs and
existing destination filenames. For an install, it checks the final file's
complete GME integrity and SHA-256, then compares the prior inventory's names,
sizes and IDs. This is not a full hash comparison of all existing content.

Success is `INSTRUMENTATION_CODE: -1` and a passed stream message. Failure is
code `0`; ADB's process exit status alone is not the acceptance result. Output
contains counts, so retain raw results privately. This runner proves real
storage operations, not every UI interaction, playback or hot-unplug recovery.

For metadata without requiring an attached pen, pass `-e metadataProductId ID`.
It refreshes the catalog, verifies an unambiguous audio-file match, reads a
publisher-confirmed ISBN and decodes cached thumbnail/detail artwork. A
subsequent `-e metadataProductId ID -e metadataCachedOnly true` run uses only
local catalog, page details and image files and makes no metadata requests.
These modes do not download or install audio files.

To test manual removal without selecting a real installed book, pass
`-e removalFixtureProductId ID` for an unambiguous, already-cached product. This
mode makes no download requests. It installs a uniquely named disposable copy,
uses the app's removal action, reloads the persisted backup, restores and hashes
the copy, checks refusal to overwrite, and cleans up only that unique fixture.
It compares the original inventory's filenames, sizes and IDs after removal and
cleanup. This is an opt-in pen-write test, never automatic cleanup of real books.

`-e cacheFixtureProductId ID` exercises retention using an already-cached,
unambiguous source product. It temporarily selects Transfer only, installs a
uniquely named/keyed cached copy and checks source deletion after read-back,
then downloads and installs a new disposable copy and checks temporary cleanup.
Phone deletion/clear scopes are exercised in separate fixture directories.
It restores the prior retention preference and compares original phone/pen
inventories; real originals are not selected for cleanup. This mode makes a
publisher audio request. Download-only smoke mode requires Keep downloads.
