# First-spike acceptance

Date: 2026-11-01. Evidence is limited to the operations listed here. Raw
screenshots, inventories, phone endpoints and pen identity data stay private.

| Check | Evidence |
| --- | --- |
| Local build | Debug APK assembled with JDK 17, AGP 9.4.1 and Gradle 9.8.1 |
| Content checks | JVM tests exercise GME checksum/size/ID failures, short reads, CSV quoting, shared IDs and download URL restrictions |
| Android lint | Passed; platform/version and backup-policy warnings are not compatibility acceptance |
| Physical deployment | App installed and launched on a Pixel 7a |
| Folder permission | Android's document-tree picker exposed the pen and granted persistent access |
| Inventory | Real GME headers, content totals, distinct product IDs and free space matched a prior host inventory |
| Catalog/download | Implemented; end-to-end phone acceptance pending |
| Write/read-back | Implemented; selected title ID 248 awaits end-to-end transfer acceptance |
| Playback | Requires a person to disconnect the pen and tap the physical book |
| Second pen | Not tested |

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
