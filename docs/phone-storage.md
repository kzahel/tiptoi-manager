# Phone audio storage

Phone storage is a Library destination in the drawer, also linked from Downloads.
It shows audio usage and provides these explicit operations:

- Downloaded audio: select any saved file to delete it. Unknown/older catalog
  versions are listed too, using a short local filename when no title matches.
- Pen backups: select a title to delete all its saved backup revisions.
- Clear downloads: deletes downloaded and temporary audio, keeping pen backups.
- Delete all phone audio: deletes downloads, temporary files and all backups,
  including incomplete/orphan backup folders. Its confirmation explicitly warns
  that backup-only titles lose their offline restoration copy.

Manage phone copies on a book offers deletion of its current downloaded file
and/or all matching pen backups. These operations never address the USB pen.
Catalog details, artwork, settings and the persisted folder grant are outside
these audio directories and remain available. This is audio cleanup, not an
Android Clear app storage operation.

## Retention

**Keep downloads** is the default. Downloads are verified and retained for
later offline installation, including reuse across pens.

**Transfer only** offers Download & install for an absent title. A connected pen
and sufficient reported pen space are checked before downloading. The audio is
staged on the phone, verified, copied via the normal temporary USB file, and
read-back verified before the final pen filename is created. The temporary
phone audio is deleted in a finally block on success and failure; a failed
transfer requires downloading again. No direct network-to-pen streaming is used.
If deletion fails, Transfers reports an error and Phone storage can clear the
leftover. Process death can leave a temporary file until the next app startup,
which clears the staging directory.

Changing mode does not silently purge existing offline downloads. Installing
an existing download in Transfer only mode deletes it after verified success;
a failed install preserves it. Cleanup failure after that successful install
is reported separately so a verified install is not presented as a failed copy.
In Transfer only mode, catalog-only titles require connection and sufficient
pen space instead of offering a persistent Download to phone action.

Pen-removal backups are intentional recovery copies and are kept in either
mode, including after restoration. They can be deleted individually/by title
or by the explicitly confirmed Delete all phone audio operation. Uninstalling
or clearing Android app storage also erases them. There is no automatic backup
retention limit or cache size budget in this revision.

Phone deletion and transfers share the application's single operation worker.
A cleanup request or retention change cannot interrupt an active transfer.
