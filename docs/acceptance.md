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

Publisher metadata revision: JVM tests cover shared-ID/filename joins, retained
editions, unsafe URL rejection, matching publisher-page identity, ISBN checksum
validation and local-cache loading. Live Pixel requests matched audio ID 248
to article 49230, obtained a valid ISBN and decoded cover files. A second
cached-only device run passed without metadata requests. The UI exercised the
cover list, larger artwork, ISBN info dialog, description and Back/query
restoration. Broader title/edition coverage remains upstream-dependent.

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
No overwrite, firmware operation or multi-pen sync is implemented. Manual
removal and restoration are covered separately below.

The storage route follows Android's [document access API](https://developer.android.com/training/data-storage/shared/documents-files).
Physical browsing in another file manager alone is not app acceptance; the
inventory result above came from this app's own granted document tree.

## Manual removal, restoration and sorting

JVM checks cover oldest/newest ordering with missing dates last, equal-date/size
ordering, the installed-book action matrix, shared-support protection, strict
streaming integrity failures and backup reload with original dates and per-pen
preference. All 14 JVM tests and Android lint passed locally.

On the physical Pixel and pen, the opt-in runner created a uniquely named copy
of the already-cached, selected audio ID 248. It exercised the app's removal
operation, reloaded the durable verified backup, restored without download
requests, matched the full read-back SHA-256, and confirmed an existing filename
was refused. Cleanup removed only the generated copy and its test backup.
The original inventory's filenames, sizes and header IDs matched before and
after. This does not assert a full hash of every original book.

The provider reported file-modified dates, including old dates on existing
content; these establish availability, not installation or playback recency.
UI checks covered the sort picker, oldest/newest/largest views, the installed
book's Remove action and cancellation, the optional phone-download menu, the
file-date info dialog and Back/query restoration. No original book was selected
for deletion. Physical playback, sudden disconnect/power-loss recovery, and
other provider/pen compatibility remain separate checks.

## Phone cleanup and transfer-only downloads

All 19 JVM tests and Android lint passed. New checks exercise per-file and bulk
phone cleanup, preservation of metadata/artwork and other titles' backups,
backup revision deletion, success/failure temporary cleanup, preservation of
existing downloads after failed installs, and connection/space gating.

On the physical Pixel and pen, the cache fixture installed a uniquely named
copy of selected ID 248 from a disposable cached source in Transfer only mode.
Read-back succeeded and that cached source was deleted. A second test downloaded
and installed the same uniquely named fixture, verified its complete SHA-256,
and confirmed the temporary phone directory was empty afterward. Per-file and
bulk phone cleanup were also exercised in isolated fixture directories.
The original pen inventory and existing phone download/backup names matched
after cleanup; the selected original cached source's hash matched too. The
previous retention preference was restored.

Physical UI checks exercised both retention choices, the clear-downloads and
clear-all confirmations (canceled on real data), actual deletion of a small
uniquely keyed phone fixture, preservation of the original offline download,
and the per-book Manage phone copies/delete confirmation. No original audio
or backup was selected for deletion. Bulk deletion of real family data,
process-kill/power-loss staging recovery, and network/USB interruption on the
physical device remain untested. Temporary failure cleanup is covered by JVM
checks, not a deliberate cable pull.
