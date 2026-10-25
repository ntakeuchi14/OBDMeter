package com.obdmeter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import com.obdmeter.data.FontEntry
import kotlinx.coroutines.launch

/** 選んだファイル ([read] で中身を読む) */
class PickedFile(val name: String, val read: suspend () -> ByteArray)

/** 書き出し先 ([write] で書き、保存先の説明 (なければ null) を返す) */
class FileTarget(val write: suspend (ByteArray) -> String?)

/** Web 版か (アダプター・フロート表示・接続テストなど、Android だけの機能を隠すのに使う) */
expect val isWebApp: Boolean

/** 短い通知 (Android はトースト) を出す関数 */
@Composable
expect fun rememberToast(): (String) -> Unit

/** 戻る操作 (Android の戻るボタン) */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * ファイルを選ぶ画面を開く関数を返す (開けなければ false)。
 * [mimeTypes] が image/ だけなら写真を選ぶ画面を使う
 */
@Composable
expect fun rememberFileOpener(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): () -> Boolean

/** 保存先を選んで書き出す関数を返す (引数は既定のファイル名)。保存先が決まったら [onTarget] を呼ぶ */
@Composable
expect fun rememberFileSaver(mimeType: String, onTarget: (FileTarget) -> Unit): (String) -> Unit

/** テキストを共有する (Android は共有メニュー、Web はテキストファイルのダウンロード) */
@Composable
expect fun rememberShareText(): (subject: String, text: String) -> Unit

/** 保存済みの画像 (メーターやページの背景)。読み込むまでは null */
@Composable
expect fun rememberGaugeImage(name: String?): ImageBitmap?

/**
 * 取り込んだフォント。読めなければ null。
 * [forLineHeightText] は行の高さを指定した文字 (画面全体の既定の文字スタイルを使う Text) 用。
 * Android はその場合だけ行の高さの決め方が変わるフォントがあるので、Web 版で同じにするのに使う
 */
@Composable
expect fun rememberFontFamily(entry: FontEntry?, forLineHeightText: Boolean = false): FontFamily?

/** ぼかした影 ([path] を ([dx], [dy]) ずらして、[blur] px でぼかす)。針やメーター自体の影に使う */
expect fun DrawScope.drawSoftShadow(path: Path, dx: Float, dy: Float, blur: Float, alpha: Float)

/** 保存先などの、Android と Web で違うところ。アプリの最上位で渡す */
val LocalPlatform = androidx.compose.runtime.staticCompositionLocalOf<com.obdmeter.AppPlatform?> { null }

/**
 * 画像を選んで取り込む (長辺 [maxSize] まで縮小して保存) 画面を開く関数を返す (開けなければ false)。
 * 選んだら [onStart]、取り込み終えたら保存した名前を [onResult] に返す
 */
@Composable
fun rememberImageImporter(
    maxSize: Int = 1024,
    onStart: () -> Unit = {},
    onResult: (Result<String>) -> Unit,
): () -> Boolean {
    val platform = LocalPlatform.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val start = androidx.compose.runtime.rememberUpdatedState(onStart)
    val result = androidx.compose.runtime.rememberUpdatedState(onResult)
    return rememberFileOpener(listOf("image/*")) { f ->
        start.value()
        scope.launch {
            result.value(runCatching {
                (platform ?: error("画像を保存できません")).importImage(f.read(), maxSize)
            })
        }
    }
}

/** フロート表示の許可 (「ほかのアプリの上に重ねて表示」) の状態 */
class FloatPermissionState(val canDraw: Boolean, val request: () -> Unit, val openSettings: () -> Unit)

@Composable
expect fun rememberFloatPermission(): FloatPermissionState

/** この端末の画面の大きさの説明 (Android)。Web は null */
@Composable
expect fun deviceScreenInfo(): String?
