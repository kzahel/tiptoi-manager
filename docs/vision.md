# Motivation, vision and first spike

## Problem

The charging station is convenient when it works but offers little visibility
into connection failures, pending downloads, installed titles or available
space. A desktop is an unnecessary dependency for a USB storage transfer, while
the documented Android browser-plus-file-manager route makes the parent do all
the coordination. Managing two pens repeats that work.

## Intended experience

Connect a pen to an Android phone with a data-capable cable/OTG adapter, grant
USB access, and see its content and free space. Select a book in a locally
cached catalog, download it once, and install it with clear progress and a
verified result. Name a second pen and apply the same selection when it is
connected. All family-specific state stays on the phone.

The application is a parent-facing management utility; ordinary pen playback
continues independently. A charging station and a Ravensburger account are not
required for standard book management.

## Core data

- **Pen:** verified device identity, local nickname, model/firmware observations,
  last connected time and last observed capacity/inventory.
- **Catalog entry:** product ID, title, language when known, version, URL,
  expected size and source. A product can have multiple associated files.
- **Cached content:** filename, product ID read from its GME header, version,
  size, checksum/hash observations and local path.
- **Selection:** desired content for a pen or shared family collection, with
  pinned items and content dependencies.
- **Transfer:** source, selected destination, operation, progress and verified
  completion. Requests and installs are not playback events.

Do not use product ID alone to deduplicate files. Preserve required companion
files for linked content such as Wissen & Quizzen and figures.

## Cleanup

Offer storage accounting and understandable suggested removals. Favorites stay
pinned. Installation age, last app request and last sync may inform suggestions
but must be labeled honestly. Unobserved child playback cannot produce a true
least-recently-used ordering. File timestamps are not an established substitute.
Retain downloaded files on the phone so removed content can be restored offline.

## Multi-pen synchronization

Keep one local library and independent inventories. Both pens can use the same
desired selection, or have individual additions. Connect them sequentially;
simultaneous USB connections and a network service are unnecessary. Show the
planned additions, updates and removals before applying a sync. Content sync
does not copy serial numbers, firmware, configuration or game state by default.

## Kickoff checkpoint

Repository setup is authorized; implementation is not started. Check in with
the owner before the first spike and confirm its bounded scope. Proposed first
spike:

1. Detect one attached fourth-generation pen on an Android phone, request the
   normal USB permission and read its volume without modifications.
2. Show valid GME headers, grouped product IDs, filenames, size and free space.
3. Resolve a selected standard title from the public catalog.
4. Cache it locally and perform one bounded install with transfer verification.
5. Reconnect the pen and confirm the intended book plays.
6. Inspect the second pen's identity and prove that cached content can be used
   with it without cloning device metadata.

Assess Android's storage-framework route versus direct USB mass-storage access
through libaums on the actual device. Prefer the smallest reliable native
Kotlin implementation; confirm application ID, supported Android baseline and
initial signing/distribution choices before establishing release identity.

## Later milestones

- Library, pen and transfer views with offline caches and resume/recovery.
- Multi-pen selections and dependency-aware synchronization.
- Cleanup suggestions and restoration of removed content.
- Automatic handling of pending pen requests only after the request mechanism
  is demonstrated; the current queue-file hypothesis remains unverified.
- CI-built APKs, stable project signing and tagged GitHub publication.
- Optional store distribution after the basic USB experience is useful.

Firmware modification, a new playback-history recorder, paid legacy media
licensing and cloud family synchronization are outside the first spike.
