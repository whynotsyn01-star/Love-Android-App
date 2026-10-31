#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.10.2 is required. Install Gradle, then run this script."
  exit 1
fi
gradle assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
