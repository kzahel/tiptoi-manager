"""Cross-process import ownership and preservation of literal product contracts."""
from pathlib import Path
import tempfile
import unittest
from localization_common import Invalid, catalog_lock, validate_literals

class CommonTest(unittest.TestCase):
    def test_import_locks_are_exclusive_and_release_without_unlinking(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with catalog_lock(root,'ios','de'):
                with self.assertRaises(Invalid):
                    with catalog_lock(root,'ios','fr'): pass
            with catalog_lock(root,'ios','fr'): pass
            self.assertTrue((root/'.local/localization-import-locks/ios.lock').is_file())

    def test_literal_protocol_and_command_values_are_not_translated(self):
        source = 'Use Pen Library for tiptoi with USB. Check SHA-256 and ISBN at https://example.org.'
        validate_literals(source, 'Pen Library for tiptoi per USB verwenden. SHA-256 und ISBN auf https://example.org prüfen.')
        for old,new in [('Pen Library','Stiftbibliothek'),('USB','Bluetooth'),('SHA-256','SHA-1'),('ISBN','EAN'),('https://example.org','https://example.com')]:
            with self.assertRaises(Invalid): validate_literals(source,source.replace(old,new))
