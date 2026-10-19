#!/bin/bash
set -euo pipefail
umask 077

project_root="$(cd "$(dirname "$0")/.." && pwd)"
credential_file="$project_root/.env.kis"
if [[ -L "$credential_file" || ( -e "$credential_file" && ! -f "$credential_file" ) ]]; then
  printf '일반 로컬 설정 파일만 사용할 수 있습니다.\n' >&2
  exit 1
fi

if [[ -f "$credential_file" ]]; then
  printf '기존 로컬 KIS 설정을 새 값으로 바꿀까요? [y/N] '
  read -r replace_existing
  if [[ "$replace_existing" != "y" && "$replace_existing" != "Y" ]]; then
    printf '변경하지 않았습니다.\n'
    exit 0
  fi
fi

printf 'KIS Developers에서 App Key를 복사한 뒤 붙여넣고 Enter를 누르세요. 입력값은 화면에 표시되지 않습니다.\n'
read -r -s -p 'App Key: ' app_key
printf '\n'
printf '이제 App Secret 복사 버튼을 눌러 붙여넣고 Enter를 누르세요. 입력값은 화면에 표시되지 않습니다.\n'
read -r -s -p 'App Secret: ' app_secret
printf '\n'

if [[ ${#app_key} -lt 16 || ${#app_key} -gt 512 || ${#app_secret} -lt 16 || ${#app_secret} -gt 1024 \
      || ! "$app_key" =~ ^[A-Za-z0-9_+/=-]+$ || ! "$app_secret" =~ ^[A-Za-z0-9_+/=-]+$ ]]; then
  unset app_key app_secret
  printf '발급받은 키·Secret 전체 형식을 확인해 주세요. 저장하지 않았습니다.\n' >&2
  exit 1
fi

temporary_file="$(mktemp "$project_root/.env.kis.XXXXXXXX")"
trap 'rm -f "$temporary_file"' EXIT
{
  printf 'KIS_APP_KEY=%s\n' "$app_key"
  printf 'KIS_APP_SECRET=%s\n' "$app_secret"
} > "$temporary_file"
chmod 600 "$temporary_file"
mv -f "$temporary_file" "$credential_file"
unset app_key app_secret

printf '완료: 키는 이 맥의 프로젝트 내부 .env.kis에만 저장했습니다. 값은 출력하거나 Git에 추가하지 않았습니다.\n'
