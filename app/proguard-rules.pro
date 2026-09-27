# BluetoothDevice#getBatteryLevel はリフレクションで呼ぶ（フレームワーク側なので keep 不要）

# Shizuku で shell コマンドを動かす（API 13 で非公開になった newProcess をリフレクションで呼ぶ。使われていないので消されてしまう）
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
