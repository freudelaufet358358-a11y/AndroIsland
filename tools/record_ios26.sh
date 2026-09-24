#!/usr/bin/env bash
# 実機で iOS 26 の見本（test-data/reference-ios26-short.csv）と同じ 5 つの遷移を起こして録画し、遷移ごとに比べる。
#
#   tools/record_ios26.sh [出力フォルダ]
#
# 端末の設定は変えない（待機時の島・自動の寸法は受け口で一時的に切り替え、終わったら戻す）。
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env.sh
PKG=dev.ryunosuke.island
RCV=$PKG/.debug.ShellCommandReceiver
OUT=${1:-build/ios26-compare}
mkdir -p "$OUT"
state=$(timeout 10 adb get-state 2>&1 | tr -d '\r' || true)
if [ "$state" != "device" ]; then echo "端末が見つかりません（state=$state）" >&2; exit 2; fi
cmd() { timeout 5 adb shell am broadcast -n $RCV --es cmd "\"$1\"" >/dev/null; }

cmd "visible on"
timeout 10 adb shell am start -n $PKG/.debug.LightBackgroundActivity --ez bars false >/dev/null
sleep 1.5
cmd "autosize on"
cmd "idle on"
cmd "refonly on"
sleep 1.0
timeout 5 adb shell rm -f /sdcard/island-ios26.mp4
timeout 25 adb shell screenrecord --size ${REC_SIZE:-720x1600} --bit-rate ${REC_BITRATE:-20000000} --time-limit 12 /sdcard/island-ios26.mp4 &
REC=$!
sleep 1.2
cmd refseq26
wait $REC || true
sleep 0.5
timeout 30 adb pull /sdcard/island-ios26.mp4 "$OUT/recording.mp4" >/dev/null
timeout 5 adb shell rm -f /sdcard/island-ios26.mp4
cmd "refonly off"
cmd "idle off"
cmd "autosize off"
cmd "visible off"
timeout 5 adb shell input keyevent BACK
python3 tools/compare_ios26.py "$OUT/recording.mp4" test-data/reference-ios26-short.csv "$OUT"
