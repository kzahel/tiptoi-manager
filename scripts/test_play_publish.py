"""Regression checks for Play edit ordering, retries and candidate promotion."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import play_publish as publisher


class PlayPublishingTest(unittest.TestCase):
    def test_listing_orders_screenshots_and_skips_unchanged_assets(self):
        old = hashlib.sha256(b'old').hexdigest()
        desired = {'title': 'Title', 'shortDescription': 'Short', 'fullDescription': 'Full'}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            listing = root / 'listing.json'
            listing.write_text(json.dumps(desired))
            screenshots = [root / '01-connect.png', root / '02-find.png']
            for path in screenshots:
                path.write_bytes(path.name.encode())
            files = [('de-DE', listing, {'phoneScreenshots': screenshots})]
            remote = [{'sha256': old}]
            operations = []

            def call(method, path, body=None, media=None):
                operations.append((method, path, media))
                if path == '/edit/listings':
                    return {'listings': [{'language': 'de-DE', **desired, 'video': 'retained'}]}
                if method == 'GET':
                    return {'images': remote.copy()}
                if method == 'DELETE':
                    remote.clear()
                if method == 'POST':
                    remote.append({'sha256': hashlib.sha256(body).hexdigest()})
                return {}

            play = Mock(call=call)
            with patch.object(publisher, 'listing_assets', return_value=files):
                self.assertEqual(publisher.sync_listings(play, '/edit', root), ['de-DE/phoneScreenshots'])
                self.assertEqual([row[0] for row in operations], ['GET', 'GET', 'DELETE', 'POST', 'POST', 'GET'])
                self.assertEqual([row[2] for row in operations if row[0] == 'POST'], ['image/png'] * 2)
                self.assertEqual(publisher.image_hashes(remote), [publisher.sha256(file) for file in screenshots])
                operations.clear()
                self.assertEqual(publisher.sync_listings(play, '/edit', root), [])
                self.assertEqual([row[0] for row in operations], ['GET', 'GET'])

    def test_production_requires_verified_completed_internal_version(self):
        play = Mock()
        bundle = {'versionCode': 103, 'sha256': 'a' * 64}
        plan = {'code': '103', 'version': '0.2.0'}
        production = {'track': 'production', 'releases': [{'versionCodes': ['102'], 'status': 'completed'}]}
        store = publisher.ROOT / 'store'
        with self.assertRaisesRegex(ValueError, 'currently on the completed internal track'):
            publisher.sync_release(play, '/edit', [production], [bundle], 'production', plan, store, None, 'a' * 64)
        play.call.assert_not_called()
        internal = {'track': 'internal', 'releases': [{'versionCodes': ['103'], 'status': 'completed'}]}
        with self.assertRaisesRegex(ValueError, 'different bundle'):
            publisher.sync_release(play, '/edit', [production, internal], [bundle], 'production', plan, store, None, 'b' * 64)
        play.call.assert_not_called()
        self.assertTrue(publisher.sync_release(
            play, '/edit', [production, internal], [bundle], 'production', plan, store, None, 'a' * 64))
        method, path, request = play.call.call_args.args
        self.assertEqual((method, path), ('PUT', '/edit/tracks/production'))
        self.assertEqual(request['releases'][0]['versionCodes'], ['103'])
        self.assertEqual({note['language'] for note in request['releases'][0]['releaseNotes']}, set(publisher.LOCALES))

    def test_uncertain_commit_is_neither_retried_nor_deleted(self):
        play = Mock()
        play.call.side_effect = [{'id': 'edit'}, {}, {}, {}, TimeoutError('response lost')]
        with patch.object(publisher, 'sync_listings', return_value=['de-DE/text']):
            with self.assertRaises(TimeoutError):
                publisher.publish(play, {'operation': 'listings'}, 'source', None, None, None)
        routes = [(args.args[0], args.args[1]) for args in play.call.call_args_list]
        self.assertEqual(sum(':commit' in route for _, route in routes), 1)
        self.assertIn('ERROR_IF_IN_REVIEW', routes[-1][1])
        self.assertNotIn(('DELETE', '/edit'), routes)

    def test_read_only_check_discards_its_edit(self):
        play = Mock()
        play.call.side_effect = [{'id': 'edit'}, {}, {}, {'listings': [{'language': 'de-DE'}]}, {}]
        result = publisher.publish(play, {'operation': 'check'}, 'source', None, None, None)
        self.assertEqual(result['status'], 'access_verified')
        self.assertEqual(result['listingLocales'], ['de-DE'])
        play.call.assert_called_with('DELETE', '/edit')


if __name__ == '__main__':
    unittest.main()
