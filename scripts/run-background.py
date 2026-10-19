#!/usr/bin/env python3
"""Start the existing local runner independently of a terminal/chat session.
Runs research and owner-confirmed paper sales. Automatic trading is disabled.
"""
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

def main():
    root=Path(__file__).resolve().parent.parent
    os.umask(0o077)
    probe=subprocess.run(['lsof','-nP','-iTCP:8090','-sTCP:LISTEN','-t'],capture_output=True,text=True)
    if probe.stdout.strip():
        print('8090 서버가 이미 실행 중입니다. 중복 실행하지 않았습니다.')
        return 1
    dependency=subprocess.run([sys.executable,str(root/'scripts/check-local-dependencies.py')],cwd=root)
    if dependency.returncode:return 1
    record=root/'build/runtime/background.json'
    if record.is_file() and not record.is_symlink():
        try:
            pid=int(json.loads(record.read_text())['pid'])
            command=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True).stdout
            if str(root/'scripts/run-local.sh') in command or 'capital-preservation-trader.jar' in command:
                print('기존 백그라운드 서버가 시작/종료 중입니다. 중복 실행하지 않았습니다.')
                return 1
        except (ValueError,KeyError,json.JSONDecodeError): pass
    logs=root/'build/logs';logs.mkdir(parents=True,exist_ok=True,mode=0o700)
    log=logs/'server-background.log'
    if log.is_symlink() or record.is_symlink():
        print('실행 상태 파일 경로를 확인하세요. 서버를 시작하지 않았습니다.')
        return 1
    with log.open('ab') as output:
        process=subprocess.Popen([str(root/'scripts/run-local.sh')],cwd=root,stdin=subprocess.DEVNULL,
            stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
    record.parent.mkdir(parents=True,exist_ok=True)
    record.write_text(json.dumps({'pid':process.pid,'startedAt':datetime.now(timezone.utc).isoformat(),'log':str(log)})+'\n')
    record.chmod(0o600);log.chmod(0o600)
    print('백그라운드 서버 시작 요청을 보냈습니다. /overview에서 연구·수집 상태를 확인하세요.')
    print('맥 종료·수동 시스템 잠자기·재부팅 자동 복구는 별도입니다.')
    return 0

if __name__=='__main__':
    try: sys.exit(main())
    except Exception:
        print('백그라운드 시작 실패 · 비밀값/원문 오류 미출력')
        sys.exit(1)
