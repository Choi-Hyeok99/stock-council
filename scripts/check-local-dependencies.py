#!/usr/bin/env python3
"""Check existing local dependencies without reading credentials or changing Docker state."""
import subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
def check():
    try:
        daemon=subprocess.run(['docker','info','--format','{{.ServerVersion}}'],cwd=ROOT,capture_output=True,timeout=8)
        if daemon.returncode:return 'Docker Desktop이 실행되지 않았습니다. Docker를 켠 뒤 다시 시작해 주세요.'
        database=subprocess.run(['docker','compose','exec','-T','postgres','pg_isready','-U','papertrader','-d','papertrader'],cwd=ROOT,capture_output=True,timeout=8)
        if database.returncode:return '로컬 DB가 준비되지 않았습니다. 프로젝트에서 docker compose up -d postgres 실행 후 다시 시작해 주세요.'
        return None
    except (OSError,subprocess.TimeoutExpired):return 'Docker·DB 응답을 확인하지 못했습니다. Docker Desktop 실행 상태를 확인해 주세요.'
def main():
    issue=check()
    if issue:print(issue);return 1
    return 0
if __name__=='__main__':sys.exit(main())
