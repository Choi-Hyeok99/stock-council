#!/bin/bash
set -euo pipefail
umask 077

project_root="$(cd "$(dirname "$0")/.." && pwd)"
credential_file="$project_root/.env.kis-live"
if [[ -L "$credential_file" || ( -e "$credential_file" && ! -f "$credential_file" ) ]]; then
  printf '일반 로컬 설정 파일만 사용할 수 있습니다.\n' >&2
  exit 1
fi
if [[ -f "$credential_file" ]]; then
  read -r -p '기존 실전 API 설정을 교체할까요? [y/N] ' replace_existing
  [[ "$replace_existing" == y || "$replace_existing" == Y ]] || exit 0
fi
printf '실전 전용 App Key/Secret과 계좌를 입력합니다. 저장만 하며 실전 매매를 켜지 않습니다.\n'
read -r -s -p 'LIVE App Key: ' live_app_key; printf '\n'
read -r -s -p 'LIVE App Secret: ' live_app_secret; printf '\n'
read -r -s -p '실전 계좌 앞 8자리: ' live_account_number; printf '\n'
read -r -s -p '계좌 상품코드 뒤 2자리: ' live_account_product; printf '\n'
if [[ ${#live_app_key} -lt 16 || ${#live_app_key} -gt 512 || ${#live_app_secret} -lt 16 || ${#live_app_secret} -gt 1024 \
      || ! "$live_app_key" =~ ^[A-Za-z0-9_+/=-]+$ || ! "$live_app_secret" =~ ^[A-Za-z0-9_+/=-]+$ \
      || ! "$live_account_number" =~ ^[0-9]{8}$ || ! "$live_account_product" =~ ^[0-9]{2}$ ]]; then
  printf '입력 형식을 확인해 주세요. 설정을 저장하지 않았습니다.\n' >&2
  exit 1
fi
temporary_file="$(mktemp "$project_root/.env.kis-live.XXXXXXXX")"
trap 'rm -f "$temporary_file"' EXIT
{
  printf 'KIS_LIVE_APP_KEY=%s\n' "$live_app_key"
  printf 'KIS_LIVE_APP_SECRET=%s\n' "$live_app_secret"
  printf 'KIS_LIVE_ACCOUNT_NUMBER=%s\n' "$live_account_number"
  printf 'KIS_LIVE_ACCOUNT_PRODUCT=%s\n' "$live_account_product"
} > "$temporary_file"
chmod 600 "$temporary_file"
mv -f "$temporary_file" "$credential_file"
unset live_app_key live_app_secret live_account_number live_account_product
printf '권한 600의 로컬 실전 전용 파일에 저장했습니다. 실전 운용은 기본 차단 상태이며 앱 재시작 후 설정을 읽습니다.\n'
