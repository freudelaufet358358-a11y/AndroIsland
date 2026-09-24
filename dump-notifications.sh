#!/usr/bin/env bash
# 判定の合わせ込み用。時計・電話・マップなど対象アプリの通知だけを test-data/ に書き出す
# （他のアプリの通知は個人的な内容を含むので保存しない）。
#   ./dump-notifications.sh [パッケージ名 ...]
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh
PKGS=("$@")
[ ${#PKGS[@]} -eq 0 ] && PKGS=(com.google.android.deskclock com.google.android.dialer com.google.android.apps.maps com.android.systemui)
ts=$(date +%Y%m%d-%H%M%S)
out="test-data/notifications-$ts.txt"
all=$(timeout 25 adb shell dumpsys notification --noredact)
: > "$out"
for p in "${PKGS[@]}"; do
  # NotificationRecord のブロックごとに切り出す
  printf '%s\n' "$all" | awk -v pkg="$p" '
    /^ +NotificationRecord\(/ { keep = index($0, "pkg=" pkg " ") > 0 }
    /^ +NotificationRecord\(/ || keep { if (keep) print }
  ' >> "$out"
done
echo "$out ($(wc -l < "$out") 行)"
