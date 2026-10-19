#!/bin/bash
set -euo pipefail
umask 077
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_file="$task_root/.env.openai"
if [[ -L "$task_file" || ( -e "$task_file" && ! -f "$task_file" ) ]]; then
  printf '일반 로컬 설정 파일만 사용할 수 있습니다.\n' >&2; exit 1
fi
printf '프로젝트 Limits에서 월 $10 + Enforce a hard limit을 켜 주세요.\n'
printf '저장은 연결 설정만 준비합니다. 대화방에서 직접 확인한 분석만 시작하며 AI 자동 주문은 없습니다.\n'
read -r -p '프로젝트 월 $10 hard limit을 설정했나요? (yes): ' task_confirmed
if [[ "$task_confirmed" != yes ]]; then printf '한도 설정 후 다시 실행해 주세요. 저장하지 않았습니다.\n'; exit 1; fi
IFS= read -r -s -p 'OpenAI API Key (숨겨 입력): ' task_key
printf '\n'
# macOS Bash 3.2 uses a regex engine that rejects the {16,500} bound.
# Check length separately; keep the same accepted length and ASCII characters.
task_key="${task_key#"${task_key%%[!$' \t\r']*}"}"
task_key="${task_key%"${task_key##*[!$' \t\r']}"}"
if [[ ${#task_key} -lt 19 || ${#task_key} -gt 503 || ! "$task_key" =~ ^sk-[A-Za-z0-9_-]+$ ]]; then
  unset task_key; printf '키가 비어 있거나 전체 키 형식이 아니에요. 발급 창의 Copy로 다시 복사해 붙여넣어 주세요. 키는 표시하지 않았고 저장하지 않았습니다.\n' >&2; exit 1
fi
task_tmp="$(mktemp "$task_root/.env.openai.XXXXXXXX")"
trap 'rm -f "$task_tmp"' EXIT
printf 'OPENAI_API_KEY=%s\nOPENAI_COMMITTEE_ENABLED=true\nOPENAI_PROJECT_HARD_LIMIT_CONFIRMED=true\n' "$task_key" > "$task_tmp"
chmod 600 "$task_tmp"
mv -f "$task_tmp" "$task_file"
unset task_key
printf '권한600의 Git 제외 파일에 저장했습니다. 연결 기능 반영 후 다음 주기에 읽습니다.\n'
