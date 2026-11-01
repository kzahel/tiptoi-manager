# Original store art

- `source/icon.svg`: original vector book/pen mark; `store/icon.png` is its
  512 × 512 export. Android's vector/adaptive icon uses the same geometry.
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

Regenerate exports with `python3 scripts/store_art.py` and `rsvg-convert`.
Store screenshots come from a dedicated disposable Android emulator, never a
synthetic recreation of app screens. Screenshot capture tooling is documented
in `store/README.md`. Keep private device captures under ignored `.local/`.

Play asset declarations label the original icon and generated feature
illustration as AI-assisted/AI-created art. Phone screenshots are actual app
captures, not generated interfaces. The book-detail capture includes normal
scrolling to show its file-management actions.
