#!/usr/bin/env python3
"""Validate checked-in Play text, image dimensions and upload limits offline."""

import json
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
LOCALES = ('en-US', 'de-DE', 'fr-FR')


def png(path, size, maximum=8 * 1024 * 1024, alpha=False):
    if path.stat().st_size > maximum:
        raise ValueError(f'Image exceeds upload size: {path}')
    with path.open('rb') as handle:
        header = handle.read(33)
    if header[:8] != b'\x89PNG\r\n\x1a\n' or header[12:16] != b'IHDR' or len(header) != 33:
        raise ValueError(f'Not a PNG: {path}')
    width, height, depth, kind = struct.unpack('>IIBB', header[16:26])
    if (width, height) != size or depth != 8 or kind not in ({2, 6} if alpha else {2}):
        raise ValueError(f'Wrong dimensions or encoding: {path}')


def check(store=ROOT / 'store'):
    png(store / 'icon.png', (512, 512), maximum=1024 * 1024, alpha=True)
    for locale in LOCALES:
        folder = store / locale
        listing = json.loads((folder / 'listing.json').read_text())
        limits = {'title': 30, 'shortDescription': 80, 'fullDescription': 4000}
        if set(listing) != set(limits):
            raise ValueError(f'{locale}: expected exactly title, shortDescription and fullDescription')
        for key, limit in limits.items():
            if not isinstance(listing[key], str) or not 1 <= len(listing[key].strip()) <= limit:
                raise ValueError(f'{locale}: invalid {key}')
        png(folder / 'feature.png', (1024, 500))
        shots = sorted((folder / 'phone').glob('*.png'))
        if not 4 <= len(shots) <= 8:
            raise ValueError(f'{locale}: expected 4–8 phone screenshots')
        if (any(not re.fullmatch(r'\d\d-[a-z0-9-]+\.png', path.name) for path in shots)
                or len({path.name[:2] for path in shots}) != len(shots)):
            raise ValueError(f'{locale}: use distinct two-digit prefixes for screenshot order')
        for path in shots:
            png(path, (1080, 1920))
        print(f'{locale}: text, feature graphic and {len(shots)} RGB screenshots checked')


if __name__ == '__main__':
    check()
