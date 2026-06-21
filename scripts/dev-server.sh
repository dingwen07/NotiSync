#!/usr/bin/env bash
# Run the NotiSync broker locally for development.
#
# Play Integrity and FCM use Google ADC. For local app-to-server debug, run
# `gcloud auth application-default login` or export GOOGLE_APPLICATION_CREDENTIALS. For protocol-only
# local testing, set NOTISYNC_PLAY_INTEGRITY_ENABLED=false (the master switch; turns off auth too).
# FCM still stays disabled unless NOTISYNC_FCM_ENABLED=true.
set -euo pipefail
cd "$(dirname "$0")/.."

export NOTISYNC_DB_PATH="${NOTISYNC_DB_PATH:-data/notisync.db}"
export NOTISYNC_FCM_ENABLED="${NOTISYNC_FCM_ENABLED:-false}"
export NOTISYNC_FCM_PROJECT_ID="${NOTISYNC_FCM_PROJECT_ID:-extrawdw-notifly}"
export NOTISYNC_PLAY_INTEGRITY_PACKAGE="${NOTISYNC_PLAY_INTEGRITY_PACKAGE:-net.extrawdw.apps.notisync}"

echo "Starting NotiSync broker on :8080 (db=$NOTISYNC_DB_PATH, fcm=$NOTISYNC_FCM_ENABLED, playIntegrity=${NOTISYNC_PLAY_INTEGRITY_ENABLED:-true})"
exec ./gradlew :server:run --console=plain
