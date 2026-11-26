#!/usr/bin/env python3
"""Capture and frame real, localized Play screenshots on a disposable emulator."""

import argparse
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'io.github.kzahel.tiptoimanager'
SCENES = ('01-connect', '02-find', '03-transfer', '04-pens')
MAGICK = shutil.which('magick')
COMPOSE = [MAGICK] if MAGICK else ['convert']
IDENTIFY = [MAGICK, 'identify'] if MAGICK else ['identify']
LOCALES = {
    'de-DE': {
        'labels': ('Stift verbinden', 'Stift anschließen', 'Zurück', 'Navigationsmenü öffnen',
                   'Smartphone-Speicher', 'Download-Speicherung', 'Nur übertragen',
                   'Downloads behalten', 'Meine Stifte', 'Demo erkunden', 'Bücher entdecken',
                   'Musik und Rhythmus', 'Herunterladen und installieren', 'Demo beenden'),
        'captions': (
            ('Stift per USB verbinden', 'Mit Datenkabel und bei Bedarf einem Adapter'),
            ('Das passende Buch finden', 'Im deutschen Katalog nach Titeln suchen'),
            ('Audio auf den Stift laden', 'Auf Wunsch direkt mit „Nur übertragen“'),
            ('Mehrere Stifte verwalten', 'Bücher für jeden Stift im Blick behalten'),
        ),
    },
    'en-US': {
        'labels': ('Connect a pen', 'Plug in your pen', 'Back', 'Open navigation menu',
                   'Phone storage', 'Download storage', 'Transfer only', 'Keep downloads',
                   'My pens', 'Explore demo', 'Browse books', 'Music and rhythm',
                   'Download & install', 'Exit demo'),
        'captions': (
            ('Connect your pen by USB', 'Use a data cable and an adapter if needed'),
            ('Find the right book', 'Search the German book catalog by title'),
            ('Send audio to the pen', 'Transfer directly with the Transfer only setting'),
            ('Manage several pens', 'See which books are on each pen'),
        ),
    },
    'fr-FR': {
        'labels': ('Connecter un stylet', 'Brancher le stylet', 'Retour', 'Ouvrir le menu de navigation',
                   'Stockage du téléphone', 'Conservation des téléchargements', 'Transfert uniquement',
                   'Conserver les téléchargements', 'Mes stylets', 'Explorer la démo',
                   'Parcourir les livres', 'Musique et rythme', 'Télécharger et installer', 'Quitter la démo'),
        'captions': (
            ('Branchez le stylet en USB', 'Avec un câble de données et, au besoin, un adaptateur'),
            ('Trouvez le bon livre', 'Recherchez un titre dans le catalogue allemand'),
            ('Transférez le fichier audio', 'En un seul geste avec « Transfert uniquement »'),
            ('Gérez plusieurs stylets', 'Retrouvez les livres chargés sur chacun'),
        ),
    },
}


def frame(raw, destination, caption, font, bold_font):
    heading, supporting = caption
    subprocess.run([
        *COMPOSE, '-size', '1080x1920', 'xc:#dcefe7',
        '-font', bold_font, '-fill', '#123e35', '-pointsize', '63',
        '-gravity', 'north', '-annotate', '+0+87', heading,
        '-font', font, '-fill', '#24564a', '-pointsize', '33',
        '-annotate', '+0+171', supporting,
        '(', str(raw), '-resize', '870x', '-bordercolor', '#ffffff', '-border', '6', ')',
        '-gravity', 'north', '-geometry', '+0+272', '-composite',
        f'PNG24:{destination}',
    ], check=True)


def capture(serial, locale, output, font, bold_font):
    if not serial.startswith('emulator-'):
        raise ValueError('Store captures require an explicitly selected disposable emulator')
    config = LOCALES[locale]
    (connect, plug, back, menu, storage, retention, transfer_only, keep, pens, demo,
     browse, music, install, exit_demo) = config['labels']
    remote_dump = '/sdcard/pen-library-store-ui.xml'

    def adb(*args):
        return subprocess.run(['adb', '-s', serial, *args], check=True, capture_output=True).stdout

    def find(label):
        for _ in range(4):
            adb('shell', 'uiautomator', 'dump', remote_dump)
            nodes = ET.fromstring(adb('exec-out', 'cat', remote_dump)).iter('node')
            for node in nodes:
                for value in (node.get('text', ''), node.get('content-desc', '')):
                    if value == label or value.startswith(label + '\n'):
                        return node
        raise RuntimeError(f'{locale}: control missing: {label}')

    def tap(label):
        bounds = list(map(int, re.findall(r'\d+', find(label).get('bounds'))))
        x1, y1, x2, y2 = bounds
        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(0.4)

    def navigate(label):
        tap(menu)
        tap(label)

    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        raw = Path(temp) / 'screen.png'

        def save(index, visible):
            find(visible)
            if index > 0:
                find(exit_demo)
            raw.write_bytes(adb('exec-out', 'screencap', '-p'))
            info = subprocess.check_output([*IDENTIFY, '-format', '%wx%h', str(raw)])
            if info != b'1080x1920':
                raise RuntimeError('Set the disposable emulator to 1080x1920 before capture')
            target = output / (SCENES[index] + '.png')
            frame(raw, target, config['captions'][index], font, bold_font)
            print(target, flush=True)

        try:
            adb('shell', 'am', 'force-stop', PACKAGE)
            adb('shell', 'cmd', 'locale', 'set-app-locales', PACKAGE, '--user', '0', '--locales', locale)
            adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
            tap(connect)
            save(0, plug)
            tap(back)

            navigate(storage)
            tap(retention)
            tap(transfer_only)
            navigate(pens)
            tap(demo)
            navigate(browse)
            save(1, music)
            tap(music)
            save(2, install)
            tap(exit_demo)
            tap(demo)
            save(3, exit_demo)

            tap(exit_demo)
            navigate(storage)
            tap(retention)
            tap(keep)
        finally:
            adb('shell', 'rm', '-f', remote_dump)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--locale', choices=LOCALES, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'store',
                        help='Root containing the per-locale export directories')
    parser.add_argument('--font', default='DejaVu-Sans')
    parser.add_argument('--bold-font', default='DejaVu-Sans-Bold')
    args = parser.parse_args()
    capture(args.serial, args.locale, args.output / args.locale / 'phone', args.font, args.bold_font)
