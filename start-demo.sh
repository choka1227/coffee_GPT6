#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
coffee_jar="runtime/coffee.jar"
if [[ ! -f "$coffee_jar" ]]; then
  coffee_jar="backend/coffee-app/target/coffee-app-0.1.0-SNAPSHOT.jar"
fi
if [[ ! -f "$coffee_jar" ]]; then
  echo '請先執行 ./scripts/build.sh 建置專案。' >&2
  exit 1
fi
exec java -jar "$coffee_jar" --spring.profiles.active=dev --server.address=127.0.0.1
