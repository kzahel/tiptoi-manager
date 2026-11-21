#!/usr/bin/env python3
"""Build the duplex Moon Base story with tttool, macOS say and Funsounds clips."""

import argparse
from dataclasses import dataclass
from pathlib import Path
import shutil
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile

from PIL import Image
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph
from reportlab.pdfgen.canvas import Canvas
import soundfile
import yaml

from build import DPI, draw_moon, make_narration


@dataclass(frozen=True)
class Scene:
    chapter: str
    title: str
    narration: str
    action: str
    label: str
    instruction: str


SCENES = (
    Scene("chapter_one", "The Missing Signal",
          "It was almost time to fly home. Then Moon Base lost contact with Earth. "
          "Jo and Pip couldn’t leave until they found out why.",
          "START", "Start here", "Turn on your pen and tap this box once."),
    Scene("chapter_two", "Tracks in the Dust",
          "Pip drove the rover along a silver cable in the moon dust. Jo followed the tracks "
          "around a crater. At the far end, the cable was lying loose beneath the antenna.",
          "rover", "Try the rover", "Tap for the motor. Tap again to find out what makes the tracks."),
    Scene("chapter_three", "Earth, Come In",
          "Jo pushed the cable back into its socket. Pip turned the dish towards a bright "
          "blue dot in the sky. The radio crackled. Someone was there!",
          "antenna", "Call Earth", "Tap for Earth’s reply. Tap again to learn what the dish does."),
    Scene("chapter_four", "Homeward Bound",
          "Earth could hear them again. Jo and Pip climbed aboard and counted down. "
          "Three, two, one! In the window, the Moon grew smaller and Earth grew bigger.",
          "rocket", "Launch the rocket", "Tap for the launch sound. When it ends, tap again to hear how a rocket lifts off."),
)

SPEECH = {
    "welcome": "Moon Base calling! Tap Read to begin our story.",
    "rover_fact": "The rover's wheels press tracks into the soft moon dust. Without wind or rain, those tracks can last a very long time.",
    "antenna_reply": "Moon Base, this is Earth. We hear you loud and clear! You're ready to come home.",
    "antenna_fact": "The dish helps send radio signals in one direction, towards Earth. Those signals travel through space even though ordinary sound can't.",
    "rocket_fact": "A rocket pushes hot gas out of its engines. The gas rushes one way and the rocket moves the other way. That's how we lift off!",
    **{scene.chapter: scene.narration for scene in SCENES},
}
EFFECTS = {"rover_sound": "tractor.mp3", "rocket_sound": "rocket.mp3"}
STEM = "Moon_Base_Story"


def outline(page, points):
    path = page.beginPath()
    path.moveTo(*points[0])
    for point in points[1:]:
        path.lineTo(*point)
    page.drawPath(path)


def draw_scene(page, chapter):
    page.saveState()
    page.translate(48, 499)
    page.setLineCap(1)
    page.setLineJoin(1)
    page.setLineWidth(1.6)
    page.line(0, 26, 500, 26)
    for x, y, width in [(29, 15, 26), (185, 9, 23), (345, 17, 32), (470, 9, 20)]:
        page.ellipse(x - width, y - 3, x + width, y + 3)
    for x, y in [(46, 132), (137, 108), (300, 140), (456, 106), (260, 113)]:
        page.line(x - 3, y, x + 3, y)
        page.line(x, y - 3, x, y + 3)
    if chapter == 1:
        page.arc(112, -55, 300, 107, 0, 180)
        page.line(112, 26, 300, 26)
        page.roundRect(153, 39, 105, 36, 10)
        page.line(205, 39, 205, 75)
        page.line(233, 107, 233, 124)
        page.circle(233, 128, 4)
        draw_moon(page, 406, 124)
    elif chapter == 2:
        page.roundRect(286, 42, 132, 41, 8)
        page.rect(333, 83, 43, 20)
        page.line(371, 102, 394, 124)
        page.circle(397, 127, 4)
        for x in (306, 351, 397):
            page.circle(x, 38, 12)
            page.circle(x, 38, 4)
        for x in range(85, 257, 23):
            page.line(x, 35, x + 11, 38)
            page.line(x + 4, 45, x + 15, 48)
        outline(page, [(16, 58), (44, 62), (92, 77), (124, 61), (190, 57), (212, 65), (269, 69)])
    elif chapter == 3:
        outline(page, [(140, 27), (192, 76), (220, 27), (140, 27)])
        dish = page.beginPath()
        dish.moveTo(133, 83)
        dish.curveTo(195, 38, 221, 75, 250, 129)
        dish.close()
        page.drawPath(dish)
        page.line(191, 106, 222, 135)
        page.circle(226, 138, 5)
        for x, y in [(277, 129), (301, 123), (328, 114)]:
            page.arc(x, y, x + 31, y + 23, -30, 118)
        draw_moon(page, 420, 100)
    else:
        page.saveState()
        page.translate(300, 79)
        page.rotate(-25)
        hull = page.beginPath()
        hull.moveTo(-19, -23)
        hull.curveTo(-23, 7, -15, 39, 0, 54)
        hull.curveTo(15, 39, 23, 7, 19, -23)
        hull.close()
        page.drawPath(hull)
        page.circle(0, 19, 9)
        outline(page, [(-19, 9), (-36, -27), (-19, -23)])
        outline(page, [(19, 9), (36, -27), (19, -23)])
        outline(page, [(-11, -30), (0, -58), (11, -30)])
        page.restoreState()
        page.arc(53, -5, 133, 79, 0, 180)
    page.restoreState()


def paragraph(page, text, x, top, width, size, leading):
    style = ParagraphStyle("moon", fontName="Helvetica", fontSize=size, leading=leading, alignment=TA_LEFT)
    block = Paragraph(text, style)
    _, height = block.wrap(width, A4[1])
    block.drawOn(page, x, top - height)


def draw_target(page, pattern, title, instruction, center_y):
    page.roundRect(47, center_y - 47, 501, 94, 9)
    page.setFont("Helvetica-Bold", 19)
    page.drawString(66, center_y + 9, title)
    paragraph(page, instruction, 66, center_y - 2, 310, 12, 16)
    with Image.open(pattern) as image:
        width, height = (pixels * 72 / DPI for pixels in image.size)
    x = 475 - width / 2
    y = center_y - height / 2
    page.rect(x - 3, y - 3, width + 6, height + 6)
    page.drawImage(str(pattern), x, y, width, height)


def draw_story(work, path, product_id):
    page = Canvas(str(path), pagesize=A4)
    page.setTitle("Moon Base - The Missing Signal")
    for number, scene in enumerate(SCENES, 1):
        page.setLineWidth(0.75)
        page.setFont("Helvetica-Bold", 11)
        page.drawString(47, 781, "MOON BASE")
        page.drawRightString(548, 781, f"CHAPTER {number} OF {len(SCENES)}")
        page.line(47, 766, 548, 766)
        page.setFont("Helvetica-Bold", 39)
        page.drawString(45, 704, scene.title)
        draw_scene(page, number)
        paragraph(page, scene.narration, 47, 478, 501, 15, 21)

        def target(key, title, instruction, y):
            draw_target(page, work / f"oid-{product_id}-{key}.png", title, instruction, y)

        if number == 1:
            target(scene.action, scene.label, scene.instruction, 306)
            target(scene.chapter, "Read", "Tap to hear this chapter. Then turn the page.", 190)
        else:
            target(scene.chapter, "Read", "Tap to hear this chapter.", 306)
            target(scene.action, scene.label, scene.instruction, 190)

        page.setFont("Helvetica", 11)
        page.drawString(47, 91, "Tap the middle of a dotted box." if number == 1 else
                        "Turn the page to continue." if number < len(SCENES) else "The end. Tap the rocket again to launch once more.")
        page.line(47, 74, 548, 74)
        page.setFont("Helvetica", 9)
        page.drawString(47, 56, "Moon Base  /  The Missing Signal")
        page.drawRightString(548, 56, str(number))
        page.showPage()
    page.save()


def make_audio(work, effects, recordings):
    audio = work / "audio"
    audio.mkdir()
    make_narration(audio, SPEECH, recordings, words_per_minute=154)
    for name, filename in EFFECTS.items():
        source = work / "effect.wav"
        subprocess.run([
            "ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-i", effects / filename,
            "-t", "4", "-ac", "1", "-ar", "22050", "-af", "afade=t=in:d=0.04,afade=t=out:st=3.65:d=0.35", source,
        ], check=True)
        samples, rate = soundfile.read(source)
        soundfile.write(audio / f"{name}.ogg", samples, rate, subtype="VORBIS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tttool", type=Path, required=True)
    parser.add_argument("--effects", type=Path, required=True, help="Funsounds public/sounds directory")
    parser.add_argument("--recordings", type=Path, help="Directory of recordings named for their clips")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    tttool = args.tttool.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    gme = output / f"{STEM}.gme"
    pdf = output / f"{STEM}.pdf"
    source = Path(__file__).with_name(f"{STEM}.yaml")
    product_id = yaml.safe_load(source.read_text())["product-id"]
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        make_audio(work, args.effects.resolve(), args.recordings)
        shutil.copyfile(source, work / source.name)
        subprocess.run([tttool, "assemble", "--no-date", source.name, gme], cwd=work, check=True)
        subprocess.run([tttool, "lint", gme], cwd=work, check=True)
        subprocess.run([
            tttool, "--dpi", str(DPI), "--pixel-size", "2", "--code-dim", "28", "oid-codes", source.name,
        ], cwd=work, check=True)
        draw_story(work, pdf, product_id)
    with ZipFile(output / f"{STEM}.zip", "w", ZIP_DEFLATED) as bundle:
        for path in (gme, pdf):
            bundle.write(path, path.name)


if __name__ == "__main__":
    main()
