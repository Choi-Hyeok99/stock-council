#!/usr/bin/env python3
"""Gracefully stop this research JVM and make a private DB/identity backup. No provider calls."""
import base64,json,os,shutil,signal,subprocess,sys,time
from pathlib import Path
from datetime import datetime,timezone
from urllib.request import Request,urlopen
ROOT=Path(__file__).resolve().parent.parent

def main():
 os.chdir(ROOT);os.umask(0o077)
 def sql(text):
  r=subprocess.run(['docker','compose','exec','-T','postgres','psql','-U','papertrader','-d','papertrader','-Atc',text],capture_output=True,text=True)
  if r.returncode:raise RuntimeError()
  return r.stdout.strip()
 cfg=dict(x.split('=',1)for x in (ROOT/'.env.login').read_text().splitlines()if '='in x and not x.startswith('#'))
 auth='Basic '+base64.b64encode((cfg.get('APP_LOGIN_USERNAME','user')+':'+cfg['APP_LOGIN_PASSWORD']).encode()).decode()
 with urlopen(Request('http://127.0.0.1:8090/api/research-status',headers={'Authorization':auth}),timeout=20)as r:v=json.load(r)
 if v.get('automaticTradingEnabled',False):raise RuntimeError()
 if sql("SELECT COUNT(*) FROM execution_order WHERE status IN ('CREATED','SUBMITTING','SUBMITTED','PARTIALLY_FILLED','CANCEL_PENDING','UNKNOWN')")!='0':
  print('미확정·미체결 주문이 있어 종료하지 않았습니다. 주문 상태를 먼저 확인하세요.');return 1
 if sql("SELECT COUNT(*) FROM committee_ai_call WHERE state IN ('RESERVED','SENT')")!='0' or sql("SELECT COUNT(*) FROM committee_ai_job WHERE state IN ('QUEUED','RUNNING','FINALIZING')")!='0':
  print('진행 중 AI 검토가 있어 종료하지 않았습니다. 완료/보류 확인 뒤 다시 실행하세요.');return 1
 if sql("SELECT COUNT(*) FROM discord_report_delivery WHERE state='SENDING'")!='0':
  print('Discord 전송 중이라 종료하지 않았습니다. 발송 결과 확인 뒤 다시 실행하세요.');return 1
 listeners=subprocess.run(['lsof','-nP','-iTCP:8090','-sTCP:LISTEN','-t'],capture_output=True,text=True).stdout.split()
 if len(set(listeners))!=1:raise RuntimeError()
 pid=int(listeners[0]);command=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True).stdout
 if 'capital-preservation-trader.jar'not in command or 'build/runtime/session-'not in command:raise RuntimeError()
 cwd=subprocess.run(['lsof','-a','-p',str(pid),'-d','cwd','-Fn'],capture_output=True,text=True).stdout
 if 'n'+str(ROOT)not in cwd.splitlines():raise RuntimeError()
 backup=ROOT/'build/backups'/('research-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'));backup.mkdir(mode=0o700)
 hashes=sql('SELECT id,md5(decision_explanation::text) FROM execution_order ORDER BY id')
 os.kill(pid,signal.SIGTERM)
 for _ in range(60):
  if not subprocess.run(['lsof','-nP','-iTCP:8090','-sTCP:LISTEN','-t'],capture_output=True,text=True).stdout.strip():break
  time.sleep(.5)
 else:raise RuntimeError()
 with (backup/'database.dump').open('wb')as output:
  r=subprocess.run(['docker','compose','exec','-T','postgres','pg_dump','-U','papertrader','-d','papertrader','-Fc'],stdout=output,stderr=subprocess.PIPE)
  if r.returncode:raise RuntimeError()
 for name in ['.env.execution-salt','.env.research-scope']:
  p=ROOT/name
  if p.is_file()and not p.is_symlink():shutil.copyfile(p,backup/name);(backup/name).chmod(0o600)
 (backup/'hashes.txt').write_text(hashes+'\n');(backup/'hashes.txt').chmod(0o600)
 pointer=ROOT/'build/runtime/research-backup.txt';pointer.write_text(str(backup));pointer.chmod(0o600)
 print('정상 종료와 비공개 DB·연구 식별 백업을 완료했습니다. 다시 시작: python3 scripts/run-background.py');return 0
if __name__=='__main__':
 try:sys.exit(main())
 except Exception:print('정상 종료/백업 확인 필요 · 자동 재시작하지 않았고 비밀값/원문 오류는 표시하지 않았습니다.');sys.exit(1)
