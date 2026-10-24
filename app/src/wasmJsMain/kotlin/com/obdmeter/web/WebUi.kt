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

    /** Esc キーで戻る (最後に登録したもの) */
    val backHandlers = ArrayList<() -> Unit>()
}
