# Named pen profiles and selections

My pens lists locally remembered pens. Connect each pen to the phone in turn,
grant access to its top-level USB folder when Android asks, and open Rename pen.
The nickname stays on this phone; it does not change the pen's voice or firmware.
Known serials reopen the same profile. Each profile keeps its last-observed
files, file dates, free space, firmware information and scan time. Offline
screens explicitly say Saved inventory or Last observed.

## Identity and connections

The 64-byte `.tiptoi.log` has four padded 16-byte fields: serial, firmware,
language and processor. A validated serial field is normalized and hashed with
a versioned namespace for profile matching. Full serials are not persisted;
Pen information shows the last four characters. Profiles have independent app
UUIDs, so names and selections are separate from the document-provider URI.
Two different identities can use the same USB volume/tree name.

Firmware and language changes do not enter the serial identity key. The full
metadata SHA-256 remains the stricter transfer-session guard: a changed session
requires rescanning/review. Previously observed fingerprints are retained for
legacy backup attribution; new removal backups also store the stable identity
key. Names, inventories, grants and selections never enter network requests.

Unrecognized serials use a visibly unverified full-metadata fallback for basic
inventory. That fallback may change with firmware and cannot reliably separate
identical unknown metadata from different pens. Collection sync is disabled for
that case. Missing or unsupported metadata is not guessed from a volume label.
The app never writes an identity marker or copies configuration between pens.

While foregrounded, the app handles USB events and polls granted folders every
three seconds using metadata-only checks. A newly recognized identity triggers
a full inventory and selects its profile. A new URI still needs Android's normal
folder grant; the app cannot silently grant itself USB access. A manual scan
also refreshes file headers and space. Only one pen is active at a time; connect
pens sequentially. Concurrent USB management is not established.

## Family and individual book selections

Choose pens on a book opens checkboxes for Family collection and each pen's
individual selection. Adding a title changes local intent only. Book selections
lets each profile include/exclude the family collection, remove selected
entries, or open a review. Removing a selection never deletes an installed file.
New profiles include the family collection by default; applying it is explicit.

Selected books on a profile shows:

- Missing files to add, with Ready offline or Download needed.
- Existing filename/size/header-ID matches, without claiming full checksum or
  exact-version validation of those pre-existing files.
- Filename conflicts, including incompatible selected versions. Conflicts must
  be resolved before applying; existing files are never overwritten.

The Add books confirmation names the destination and required downloads. The
operation binds profile UUID, serial identity and session fingerprint to that
review, then rechecks inventory, selection and space before writing. An offline
profile cannot authorize writes to a different connected pen, even if both use
the same document-tree URI. Individual install/remove/restore confirmations are
bound to their selected profile too.

Synchronization adds missing files only. Extra installed files stay in place;
manual removal still uses verified backups. It does not install firmware,
copy game state or automatically infer companion dependencies. Distinct files
sharing an audio ID remain distinct selections. Related files need explicit
selection; the review flags known shared-ID catalog entries.

Downloads are shared across all profiles. Keep downloads reuses a single
verified phone copy across sequential pen installs. Transfer only discards
staged audio, so a later pen may require another download. A failure after a
partial batch keeps verified completed installs and updates their saved
inventory; retrying a fresh review adds only the remaining files. Unfinished
USB temporary-file recovery remains separate work.

Disconnect a pen before Forget profile. Forgetting removes only its local name,
snapshot and individual selection; family selections, audio downloads, backups
and actual pen files remain. Reconnecting creates a new profile. Clearing Android
app storage/uninstalling also erases profiles; Phone storage audio cleanup does not.

See [acceptance](acceptance.md) for physical and synthetic test boundaries.
