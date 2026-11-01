#!/usr/bin/env python3
"""Validate checked-in Play metadata and required PNG export dimensions offline."""
import json
from pathlib import Path
import struct

ROOT=Path(__file__).resolve().parents[1]
def png(path,size,alpha=False):
    with path.open('rb') as handle: header=handle.read(33)
    if header[:8]!=b'\x89PNG\r\n\x1a\n' or header[12:16]!=b'IHDR':raise ValueError(f'Not a PNG: {path}')
    width,height,depth,kind=struct.unpack('>IIBB',header[16:26])
    if (width,height)!=size or depth!=8 or kind not in ({2,6} if alpha else {2}):raise ValueError(f'Wrong dimensions/encoding: {path}')
def check():
    png(ROOT/'store/icon.png',(512,512),alpha=True)
    for locale in ['en-US','de-DE','fr-FR']:
        folder=ROOT/'store'/locale
        listing=json.loads((folder/'listing.json').read_text())
        for key,limit in [('title',30),('shortDescription',80),('fullDescription',4000)]:
            if not isinstance(listing[key],str) or not 1<=len(listing[key])<=limit:raise ValueError(f'{locale}: invalid {key}')
        png(folder/'feature.png',(1024,500))
        shots=sorted((folder/'phone').glob('*.png'))
        if not 2<=len(shots)<=8:raise ValueError(f'{locale}: expected 2–8 phone screenshots')
        for path in shots:png(path,(1080,1920))
        print(f'{locale}: metadata, feature graphic and {len(shots)} RGB screenshots checked')
if __name__=='__main__':check()
