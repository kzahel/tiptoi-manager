# Original store art

- `source/icon.svg`: Farm USB icon with a vector USB badge over
  `source/farm-usb-scene.png`. The farm and orange pen were generated with
  OpenAI ImageGen on 2026-11-26, then the sound marks were removed with an
  ImageGen edit and replaced with the vector badge. The pen palette takes
  its orange reference from [Ravensburger's press artwork](https://myconvento.com/public/newsroom/data/plugin/images/run/show_media/media_id/6055647/lang/22/id/634/integration/js).
  The icon uses original artwork.
- `store/icon.png` is the 512 × 512 Play export. Android uses the same
  composition with separate scene and badge layers and extra artwork around
  the edges for adaptive masks. `source/icon-monochrome.svg` supplies the
  silhouette for Android's themed launcher icons.
- `source/library-still-life.png`: generated with OpenAI ImageGen on 2026-11-01.
  Prompt: original editorial still life, horizontal 1024:500 composition,
  warm cream background, deep teal/mint/ochre, a stack of unbranded books and
  two generic green reading styluses plus a USB cable on the right, clear left
  whitespace; no text, logos, children, publisher artwork or orange trade dress.
- `store/{en-US,de-DE,fr-FR}/feature.png`: 1024 × 500 PNG feature graphics,
  source illustration plus localized typography. The illustration depicts a
  generic reading setup; the app manages files directly over USB.
- `store/*/phone/`: actual app captures, including the visible sample-data
  banner. Sample book cover vectors are original artwork included in the app;
  no downloaded publisher covers, copyrighted book audio or family inventories
  are included in store media.

Regenerate exports with `python3 scripts/store_art.py`, `rsvg-convert` and
ImageMagick. Pass `--icons-only` to rebuild the Play and Android icons.
Store screenshots come from a dedicated disposable Android emulator, never a
synthetic recreation of app screens. Screenshot capture tooling is documented
in `store/README.md`. Keep private device captures under ignored `.local/`.

Play asset declarations identify the Farm USB icon and feature graphics as
AI-assisted art. Phone screenshots are actual app
captures, not generated interfaces. The book-detail capture includes normal
scrolling to show its file-management actions.
