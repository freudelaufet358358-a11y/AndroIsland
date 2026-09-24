#!/usr/bin/env bash
# 実機で Apple の見本（WWDC23 10194、670 秒から）と同じ順番・同じ間隔で島を動かして録画し、見本と 1 フレームずつ比べる。
#
#   tools/record_reference.sh [出力フォルダ]
#   REF_FRAMES=<見本のフレーム画像フォルダ> を付けると、同じ時刻のコマを並べた frames.png も作る
#
# 端末の設定は変えない（待機時の島は受け口の「idle on」で一時的に出し、終わったら戻す）。
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env.sh
PKG=dev.ryunosuke.island
RCV=$PKG/.debug.ShellCommandReceiver
OUT=${1:-build/reference-compare}
mkdir -p "$OUT"
state=$(timeout 10 adb get-state 2>&1 | tr -d '\r' || true)
if [ "$state" != "device" ]; then echo "端末が見つかりません（state=$state）" >&2; exit 2; fi

# ステータスバーを隠した明るい画面で測る（島に接する通知アイコンで縁がずれないように）
timeout 5 adb shell am broadcast -n $RCV --es cmd "\"visible on\"" >/dev/null
timeout 10 adb shell am start -n $PKG/.debug.LightBackgroundActivity --ez bars false >/dev/null
sleep 1.5
timeout 5 adb shell am broadcast -n $RCV --es cmd "\"autosize on\"" >/dev/null
timeout 5 adb shell am broadcast -n $RCV --es cmd "\"idle on\"" >/dev/null
sleep 1.0
timeout 5 adb shell rm -f /sdcard/island-ref.mp4
timeout 25 adb shell screenrecord --size ${REC_SIZE:-720x1600} --bit-rate ${REC_BITRATE:-20000000} --time-limit 12 /sdcard/island-ref.mp4 &
REC=$!
sleep 1.2
timeout 5 adb shell am broadcast -n $RCV --es cmd refseq >/dev/null
wait $REC || true
sleep 0.5
timeout 30 adb pull /sdcard/island-ref.mp4 "$OUT/recording.mp4" >/dev/null
timeout 5 adb shell rm -f /sdcard/island-ref.mp4
timeout 5 adb shell am broadcast -n $RCV --es cmd "\"idle off\"" >/dev/null
timeout 5 adb shell am broadcast -n $RCV --es cmd "\"autosize off\"" >/dev/null
timeout 5 adb shell am broadcast -n $RCV --es cmd "\"visible off\"" >/dev/null
timeout 5 adb shell input keyevent BACK
python3 tools/compare_reference.py "$OUT/recording.mp4" test-data/reference-wwdc23-10194.csv "$OUT" ${REF_FRAMES:-}
