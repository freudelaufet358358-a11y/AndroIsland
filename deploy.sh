#!/usr/bin/env bash
# ビルドして端末に入れ、設定画面を開く。
# 端末側のセキュリティ設定（アクセシビリティ・通知へのアクセス）はここでは触らない。初回だけ端末で許可する。
#
#   ./deploy.sh           リリース版（軽い。普段使い）
#   ./deploy.sh debug     デバッグ版
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh
VARIANT=${1:-release}
TASK="assemble${VARIANT^}"
./gradlew ":app:$TASK" --console=plain -q
APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
state=$(timeout 10 adb get-state 2>&1 | tr -d '\r' || true)
if [ "$state" != "device" ]; then
  echo "端末が見つかりません（state=$state）。USB デバッグかワイヤレスデバッグでつないでください。" >&2
  exit 2
fi
timeout 120 adb install -r "$APK"
timeout 10 adb shell am start -n dev.ryunosuke.island/.ui.app.MainActivity >/dev/null
echo "インストールしました: $APK"
