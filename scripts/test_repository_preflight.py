import importlib.util
from pathlib import Path
import unittest
import subprocess
import tempfile
import base64
import json
from urllib.parse import quote
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('guard',Path(__file__).with_name('preflight-repository.py'))
guard=importlib.util.module_from_spec(spec);spec.loader.exec_module(guard)
class RepositoryGuardTest(unittest.TestCase):
 def test_forced_private_paths_are_blocked(self):
  for p in ['.env.openai','build/logs/server.log','private/account.json','docs/history/record.md','docs/PAPER-RECORD.md']:
   self.assertFalse(guard.allowed(p))
 def test_known_secret_is_reported_without_value(self):
  value='synthetic-test-marker'
  result=guard.check('README.md',value.encode(),{value})
  self.assertEqual(result,[{'file':'README.md','line':1,'kind':'actual_local_secret'}])
  self.assertNotIn(value,str(result))
 def test_untracked_binary_is_blocked(self):
  self.assertEqual(guard.check('src/payload.bin',bytes([255,0]),set())[0]['kind'],'unreviewed_binary')
 def test_personal_email_is_blocked(self):
  value='person'+'@'+'example'+'.com'
  self.assertEqual(guard.check('README.md',value.encode(),set())[0]['kind'],'personal_email')
 def test_public_docs_and_source_are_allowed(self):
  self.assertTrue(guard.allowed('docs/ARCHITECTURE.md'))
  self.assertEqual(guard.check('src/main/example.java',b'class Example {}',set()),[])
 def test_secret_in_history_message_is_blocked(self):
  value='synthetic-history-marker'
  result=guard.check_text('<history commit>',('message '+value).encode(),{value},metadata=True)
  self.assertEqual(result[0]['kind'],'actual_local_secret')
  self.assertNotIn(value,str(result))
 def test_sensitive_filename_is_never_reported(self):
  value='synthetic-filename-marker'
  self.assertEqual(guard.safe_name('src/'+value+'.java',{value}),'<sensitive filename>')
 def test_staged_content_is_checked_even_when_worktree_is_clean(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d)
   subprocess.run(['git','init','-q',d],check=True)
   marker='synthetic-index-marker'
   (root/'.env.local').write_text('API_SECRET='+marker+'\n')
   (root/'README.md').write_text(marker)
   subprocess.run(['git','-C',d,'add','README.md'],check=True)
   (root/'README.md').write_text('public documentation')
   with patch.object(guard,'ROOT',root):
    result=guard.run(staged=True)
   self.assertTrue(result['blocked'])
   self.assertEqual(result['findings'][0]['kind'],'actual_local_secret')
   self.assertNotIn(marker,str(result))
 def test_reachable_history_message_is_checked(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d)
   subprocess.run(['git','init','-q',d],check=True)
   marker='synthetic-commit-marker'
   (root/'.env.local').write_text('API_SECRET='+marker+'\n')
   (root/'README.md').write_text('public documentation')
   subprocess.run(['git','-C',d,'add','README.md'],check=True)
   author='fixture'+'@'+'example'+'.invalid'
   subprocess.run(['git','-C',d,'-c','user.name=Fixture','-c','user.email='+author,'-c','core.hooksPath=/dev/null','commit','-q','-m',marker],check=True)
   with patch.object(guard,'ROOT',root):
    result=guard.run(history=True)
   self.assertTrue(result['blocked'])
   self.assertNotIn(marker,str(result))
 def test_raw_identity_files_are_scanned_without_key_value_syntax(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);salt='synthetic-identity-marker';scope='synthetic-scope-marker'
   (root/'.env.execution-salt').write_text(salt+'\n')
   (root/'.env.research-scope').write_text(scope+'\n')
   with patch.object(guard,'ROOT',root):values=guard.local_secrets()
   for value in [salt,scope]:
    result=guard.check('README.md',value.encode(),values)
    self.assertTrue(result);self.assertNotIn(value,str(result))
 def test_encoded_secret_and_complete_basic_pair_are_scanned(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);value='synthetic/encoded+marker=='
   (root/'.env.local').write_text('API_SECRET='+value+'\nAPP_LOGIN_USERNAME=fixture\nAPP_LOGIN_PASSWORD='+value+'\n')
   with patch.object(guard,'ROOT',root):values=guard.local_secrets()
   variants=[value,quote(value,safe=''),json.dumps(value)[1:-1],value.replace('/','\\/'),
             base64.b64encode(value.encode()).decode(),base64.urlsafe_b64encode(value.encode()).decode(),
             base64.b64encode(('fixture:'+value).encode()).decode()]
   for encoded in variants:
    result=guard.check('README.md',encoded.encode(),values)
    self.assertTrue(result);self.assertNotIn(encoded,str(result))
 def test_unsafe_local_secret_source_fails_closed(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'outside').write_text('synthetic')
   (root/'.env.local').symlink_to(root/'outside')
   with patch.object(guard,'ROOT',root):
    with self.assertRaises(ValueError):guard.local_secrets()
 def test_private_network_and_connection_strings_are_blocked(self):
  values=['https://'+'fixture'+'.tail'+'abc123'+'.ts.net',
          'postgresql://fixture:'+ 'synthetic-marker'+chr(64)+'localhost/db',
          'Basic '+base64.b64encode(b'fixture:synthetic-marker').decode()]
  for value in values:
   self.assertTrue(guard.check('README.md',value.encode(),set()))
 def test_database_dumps_and_credential_tool_files_are_blocked(self):
  for name in ['src/backup.sqlite','src/backup.sqlite3','src/account.tsv','src/account.tar.gz',
               'src/.netrc','src/.aws/credentials','src/.ssh/config','src/heap.hprof','src/trace.jfr',
               'src/private.p8','src/out/data.txt','src/.vscode/settings.json']:
   self.assertFalse(guard.allowed(name))
if __name__=='__main__':unittest.main()
