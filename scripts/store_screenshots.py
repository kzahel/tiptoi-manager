#!/usr/bin/env python3
"""Capture actual localized demo screens on an explicitly selected Android 13+ emulator.

No app storage clearing, private file access, downloaded covers or photo editing.
Only run on a dedicated disposable emulator; app language is changed per app.
"""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
PACKAGE='io.github.kzahel.tiptoimanager'
LABELS={
'en-US':('Explore demo','Home pen','Installed books','A walk in the forest'),
'de-DE':('Demo erkunden','Stift für zu Hause','Installierte Bücher','Ein Spaziergang im Wald'),
'fr-FR':('Explorer la démo','Stylet de la maison','Livres installés','Balade en forêt')}

def capture(serial,locale):
    adb_path=shutil.which('adb')
    if not adb_path: raise RuntimeError('Put Android platform-tools on PATH')
    def adb(*args):return subprocess.run([adb_path,'-s',serial,*args],check=True,capture_output=True).stdout
    if not serial.startswith('emulator-'):raise RuntimeError('Store captures require a dedicated emulator')
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales',locale)
    adb('shell','am','start','-n',PACKAGE+'/.MainActivity')
    time.sleep(1)
    def nodes():
        adb('shell','uiautomator','dump','/sdcard/pen-library-store-ui.xml')
        return list(ET.fromstring(adb('exec-out','cat','/sdcard/pen-library-store-ui.xml')).iter('node'))
    def tap(label):
        found=[n for n in nodes() if n.get('text')==label or n.get('content-desc')==label]
        if not found:raise RuntimeError('Control missing: '+label)
        b=list(map(int,re.findall(r'\d+',found[0].get('bounds'))))
        adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.3)
    out=ROOT/'store'/locale/'phone';out.mkdir(parents=True,exist_ok=True)
    try:
        for label,name in zip(LABELS[locale],['01-pens','02-pen','03-library','04-book']):
            tap(label)
            visible=nodes()
            if not any('Demo' in n.get('text','') or 'Démo' in n.get('text','') for n in visible):raise RuntimeError('Demo banner missing')
            (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
            print(out/(name+'.png'))
    finally:adb('shell','rm','-f','/sdcard/pen-library-store-ui.xml')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--locale',choices=LABELS,required=True)
    args=parser.parse_args();capture(args.serial,args.locale)
