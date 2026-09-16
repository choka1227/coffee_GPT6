#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && npm ci --no-audit --no-fund && npm run build)
(cd backend && ./mvnw -B -ntp verify)
