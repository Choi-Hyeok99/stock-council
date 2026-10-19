import importlib.util,subprocess,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('dependencies',Path(__file__).with_name('check-local-dependencies.py'))
dependencies=importlib.util.module_from_spec(spec);spec.loader.exec_module(dependencies)
class LocalDependenciesTest(unittest.TestCase):
    def test_stopped_docker_is_reported_before_database_probe(self):
        with patch.object(dependencies.subprocess,'run',return_value=subprocess.CompletedProcess([],1))as run:
            self.assertIn('Docker Desktop',dependencies.check());self.assertEqual(run.call_count,1)
    def test_unready_database_has_actionable_local_command(self):
        with patch.object(dependencies.subprocess,'run',side_effect=[subprocess.CompletedProcess([],0),subprocess.CompletedProcess([],1)]):
            self.assertIn('docker compose up -d postgres',dependencies.check())
    def test_success_and_timeout_never_read_credentials(self):
        with patch.object(dependencies.subprocess,'run',return_value=subprocess.CompletedProcess([],0)):self.assertIsNone(dependencies.check())
        with patch.object(dependencies.subprocess,'run',side_effect=subprocess.TimeoutExpired('docker',8)):self.assertIn('응답',dependencies.check())
