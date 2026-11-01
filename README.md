# Tiptoi Manager

An independent, open-source Android manager for tiptoi pens: see what is on a
pen, install books over USB, manage space, and keep a family's pens in sync.

**Status: repository bootstrap and research only. There is no Android app or
APK yet. The first implementation spike awaits the owner's check-in.**

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

After the kickoff checkpoint, prove that an Android phone can inspect a real
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

Bootstrap CI validates the manifest and compiles the helper. Android build,
signing and automatic APK publication are planned, not configured as working
features. See [distribution](docs/distribution.md).

## Independence and licensing

Project-authored code is MIT licensed. Upstream references retain their own
licenses and notices. The MIT license does not cover Ravensburger audio,
artwork, firmware, trademarks, or third-party reference code.

tiptoi is a Ravensburger trademark. This project is independent and is not
affiliated with or endorsed by Ravensburger. It does not redistribute their
book files.
