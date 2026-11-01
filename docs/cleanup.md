# Manual cleanup and restoration

Open My pen → Installed books, choose a title, then **Remove from pen**.
The confirmation names the title and the space it occupies. The operation:

1. Rechecks the selected pen identity and file metadata.
2. Streams the exact selected file into app-private phone storage, checks its
   size and SHA-256, reads the backup again, and durably saves a manifest.
3. Rechecks the pen, file metadata and full source SHA-256 before deletion.
4. Deletes only that file and confirms it is absent, with other inventory
   names, sizes and header IDs unchanged.

If phone space or verification fails before a complete backup is saved, the
app does not delete the pen file. Once the backup is verified, it is retained
on any later error. A disconnect or provider error can leave the result
uncertain; scan again. This is not a transaction across Android and the USB
filesystem, and abrupt power loss/provider faults remain limitations.

Downloads lists both publisher downloads and removal backups, including files
no longer in the catalog. **Restore to pen** checks the backup hash, copies via
a temporary file, verifies a full read-back hash, and finalizes the filename.
Existing filenames are refused; restoration does not need the publisher or a
network connection. A restored file normally receives a new filesystem date.

Backups retain the original filename, size, audio ID, file date, pen fingerprint,
SHA-256 and backup timestamp locally. They are app-private and remain after
restoration. Uninstalling the app or clearing its storage erases them. There is
no automatic eviction or export. Delete them explicitly from Manage phone copies
on a book, or from Phone storage. A publisher phone download is a separate file.

## Dates and sorting

Installed books offers Title, File date · oldest first, File date · newest first,
and Size · largest first. Dates come from the document provider's
`COLUMN_LAST_MODIFIED`; missing/zero dates are shown as unavailable and sort last
in either date direction. Equal dates or sizes are ordered by title.

This is a **file-modified date**, not creation, installation or playback history.
It can reflect the publisher's file timestamp, a copy, or a manager operation.
The app does not infer true LRU from it. The info dialog labels the source date;
Storage offers a shortcut to review oldest files. Nothing is automatically
removed when space is low.

## Shared files and actions

An audio ID can belong to multiple files. Removal affects only the selected
filename; the confirmation warns when other installed files share its ID.
Shared support ID 14999 and `WissenManage.gme` cannot be removed through this UI.
Other companion dependencies are not comprehensively modeled yet.

An installed book's primary action is **Remove from pen**. Downloading another
phone copy in Keep downloads mode is an optional three-dot action, labeled **Download
phone copy** or **Re-download phone copy**. An absent book offers restoration,
installation, connection or download according to its local and pen state.
