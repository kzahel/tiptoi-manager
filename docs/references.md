# Source references

`repos.yaml` owns the exact revisions, checkout paths, license notes and reasons
for including these sources. The initial pins were resolved on 2026-11-01.
Checkouts are ignored under `references/`; cloning does not execute upstream
builds, install applications or initialize submodules.

| Checkout | Purpose | Declared licensing / reuse boundary |
| --- | --- | --- |
| `libaums` | Android USB mass storage, permission flow, FAT32 access | Apache-2.0; review component/dependency notices before using as a dependency |
| `tttool` | GME headers, product and object IDs, custom content, protocol research | MIT; documentation and bundled assets may need separate review |
| `desktop-manager` | Catalog resolution, local library, synchronization and grouped content | MIT; study its desktop architecture without assuming Android compatibility |
| `web-manager` | Browser manager, catalog and direct-file workflow | AGPL-3.0 text plus Profit Contribution Agreement; study only, no code reuse in this MIT project |
| `ttmp32gme` | Custom audio/content generation and pen copying | MIT; authoring reference rather than the standard-book download engine |
| `babytrack` | Owned Android build/signing/distribution patterns | MIT; study public workflows, never copy signing identity or private configuration |
| `jstorrent` | Owned Android CI and signed APK publication | MIT; study public workflows, avoid unrelated product infrastructure |
| `yepcontrol` | Owned Android release provenance and Play separation | MIT; study public workflows, avoid unrelated relay/control services |

The web manager's additional terms mean it must not be described as simply
MIT-compatible or assumed reusable because its repository is public. Likewise,
GitHub visibility is not a license. Sources without a verifiable reuse license
are not needed as implementation dependencies.

## Commands

After installing `requirements-dev.txt` in a virtual environment:

```sh
python scripts/references.py validate
python scripts/references.py sync
python scripts/references.py status
python scripts/references.py sync --only libaums
```

`validate` checks the manifest and Git ignore rules without network access.
`sync` clones missing references and checks out the pinned commits; it refuses
existing dirty checkouts, unexpected origins, nested repositories and paths
outside `references/`. Owned product references use sparse checkouts to keep
release and Android source material available without fetching unrelated assets.
`status` checks every selected checkout's origin, commit and working tree.

To update a reference, choose and review a new immutable commit, edit the
manifest, synchronize, and record why the new source is relevant. A pin is a
source-review anchor, not evidence that a dependency or upstream application
was built or physically tested.
