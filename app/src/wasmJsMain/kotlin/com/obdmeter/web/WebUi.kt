package com.obdmeter.web

import kotlinx.coroutines.flow.MutableStateFlow

/** Web 版の画面全体で使うもの (通知・戻る操作・保存先) */
object WebUi {
    lateinit var platform: WebPlatform

    /** 画面の下に出す短い通知 */
    val message = MutableStateFlow<String?>(null)
    private var seq = 0
    val messageSeq = MutableStateFlow(0)

    fun toast(text: String) {
        message.value = text
        messageSeq.value = ++seq
    }

    /** 表示する画面の大きさ (null = ブラウザーのウィンドウいっぱい) */
    val screen = MutableStateFlow(ScreenSize.load())

    fun setScreen(size: ScreenSize?) {
        screen.value = size
        LocalStore.set("obd.screen", size?.let { "${it.width}x${it.height}x${it.density}" } ?: "")
    }

    /** Esc キーで戻る (最後に登録したもの) */
    val backHandlers = ArrayList<() -> Unit>()
}

/** 再現する画面の大きさ (px) と倍率 (Android の density。1 dp が何 px か) */
data class ScreenSize(val width: Int, val height: Int, val density: Float) {
    companion object {
        fun load(): ScreenSize? = LocalStore.get("obd.screen")?.split('x')?.takeIf { it.size == 3 }?.let {
            val w = it[0].toIntOrNull() ?: return null
            val h = it[1].toIntOrNull() ?: return null
            val d = it[2].toFloatOrNull() ?: return null
            if (w in 100..8000 && h in 100..8000 && d in 0.5f..4f) ScreenSize(w, h, d) else null
        }
    }
}
