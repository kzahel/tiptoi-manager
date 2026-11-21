# Moon Base

See the [custom book runbook](../../docs/custom-books.md) for setup, recording
your own narration, choosing book codes, printing and updating a pen.

## The Missing Signal

The four-chapter story prints on two A4 sheets with **two-sided, long-edge**
printing. Use **Actual size (100%)** and high print quality. Page 1 starts the
book. Each page has a **Read** box for its narration, and the last three have
an extra action that alternates on each tap. On page 4 the rocket plays the
launch sound, explains how a rocket lifts off, and then repeats the cycle.

Install `Moon_Base_Story.gme` through **Downloads → Install a .gme file**.
The story uses the original book code 927 and keeps the three original action
codes. If the one-page version is installed, first use Pen Library's **Remove
from pen** action on that sample to retain a verified backup. The ZIP contains
the duplex PDF and matching GME.

Build with tttool 1.11, the macOS Samantha voice, `ffmpeg`, and Python with
`reportlab`, `Pillow`, `soundfile` and `PyYAML`. The launch and rover motor use
`rocket.mp3` and `tractor.mp3` from the [Funsounds sound library](https://github.com/kzahel/Funsounds/tree/main/public/sounds).

```sh
python examples/moon-base/story.py --tttool /path/to/tttool \
  --effects /path/to/Funsounds/public/sounds .local/moon-base-story
```

## One-page print test

Moon Base is a one-page tiptoi sample with a spoken welcome and three things to
tap. `Moon_Base.pdf` carries the artwork and optical IDs (OIDs) for the pen to
read. `Moon_Base.gme` carries the speech and the rules for what to play. The ZIP
keeps both together for sharing. Unzip it or use the files directly.

1. Print the PDF on plain A4 paper at **Actual size (100%)**, single-sided and
   at high quality. Turn off toner saving if available.
2. Save `Moon_Base.gme` to the phone and connect the pen directly over USB.
   In Pen Library, grant access to the pen's top-level folder if prompted.
3. Open **Downloads → Install a .gme file**. Pick `Moon_Base.gme`, confirm the
   named pen, and wait for **Read-back verified** in Transfers.
4. Unplug the pen, turn it on, and tap the center of **Start** in the Fine
   column. You should hear “Welcome to Moon Base!” Then try **Rover**,
   **Antenna** and **Rocket**. If a pattern is difficult to scan, use its
   identical action in the Bold column.

The sample uses book code 927 from the [tttool-recommended private range](https://tttool.readthedocs.io/de/latest/yaml-referenz.html#product-id).
The app checks the GME checksum, refuses an occupied book code or filename, and
verifies the staged pen copy before finalizing it. It leaves the file picked on
the phone in place. If a code is taken, change the product ID and regenerate
both the PDF and GME together.

## Build on macOS

Use [tttool 1.11](https://github.com/entropia/tip-toi-reveng/releases/tag/1.11)
and a Python environment with `reportlab`, `Pillow` and `soundfile`. The script
uses the macOS Samantha voice and encodes mono Vorbis at 22,050 Hz. The Fine and
Bold patterns use 2- and 4-pixel dots on the same 1,200 dpi grid.

```sh
python examples/moon-base/build.py --tttool /path/to/tttool .local/moon-base
```

`Moon_Base.yaml` maps each action to its audio clip. `build.py` holds the spoken
lines and page layout. When changing IDs, keep the YAML and printed targets in
sync.
