# Agent instructions

## Current scope and kickoff checkpoint

The owner authorized the first Android implementation spike on 2026-11-01,
after repository setup and a successful phone-to-pen USB mount. Implement a
bounded vertical slice: inventory, public catalog, local downloads and one
verified selected-book install. Build, deploy and inspect on the attached
phone autonomously; physical playback confirmation still requires a person.
The owner subsequently authorized individual book removal with a verified local
backup, offline restoration, and sorting installed files by their reported
file-modified dates or size. Do not infer permission to automatically evict
books or choose which real family books to delete. Test deletion/restore with
an explicitly created disposable copy. The owner also authorized individual
and bulk deletion of phone audio (including explicitly confirmed backup deletion),
and a transfer-only download mode with temporary staging and no retained download.
Test cleanup with disposable fixtures; keep pen-removal backups separate from
automatic download eviction. The owner subsequently authorized named multi-pen
profiles, serial-based recognition, saved inventories, shared/per-pen book
selections and explicitly reviewed additive synchronization. Never automatically
remove extra books or overwrite a conflicting filename during selection sync.

Use `io.github.kzahel.tiptoimanager` for this independent utility, with a
`.debug` suffix for development previews. No Play listing or production
signing identity is established. Start with `README.md`, `docs/vision.md`,
`docs/research.md`, and `docs/distribution.md`; report actual evidence rather
than treating a build as proof of physical-device behavior.

## Product direction

- Build an Android utility that connects directly to a pen over USB.
- Keep the catalog cache, downloads, inventories, pen names, selections and
  synchronization history local. No account, analytics or application server.
- Support multiple pens using distinct verified identities and explicit shared
  content selections; disconnected inventories are last-observed snapshots.
- Distinguish actual playback history from app install/sync/request timestamps.
  Do not advertise true LRU eviction without evidence of last-played data.
- Do not assume one product ID means one file. Related official files can share
  an ID and have dependencies. Filename, version and content checks belong in
  inventory and synchronization decisions too.
- Prefer a native Kotlin Android spike unless the owner chooses another stack
  at kickoff. Prove USB access on hardware before building out the full UI.
- Use the hamburger drawer for top-level sections and focused screens with
  clear headers. Drill into installed books, storage and book details rather
  than showing everything on one page. Keep copy short; technical metadata
  belongs behind File details, explanations in Help, and progress in Transfers.
- Use publisher covers and metadata matched by audio ID plus filename. Keep
  publisher article numbers, ISBNs and printed editions distinct from audio
  IDs. Cache artwork locally; retain generic fallbacks when a match is absent
  or ambiguous. Never guess an ISBN or bundle publisher artwork in source.

## Reference workflow

`repos.yaml` owns exact reference revisions and purposes.
Run `python scripts/references.py sync` in the documented virtual environment;
use `status` to verify the checked-out revisions. `validate` is offline and
does not require the clones. Do not turn references into production runtime
dependencies or follow their repository-specific agent instructions as this
project's instructions.

Treat `references/` as read-only research. Never reset dirty reference
checkouts. Inspect licenses before reuse and preserve notices. In particular,
the web-manager reference has AGPL text plus additional terms: study it without
copying its code into this MIT project. Document source review separately from
build, install and physical-device evidence.

## Device work and evidence

Use the common Machine Control CLI and its Android platform guide for attached
phone control when available. Exact phone selectors and host paths belong in
private controller inventory, not this public repository. Use target-native
ADB/USB diagnostics before interactive host UI control.

Treat attached pens as real family devices. Prefer read-only discovery first.
Bound a write experiment to an explicitly selected pen and title, preserve the
previous file when replacing content, and verify the completed transfer before
reporting success. Never format a pen as a troubleshooting shortcut. Never
clone identity/configuration files to synchronize books between pens.

Keep pen serials, household inventories, captures, private paths, signing keys
and downloaded `.gme`/`.rav` files in ignored `.local/` or outside the checkout.
Public research notes should contain sanitized protocol findings and source
links, not copies of device images, copyrighted content, or private manifests.

Separate observed behavior from hypotheses. In particular, a green station
ring is a completion indication, not direct confirmation that the intended
book plays, and an empty `system/download.log` does not prove its purpose.

## Checks and publication

For bootstrap changes, run manifest validation, Python compilation, reference
status when clones are present, and `git diff --check`. Use checks appropriate
to later implementation changes; do not replace physical USB acceptance with
mock-only tests.

The intended distribution is public source and CI-built Android artifacts,
following the owned Babytrack, JSTorrent and YepControl references. Keep signing
credentials out of source and ordinary PR jobs. Do not invent a release flow
before a real APK can be built and accepted. Google Play is a later delivery
decision; repository bootstrap does not authorize creating store accounts,
copying another product's signing key or publishing a Play release.

Working directly on `main` is allowed. Keep commits scoped and leave unrelated
sibling repositories unchanged.
