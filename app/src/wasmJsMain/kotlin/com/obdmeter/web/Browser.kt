package com.obdmeter.web

import kotlinx.coroutines.await
import kotlin.js.Promise

// ブラウザーの機能 (保存領域・ファイルの読み書き) を Kotlin から使うための薄い橋渡し。
// バイト列は「1 文字 = 1 バイト」の文字列にして受け渡す (文字列は 1 回の呼び出しでまとめて渡せるので速い)

private fun lsGet(key: String): String? = js("{ try { return window.localStorage.getItem(key); } catch (e) { return null; } }")

private fun lsSet(key: String, value: String): Boolean =
    js("{ try { window.localStorage.setItem(key, value); return true; } catch (e) { return false; } }")

object LocalStore {
    fun get(key: String): String? = lsGet(key)
    fun set(key: String, value: String): Boolean = lsSet(key, value)
}

fun ByteArray.toBinaryString(): String {
    val chars = CharArray(size)
    for (i in indices) chars[i] = (this[i].toInt() and 0xFF).toChar()
    return chars.concatToString()
}

fun String.binaryToBytes(): ByteArray {
    val out = ByteArray(length)
    for (i in indices) out[i] = this[i].code.toByte()
    return out
}

private fun idbOpen(): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var req = indexedDB.open('obdmeter', 1);
        req.onupgradeneeded = function () { req.result.createObjectStore('files'); };
        req.onsuccess = function () { resolve(req.result); };
        req.onerror = function () { reject(req.error); };
    })
""")

private fun idbPut(db: JsAny, key: String, value: String): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var tx = db.transaction('files', 'readwrite');
        tx.objectStore('files').put(value, key);
        tx.oncomplete = function () { resolve(null); };
        tx.onerror = function () { reject(tx.error); };
    })
""")

private fun idbGet(db: JsAny, key: String): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var req = db.transaction('files', 'readonly').objectStore('files').get(key);
        req.onsuccess = function () { resolve(req.result === undefined ? null : req.result); };
        req.onerror = function () { reject(req.error); };
    })
""")

private fun idbDelete(db: JsAny, key: String): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var tx = db.transaction('files', 'readwrite');
        tx.objectStore('files').delete(key);
        tx.oncomplete = function () { resolve(null); };
        tx.onerror = function () { reject(tx.error); };
    })
""")

private fun idbKeys(db: JsAny): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var req = db.transaction('files', 'readonly').objectStore('files').getAllKeys();
        req.onsuccess = function () { resolve(req.result.join('\n')); };
        req.onerror = function () { reject(req.error); };
    })
""")

private fun jsToString(v: JsAny?): String? = js("(typeof v === 'string') ? v : null")

/** ブラウザーの IndexedDB に置くファイル (画像・フォント) */
object FileStore {
    private var db: JsAny? = null

    private suspend fun db(): JsAny = db ?: idbOpen().await<JsAny?>()!!.also { db = it }

    suspend fun put(key: String, bytes: ByteArray) { idbPut(db(), key, bytes.toBinaryString()).await<JsAny?>() }

    suspend fun get(key: String): ByteArray? = jsToString(idbGet(db(), key).await<JsAny?>())?.binaryToBytes()

    suspend fun delete(key: String) { idbDelete(db(), key).await<JsAny?>() }

    suspend fun keys(): List<String> =
        jsToString(idbKeys(db()).await<JsAny?>()).orEmpty().split('\n').filter { it.isNotEmpty() }
}

private fun jsDownload(name: String, data: String, mime: String): Unit = js("""{
    var bytes = new Uint8Array(data.length);
    for (var i = 0; i < data.length; i++) bytes[i] = data.charCodeAt(i);
    var url = URL.createObjectURL(new Blob([bytes], { type: mime }));
    var a = document.createElement('a');
    a.href = url; a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
}""")

/** ファイルとしてダウンロードさせる */
fun downloadFile(name: String, bytes: ByteArray, mime: String) = jsDownload(name, bytes.toBinaryString(), mime)

private fun jsPickFile(accept: String, onPicked: (JsAny) -> Unit): Unit = js("""{
    var input = document.createElement('input');
    input.type = 'file'; input.accept = accept; input.style.display = 'none';
    input.onchange = function () { if (input.files && input.files.length > 0) onPicked(input.files[0]); input.remove(); };
    document.body.appendChild(input);
    input.click();
}""")

private fun jsFileName(file: JsAny): String = js("file.name")

private fun jsReadFile(file: JsAny): Promise<JsAny?> = js("""
    file.arrayBuffer().then(function (buf) {
        var bytes = new Uint8Array(buf);
        var parts = [];
        for (var i = 0; i < bytes.length; i += 8192) parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 8192)));
        return parts.join('');
    })
""")

private fun jsFetchBinary(url: String): Promise<JsAny?> = js("""
    fetch(url).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); }).then(function (buf) {
        var bytes = new Uint8Array(buf);
        var parts = [];
        for (var i = 0; i < bytes.length; i += 8192) parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 8192)));
        return parts.join('');
    })
""")

class BrowserFile(private val file: JsAny) {
    val name: String get() = jsFileName(file)
    suspend fun read(): ByteArray = jsToString(jsReadFile(file).await<JsAny?>())!!.binaryToBytes()
}

/** ファイルを選ぶ画面を開く ([accept] は input の accept 属性) */
fun pickFile(accept: String, onPicked: (BrowserFile) -> Unit) = jsPickFile(accept) { onPicked(BrowserFile(it)) }

/** サイトに置いたファイルを読む */
suspend fun fetchBinary(url: String): ByteArray? =
    runCatching { jsToString(jsFetchBinary(url).await<JsAny?>())?.binaryToBytes() }.getOrNull()

private fun jsAppVersion(): String = js("(window.OBDMETER_VERSION || '')")

fun appVersion(): String = jsAppVersion()
