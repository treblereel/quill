import hashlib
from pathlib import Path
import tempfile
import unittest

import quill_homebrew as brew


class HomebrewFormulaTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / 'Formula/quill.rb'
        for platform in brew.PLATFORMS:
            name = f'quill-{platform}.tar.gz'
            data = platform.encode()
            (self.root / name).write_bytes(data)
            (self.root / (name + '.sha256')).write_text(hashlib.sha256(data).hexdigest() + '  dist/' + name + '\n')

    def generate(self, version='1.2.3'):
        brew.generate(version, 'treblereel/quill', self.root, self.output)
        return self.output.read_text()

    def test_all_platform_urls_and_hashes_and_native_only_install(self):
        formula = self.generate()
        for platform in brew.PLATFORMS:
            self.assertIn(f'/v1.2.3/quill-{platform}.tar.gz', formula)
            self.assertIn(hashlib.sha256(platform.encode()).hexdigest(), formula)
        self.assertEqual(4, formula.count('      url '))
        self.assertIn('bin.install "quill"', formula)
        self.assertNotIn('depends_on "openjdk', formula)
        self.assertIn('method: "initialize"', formula)
        self.assertIn('"tools/list"', formula)
        self.assertIn('assert_predicate process.value, :success?', formula)
        self.assertEqual(formula, self.generate())

    def test_accepts_shasum_formats(self):
        name = 'quill-macos-aarch64.tar.gz'
        checksum = hashlib.sha256(b'macos-aarch64').hexdigest()
        for prefix, marker in (('', ' '), ('./', ' '), ('dist/', '*')):
            with self.subTest(prefix=prefix, marker=marker):
                (self.root / (name + '.sha256')).write_text(checksum.upper() + ' ' + marker + prefix + name + '\n')
                self.assertEqual(checksum, brew.archive_checksum(self.root, 'macos-aarch64'))

    def test_invalid_input_preserves_existing_formula(self):
        original = self.generate()
        name = 'quill-linux-x86_64.tar.gz'
        for content in ('0' * 64 + '  ' + name, 'f' * 63 + '  ' + name,
                        'f' * 64 + '  other.tar.gz', 'f' * 64 + '  ../' + name,
                        'f' * 64 + '  ' + name + '\n' + 'f' * 64 + '  ' + name):
            with self.subTest(content=content):
                (self.root / (name + '.sha256')).write_text(content)
                with self.assertRaises(ValueError):
                    self.generate()
                self.assertEqual(original, self.output.read_text())

    def test_missing_archive_or_checksum_fails(self):
        for extension in ('', '.sha256'):
            with self.subTest(extension=extension):
                path = self.root / ('quill-linux-aarch64.tar.gz' + extension)
                previous = path.read_bytes()
                path.unlink()
                with self.assertRaises(FileNotFoundError):
                    self.generate()
                self.assertFalse(self.output.exists())
                path.write_bytes(previous)

    def test_version_and_repository_cannot_inject_ruby_or_urls(self):
        for version in ('v1.2.3', '01.2.3', '1', '1.2\n', '1.2/#{evil}', '1.2"; abort', '../1.2'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.generate(version)
        for repository in ('../quill', 'treblereel/quill/extra', 'owner/#{evil}', 'owner/repo"', 'owner/repo\n'):
            with self.subTest(repository=repository), self.assertRaises(ValueError):
                brew.render_formula('1.2.3', repository, {p: 'a' * 64 for p in brew.PLATFORMS})

    def test_prerelease_can_be_generated_but_is_not_stable(self):
        for version in ('1.2', '1.2.3'):
            self.assertTrue(brew.stable_version(version))
        for version in ('0.1-SNAPSHOT', '1.2.3-rc.1'):
            self.assertIn(f'version "{version}"', self.generate(version))
            self.assertFalse(brew.stable_version(version))

    def test_missing_platform_and_invalid_digest_fail(self):
        for checksums in ({}, {p: 'z' * 64 for p in brew.PLATFORMS}):
            with self.assertRaises(ValueError):
                brew.render_formula('1.2.3', 'treblereel/quill', checksums)

    def test_stale_release_cannot_downgrade_tap(self):
        self.output.parent.mkdir()
        for old, new, rejected in (('1.3.0', '1.2.3', True), ('1.2', '1.2.0', False),
                                  ('1.2.9', '1.2.10', False), ('1.2.3', '1.2.3', False),
                                  ('1.2.3', '1.2.4', False), ('1.2-SNAPSHOT', '1.2.3', True)):
            with self.subTest(old=old, new=new):
                self.output.write_text(f'  version "{old}"\n')
                if rejected:
                    with self.assertRaises(ValueError):
                        brew.check_upgrade(new, self.output)
                else:
                    brew.check_upgrade(new, self.output)
        for text in ('', '  version "1.2.3"\n  version "1.2.4"\n'):
            self.output.write_text(text)
            with self.assertRaises(ValueError):
                brew.check_upgrade('1.2.4', self.output)
        brew.check_upgrade('1.2.4', self.root / 'missing.rb')

    def test_existing_version_cannot_be_replaced_with_different_archives(self):
        original = self.generate()
        brew.generate('1.2.3', 'treblereel/quill', self.root, self.output, self.output)
        self.assertEqual(original, self.output.read_text())
        name = 'quill-macos-aarch64.tar.gz'
        (self.root / name).write_bytes(b'changed release')
        checksum = hashlib.sha256(b'changed release').hexdigest()
        (self.root / (name + '.sha256')).write_text(checksum + '  ' + name)
        with self.assertRaisesRegex(ValueError, 'existing release version'):
            brew.generate('1.2.3', 'treblereel/quill', self.root, self.output, self.output)
        self.assertEqual(original, self.output.read_text())
        brew.generate('1.2.4', 'treblereel/quill', self.root, self.output, self.output)
        self.assertIn('version "1.2.4"', self.output.read_text())


if __name__ == '__main__':
    unittest.main()
