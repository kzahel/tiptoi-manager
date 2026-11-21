# Custom tiptoi book runbook

Use this workflow to give a printed story its own audio, or to make a separate
tap card that reads aloud from a physical book. The pen reads the optical codes
on the paper and plays the matching audio in a `.gme` file. The ZIP is a
convenience for keeping the PDF and GME together when sharing the build.

## Set up the tools

On macOS, unpack [tttool 1.11](https://github.com/entropia/tip-toi-reveng/releases/tag/1.11)
into an ignored directory under `.local/`. Keep its executable and libraries
together. Install the Python libraries into the project environment, and have
`ffmpeg` available for recordings and effects. Poppler provides `pdfinfo` and
`pdfimages` for the optional print checks.

```sh
test -x .venv/bin/python || uv venv .venv
uv pip install --python .venv/bin/python reportlab Pillow soundfile PyYAML
TTTOOL="/path/to/extracted/tttool-1.11/tttool"
```

The sample scripts use the macOS Samantha voice for any speech clip without a
recording. They convert supplied audio into the same 22,050 Hz mono Vorbis
format. The story sources its two effects from a local checkout of [Funsounds](https://github.com/kzahel/Funsounds/tree/main/public/sounds).

## Build the examples

Use the one-page probe to test a new printer, paper or toner setting. The four-page
story uses the Fine patterns that scanned on the Brother laser printer.

```sh
.venv/bin/python examples/moon-base/build.py --tttool "$TTTOOL" .local/moon-base-probe

.venv/bin/python examples/moon-base/story.py --tttool "$TTTOOL" \
  --effects "$HOME/code/Funsounds/public/sounds" .local/moon-base-story
```

Each build creates a PDF, a GME and a ZIP containing those two files. Both
scripts run `tttool lint` and generate the optical codes during the build.
Use a fresh output folder for each revision you want to keep.

## Record your own read-aloud

1. Record a short welcome and one clip per page or chapter. Pause between pages
   and save each as a separate file so the listener can replay a single page.
   Phone voice memos in `.m4a` work, as do `.wav`, `.mp3`, `.flac`, `.ogg` and
   AIFF files. Trim long silences before exporting.
2. Put the files in an ignored directory such as `.local/read-aloud/`. For the
   Moon Base story, name them `welcome.m4a`, `chapter_one.m4a`,
   `chapter_two.m4a`, `chapter_three.m4a` and `chapter_four.m4a`. You can also
   record `rover_fact`, `antenna_reply`, `antenna_fact` or `rocket_fact`.
   Give each clip one file. For the single-page probe, the clip names are
   `welcome`, `rover`, `antenna` and `rocket`.
3. Build with `--recordings`. Clips you supply replace the matching speech and
   the rest use Samantha. Unknown clip names and duplicate recordings stop
   the build so a typo cannot silently fall back to TTS.

```sh
.venv/bin/python examples/moon-base/story.py --tttool "$TTTOOL" \
  --effects "$HOME/code/Funsounds/public/sounds" --recordings .local/read-aloud \
  .local/moon-base-read-aloud
```

To adapt this to a different book, copy the [example directory](../examples/moon-base/)
so the [story script](../examples/moon-base/story.py) can still import its shared
audio helpers. Set `STEM` to the name you want for the output files and give
the [YAML](../examples/moon-base/Moon_Base_Story.yaml) the same stem. Change the
story title in `draw_story` and the chapter titles, text and action labels in
`SCENES`. Replace or omit the drawings from `draw_scene`.

In the YAML, `scriptcodes` map each panel name to an optical code, and `scripts`
map taps to the clip names. Use a book code unused on your pen. tttool recommends
900–950 for private projects. Keep that code and the panel codes stable when
revising audio for an already printed page. [tttool YAML reference](https://tttool.readthedocs.io/de/latest/yaml-referenz.html#product-id)

For a companion sheet next to an existing book, use one **Start** box and one
numbered **Read** box per page. The printed labels can stay short even when the
recordings are long. Give each Read box a distinct script name, clip name and
optical code. Moon Base’s rocket and rover show how to use a register to
alternate a sound effect and spoken line on repeated taps.

## Print, install and test

1. Open the PDF and make sure the dots are clear of text and artwork. Print on
   plain A4 at **Actual size (100%)** and high quality. For the story, select
   **two-sided, flip on long edge**. The Fine patterns use two-pixel dots at
   1,200 dpi. `pdfimages -list` should report about 1,200 ppi in both axes.
2. Copy the GME to the phone’s Downloads folder and plug the pen into the phone.
   In Pen Library, grant access to the top-level USB folder. For an update to
   the same book code, use **Remove from pen** on the old copy after the new
   file is ready on the phone. Pen Library verifies a phone backup before it
   removes the old copy.
3. Choose **Downloads → Install a .gme file**, select the file and confirm the
   named pen. Wait for **Read-back verified** in Transfers.
4. Unplug the pen, turn it on and tap **Start** before testing a **Read** box
   or an action. For Moon Base, tap the page 4 rocket three times, lifting the
   pen and letting each clip finish. Expect launch, explanation, launch.

Record print and playback results separately from file transfer. For an
independent read-back over ADB, see [the USB smoke runner](device-smoke.md).
To roll back, remove the new copy before restoring the backup so the pen has
one file for that book code.

## Keep source in Git

Track the Python and YAML, plus this runbook. Build into `.local/` so the
generated clips, GME, PDF, ZIP and personal recordings stay out of Git. The
four-page PDF is small enough to publish separately as a printable example if
you want a ready-made download. The ZIP already includes the PDF and GME.
