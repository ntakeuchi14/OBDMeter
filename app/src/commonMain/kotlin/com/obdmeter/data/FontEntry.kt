package com.obdmeter.data

import kotlinx.serialization.Serializable

/**
 * 取り込んだフォント 1 つ。アプリ内に保存し、どのメーターからでも [id] で参照する。
 * @param sha ファイル内容のハッシュ。同じフォントを二重に取り込まないために使う
 */
@Serializable
data class FontEntry(
    val id: String,
    val name: String,
    val file: String,
    val sha: String = "",
)
