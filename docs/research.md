# Research baseline

Initial investigation: 2026-11-01. Findings below distinguish source claims,
sanitized local observations, and questions for the next phase. Private device
inventories and raw captures are not committed.

## Established starting points

### USB and downloads

The fourth-generation pen's Wi-Fi functionality is in the optional charging
station. The pen exposes USB mass storage for direct content management.
Ravensburger documents downloading standard book files manually and copying
them onto the pen. A public standard-book URL was checked without credentials
and returned HTTP 200 with the expected content length.

The station catalog is available at:

```text
https://cdn.ravensburger.de/db/tiptoi2.csv
```

The catalog mixes item records with firmware/header/special records. Do not
interpret every numeric first column as an ordinary book ID. Repeated product
IDs also occur. A parser needs to preserve grouped files and distinguish their
roles. Catalog availability and schema are upstream behavior, not a supported
API stability promise.

The richer catalog endpoint is:

```text
https://ttapiv2.ravensburger.com/api/v2/catalog/de_DE
```

An unauthenticated request returned HTTP 403 during the investigation. The
geeks-r-us manager documents application OAuth for this endpoint. That is
different from a personal account or book-ownership entitlement. The public
CSV/direct-file route is sufficient to investigate an account-free first spike.

### GME headers and product groups

The reverse-engineered format documents a little-endian product ID at offset
`0x14` and the magic value `0x238b` at offset `0x08`. A local pen's headers were
read successfully without modifying them. Its ordinary content included groups
where **multiple distinct GME files intentionally shared one product ID**,
notably figures and Wissen & Quizzen. `WissenManage.gme` used special ID 14999.

A seasons file matched its catalog size and additive checksum. This checks one
file for obvious corruption; it is not playback acceptance, proof of the latest
version, or proof that a similarly named book uses that file.

Inventory should retain both file identity and product identity. Do not remove
all but one file with a repeated ID, and do not assume every GME is a standalone
book. Companion-file relationships need explicit handling.

### Pen identity

The community documents `.tiptoi.log` as a small binary metadata file containing
the pen serial, firmware version, language and processor identifier. A 64-byte
file with that name was observed. The current parser recognized the connected
pen's serial field; repeated scanning and local profile reload passed. Synthetic
checks cover firmware/language changes. A comparison of both physical pens and
recognition across a real firmware update remain unverified. Do not log or publish raw
serials, rewrite that file, or copy it to another pen.

### Missing-book requests

The documented station workflow is to tap a missing book's start symbol, wait
for the missing-audio announcement to finish, and dock the pen. Some state must
carry the request to the station, but its representation has not been proven.

An empty `system/download.log` was observed. It is a candidate for investigation,
not a verified queue format. A requested before/after experiment did not yet
establish a changed request file. Do not assume a persistent multi-item queue,
an ID encoding, or request-consumption behavior.

### Playback history and cleanup

Ravensburger's privacy statement says the pen does not store which codes are
read. No documented last-played field was found. Some observed file timestamps
were old content/default dates; they do not establish when a child used a book.
App inventory scans can also make filesystem access timestamps misleading.

Use app-known install/request/sync times and explicit pins for cleanup. Any
claim of true least-recently-played eviction needs separate evidence.

### Hardware friction

Power and data connectivity are separate observations. The pen charged on a
connection where the computer saw no USB device. A different cable alone did
not establish enumeration; using a hub port known to carry data exposed the
pen's volume. This narrowed the fault to the connection path but did not isolate
an exact bad component. Do not label every detection failure as a cable fault.

## Android spike evidence

The native Kotlin spike subsequently received persistent document-tree access
through Android's picker and read the pen's actual GME inventory and free space.
The same application code refreshed the public CSV, downloaded a selected
standalone title and installed it with final-file read-back verification.
Direct libaums access was unnecessary for this observed phone/pen combination.
It remains a fallback, not an adopted dependency or a compatibility claim.

See the [acceptance record](acceptance.md) and [opt-in physical runner](device-smoke.md)
for evidence and limits. Playback, a complete tap-driven UI test, another pen,
and interruption/reconnect recovery still need separate checks.

## Remaining questions

1. Which other Android phones/providers expose a usable document tree, and
   which need direct USB host access through libaums?
2. Can both physical pens be identified reliably without a writable marker?
3. Which filenames, versions and companion dependencies distinguish content
   sharing one ID?
4. Does a missing-book tap change `download.log`, another file, or internal
   state? Does the request survive power-off or multiple taps?
5. What happens on cable removal, insufficient space and interrupted overwrite?
6. Which Android/cable/pen combinations can the project actually claim to have
   validated?

## Primary references

- [Fourth-generation pen](https://service.ravensburger.de/tiptoi%C2%AE/Produkte_FAQs/tiptoi%C2%AE_Stift_der_4.Generation)
- [Station workflow and indicators](https://service.ravensburger.de/tiptoi%C2%AE/Produkte_FAQs/tiptoi%C2%AE_Ladestation)
- [Manual content transfer](https://service.ravensburger.de/tiptoi%C2%AE/tiptoi_Manager/Haendischer_Download_einer_tiptoi_Audiodatei)
- [Android/mobile manual transfer](https://service.ravensburger.de/tiptoi%C2%AE/Produkte_FAQs/Download_von_tiptoi_Audiodateien_mit_Smartphone_oder_Tablet)
- [Account and legacy paid content](https://service.ravensburger.de/tiptoi%C2%AE/tiptoi_Manager/Registrierung_tiptoi%C2%AE_Stift)
- [Usage-data statement, section 7.7.3](https://www.ravensburger.ie/en-IE/ravensburger/common/start/privacy-policy)
- [GME format](https://github.com/entropia/tip-toi-reveng/blob/master/GME-Format.md)
- [Pen metadata](https://github.com/entropia/tip-toi-reveng/wiki/PEN-Firmware#tiptoilog)
- [OID concepts](https://tttool.readthedocs.io/de/latest/konzepte.html)
- [Android USB host APIs](https://developer.android.com/develop/connectivity/usb/host)

Pinned source checkouts are listed in [references](references.md).
