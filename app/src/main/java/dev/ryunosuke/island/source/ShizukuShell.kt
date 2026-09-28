package dev.ryunosuke.island.source

import android.app.ActivityManager
import android.bluetooth.BluetoothDevice
import android.content.AttributionSource
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Shizuku（adb shell と同じ権限でシステムを呼べるようにするアプリ）を通して、普通のアプリにはできないことをする。
 *
 * - セットアップの許可（アクセシビリティ・通知へのアクセス・実行時の権限・WRITE_SECURE_SETTINGS）をまとめて付ける。
 *   付けたものは端末の設定として残るので、あとで Shizuku が止まっても消えない
 * - 省電力を `cmd power set-mode` で直接入れ・切りする（設定画面で手動で入れたものも切れる）
 * - 最近のタスクの一覧を読む（Recents で払われたアプリを突き止める）
 * - Bluetooth 機器のメタデータを読む（Evolution X の BtHelper が書いた、AirPods の左右とケースの電池）
 * - root で動いているときだけ、ステータスバーの真ん中を島の幅だけ空ける（[StatusBarGap]）
 *
 * root なしの Shizuku は端末を再起動すると止まる。呼ぶ側は毎回 [isReady] を確かめ、使えなければ今までのやり方に戻す。
 * Shizuku の呼び出しはどれもプロセスをまたぐので、[handler] のスレッドで行う。
 */
object ShizukuShell {
    enum class Status {
        /** Shizuku のアプリが入っていない */
        NotInstalled,

        /** 入っているが動いていない（root なしでは端末の再起動のたびに起動し直す） */
        NotRunning,

        /** 動いているが、Island に使う許可をまだ出していない */
        NoPermission,
        Ready,
    }

    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    private const val REQUEST_CODE = 1

    /** 最近のタスクを何件まで見るか（Recents に並ぶ数より十分多く） */
    private const val MAX_TASKS = 100

    /** Shizuku を呼ぶ専用のスレッド */
    val handler: Handler by lazy { Handler(HandlerThread("Shizuku").apply { start() }.looper) }

    /** Island に使う許可を求めている間、許可されたらすること */
    @Volatile
    private var onGranted: (() -> Unit)? = null

    private val _changes = MutableStateFlow(0)

    /** Shizuku が動き出した・止まった・使用の許可が決まったときに 1 つ増える（使えるかを見直すきっかけ） */
    val changes: StateFlow<Int> get() = _changes

    init {
        Shizuku.addBinderReceivedListenerSticky { _changes.update { it + 1 } }
        Shizuku.addBinderDeadListener { _changes.update { it + 1 } }
        Shizuku.addRequestPermissionResultListener { code, result ->
            _changes.update { it + 1 }
            if (code != REQUEST_CODE) return@addRequestPermissionResultListener
            val action = onGranted
            onGranted = null
            Log.i(TAG, "Shizuku の使用 ${if (result == PackageManager.PERMISSION_GRANTED) "許可" else "拒否"}")
            if (result == PackageManager.PERMISSION_GRANTED && action != null) handler.post(action)
        }
    }

    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(context: Context): Status {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            val installed = runCatching { context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0) }.isSuccess
            return if (installed) Status.NotRunning else Status.NotInstalled
        }
        return if (isReady()) Status.Ready else Status.NoPermission
    }

    /** shell の uid（adb なら 2000、root で起動していれば 0）。使えなければ -1 */
    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** root で動いている（adb の shell には許されていないこともできる） */
    fun isRoot(): Boolean = isReady() && uid() == 0

    /**
     * Island に Shizuku を使う許可を求め、許可されたら [then] を [handler] のスレッドで呼ぶ。
     * 前に「今後表示しない」で断っていると確認が出ないので、そのときは Shizuku のアプリを開く
     */
    fun requestPermission(context: Context, then: () -> Unit) {
        if (isReady()) {
            handler.post(then)
            return
        }
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            openManager(context)
            return
        }
        onGranted = then
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure {
            Log.w(TAG, "Shizuku の使用の許可を求められない", it)
            onGranted = null
        }
    }

    /** Shizuku のアプリを開く（入っていなければ入手ページ） */
    fun openManager(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "Shizuku を開けない", it) }
    }

    /**
     * 非公開の Shizuku.newProcess（API 13 で公開をやめたが、中身は残っている）。
     * R8 に消されないよう proguard-rules.pro で残している
     */
    private val newProcess: Method by lazy {
        Shizuku::class.java
            .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    /**
     * shell コマンドを 1 つ Shizuku 側で動かし、終わるまで待って標準出力を返す。adb shell で打つのと同じ権限で動く。
     * Shizuku が使えない・終了コードが 0 以外なら例外。[handler] のスレッドから呼ぶ
     */
    fun exec(vararg cmd: String): String {
        check(isReady()) { "Shizuku が使えない" }
        val p = newProcess.invoke(null, arrayOf(*cmd), null, null) as Process
        // どれも出力はごく短いので、標準出力 → 標準エラーの順に読み切ってよい
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }.trim()
        val code = p.waitFor()
        Log.i(TAG, "${cmd.joinToString(" ")} → $code${if (err.isNotEmpty()) " $err" else ""}")
        check(code == 0) { "${cmd.joinToString(" ")} が失敗 ($code): $err" }
        return out
    }

    /**
     * 最近のタスク（Recents に並ぶもの）のアプリ。shell の権限（REAL_GET_TASKS）で呼ぶので、他のアプリのタスクも見える。
     * 隠し API（IActivityTaskManager#getRecentTasks）をリフレクションで呼ぶ。[handler] のスレッドから呼ぶ
     */
    fun recentTaskPackages(): Set<String> {
        check(isReady()) { "Shizuku が使えない" }
        allowHiddenApi()
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task"))
        val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        val getRecentTasks = Class.forName("android.app.IActivityTaskManager").methods
            .first { it.name == "getRecentTasks" && it.parameterCount == 3 }
        // (件数, フラグ, ユーザー番号)。ユーザー番号は uid / 100000（UserHandle.PER_USER_RANGE）
        val slice = getRecentTasks.invoke(
            atm, MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE, android.os.Process.myUid() / 100_000,
        ) ?: return emptySet()
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
        return list.mapNotNullTo(HashSet()) { t ->
            (t as? ActivityManager.RecentTaskInfo)?.let { it.baseIntent.component?.packageName ?: it.baseActivity?.packageName }
        }
    }

    /**
     * Bluetooth 機器のメタデータ（BluetoothDevice#getMetadata。左右とケースの電池など）を読む。読めなかったキーは null。
     * 普通のアプリには許されていない（BLUETOOTH_PRIVILEGED が要る）ので、このプロセスが持っている IBluetooth の binder を
     * Shizuku 越しに呼ぶ（shell も root もこの権限を持っている）。隠し API をリフレクションで呼ぶ。[handler] のスレッドから呼ぶ
     */
    fun bluetoothMetadata(device: BluetoothDevice, keys: IntArray): Map<Int, ByteArray?> {
        check(isReady()) { "Shizuku が使えない" }
        allowHiddenApi()
        try {
            // BluetoothDevice#getMetadata も、この IBluetooth を呼んでいる（Bluetooth がオフなら null）
            val local = BluetoothDevice::class.java.getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null) as? IInterface ?: error("Bluetooth がオフ")
            val bt = Class.forName("android.bluetooth.IBluetooth\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(local.asBinder()))
            val getMetadata = Class.forName("android.bluetooth.IBluetooth").methods
                .first { it.name == "getMetadata" && it.parameterCount == 3 }
            // BLUETOOTH_PRIVILEGED は呼び出し元（Shizuku の uid）で、BLUETOOTH_CONNECT はここで名乗る受け手で確かめられる。
            // 受け手は shell にする（root で動いていても。root は他の uid を名乗れる）
            val source = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            return keys.associateWith { getMetadata.invoke(bt, device, it, source) as ByteArray? }
        } catch (e: InvocationTargetException) {
            // 呼んだ先の例外（権限が無い SecurityException など）をそのまま見せる
            throw e.targetException
        }
    }

    @Volatile
    private var hiddenApiAllowed = false

    /** このプロセスだけ、隠し API の制限を外す（IActivityTaskManager・ParceledListSlice・IBluetooth を呼ぶため） */
    private fun allowHiddenApi() {
        if (!hiddenApiAllowed) hiddenApiAllowed = HiddenApiBypass.addHiddenApiExemptions("L")
    }

    /** adb の shell の uid（Process.SHELL_UID は隠し API）とパッケージ */
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    private const val TAG = "IslandShizuku"
}
