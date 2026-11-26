"""Export the Play artwork and Android launcher layers from the source art."""

import argparse
import base64
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'art/source'
STORE = ROOT / 'store'
DRAWABLE = ROOT / 'android/app/src/main/res/drawable-nodpi'
SVG = 'http://www.w3.org/2000/svg'
XLINK = 'http://www.w3.org/1999/xlink'
ET.register_namespace('', SVG)
ET.register_namespace('xlink', XLINK)


def render(svg, destination):
    subprocess.run(['rsvg-convert', svg, '-o', destination], check=True)


def render_svg(svg, destination):
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / 'icon.svg'
        path.write_text(svg)
        render(path, destination)


def export_icons():
    DRAWABLE.mkdir(exist_ok=True)
    icon = SOURCE / 'icon.svg'
    render(icon, STORE / 'icon.png')
    layers = ET.parse(icon).getroot()
    badge = layers.find(f'{{{SVG}}}g[@id="usb-badge"]')
    if badge is None:
        raise ValueError('The icon SVG is missing its USB badge layer')

    # The visible 72 dp artwork sits in the center of each 108 dp layer.
    # The remaining 18 dp on each edge supports adaptive motion and clipping.
    render_svg(
        f'<svg xmlns="{SVG}" width="768" height="768" viewBox="-128 -128 768 768">'
        + ET.tostring(badge, encoding='unicode') + '</svg>',
        DRAWABLE / 'ic_farm_usb_badge.png',
    )
    monochrome = ET.parse(SOURCE / 'icon-monochrome.svg').getroot()
    monochrome.attrib.update(width='768', height='768', viewBox='-128 -128 768 768')
    render_svg(ET.tostring(monochrome, encoding='unicode'), DRAWABLE / 'ic_farm_usb_monochrome.png')
    subprocess.run([
        'magick', SOURCE / 'farm-usb-scene.png', '-resize', '512x512', '-filter', 'point',
        '-virtual-pixel', 'edge', '-set', 'option:distort:viewport', '768x768-128-128',
        '-distort', 'SRT', '0', '+repage', '-strip', f'PNG24:{DRAWABLE / "ic_farm_usb_scene.png"}',
    ], check=True)


def export_feature_graphics():
    encoded = base64.b64encode((SOURCE / 'library-still-life.png').read_bytes()).decode()
    captions = (
        ('en-US', 'Book audio', 'to your pen', 'From your Android phone over USB'),
        ('de-DE', 'tiptoi-Audio', 'auf den Stift', 'Vom Android-Handy per USB'),
        ('fr-FR', 'L’audio tiptoi', 'sur le stylet', 'Depuis votre téléphone Android'),
    )
    for locale, first, second, subtitle in captions:
        svg = f'''<svg xmlns="{SVG}" xmlns:xlink="{XLINK}" width="1024" height="500" viewBox="0 0 1024 500">
<rect width="1024" height="500" fill="#F6F8F5"/><image width="1024" height="500" xlink:href="data:image/png;base64,{encoded}"/>
<g font-family="Arial, sans-serif" fill="#16483E"><text x="38" y="95" font-size="25" font-weight="700">Pen Library</text><text x="38" y="164" font-size="40" font-weight="700">{escape(first)}</text><text x="38" y="212" font-size="40" font-weight="700">{escape(second)}</text><text x="38" y="255" font-size="19">{escape(subtitle)}</text></g></svg>'''
        feature = STORE / locale / 'feature.png'
        render_svg(svg, feature)
        subprocess.run(['magick', feature, f'PNG24:{feature}'], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--icons-only', action='store_true', help='Export the Play and Android icons only')
    args = parser.parse_args()
    export_icons()
    if not args.icons_only:
        export_feature_graphics()
