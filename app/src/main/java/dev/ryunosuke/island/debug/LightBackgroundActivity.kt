package dev.ryunosuke.island.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController

/**
 * 島の録画用の白い画面（adb shell からのみ起動できる）。Apple の見本も明るい背景に黒い島なので、
 * 同じ条件で縁の位置を測れるようにする。extra "bars"=false でステータスバーも隠す（島に接する通知アイコンで
 * 縁の位置がずれないように。そのときは受け口の「visible on」で全画面でも島を出しておく）。
 */
class LightBackgroundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.rgb(226, 228, 226)) })
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        if (!intent.getBooleanExtra("bars", true)) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
        }
    }
}
