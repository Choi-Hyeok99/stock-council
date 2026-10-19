#!/bin/bash
set -euo pipefail
umask 077
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_file="$task_root/.env.dart"
if [[ -L "$task_file" || ( -e "$task_file" && ! -f "$task_file" ) ]]; then
  printf '일반 로컬 설정 파일만 사용할 수 있습니다.\n' >&2; exit 1
fi
printf 'OpenDART 인증키를 숨겨 입력합니다. 키는 채팅에 보내지 마세요.\n'
read -r -s -p 'DART API Key: ' task_key
printf '\n'
if [[ ! "$task_key" =~ ^[A-Za-z0-9]{40}$ ]]; then
  unset task_key; printf '발급받은 40자리 키를 확인해 주세요.\n' >&2; exit 1
fi
task_tmp="$(mktemp "$task_root/.env.dart.XXXXXXXX")"
trap 'rm -f "$task_tmp"' EXIT
printf 'DART_API_KEY=%s\n' "$task_key" > "$task_tmp"
chmod 600 "$task_tmp"
mv -f "$task_tmp" "$task_file"
unset task_key
printf '권한 600의 Git 제외 설정에 저장했습니다. 수집기가 다음 주기에 읽습니다.\n'
