#!/usr/bin/env python3
"""Offline Android localization gate adapted from web-server-chrome.

Separate independent-review provenance from this release's single-session
checks. Never manufacture a second reviewer identity. No models run in CI.
"""
import argparse
from pathlib import Path
from localization_android import Workspace, CONFIG
from localization_common import Invalid, atomic_write, json_text, read_json

ROOT = Path(__file__).resolve().parents[1]

def check(root=ROOT):
    config = read_json(root / CONFIG)
    for locale in config['locales']:
        ws = Workspace(root, locale)
        report = ws.report()
        record_path = root / 'localization/author-checks' / f'{locale}.json'
        author = read_json(record_path) if record_path.exists() else {}
        errors = {key: value for key, value in report.items() if key not in {'unreviewed','stale','conflicting','terminologyChanged','terminologyUntracked'} and value}
        for name in ws.source:
            row = author.get('resources', {}).get(name, {})
            independently_reviewed = name not in set().union(*(set(report[key]) for key in ['unreviewed','stale','conflicting','terminologyChanged','terminologyUntracked']))
            checked = (author.get('schemaVersion') == 1 and author.get('locale') == locale and author.get('reviewMode') == 'single-session' and author.get('author') and row.get('sourceHash') == ws.source[name]['sourceHash'] and row.get('translationHash') == ws.translation_hash(name) and row.get('terminologyHash') == ws.terminology_hash(name))
            if not (independently_reviewed or checked): errors.setdefault('uncheckedOrChanged', []).append(name)
        if errors: raise Invalid(f'{locale}: {json_text(errors)}')
        print(f'{locale}: {len(ws.source)} resources checked; independent review recorded for {len(ws.source)-len(report["unreviewed"])}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['check', 'record-author-check'])
    parser.add_argument('--locale', choices=['de','fr'])
    parser.add_argument('--author')
    args = parser.parse_args()
    try:
        if args.command == 'check': check()
        else:
            if not args.locale or not args.author: parser.error('Recording requires --locale and actual --author identity')
            ws = Workspace(ROOT,args.locale)
            if set(ws.source) != set(ws.translated): raise Invalid('Missing or obsolete translations')
            for name, row in ws.translated.items(): ws.validate_translation(name,row['value'],row['kind'])
            record = {'schemaVersion':1,'locale':args.locale,'reviewMode':'single-session','author':args.author,'resources':{name:{'sourceHash':row['sourceHash'],'translationHash':ws.translation_hash(name),'terminologyHash':ws.terminology_hash(name)} for name,row in ws.source.items()}}
            atomic_write(ROOT/'localization/author-checks'/f'{args.locale}.json',json_text(record))
            print('Recorded single-session author check, not independent review.')
    except (Invalid, ValueError, OSError) as error:
        parser.exit(1,f'i18n: {error}\n')
