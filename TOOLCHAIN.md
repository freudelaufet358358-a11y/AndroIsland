# ビルド環境

vocab-android と同じ組み合わせ（詳細と踏んだ制約は `~/ClaudeCode/vocab-android/TOOLCHAIN.md`）。

    source ./env.sh
    ./gradlew :app:assembleRelease      # 普段使い（R8 で縮めて約 2MB、debug 鍵で署名）
    ./gradlew :app:assembleDebug        # デバッグ版（約 30MB）

| 要素 | バージョン |
|---|---|
| JDK | Temurin 21（`~/.local/jdk-21`） |
| Gradle | 9.7.1（wrapper） |
| AGP | 9.4.1（組み込み Kotlin 2.3.21） |
| compileSdk | 37（`compileSdkMinor = 2`） |
| minSdk | 34 |

## このアプリ固有の判断

- **minSdk 34。** `AttachedSurfaceControl.setTouchableRegion`（窓を動かさずに触れる範囲だけ変える）が 34 から。
  自分の Pixel 6a（Android 16）専用なので下げる理由がない
- **Hilt・Room は使わない。** 部品が少ないので `IslandApp` の `Graph` で手で組む。
  設定は DataStore、内蔵時計の状態は SharedPreferences（タイマー終了の受信でプロセスが起き直したとき同期で読みたい）
- **debug と release は同じ applicationId。** アクセシビリティ・通知へのアクセスの許可は applicationId に付くので、
  入れ替えても許可し直さなくて済むようにしている
- **Android 16 の定数は文字列と数値で持つ。** `FLAG_PROMOTED_ONGOING` などは API 36 からなので、
  minSdk 34 のまま参照すると lint が怒る。値は変わらないので直書きしている
