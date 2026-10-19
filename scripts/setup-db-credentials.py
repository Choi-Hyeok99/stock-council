#!/usr/bin/env python3
"""Create owner-private local DB credentials for a new installation; never rotates an existing database."""
from pathlib import Path
import os,secrets,sys

def main():
    os.umask(0o077);root=Path(__file__).resolve().parent.parent;target=root/'.env.db'
    if target.is_symlink():raise ValueError()
    if target.exists():
        if not target.is_file() or target.stat().st_mode&0o077:raise ValueError()
        print('기존 비공개 DB 설정을 유지합니다.');return 0
    key=secrets.token_hex(32)
    fd=os.open(target,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as out:out.write('DB_PASSWORD='+key+'\nPOSTGRES_PASSWORD='+key+'\n')
    print('권한600의 Git 제외 DB 설정 생성 완료. 기존 DB 비밀번호는 변경하지 않습니다.');return 0

if __name__=='__main__':
    try:sys.exit(main())
    except Exception:print('DB 설정 경로/권한을 확인하세요. 비밀값은 출력하지 않았습니다.');sys.exit(1)
