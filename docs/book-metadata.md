# Publisher metadata and covers

The download CSV remains authoritative for selectable audio URLs, filenames,
sizes and versions. A separate, optional publisher catalog enriches those
entries with proper titles, series, descriptions, ages and cover URLs. The
app connects directly to Ravensburger; it has no project-hosted metadata API.

## Matching and editions

A publisher product is identified by an article number. Its `gameFiles` refer
to audio IDs and filenames. Join on **both** filename and audio ID. An audio
ID alone is not a unique book or file key. Multiple publisher editions may
share audio; retain those products instead of silently deduplicating them.
When candidates disagree on the title, keep the generic presentation rather
than assigning an arbitrary cover. Shared support content is not a book.

The info button lists publisher article numbers separately from audio IDs and
versions. ISBNs and page counts are fetched on demand from the matching
publisher product page. The structured Product `mpn` must match the article,
and the ISBN checksum must validate. ISBNs are never derived from audio IDs,
article numbers or filename guesses. Some products have no ISBN or unavailable
pages; their audio remains usable. A multi-edition match is labeled explicitly.

## Network and local caches

- Publisher catalog: `https://ttapiv2.ravensburger.com/api/v2/catalog/de_DE`.
- Application authorization: `https://oauth.ravensburger.com/oauth/token`.
- Product pages: `https://www.ravensburger.de/de-DE/pdp/ARTICLE`.
- Images: HTTPS `/images/` URLs on `cdn.ravensburger.de` or `ravensburger.cloud`.

Catalog refresh downloads the audio CSV and attempts metadata enrichment.
Failure preserves cached metadata and the basic audio catalog. On startup,
metadata loads from local disk without a catalog request. Opening a book may
fetch its missing or older-than-seven-days publisher-page details. A failed
request retains cached data and is not repeatedly retried during rendering.

Covers are fetched when their views are drawn, not for every offscreen row.
Image downloads have a byte limit, checked dimensions and sampled bitmap
loading. Stale view callbacks cannot put another book's artwork into a reused
view. Memory cache is bounded to 12 MiB; the app-owned disk cover cache to
64 MiB. Cache eviction affects covers only, never pen files or audio downloads.
Already cached images, catalog information and ISBNs remain usable offline.
Unvisited artwork may still need a connection. Refresh clears failed-image
attempts so a temporary network failure can be retried.

No household inventory, pen identity or playback data is sent in these requests.
OAuth tokens stay in memory. Authorization headers never follow redirects.
Descriptions are parsed as plain text; downloaded HTML is not executed.

## Build configuration

The catalog uses an application-client registration, not a personal account.
Released previews are configured in CI. App users do not register or sign in.
Client values are read from `TIPTOI_CATALOG_CLIENT_FILE`, or the ignored
`android/local.properties` entry `catalogClientFile`. That file has properties
`client.id` and `client.secret`; keep it outside Git. Publicly distributed
application-client values are not confidential once included in an APK.
They do not authorize book purchases, private accounts or pen inventories.

Trusted main builds receive `CATALOG_CLIENT_ID` and `CATALOG_CLIENT_SECRET`
through GitHub secrets. PRs receive neither. A developer build without that
configuration still supports the CSV catalog, cached data and USB operations.
The application-client flow was researched in the pinned MIT-licensed
[desktop manager](https://github.com/geeks-r-us/tiptoi-manager/blob/8c4abc1090f844934986198e821f6a6c4d7d353e/src/tiptoi_manager/infra/ravensburger_api.py);
no reference checkout is a runtime or build input.

## Evidence

On 2026-11-01 the live publisher catalog returned 343 products with images.
That is an upstream observation, not a promised count or complete mapping of
all CSV files. The Pixel matched audio ID 248 to article 49230, fetched its
publisher-confirmed ISBN and decoded both thumbnail and larger artwork. A
second cached-only instrumentation run read the stored metadata, ISBN and
images without making network requests. The UI exposed covers, description,
info fields and query restoration after Back. Raw responses and screenshots
remain ignored; publisher artwork and descriptions are not bundled in Git or
in the APK.
