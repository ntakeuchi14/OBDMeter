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

internal fun idbOpenShared(): Promise<JsAny?> = idbOpen()

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

// ---- 既定の保存先のフォルダー (File System Access API。Edge・Chrome) ----
// 選んだフォルダーのハンドルは IndexedDB (files ストアの "saveFolderHandle") に覚えておく

private fun jsFolderSupported(): Boolean = js("typeof window.showDirectoryPicker === 'function'")

private fun jsPickFolder(db: JsAny): Promise<JsAny?> = js("""
    window.showDirectoryPicker({ id: 'obdmeter-save', mode: 'readwrite' }).then(function (h) {
        return new Promise(function (resolve, reject) {
            var tx = db.transaction('files', 'readwrite');
            tx.objectStore('files').put(h, 'saveFolderHandle');
            tx.oncomplete = function () { resolve(h.name); };
            tx.onerror = function () { reject(tx.error); };
        });
    }).catch(function (e) { return (e && e.name === 'AbortError') ? null : Promise.reject(e); })
""")

// 保存のボタンを押した直後 (ユーザーの操作の中) に呼んで、書き込みの許可を確かめる (無ければ許可を求める)
private fun jsFolderPermission(db: JsAny): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var req = db.transaction('files', 'readonly').objectStore('files').get('saveFolderHandle');
        req.onsuccess = function () { resolve(req.result || null); };
        req.onerror = function () { reject(req.error); };
    }).then(function (h) {
        if (!h) return 'none';
        return h.queryPermission({ mode: 'readwrite' }).then(function (p) {
            if (p === 'granted') return 'granted';
            return h.requestPermission({ mode: 'readwrite' });
        });
    })
""")

private fun jsSaveToFolder(db: JsAny, name: String, data: String): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var req = db.transaction('files', 'readonly').objectStore('files').get('saveFolderHandle');
        req.onsuccess = function () { resolve(req.result || null); };
        req.onerror = function () { reject(req.error); };
    }).then(function (h) {
        if (!h) throw new Error('保存先のフォルダーがありません');
        var dot = name.lastIndexOf('.');
        var base = dot > 0 ? name.substring(0, dot) : name;
        var ext = dot > 0 ? name.substring(dot) : '';
        // 同じ名前のファイルがあれば「名前 (1).拡張子」…にする
        function pick(i) {
            var n = i === 0 ? name : base + ' (' + i + ')' + ext;
            return h.getFileHandle(n).then(function () { return pick(i + 1); }, function () { return n; });
        }
        return pick(0).then(function (n) {
            return h.getFileHandle(n, { create: true }).then(function (fh) {
                return fh.createWritable().then(function (w) {
                    var bytes = new Uint8Array(data.length);
                    for (var i = 0; i < data.length; i++) bytes[i] = data.charCodeAt(i);
                    return w.write(bytes).then(function () { return w.close(); }).then(function () { return h.name + '/' + n; });
                });
            });
        });
    })
""")

private fun jsClearFolder(db: JsAny): Promise<JsAny?> = js("""
    new Promise(function (resolve, reject) {
        var tx = db.transaction('files', 'readwrite');
        tx.objectStore('files').delete('saveFolderHandle');
        tx.oncomplete = function () { resolve(null); };
        tx.onerror = function () { reject(tx.error); };
    })
""")

/** 既定の保存先のフォルダー */
object SaveFolderStore {
    private var db: JsAny? = null
    private suspend fun db(): JsAny = db ?: idbOpenShared().await<JsAny?>()!!.also { db = it }

    val supported: Boolean get() = jsFolderSupported()

    /** フォルダーを選ぶ (やめたら null)。フォルダー名を返す */
    suspend fun pick(): String? = jsToString(jsPickFolder(db()).await<JsAny?>())

    /** 書き込みの許可を確かめる (無ければ求める)。許可されたら true */
    suspend fun ensurePermission(): Boolean = jsToString(jsFolderPermission(db()).await<JsAny?>()) == "granted"

    /** フォルダーに保存する。「フォルダー名/ファイル名」を返す */
    suspend fun save(name: String, bytes: ByteArray): String =
        jsToString(jsSaveToFolder(db(), name, bytes.toBinaryString()).await<JsAny?>()) ?: name

    suspend fun clear() { jsClearFolder(db()).await<JsAny?>() }
}
