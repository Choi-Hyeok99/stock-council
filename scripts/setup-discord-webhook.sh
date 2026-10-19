#!/bin/bash
set -euo pipefail
umask 077

task_project_root="$(cd "$(dirname "$0")/.." && pwd)"
task_config_file="$task_project_root/.env.discord"
if [[ -L "$task_config_file" || ( -e "$task_config_file" && ! -f "$task_config_file" ) ]]; then
  printf '일반 로컬 설정 파일에만 저장할 수 있습니다.\n' >&2
  exit 1
fi
if [[ -f "$task_config_file" ]]; then
  read -r -p '기존 Discord 연결 주소를 교체할까요? [y/N] ' task_replace
  [[ "$task_replace" == y || "$task_replace" == Y ]] || exit 0
fi
printf 'Discord에서 복사한 웹훅 주소를 입력합니다. 입력값은 화면에 보이지 않습니다.\n'
read -r -s -p 'Discord Webhook URL: ' task_webhook_url
printf '\n'
if [[ ! "$task_webhook_url" =~ ^https://discord\.com/api/webhooks/[0-9]{17,20}/[A-Za-z0-9_-]{40,200}$ ]]; then
  unset task_webhook_url
  printf 'discord.com의 텍스트 채널 웹훅 주소를 확인해 주세요. 저장하지 않았습니다.\n' >&2
  exit 1
fi
task_temporary_file="$(mktemp "$task_project_root/.env.discord.XXXXXXXX")"
trap 'rm -f "$task_temporary_file"' EXIT
printf 'DISCORD_WEBHOOK_URL=%s\n' "$task_webhook_url" > "$task_temporary_file"
chmod 600 "$task_temporary_file"
mv -f "$task_temporary_file" "$task_config_file"
unset task_webhook_url
printf '권한 600의 Git 제외 로컬 파일에 저장했습니다. 주소는 출력하지 않았습니다.\n'
printf '이 도구는 설정만 저장합니다. Discord에 메시지를 보내거나 매매를 실행하지 않습니다.\n'
