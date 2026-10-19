"""Synthetic temporary files only. Never reads project credentials or calls a provider."""
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent


class CredentialSetupTest(unittest.TestCase):
    def run_setup(self, root, live=False, existing=False, key=None, secret=None):
        script = 'setup-kis-live-credentials.sh' if live else 'setup-kis-credentials.sh'
        target = root / 'scripts' / script
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(SCRIPTS / script, target)
        key = key or 'K' * 24
        secret = secret or 'S' * 180
        inputs = (['y'] if existing else []) + [key, secret]
        if live:
            inputs += ['1' * 8, '01']
        return subprocess.run(['/bin/bash', str(target)], input='\n'.join(inputs) + '\n',
                              capture_output=True, text=True, timeout=10)

    def test_private_files_and_large_regex_bounds_work_on_system_bash(self):
        for live in [False, True]:
            with self.subTest(live=live), tempfile.TemporaryDirectory() as d:
                root = Path(d)
                result = self.run_setup(root, live)
                self.assertEqual(result.returncode, 0)
                target = root / ('.env.kis-live' if live else '.env.kis')
                self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
                self.assertNotIn('K' * 24, result.stdout + result.stderr)
                self.assertNotIn('S' * 180, result.stdout + result.stderr)
                self.assertEqual(len(list(root.glob(target.name + '.*'))), 0)

    def test_symlink_destination_is_rejected_without_changing_victim(self):
        for live in [False, True]:
            with self.subTest(live=live), tempfile.TemporaryDirectory() as d:
                root = Path(d)
                victim = root / 'victim'
                victim.write_text('keep')
                (root / ('.env.kis-live' if live else '.env.kis')).symlink_to(victim)
                self.assertNotEqual(self.run_setup(root, live).returncode, 0)
                self.assertEqual(victim.read_text(), 'keep')

    def test_old_predictable_temporary_path_is_never_followed(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            victim = root / 'victim'
            victim.write_text('keep')
            (root / '.env.kis.tmp').symlink_to(victim)
            self.assertEqual(self.run_setup(root).returncode, 0)
            self.assertEqual(victim.read_text(), 'keep')

    def test_invalid_replacement_preserves_current_file(self):
        for live in [False, True]:
            with self.subTest(live=live), tempfile.TemporaryDirectory() as d:
                root = Path(d)
                target = root / ('.env.kis-live' if live else '.env.kis')
                target.write_text('keep')
                result = self.run_setup(root, live, existing=True, secret='invalid!')
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(target.read_text(), 'keep')


if __name__ == '__main__':
    unittest.main()
