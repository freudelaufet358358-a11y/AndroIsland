package dev.ryunosuke.island.ui.island

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** iOS ダークモードのシステムカラー */
object IslandColors {
    val Orange = Color(0xFFFF9F0A)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Blue = Color(0xFF0A84FF)
    val Indigo = Color(0xFF5E5CE6)
    val Yellow = Color(0xFFFFD60A)
    val Gray = Color(0xFF8E8E93)
    val Secondary = Color(0x99FFFFFF)
    val ButtonFill = Color(0xFF2C2C2E)
    val Track = Color(0x33FFFFFF)
}

object IslandText {
    /** 数字の幅を揃える（時間表示がガタつかないように） */
    val tabular = TextStyle(fontFeatureSettings = "tnum")

    val compact = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", color = Color.White)
    val title = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    val subtitle = TextStyle(fontSize = 14.sp, color = IslandColors.Secondary)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", color = IslandColors.Secondary)
    val bigTime = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", color = Color.White)
}

/** Material Symbols の形（Apache 2.0）を 24×24 の ImageVector にしたもの */
object IslandIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (p in paths) addPath(addPathNodes(p), fill = SolidColor(Color.White))
        }.build()

    val Play = icon("play", "M8,5.14v13.72c0,0.79 0.87,1.27 1.54,0.84l10.78,-6.86c0.62,-0.39 0.62,-1.29 0,-1.68L9.54,4.3C8.87,3.87 8,4.35 8,5.14z")
    val Pause = icon("pause", "M7,19h3c0.55,0 1,-0.45 1,-1V6c0,-0.55 -0.45,-1 -1,-1H7C6.45,5 6,5.45 6,6v12C6,18.55 6.45,19 7,19zM14,5c-0.55,0 -1,0.45 -1,1v12c0,0.55 0.45,1 1,1h3c0.55,0 1,-0.45 1,-1V6c0,-0.55 -0.45,-1 -1,-1H14z")
    val Next = icon("next", "M2.5,6.9v10.2c0,0.8 0.9,1.3 1.6,0.8l7.2,-5.1c0.6,-0.4 0.6,-1.2 0,-1.6L4.1,6.1C3.4,5.6 2.5,6.1 2.5,6.9zM12.5,6.9v10.2c0,0.8 0.9,1.3 1.6,0.8l7.2,-5.1c0.6,-0.4 0.6,-1.2 0,-1.6l-7.2,-5.1C13.4,5.6 12.5,6.1 12.5,6.9z")
    val Previous = icon("previous", "M21.5,6.9v10.2c0,0.8 -0.9,1.3 -1.6,0.8l-7.2,-5.1c-0.6,-0.4 -0.6,-1.2 0,-1.6l7.2,-5.1C20.6,5.6 21.5,6.1 21.5,6.9zM11.5,6.9v10.2c0,0.8 -0.9,1.3 -1.6,0.8l-7.2,-5.1c-0.6,-0.4 -0.6,-1.2 0,-1.6l7.2,-5.1C10.6,5.6 11.5,6.1 11.5,6.9z")
    val Forward = icon("forward", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z")
    val Rewind = icon("rewind", "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z")
    val Output = icon("output", "M6,22h12l-6,-6zM21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h4v-2H3V5h18v12h-4v2h4c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z")
    val Phone = icon("phone", "M20.01,15.38c-1.23,0 -2.42,-0.2 -3.53,-0.56 -0.35,-0.12 -0.74,-0.03 -1.01,0.24l-1.57,1.97c-2.83,-1.35 -5.48,-3.9 -6.89,-6.83l1.95,-1.66c0.27,-0.28 0.35,-0.67 0.24,-1.02 -0.37,-1.11 -0.56,-2.3 -0.56,-3.53 0,-0.54 -0.45,-0.99 -0.99,-0.99H4.19C3.65,3 3,3.24 3,3.99 3,13.28 10.73,21 20.01,21c0.71,0 0.99,-0.63 0.99,-1.18v-3.45c0,-0.54 -0.45,-0.99 -0.99,-0.99z")
    val PhoneDown = icon("phone_down", "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9 -0.98,0.49 -1.87,1.12 -2.66,1.85 -0.18,0.18 -0.43,0.28 -0.7,0.28 -0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08c-0.18,-0.17 -0.29,-0.42 -0.29,-0.7 0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.67c0.18,0.18 0.29,0.43 0.29,0.71 0,0.28 -0.11,0.53 -0.29,0.71l-2.48,2.48c-0.18,0.18 -0.43,0.29 -0.71,0.29 -0.27,0 -0.52,-0.11 -0.7,-0.28 -0.79,-0.74 -1.69,-1.36 -2.67,-1.85 -0.33,-0.16 -0.56,-0.5 -0.56,-0.9v-3.1C15.15,9.25 13.6,9 12,9z")
    val Video = icon("video", "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z")
    val Speaker = icon("speaker", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
    val MicOff = icon("mic_off", "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01V11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z")
    val Stopwatch = icon("stopwatch", "M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    val Timer = icon("timer", "M6,2v6h0.01L6,8.01 10,12l-4,4 0.01,0.01H6V22h12v-5.99h-0.01L18,16l-4,-4 4,-3.99 -0.01,-0.01H18V2H6zM16,16.5V20H8v-3.5l4,-4 4,4zM12,11.5l-4,-4V4h8v3.5l-4,4z")
    val Alarm = icon("alarm", "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8H11v6l4.75,2.85 0.75,-1.23 -4,-2.37V8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    val Snooze = icon("snooze", "M7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7zM9,11h3.63L9,15.2V17h6v-2h-3.63L15,10.8V9H9v2z")
    val Close = icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Stop = icon("stop", "M7,6h10c0.55,0 1,0.45 1,1v10c0,0.55 -0.45,1 -1,1H7c-0.55,0 -1,-0.45 -1,-1V7C6,6.45 6.45,6 7,6z")
    val Flag = icon("flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    val Reset = icon("reset", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    val Plus = icon("plus", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Bell = icon("bell", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")
    val BellOff = icon("bell_off", "M20,18.69L7.84,6.14 5.27,3.49 4,4.76l2.8,2.8v0.01c-0.52,0.99 -0.8,2.16 -0.8,3.42v5l-2,2v1h13.73l2,2L21,19.72l-1,-1.03zM12,22c1.11,0 2,-0.89 2,-2h-4c0,1.11 0.89,2 2,2zM18,14.68V11c0,-3.08 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68c-0.15,0.03 -0.29,0.08 -0.42,0.12 -0.1,0.03 -0.2,0.07 -0.3,0.11h-0.01c-0.01,0 -0.01,0 -0.02,0.01 -0.23,0.09 -0.46,0.2 -0.68,0.31 0,0 -0.01,0 -0.01,0.01L18,14.68z")
    val Vibrate = icon("vibrate", "M0,15h2V9H0v6zM3,17h2V7H3v10zM22,9v6h2V9h-2zM19,17h2V7h-2v10zM16.5,3h-9C6.67,3 6,3.67 6,4.5v15c0,0.83 0.67,1.5 1.5,1.5h9c0.83,0 1.5,-0.67 1.5,-1.5v-15c0,-0.83 -0.67,-1.5 -1.5,-1.5zM16,19H8V5h8v14z")
    val Moon = icon("moon", "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10 3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z")
    val Headphones = icon("headphones", "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z")
    val Bolt = icon("bolt", "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z")
    val Music = icon("music", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    val Navigation = icon("navigation", "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z")
    val Download = icon("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
}
