#!/usr/bin/env python3
"""Owner enters a local password without echo; existing login is never overwritten."""
from pathlib import Path
import os,sys,getpass,re
def main():
    os.umask(0o077);p=Path(__file__).resolve().parent.parent/'.env.login'
    if p.is_symlink():raise ValueError()
    if p.exists():
        if not p.is_file() or p.stat().st_mode&0o077:raise ValueError()
        print('기존 로컬 로그인을 유지합니다.');return 0
    if not sys.stdin.isatty():raise ValueError()
    value=getpass.getpass('로컬 비밀번호 (숨김, 12자 이상): ')
    if len(value)<12 or len(value.encode())>72 or not re.fullmatch(r'[A-Za-z0-9_.!@?+#-]+',value):
        print('12~72바이트의 영문·숫자·_.!@?+#-를 사용하세요.');return 1
    if value!=getpass.getpass('비밀번호 다시 입력 (숨김): '):
        print('두 입력이 다릅니다. 저장하지 않았습니다.');return 1
    fd=os.open(p,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as out:out.write('APP_LOGIN_USERNAME=user\nAPP_LOGIN_PASSWORD='+value+'\n')
    print('권한600의 Git 제외 로그인 파일 저장 완료. 사용자 이름은 user입니다.');return 0
if __name__=='__main__':
    try:sys.exit(main())
    except Exception:print('터미널·설정 경로·권한을 확인하세요. 비밀번호는 출력하지 않았습니다.');sys.exit(1)
