#!/usr/bin/env python3
"""Build the Moon Base sample using tttool and the macOS Samantha voice."""

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile

from PIL import Image
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen.canvas import Canvas
import soundfile


DPI = 1200
TARGETS = [
    (927, "Start", "Hear the welcome message."),
    (5101, "Rover", "Find the tracks in the dust."),
    (5102, "Antenna", "Send a message to Earth."),
    (5103, "Rocket", "Count down to liftoff."),
]
SPEECH = {
    "welcome": "Welcome to Moon Base! Tap the rover, the antenna, or the rocket to explore.",
    "rover": "Crunch, crunch! The rover leaves tracks in the moon dust.",
    "antenna": "Hello, Earth! Moon Base calling. Can you hear us?",
    "rocket": "Three, two, one. Liftoff! See you back on Earth.",
}
RECORDING_SUFFIXES = {".aif", ".aiff", ".flac", ".m4a", ".mp3", ".ogg", ".wav"}


def recording_paths(directory, clip_names):
    if directory is None:
        return {}
    if not directory.is_dir():
        raise ValueError(f"Recordings directory does not exist: {directory}")
    recordings = {}
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.suffix.lower() not in RECORDING_SUFFIXES:
            continue
        name = path.stem
        if name not in clip_names:
            raise ValueError(f"Unknown clip {path.name}. Expected stems: {', '.join(sorted(clip_names))}")
        if name in recordings:
            raise ValueError(f"More than one recording for {name}: {recordings[name].name} and {path.name}")
        recordings[name] = path
    if not recordings:
        raise ValueError(f"No supported recordings found in {directory}")
    return recordings


def make_narration(audio, speech, recordings_directory, words_per_minute):
    recordings = recording_paths(recordings_directory, speech)
    source = audio.parent / "speech.wav"
    for name, text in speech.items():
        if name in recordings:
            subprocess.run([
                "ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", recordings[name],
                "-map", "0:a:0", "-ar", "22050", "-ac", "1", source,
            ], check=True)
        else:
            subprocess.run([
                "say", "-v", "Samantha", "-r", str(words_per_minute), "--file-format=WAVE",
                "--data-format=LEI16@22050", "-o", source, text,
            ], check=True)
        samples, rate = soundfile.read(source)
        if rate != 22050 or samples.ndim != 1 or samples.size == 0:
            raise ValueError(f"{name} must contain mono audio at 22050 Hz")
        soundfile.write(audio / f"{name}.ogg", samples, rate, subtype="VORBIS")


def draw_moon(page, x, y):
    page.setLineWidth(1.5)
    page.circle(x, y, 25)
    page.circle(x - 7, y + 8, 6)
    page.circle(x + 11, y - 2, 4)
    page.circle(x - 4, y - 12, 3)
    for dx, dy in [(-37, 13), (34, 17), (40, -14)]:
        page.line(x + dx - 3, y + dy, x + dx + 3, y + dy)
        page.line(x + dx, y + dy - 3, x + dx, y + dy + 3)


def draw_page(work, path):
    page = Canvas(str(path), pagesize=A4)
    page.setTitle("Moon Base | Printable tiptoi test")
    page.setFont("Helvetica-Bold", 43)
    page.drawString(44, 743, "Moon Base")
    draw_moon(page, 484, 750)
    page.setFont("Helvetica", 13)
    page.drawString(46, 707, "Turn on the pen and tap Start. Then explore the base.")
    page.drawString(46, 686, "Both columns play the same sounds. Try Bold if Fine is hard to scan.")

    centers = {2: 370, 4: 484}
    page.setFont("Helvetica-Bold", 12)
    for dot_size, label in [(2, "Fine"), (4, "Bold")]:
        page.drawCentredString(centers[dot_size], 632, label)
    for index, (code, title, hint) in enumerate(TARGETS):
        top = 616 - index * 123
        page.setLineWidth(0.5)
        page.line(46, top, 549, top)
        page.setFont("Helvetica-Bold", 22)
        page.drawString(46, top - 46, title)
        page.setFont("Helvetica", 12)
        page.drawString(46, top - 65, hint)
        for dot_size, center in centers.items():
            pattern = work / str(dot_size) / f"oid-{code}.png"
            with Image.open(pattern) as image:
                width, height = (pixels * 72 / DPI for pixels in image.size)
            page.rect(center - width / 2 - 4, top - 60 - height / 2 - 4, width + 8, height + 8)
            # Preserve the native dot pitch. Ink inside each box is only the OID pattern.
            page.drawImage(str(pattern), center - width / 2, top - 60 - height / 2, width, height)

    page.line(46, 124, 549, 124)
    page.setFont("Helvetica", 10)
    page.drawString(46, 96, "Print on A4 at Actual size (100%). Use plain paper and high print quality.")
    page.drawString(46, 79, "Audio file: Moon_Base.gme   |   Book code: 927   |   English speech")
    page.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tttool", type=Path, required=True)
    parser.add_argument("--recordings", type=Path, help="Directory of recordings named for their clips")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    tttool = args.tttool.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    audio_file = output / "Moon_Base.gme"
    page_file = output / "Moon_Base.pdf"

    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        audio = work / "audio"
        audio.mkdir()
        make_narration(audio, SPEECH, args.recordings, words_per_minute=155)
        yaml_file = work / "Moon_Base.yaml"
        shutil.copyfile(Path(__file__).with_name(yaml_file.name), yaml_file)
        subprocess.run([tttool, "assemble", "--no-date", yaml_file.name, audio_file], cwd=work, check=True)
        subprocess.run([tttool, "lint", audio_file], cwd=work, check=True)
        codes = ",".join(str(item[0]) for item in TARGETS)
        for dot_size in (2, 4):
            patterns = work / str(dot_size)
            patterns.mkdir()
            subprocess.run([
                tttool, "--dpi", str(DPI), "--pixel-size", str(dot_size),
                "--code-dim", "28", "oid-code", codes,
            ], cwd=patterns, check=True)
        draw_page(work, page_file)

    with ZipFile(output / "Moon_Base.zip", "w", ZIP_DEFLATED) as bundle:
        for path in (audio_file, page_file):
            bundle.write(path, path.name)


if __name__ == "__main__":
    main()
