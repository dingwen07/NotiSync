#!/usr/bin/env bash
# Run the NotiSync broker locally for development.
#
# FCM: if you want real push, first run `gcloud auth application-default login` (or export
# GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json) and set NOTISYNC_FCM_ENABLED=true.
# Otherwise FCM auto-disables and the WebSocket transport carries everything.
set -euo pipefail
cd "$(dirname "$0")/.."

export NOTISYNC_DB_PATH="${NOTISYNC_DB_PATH:-data/notisync.db}"
export NOTISYNC_FCM_ENABLED="${NOTISYNC_FCM_ENABLED:-false}"
export NOTISYNC_FCM_PROJECT_ID="${NOTISYNC_FCM_PROJECT_ID:-extrawdw-notifly}"

echo "Starting NotiSync broker on :8080 (db=$NOTISYNC_DB_PATH, fcm=$NOTISYNC_FCM_ENABLED)"
exec ./gradlew :server:run --console=plain
