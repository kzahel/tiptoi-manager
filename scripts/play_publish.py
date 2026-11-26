#!/usr/bin/env python3
"""Publish source-bound bundles and localized store assets through Google Play edits."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

import check_store

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'io.github.kzahel.tiptoimanager'
API = 'https://androidpublisher.googleapis.com'
LOCALES = check_store.LOCALES
FIELDS = ('title', 'shortDescription', 'fullDescription')
TAG = r'play-v(\d+\.\d+\.\d+)-([1-9]\d*)'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def release_notes(store):
    raw = (store / 'release-notes.txt').read_text()
    pattern = re.compile(r'<([a-z]{2}-[A-Z]{2})>\s*(.*?)\s*</\1>', re.DOTALL)
    matches = pattern.findall(raw)
    notes = dict(matches)
    if (pattern.sub('', raw).strip() or len(matches) != len(LOCALES)
            or set(notes) != set(LOCALES) or any(not 1 <= len(note) <= 500 for note in notes.values())):
        raise ValueError('Expected one release note of 1–500 characters per Play listing locale')
    return [{'language': locale, 'text': notes[locale]} for locale in LOCALES]


def listing_assets(store):
    for locale in LOCALES:
        folder = store / locale
        images = {
            'featureGraphic': [folder / 'feature.png'],
            'phoneScreenshots': sorted((folder / 'phone').glob('*.png')),
        }
        if locale == 'en-US':
            images['icon'] = [store / 'icon.png']
        yield locale, folder / 'listing.json', images


def store_hashes(store):
    paths = [store / 'release-notes.txt']
    for _, listing, images in listing_assets(store):
        paths.append(listing)
        paths.extend(path for group in images.values() for path in group)
    return {path.relative_to(store).as_posix(): sha256(path) for path in sorted(paths)}


def app_version():
    build = (ROOT / 'android/app/build.gradle.kts').read_text()
    return re.search(r'versionName = "([^"]+)"', build).group(1)


def release_tag(version, code):
    return f'v{version}-rc{code}'


def select_ref(ref):
    """Validate the source ref before signing, reading credentials or writing to Play."""
    if ref == 'refs/heads/main':
        return {'operation': 'check', 'version': app_version(), 'code': '', 'release': ''}
    tag = ref.removeprefix('refs/tags/')
    if tag == ref:
        raise ValueError('Expected the main branch or a release tag')
    if re.fullmatch(r'play-listing-[A-Za-z0-9][A-Za-z0-9._-]*', tag):
        return {'operation': 'listings', 'version': '', 'code': '', 'release': ''}
    build_tag = tag.replace('play-promote-v', 'play-v', 1) if tag.startswith('play-promote-v') else tag
    matched = re.fullmatch(TAG, build_tag)
    if not matched:
        raise ValueError('Expected play-vMAJOR.MINOR.PATCH-CODE or play-promote-vMAJOR.MINOR.PATCH-CODE')
    version, code = matched.groups()
    if version != app_version() or not 1 <= int(code) <= 2_100_000_000:
        raise ValueError('Tag must match the app version and contain a valid Play version code')
    return {'operation': 'internal' if build_tag == tag else 'production',
            'version': version, 'code': code, 'release': release_tag(version, code), 'build_tag': build_tag}


def plans(ref, source, version_code=None):
    plan = select_ref(ref)
    if not re.fullmatch(r'[a-f0-9]{40}', source):
        raise ValueError('Invalid source revision')
    checkout = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if checkout != source:
        raise ValueError('The checkout differs from the source revision')
    if plan['operation'] == 'check' and version_code is None:
        return plan
    ancestor = subprocess.run(
        ['git', 'merge-base', '--is-ancestor', source, 'origin/main'], cwd=ROOT,
    ).returncode
    if ancestor:
        raise ValueError('Release and listing tags must point to a commit on origin/main')
    if plan['operation'] == 'check':
        if not version_code.isascii() or not version_code.isdecimal() or not 1 <= int(version_code) <= 2_100_000_000:
            raise ValueError('Invalid Play version code')
        plan.update(operation='candidate', code=str(int(version_code)),
                    release=release_tag(plan['version'], int(version_code)))
    if plan['operation'] == 'production':
        build_source = subprocess.check_output(
            ['git', 'rev-parse', '--verify', f"refs/tags/{plan['build_tag']}^{{commit}}"],
            cwd=ROOT, text=True,
        ).strip()
        if build_source != source:
            raise ValueError('Production tag must point to the same commit as the internal tag')
    return plan


def verify_candidate(directory, source, plan, store, verify_bundle=False):
    receipt = json.loads((directory / 'source.json').read_text())
    expected = dict(package=PACKAGE, commit=source, versionName=plan['version'],
                    versionCode=int(plan['code']), tag=plan['build_tag'], storeSha256=store_hashes(store))
    if any(receipt.get(key) != value for key, value in expected.items()):
        raise ValueError('Release receipt differs from the source tag or listing files')
    digest = receipt['sha256']['pen-library.aab']
    if not re.fullmatch(r'[a-f0-9]{64}', digest):
        raise ValueError('Invalid bundle checksum in release receipt')
    if verify_bundle and sha256(directory / 'pen-library.aab') != digest:
        raise ValueError('Bundle checksum differs from release receipt')
    return digest


class Play:
    def __init__(self, token):
        if not token:
            raise ValueError('PLAY_ACCESS_TOKEN is required')
        self.token = token

    def call(self, method, path, body=None, media=None):
        prefix = '/upload' if media else ''
        url = f'{API}{prefix}/androidpublisher/v3/applications/{PACKAGE}/edits{path}'
        data = body if media else (None if body is None else json.dumps(body).encode())
        request = Request(url, data=data, method=method, headers={
            'Authorization': f'Bearer {self.token}',
            'Content-Type': media or 'application/json',
        })
        retry_delays = (2, 4, None) if method == 'GET' else (None,)
        for delay in retry_delays:
            try:
                with urlopen(request, timeout=180 if media else 30) as response:
                    content = response.read()
                    return json.loads(content) if content else {}
            except HTTPError as error:
                with error:
                    if delay is not None and error.code in (429, 500, 502, 503, 504):
                        print(f'Play API GET {path}: HTTP {error.code}, retrying in {delay}s',
                              file=sys.stderr)
                    else:
                        detail = ''
                        if error.headers.get_content_type() == 'application/json':
                            try:
                                detail = json.loads(error.read()).get('error', {}).get('message', '')
                            except (ValueError, AttributeError):
                                pass
                        detail = detail.replace(self.token, '[redacted]').strip()
                        raise RuntimeError(
                            f'Play API {method} {path}: HTTP {error.code} {detail[:350]}',
                        ) from None
                time.sleep(delay)


def image_hashes(images):
    return [image['sha256'].lower() for image in images]


def sync_listings(play, edit, store):
    current = play.call('GET', f'{edit}/listings').get('listings', [])
    by_locale = {item['language']: item for item in current}
    changes = []
    for locale, text, groups in listing_assets(store):
        path = f'{edit}/listings/{locale}'
        desired = json.loads(text.read_text())
        if any(by_locale.get(locale, {}).get(key) != desired[key] for key in FIELDS):
            update = play.call('PATCH' if locale in by_locale else 'PUT', path, desired)
            if any(update.get(key) != desired[key] for key in FIELDS):
                raise ValueError(f'Play text read-back differs for {locale}')
            changes.append(f'{locale}/text')
        for kind, files in groups.items():
            folder = f'{path}/{kind}'
            digests = [sha256(file) for file in files]
            current_images = play.call('GET', folder).get('images', [])
            if image_hashes(current_images) == digests:
                continue
            if current_images:
                play.call('DELETE', folder)
            generated = ('aiGeneratedStateAiGeneratedDeveloperAttested' if kind == 'featureGraphic'
                         else 'aiGeneratedStateNotAiGenerated')
            for file in files:
                play.call('POST', f'{folder}?uploadType=media&aiGeneratedState={generated}',
                          file.read_bytes(), media='image/png')
            if image_hashes(play.call('GET', folder).get('images', [])) != digests:
                raise ValueError(f'Play image hashes or order differ for {locale}/{kind}')
            changes.append(f'{locale}/{kind}')
    return changes


def has_version(release, code):
    return [str(value) for value in release.get('versionCodes', [])] == [str(code)]


def sync_release(play, edit, tracks, bundles, operation, plan, store, bundle, digest):
    code = int(plan['code'])
    active = {item['track']: item.get('releases', []) for item in tracks}
    releases = active.get(operation, [])
    existing = next((item for item in bundles if item['versionCode'] == code), None)
    if existing and existing['sha256'].lower() != digest:
        raise ValueError('Play already has a different bundle with this version code')
    if existing and any(has_version(item, code) and item.get('status') == 'completed' for item in releases):
        return False
    if any(item['status'] in ('inProgress', 'halted', 'draft') for item in releases):
        raise ValueError(f'Resolve the existing rollout or draft on {operation} before publishing')
    compared = releases + active.get('production', []) if operation == 'internal' else releases
    for item in compared:
        if any(int(value) >= code for value in item.get('versionCodes', [])):
            raise ValueError('Play already has an equal or newer release on this track or production')
    if operation == 'production':
        if not existing or not any(has_version(item, code) and item.get('status') == 'completed'
                                   for item in active.get('internal', [])):
            raise ValueError('Promote a verified bundle currently on the completed internal track')
    if not existing:
        uploaded = play.call('POST', f'{edit}/bundles?uploadType=media', bundle.read_bytes(),
                             media='application/octet-stream')
        if uploaded['versionCode'] != code or uploaded['sha256'].lower() != digest:
            raise ValueError('Play uploaded a bundle with the wrong version or checksum')
    play.call('PUT', f'{edit}/tracks/{operation}', {
        'track': operation,
        'releases': [{'name': plan['version'], 'versionCodes': [str(code)], 'status': 'completed',
                      'releaseNotes': release_notes(store)}],
    })
    return True


def publish(play, plan, source, store, candidate, digest):
    operation = plan['operation']
    edit_id = play.call('POST', '', {})['id']
    edit = '/' + quote(edit_id, safe='')
    commit_attempted = False
    try:
        tracks = play.call('GET', f'{edit}/tracks').get('tracks', [])
        bundles = play.call('GET', f'{edit}/bundles').get('bundles', [])
        result = {'operation': operation, 'source': source, 'initialTrackVersions': {
            track['track']: [release.get('versionCodes', []) for release in track.get('releases', [])]
            for track in tracks if track['track'] in ('internal', 'production')
        }}
        if operation == 'check':
            listings = play.call('GET', f'{edit}/listings').get('listings', [])
            result.update(status='access_verified', listingLocales=[item['language'] for item in listings])
            return result
        releases_changed = operation in ('internal', 'production') and sync_release(
            play, edit, tracks, bundles, operation, plan, store, candidate / 'pen-library.aab', digest)
        listing_changes = sync_listings(play, edit, store) if operation in ('production', 'listings') else []
        result.update(listingsChanged=listing_changes, status='unchanged')
        if digest:
            result.update(versionCode=int(plan['code']), sha256=digest)
        if not releases_changed and not listing_changes:
            return result
        play.call('POST', f'{edit}:validate', {})
        commit_attempted = True
        try:
            play.call('POST', f'{edit}:commit?changesNotSentForReview=false&changesInReviewBehavior=ERROR_IF_IN_REVIEW', {})
        except Exception:
            print(f'Commit did not return success for edit {edit_id}; reconcile in Play Console before rerunning.',
                  file=sys.stderr)
            raise
        result.update(status='submitted', editId=edit_id)
        return result
    finally:
        if not commit_attempted:
            try:
                play.call('DELETE', edit)
            except Exception:
                print(f'Could not discard uncommitted edit {edit_id}', file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('plan', 'receipt', 'verify', 'publish'))
    parser.add_argument('--ref', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--version-code')
    parser.add_argument('--candidate', type=Path)
    args = parser.parse_args()
    plan = plans(args.ref, args.source, args.version_code)
    store = ROOT / 'store'
    if args.command == 'plan':
        for key, value in plan.items():
            print(f'{key}={value}')
    elif args.command == 'receipt':
        receipt = {
            'package': PACKAGE, 'commit': args.source, 'run': os.environ['RUN_URL'],
            'runId': os.environ['GITHUB_RUN_ID'], 'tag': plan.get('build_tag'),
            'versionName': plan['version'], 'versionCode': int(plan['code']),
            'storeSha256': store_hashes(store),
            'sha256': {path.name: sha256(path) for path in sorted(args.candidate.iterdir()) if path.is_file()},
        }
        (args.candidate / 'source.json').write_text(json.dumps(receipt, indent=2) + '\n')
        (args.candidate / 'SHA256SUMS').write_text(''.join(
            f'{digest}  {name}\n' for name, digest in receipt['sha256'].items()))
    else:
        check_store.check()
        release_notes(store)
        digest = None
        if plan['operation'] in ('internal', 'production'):
            digest = verify_candidate(args.candidate, args.source, plan, store,
                                      verify_bundle=plan['operation'] == 'internal')
        if args.command == 'publish':
            result = publish(Play(os.environ.get('PLAY_ACCESS_TOKEN')), plan, args.source, store, args.candidate, digest)
            print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError) as error:
        raise SystemExit(str(error)) from None
