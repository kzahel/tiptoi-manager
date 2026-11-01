# Tiptoi Manager

An independent, open-source Android manager for tiptoi pens: see what is on a
pen, install books over USB, manage space, and keep a family's pens in sync.

**Status: experimental native Android spike.** USB inventory has been exercised
on a real fourth-generation pen. Catalog search, offline downloads and verified,
non-overwriting installs, manual removal with verified phone backups and offline
restoration are implemented; see the [acceptance record](docs/acceptance.md)
for what has actually been tested. This is not a stable release.

[Preview APKs](https://github.com/kzahel/tiptoi-manager/releases) are built and
published automatically from `main`. Previews use a dedicated signing key and
the application ID `io.github.kzahel.tiptoimanager.debug`.

## Motivation

Adding a book should not require diagnosing a charging station's Wi-Fi,
installing a desktop manager, or manually finding and copying files through
several mobile apps. Families with multiple pens should be able to see what
each one contains and make deliberate, understandable changes.

The fourth-generation pen exposes its content as USB mass storage. Standard
book files can be downloaded from Ravensburger without a personal account.
That makes a local Android USB manager practical without a charging station
or a hosted application service.

## Vision

- **Connected pen:** installed titles, versions, grouped files, free space and
  clear transfer progress.
- **Library:** browse a cached catalog, download selected titles, and retain
  files on the phone for offline installation.
- **My pens:** local nicknames and independent inventories, clearly labeled
  with their last observation when disconnected.
- **Sync:** apply a shared book selection to two or more pens, connecting them
  one at a time and reusing the same local downloads.
- **Cleanup:** pin favorites, explain space requirements, and suggest removals
  using information the app actually knows.

No account, analytics, cloud backend, or remote pen inventory is needed.
Networking is limited to explicit catalog/content downloads and, if later
implemented, explicit update checks. Cached content and pen management should
work offline.

## First goal

Prove that an Android phone can inspect a real
pen, identify installed content and free space, and install one selected book
with a verified result. Then add local caching, multiple-pen inventory and
shared selections. Prefer a small, working vertical slice before a full UI.

True least-recently-played cleanup is not established: there is no documented
playback-history field to rely on. Installation and synchronization dates are
different from playback dates. Also, product IDs are not unique file keys:
some official content groups intentionally contain multiple files with one ID.

## Start here

- [Agent instructions](AGENTS.md)
- [Product scope and implementation checkpoint](docs/vision.md)
- [Protocol research and unresolved questions](docs/research.md)
- [Reference repositories and licenses](docs/references.md)
- [Android CI and publication direction](docs/distribution.md)
- [Physical acceptance and remaining checks](docs/acceptance.md)
- [Opt-in device smoke runner](docs/device-smoke.md)
- [Navigation and screen responsibilities](docs/navigation.md)
- [Publisher metadata, covers and offline caching](docs/book-metadata.md)
- [Phone audio cleanup and retention](docs/phone-storage.md)
- [Named pen profiles and shared selections](docs/multi-pen.md)

## Reference checkouts

`repos.yaml` pins upstream source references. Checkouts live in the ignored
`references/` directory; they are not submodules, build inputs or bundled code.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python scripts/references.py validate
.venv/bin/python scripts/references.py sync
.venv/bin/python scripts/references.py status
```

On Windows, use the corresponding Python executable inside the virtual
environment. The helper also accepts `--only NAME` to select one reference.
Existing dirty checkouts are preserved and cause synchronization to stop.

## Build and try the spike

Use JDK 17 and an Android SDK with platform 36. From `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb -s YOUR_PHONE install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Connect a pen to the phone with a data-capable cable or OTG adapter.
2. Open My pens, choose the pen's top-level USB folder, and allow access.
3. Use the hamburger menu to open Browse books and load the German catalog.
4. Search for a title or product ID, open the book, and choose Download to phone.
5. Open the saved book from Downloads and choose Install on pen. Keep the app
   open until Transfers reports completion. Unplug and check playback with the book.

My pens recognizes previously connected pens and stores local names and inventories.
Open a profile to rename it, inspect installed books or review storage. Offline
profiles show last-observed data and cannot authorize a write to another pen.
Use Choose pens on a book to select the family collection or individual pens,
then Selected books on each profile to review and add missing files. Extra files
are kept and conflicting filenames are refused. See [multi-pen profiles](docs/multi-pen.md).

Pen details open separate Installed books and Storage screens. Book details show
the available action; File details holds filenames and versions. Transfers
shows the current or most recent transfer in this session.

Installed books can be sorted by title, file-modified date (oldest/newest) or
size (largest first). Open a book and choose **Remove from pen** to free space;
the app verifies a local backup before deletion. **Downloads** includes these
backups and offers **Restore to pen**, without a network request. Backups stay
on the phone after restoration. Uninstalling the app or clearing its storage
erases them; there is no automatic backup eviction. See [cleanup](docs/cleanup.md).

For installed books, phone downloads are optional actions in the three-dot menu.
With **Keep downloads** (the default), Download saves a phone copy for reuse.
In **Phone storage**, choose **Transfer only** to download and install in one
step, using temporary audio that is discarded after the transfer. Existing
downloads are deleted only after a verified install or explicit cleanup.
Pen-removal backups remain separate until you delete them.

Use **Manage phone copies** on a book to delete its download or backups.
**Phone storage** can clear downloads while keeping backups, or explicitly
delete all phone audio including backups. Catalog details and covers stay
cached separately. See [phone storage](docs/phone-storage.md).

The app never replaces existing filenames in this spike. It does not install
firmware, automatically select companion files, or claim playback-based cleanup.
Keep the app open during transfers; interruption recovery remains later work. Publisher covers, titles,
descriptions and age ranges enrich the catalog. ISBNs and article numbers are
behind the info button. Viewed artwork and details are cached locally, with
filename/icon fallbacks when publisher metadata is absent or ambiguous.

Android CI runs unit tests and lint, builds an APK and publishes experimental
previews. Bootstrap CI also checks the reference manifest and Python helper.
See [distribution](docs/distribution.md) for signing and update details.

## Independence and licensing

Project-authored code is MIT licensed. Upstream references retain their own
licenses and notices. The MIT license does not cover Ravensburger audio,
artwork, firmware, trademarks, or third-party reference code.

tiptoi is a Ravensburger trademark. This project is independent and is not
affiliated with or endorsed by Ravensburger. It does not redistribute their
book files.
