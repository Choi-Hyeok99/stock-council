#!/bin/zsh
set -euo pipefail
umask 077
task_project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$task_project_root"
python3 scripts/check-local-dependencies.py
task_java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
JAVA_HOME="$task_java_home" ./gradlew bootJar --console=plain
mkdir -p build/runtime
task_runtime_dir="$(mktemp -d build/runtime/session-XXXXXXXX)"
task_runtime_jar="$task_runtime_dir/capital-preservation-trader.jar"
cp build/libs/capital-preservation-trader-0.1.0-SNAPSHOT.jar "$task_runtime_jar"
# Future builds update build/libs only, preserving lazily loaded classes of this running JVM.
task_keep_awake="${TRADER_KEEP_AWAKE:-true}"
if [[ "$task_keep_awake" != true && "$task_keep_awake" != false ]]; then
  print -u2 'TRADER_KEEP_AWAKE는 true 또는 false로 설정하세요.'
  exit 1
fi
if [[ "$task_keep_awake" == true && "$(uname -s)" == Darwin ]]; then
  # Keep the system awake only while this JVM runs. The screen can turn off and lock.
  # Manual sleep, closing the lid, loss of power/network and reboot still stop the app.
  exec /usr/bin/caffeinate -i "$task_java_home/bin/java" -Dtrader.keepAwakeRequested=true -jar "$task_runtime_jar"
fi
exec "$task_java_home/bin/java" -Dtrader.keepAwakeRequested=false -jar "$task_runtime_jar"
