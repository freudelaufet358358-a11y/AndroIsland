#!/usr/bin/env bash
# 実機での確認。adb で自動化できる範囲を一括で流す（vocab-android の同名スクリプトと同じ流儀）。
#
# 重要: adb の呼び出しは必ず timeout で包み、各段階の前に端末の生存を確かめる。
# 端末を見失った時点で UNKNOWN として中断する（logcat が空 = クラッシュ 0 件 と区別できないため）。
#
# 端末の設定は変えない。充電だけは dumpsys battery の試験用フックで模擬し、最後に必ず元に戻す。
set -u
cd "$(dirname "$0")"
source ./env.sh

PKG=dev.ryunosuke.island
RCV=$PKG/.debug.ShellCommandReceiver
OUT=build/device-check
ADB_TIMEOUT=25
APK=${APK:-app/build/outputs/apk/release/app-release.apk}
mkdir -p "$OUT"

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAILED=$((FAILED+1)); }
info() { printf '  · %s\n' "$1"; }
FAILED=0

A() { timeout "$ADB_TIMEOUT" adb "$@" 2>&1; }

require_device() {
  local state
  state=$(timeout 10 adb get-state 2>&1 | tr -d '\r')
  if [ "$state" != "device" ]; then
    echo
    echo "端末を見失いました（state=$state）。ここまでの結果のみ有効で、以降は未確認です。"
    exit 2
  fi
}

shot() {
  timeout "$ADB_TIMEOUT" adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null
  if [ -s "$OUT/$1.png" ]; then info "screenshot: $OUT/$1.png"; else rm -f "$OUT/$1.png"; fail "スクリーンショットを撮れなかった ($1)"; fi
}

# 島を操作する（DUMP 権限で守られた受け口。adb shell からだけ送れる）
cmd() { A shell am broadcast -n "$RCV" --es cmd "\"$*\"" >/dev/null; }

# 島の今の状態を 1 行で
state() {
  A logcat -c >/dev/null
  cmd state
  sleep 0.4
  A logcat -d -s IslandShell:I | grep -o 'state=.*' | tail -1
}

expect() {
  local s
  s=$(state)
  if [[ "$s" == *"$1"* ]]; then pass "$2"; else fail "$2 — $s"; fi
}

# state の region= から、最初の矩形（島の本体）を取り出す
region() { state | sed -n 's/.*region=\([0-9-]*\),\([0-9-]*\),\([0-9-]*\),\([0-9-]*\).*/\1 \2 \3 \4/p'; }

dp() { echo $(( $1 * DENSITY / 160 )); }

echo "== 端末 =="
require_device
info "$(A shell getprop ro.product.model | tr -d '\r') / Android $(A shell getprop ro.build.version.release | tr -d '\r') / $(A shell getprop ro.modversion | tr -d '\r')"
DENSITY=$(A shell wm density | tr -d '\r' | grep -o '[0-9]*$')
SIZE=$(A shell wm size | tr -d '\r' | grep -o '[0-9]*x[0-9]*$')
W=${SIZE%x*}; H=${SIZE#*x}
info "画面 ${W}x${H} / ${DENSITY}dpi"

echo "== インストール =="
require_device
if [ -f "$APK" ]; then
  out=$(timeout 120 adb install -r "$APK" 2>&1)
  if [[ "$out" == *Success* ]]; then pass "インストール ($APK)"; else fail "インストール: $out"; exit 1; fi
else
  fail "$APK がありません（./gradlew :app:assembleRelease を先に）"; exit 1
fi

echo "== 権限 =="
require_device
a11y=$(A shell settings get secure enabled_accessibility_services | tr -d '\r')
listeners=$(A shell settings get secure enabled_notification_listeners | tr -d '\r')
if [[ "$a11y" == *"$PKG"* ]]; then pass "アクセシビリティが有効"; else fail "アクセシビリティが無効（端末の Island アプリから許可してください）"; fi
if [[ "$listeners" == *"$PKG"* ]]; then pass "通知へのアクセスが有効"; else fail "通知へのアクセスが無効（端末の Island アプリから許可してください）"; fi
if [ "$FAILED" -gt 0 ]; then echo "権限がないと以降を確かめられないので中断します"; exit 1; fi

A shell am start -n $PKG/.ui.app.MainActivity >/dev/null
sleep 1.5
# 端末で動いている本物の活動（ストップウォッチ・音楽など）は隠し、ここで出すものだけで確かめる。終わったら必ず戻す
cmd refonly on
trap 'cmd refonly off; cmd idle off' EXIT
A logcat -c >/dev/null
if A shell dumpsys window windows | grep -q "Island"; then pass "島の窓がある"; else fail "島の窓が見つからない（アクセシビリティを一度オフ→オンにしてみてください）"; fi

echo "== 触れる範囲（島の外は素通し） =="
require_device
cmd clear; A shell am broadcast -n "$RCV" --es cmd "\"media stop\"" >/dev/null
sleep 1
r=$(state)
if [[ "$r" == *"region=-"* ]]; then pass "何も出ていないときは全部素通し"; else fail "何も出ていないのに触れる範囲がある — $r"; fi
A shell dumpsys window windows | grep -iE "touchable|Island" | grep -iA2 "Island" | head -6 | sed 's/^/      /'

echo "== 待機時の島（長押しで通知シェードが開かない・下へ引くと開く） =="
require_device
focus() { A shell dumpsys window | grep -m1 -o "mCurrentFocus=.*"; }
cmd idle on; sleep 1
read -r L T R B <<<"$(region)"
if [ -n "${L:-}" ]; then
  pass "待機時の島も触れる ($L,$T)-($R,$B)"
  cx=$(( (L + R) / 2 )); cy=$(( (T + B) / 2 ))
  A shell input swipe $cx $cy $cx $cy 1200 >/dev/null
  sleep 1
  if [[ "$(focus)" != *NotificationShade* ]]; then pass "島を長押ししても通知シェードは開かない"; else fail "長押しで通知シェードが開いた"; A shell cmd statusbar collapse >/dev/null; sleep 1; fi
  A shell input swipe $cx $cy $cx $(( cy + $(dp 300) )) 300 >/dev/null
  sleep 1.2
  if [[ "$(focus)" == *NotificationShade* ]]; then pass "島から下へ引くと通知シェードが開く"; else fail "下へ引いても通知シェードが開かない — $(focus)"; fi
  A shell cmd statusbar collapse >/dev/null
  sleep 1.5
else
  fail "待機時の島が触れない（長押しがステータスバーに届いて通知シェードが開いてしまう）"
fi
cmd idle off; sleep 0.6

echo "== デモ（見た目） =="
for t in Media IncomingCall OngoingCall Timer Stopwatch Alarm Navigation Download Recording; do
  require_device
  cmd clear; sleep 0.6
  cmd demo "$t"; sleep 1.2
  expect "primary=" "デモ $t が主役になる"
  shot "demo-$t"
done
cmd clear; sleep 0.6
cmd demo Timer; cmd demo Media; sleep 1.2
expect "secondary=" "2 つ同時で右に丸が出る"
shot "demo-split"

echo "== スワイプでしまう・戻す =="
require_device
cmd clear; sleep 0.6
cmd demo Timer; cmd demo Media; sleep 1.2
read -r L T R B <<<"$(region)"
cy=$(( (T + B) / 2 ))
A shell input swipe $(( L + (R - L) / 4 )) $cy $(( L + (R - L) * 3 / 4 )) $cy 150 >/dev/null; sleep 1
expect "hidden=1" "左右スワイプで主の活動をしまう"
read -r L T R B <<<"$(region)"
A shell input swipe $(( L + (R - L) / 4 )) $cy $(( L + (R - L) * 3 / 4 )) $cy 150 >/dev/null; sleep 1
s=$(state)
if [[ "$s" == *"primary=- "* && "$s" == *"hidden=2"* && "$s" != *"region=-"* ]]; then pass "全部しまっても島は残る（待機時の島を消す設定でも）"; else fail "全部しまうと島が消える — $s"; fi
A shell input tap $(( W / 2 )) $cy >/dev/null; sleep 1.2
expect "hidden=0" "残った島をタップすると戻る"
cmd clear; sleep 1
expect "region=-" "しまったものが無くなると島も消える"

echo "== 一時表示 =="
for a in charging battery silent dnd device pods unlock; do
  require_device
  cmd clear; sleep 0.5
  cmd alert "$a"; sleep 0.5
  # ロック解除は 1.3 秒で引っ込むので、状態を聞く前に撮る
  shot "alert-$a"
  expect "alert=" "一時表示 $a"
  if [ "$a" = battery ]; then
    # iOS 26 と同じく展開した形（高さ 2.8H）で出る
    read -r L T R B <<<"$(region)"
    if [ -n "${L:-}" ] && [ $(( B - T )) -gt $(dp 80) ]; then pass "電池残量低下は展開した形 ($L,$T)-($R,$B)"; else fail "電池残量低下が展開した形になっていない"; fi
  fi
  if [ "$a" = pods ]; then
    read -r L T R B <<<"$(region)"
    if [ -n "${L:-}" ] && [ $(( B - T )) -gt $(dp 80) ]; then pass "イヤホンの電池の内訳は展開した形 ($L,$T)-($R,$B)"; else fail "イヤホンの電池の内訳が展開した形になっていない"; fi
  fi
  sleep 2.5
done
cmd clear; sleep 0.5
# イヤホンの表示（左右とケースの内訳つき）をタップすると、内訳を開く
cmd alert device; sleep 0.8
read -r L T R B <<<"$(region)"
if [ -n "${L:-}" ]; then
  A shell input tap $(( (L + R) / 2 )) $(( (T + B) / 2 )) >/dev/null; sleep 1
  read -r L T R B <<<"$(region)"
  if [ -n "${L:-}" ] && [ $(( B - T )) -gt $(dp 80) ]; then pass "イヤホンの表示をタップすると電池の内訳が開く"; shot "alert-pods-tap"; else fail "イヤホンの表示をタップしても内訳が開かない"; fi
else
  fail "イヤホンの表示の位置が取れない"
fi
sleep 6.5
cmd clear; sleep 0.5

echo "== 展開と操作（本物の MediaSession） =="
require_device
cmd clear
cmd media start
sleep 1.5
expect "Media(テスト曲 1,playing=true)" "MediaSession を拾う"
read -r L T R B <<<"$(region)"
if [ -n "${L:-}" ]; then
  cx=$(( (L + R) / 2 )); cy=$(( (T + B) / 2 ))
  info "島 ($L,$T)-($R,$B)"
  A shell input swipe $cx $cy $cx $cy 700 >/dev/null
  sleep 1
  expect "expanded=true" "長押しで展開する"
  shot "media-expanded"
  read -r L T R B <<<"$(region)"
  cx=$(( (L + R) / 2 ))
  # 下から: 余白 14dp、操作列 48dp の中央
  play_y=$(( B - $(dp 38) ))
  A logcat -c >/dev/null
  A shell input tap $cx $play_y >/dev/null
  sleep 0.8
  if A logcat -d -s IslandShell:I | grep -q "media-callback pause"; then pass "再生/停止ボタンが効く"; else fail "再生/停止ボタンが効かない"; fi
  next_x=$(( cx + $(dp 28) + $(dp 28) + $(dp 24) ))
  A shell input tap $next_x $play_y >/dev/null
  sleep 0.8
  expect "テスト曲 2" "次の曲ボタンが効く"
  seek_y=$(( B - $(dp 14) - $(dp 48) - $(dp 8) - $(dp 12) ))
  A logcat -c >/dev/null
  A shell input swipe $(( L + $(dp 90) )) $seek_y $(( R - $(dp 90) )) $seek_y 400 >/dev/null
  sleep 0.8
  if A logcat -d -s IslandShell:I | grep -q "media-callback seek"; then pass "シークバーのドラッグでシークする"; else fail "シークしない"; fi
  # 右下の出力先ボタン → システムの「メディア出力」ダイアログ
  A shell input tap $(( R - $(dp 40) )) $play_y >/dev/null
  sleep 1.5
  if A shell dumpsys window | grep -m1 "mCurrentFocus" | grep -q "MediaOutputDialog"; then pass "出力先ボタンでメディア出力のダイアログが開く"; else fail "出力先のダイアログが開かない"; fi
  A shell input keyevent BACK >/dev/null
  sleep 0.8
  cmd expand
  sleep 1
  # 島の外（島のすぐ下。設定画面の説明文なので押しても何も起きない）を触ると畳む
  A shell input tap $(( W / 2 )) $(( B + $(dp 40) )) >/dev/null
  sleep 1
  expect "expanded=false" "外側を触ると畳む"
else
  fail "島の位置が取れない"
fi
cmd media stop
sleep 0.5

echo "== 隠す条件 =="
require_device
cmd clear; A shell input keyevent HOME >/dev/null; sleep 0.8
cmd demo Media; sleep 1.2
A shell am start -n $PKG/.debug.FullscreenTestActivity >/dev/null
sleep 2
expect "region=-" "全画面のアプリでは隠れる"
A shell input keyevent BACK >/dev/null
sleep 1.5
expect "Media(" "全画面を抜けると戻る"
A shell cmd statusbar expand-notifications >/dev/null
sleep 1.5
expect "region=-" "通知シェードを開くと隠れる"
A shell cmd statusbar collapse >/dev/null
sleep 1.5
r=$(state)
if [[ "$r" != *"region=-"* ]]; then pass "通知シェードを閉じると戻る"; else fail "通知シェードを閉じても戻らない — $r"; fi
cmd clear

echo "== おやすみモード（切り替えて必ず元に戻す） =="
require_device
zen=$(A shell dumpsys notification | grep -m1 -o "mZenMode=[A-Z_]*")
if [[ "$zen" == "mZenMode=ZEN_MODE_OFF" ]]; then
  A shell cmd notification set_dnd priority >/dev/null
  sleep 0.6
  expect "alert=dnd" "おやすみモードをオンにすると出る"
  A shell cmd notification set_dnd off >/dev/null
  sleep 0.6
  expect "alert=dnd" "オフにすると出る"
  info "元に戻した: $(A shell dumpsys notification | grep -m1 -o "mZenMode=[A-Z_]*")"
else
  info "おやすみモードが既にオン（$zen）なので触らない"
fi

echo "== 充電（dumpsys battery で模擬。最後に戻す） =="
require_device
cmd clear
A shell dumpsys battery unplug >/dev/null
sleep 1
A logcat -c >/dev/null
A shell dumpsys battery set ac 1 >/dev/null
sleep 0.6
expect "alert=charging" "充電をつなぐと出る"
shot "real-charging"
A shell dumpsys battery reset >/dev/null
info "電池の状態を元に戻した"

echo "== 省電力（電池残量低下の表示から。dumpsys battery で残量 15% の放電中を模擬し、最後に戻す） =="
require_device
saver() { A shell dumpsys power | sed -n '/Battery saver state machine/,/mState=/p' | grep -m1 -o "Enabled=[a-z]*"; }
trig() { A shell settings get global low_power_trigger_level | tr -d '\r'; }
# 電池残量低下の表示を出し、その省電力のボタンの位置を bx, by に入れる
battery_button() {
  cmd alert battery; sleep 1.2
  read -r L T R B <<<"$(region)"
  # ボタンは右端から 0.78H + 幅の半分（1.17H）。H は待機時の島の高さ（region の高さの 1/2.8）
  bh=$(( (B - T) * 10 / 28 ))
  bx=$(( R - bh * 195 / 100 )); by=$(( (T + B) / 2 ))
}
if ! A shell dumpsys package $PKG | grep -q "WRITE_SECURE_SETTINGS: granted=true"; then
  info "WRITE_SECURE_SETTINGS が未許可なので飛ばす（島のタップは設定画面を開く）"
elif [[ "$(saver)" == "Enabled=true" ]]; then
  info "省電力が既にオンなので触らない"
else
  before=$(trig)
  A shell dumpsys battery unplug >/dev/null
  A shell dumpsys battery set level 15 >/dev/null
  sleep 1
  battery_button
  A shell input tap $bx $by >/dev/null; sleep 1.5
  if [[ "$(saver)" == "Enabled=true" ]]; then pass "島のボタンで省電力が入る"; else fail "島のボタンで省電力が入らない"; fi
  shot "alert-battery-on"
  A shell input tap $bx $by >/dev/null; sleep 1.5
  if [[ "$(saver)" == "Enabled=false" && "$(trig)" == "$before" ]]; then pass "もう一度押すと切れて、スケジュールも元に戻る"; else fail "もう一度押しても切れない — $(saver) 閾値=$(trig)"; fi
  cmd alert battery; sleep 1.2
  A shell input tap $bx $by >/dev/null; sleep 1.5
  # 設定画面・クイック設定のスイッチと同じ経路（PowerManager.setPowerSaveModeEnabled）で切る
  A shell cmd power set-mode 0 >/dev/null; sleep 1.5
  if [[ "$(saver)" == "Enabled=false" && "$(trig)" == "$before" ]]; then pass "島で入れた省電力を設定のスイッチで切れる（スケジュールも元に戻る）"; else fail "設定のスイッチで切れない — $(saver) 閾値=$(trig)"; fi
  A shell dumpsys battery reset >/dev/null
  info "電池の状態を元に戻した（省電力: $(saver)）"
fi
cmd clear

echo "== Shizuku（動いていて、Island に使う許可が出ているときだけ） =="
require_device
A logcat -c >/dev/null
cmd shizuku; sleep 1.5
sz=$(A logcat -d -s IslandShell:I | grep -o 'shizuku=.*' | tail -1)
if [[ "$sz" != shizuku=Ready* ]]; then
  info "飛ばす（${sz:-応答なし}）"
else
  info "$sz"
  if [[ "$sz" == *"tasks=[]"* || "$sz" == *"tasks=error"* ]]; then fail "Shizuku で最近のタスクを読めない"; else pass "Shizuku で最近のタスクを読める"; fi
  if [[ "$(saver)" == "Enabled=true" ]]; then
    info "省電力が既にオンなので触らない"
  else
    before=$(trig)
    A shell dumpsys battery unplug >/dev/null
    A shell dumpsys battery set level 15 >/dev/null
    sleep 1
    # 設定画面のスイッチで入れたのと同じ「手動」のオン（Shizuku が無いと島からは切れない）
    A shell cmd power set-mode 1 >/dev/null; sleep 1
    battery_button
    A shell input tap $bx $by >/dev/null; sleep 1.5
    if [[ "$(saver)" == "Enabled=false" && "$(trig)" == "$before" ]]; then pass "手動で入れた省電力を島のボタンで切れる（スケジュールには触らない）"; else fail "手動で入れた省電力を島から切れない — $(saver) 閾値=$(trig)"; A shell cmd power set-mode 0 >/dev/null; fi
    A shell dumpsys battery reset >/dev/null
    info "電池の状態を元に戻した（省電力: $(saver)）"
  fi
  # ペアリング済みの機器のメタデータ（Evolution X の BtHelper が AirPods の左右とケースの電池を書く）を Shizuku で読めるか。
  # 何も書かれていない機器は null。つないでいる AirPods があれば、その内訳が出る
  A logcat -c >/dev/null
  cmd earbuds; sleep 1.5
  eb=$(A logcat -d -s IslandShell:I | grep -o 'earbuds=.*' | tail -1)
  info "${eb:-earbuds: 応答なし}"
  if [[ -z "$eb" || "$eb" == *"error:"* ]]; then fail "Shizuku で Bluetooth のメタデータを読めない"; else pass "Shizuku で Bluetooth のメタデータを読める"; fi
fi
cmd clear

# 切り替えると開いているアプリの画面が全部作り直されるので、スクリプトからはオン・オフしない（設定でオンのときに確かめるだけ）
echo "== ステータスバーの空き（設定でオンのときだけ。root の Shizuku が要る） =="
require_device
A logcat -c >/dev/null
cmd statusbar; sleep 0.5
sb=$(A logcat -d -s IslandShell:I | grep -o 'statusbar=.*' | tail -1)
case "$sb" in
  ""|*"on=false"*) info "飛ばす（${sb:-応答なし}）" ;;
  statusbar=Applied*)
    pass "ステータスバーの真ん中を空けている（${sb#statusbar=}）"
    cmd demo Media; sleep 1.2
    shot "statusbar-gap"
    cmd clear ;;
  statusbar=Failed*) fail "ステータスバーを空けられない — $sb" ;;
  *) info "まだ空けていない（$sb）" ;;
esac

echo "== 滑らかさ =="
require_device
cmd demo Media; sleep 1
A shell dumpsys gfxinfo $PKG reset >/dev/null
for i in 1 2 3 4 5; do cmd expand; sleep 0.9; cmd collapse; sleep 0.9; done
A shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|50th|90th|99th" | sed 's/^/      /'
cmd clear

echo "== クラッシュ =="
require_device
n=$(A logcat -d -b crash | grep -c "$PKG" || true)
if [ "$n" -eq 0 ]; then pass "クラッシュなし"; else fail "クラッシュ $n 件"; A logcat -d -b crash | grep -A12 "$PKG" | head -30 | sed 's/^/      /'; fi

echo
if [ "$FAILED" -eq 0 ]; then echo "すべて通りました"; else echo "$FAILED 件失敗"; fi
exit $(( FAILED > 0 ))
