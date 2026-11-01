# First-spike acceptance

Date: 2026-11-01. Evidence is limited to the operations listed here. Raw
screenshots, inventories, phone endpoints and pen identity data stay private.

| Check | Evidence |
| --- | --- |
| Local build | Debug APK assembled with JDK 17, AGP 9.4.1 and Gradle 9.8.1 |
| Content checks | JVM tests exercise GME checksum/size/ID failures, short reads, CSV quoting, shared IDs and download URL restrictions |
| Android lint | Passed; platform/version warnings are not compatibility acceptance |
| Physical deployment | App installed and launched on a Pixel 7a |
| Folder permission | Android's document-tree picker exposed the pen and granted persistent access |
| Inventory | Real GME headers, content totals, distinct product IDs and free space matched a prior host inventory |
| Catalog/download | Physical instrumentation invoked the app's catalog refresh and downloaded ID 248; GME size, ID and checksum passed |
| Write/read-back | The app installed selected ID 248; the runner independently reopened the final GME and matched SHA-256 against the cached download |
| Prior content | Before/after filenames, sizes and header IDs matched for every pre-existing GME; no overwrite was attempted |
| Inventory after restart | A second, read-only instrumentation run rediscovered the installed title and local catalog |
| CI publication | Unit tests, lint, signed APK and automatic GitHub prerelease succeeded |
| Complete UI flow | Original picker/inventory observed; the navigation revision below additionally exercises browsing and a download through the real UI. UI installation of an absent title remains separate. |
| Playback | Requires a person to disconnect the pen and tap the physical book |
| Second pen | Not tested |

## Navigation revision

The owner requested a hamburger menu, distinct sections, shorter copy and one
task per screen. The revision adds My pen, Browse books, Downloads, Transfers
and Help & about, with child screens for installed books, storage and book details.

Physical Pixel checks exercised the drawer, system Back to close it, toolbar
Back through child screens, the installed-file list, storage, catalog search,
book details and its separate file-information dialog. Returning from a book
retained the catalog query. Downloads showed the cached title. A deliberate
re-download of the previously selected title went through the real confirmation
dialog, Transfers and the completed state; View book returned to its offline
status. Existing pen content was not rewritten during these UI checks.

Unit tests, Android lint and app/test APK builds passed. Runtime license assets
were checked in the APK. Transfer presentation remains session-only state;
rotation, process-death restoration, an absent-title UI install and broad
accessibility/device compatibility are not established by these checks.

## Boundaries

The current pen fingerprint hashes the 64-byte metadata file. It is an
installation-session safeguard, not a proven stable identity across firmware
updates or both pens. Inventory scans check GME headers, not every installed
file's full checksum. Downloads check size, header product ID and the trailing
additive checksum. Transfers additionally compare a full read-back SHA-256
before renaming the temporary file to its final GME filename.

Existing destination filenames are refused. Provider rename support is required.
On a caught transfer error, the app attempts to remove only its own temporary
file. Power loss, process death or disconnect can leave a `.tiptoi-manager-*.part`
file; automatic recovery is not implemented. Keep the app open during transfers.
No overwrite, book deletion, firmware operation or multi-pen sync is implemented.

The storage route follows Android's [document access API](https://developer.android.com/training/data-storage/shared/documents-files).
Physical browsing in another file manager alone is not app acceptance; the
inventory result above came from this app's own granted document tree.
