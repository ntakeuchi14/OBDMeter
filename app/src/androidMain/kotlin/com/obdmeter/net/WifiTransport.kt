package com.obdmeter.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.obdmeter.core.Transport
import com.obdmeter.core.TransportException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Wi-Fi アダプター (MHD の Wi-Fi アダプターなど、自分でアクセスポイントになるもの) と TCP でつなぐ。
 * アダプターの Wi-Fi はインターネットにつながらないので、Android がモバイル通信へ回さないように Wi-Fi のネットワークに固定する
 */
class WifiTransport(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3000,
) : Transport {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    /** Wi-Fi の省電力を止めておくロック (省電力のままだと 1 往復ごとに数十〜数百 ms 遅れることがある) */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun open() {
        acquireLowLatency()
        val s = try {
            val net = wifiNetwork(context)
            val sock = net?.socketFactory?.createSocket() ?: Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            sock
        } catch (e: Exception) {
            throw TransportException("Wi-Fi アダプター ($host:$port) に接続できません: ${e.message}", e)
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun write(data: ByteArray) {
        val o = output ?: throw TransportException("Wi-Fi アダプターに接続していません")
        try { o.write(data); o.flush() } catch (e: Exception) { throw TransportException("Wi-Fi アダプターへの送信に失敗しました: ${e.message}", e) }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val s = socket ?: throw TransportException("Wi-Fi アダプターに接続していません")
        val i = input ?: return 0
        return try {
            s.soTimeout = timeoutMs.coerceAtLeast(1)
            val n = i.read(buffer)
            if (n < 0) throw TransportException("Wi-Fi アダプターとの接続が切れました")
            n
        } catch (_: SocketTimeoutException) {
            0
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException("Wi-Fi アダプターからの受信に失敗しました: ${e.message}", e)
        }
    }

    /** 溜まっているデータだけ捨てる (TCP は待たなくても届いた分がわかるので、待ち時間を入れない) */
    override fun purge() {
        val i = input ?: return
        val tmp = ByteArray(512)
        var guard = 0
        runCatching { while (i.available() > 0 && guard++ < 50) { if (i.read(tmp, 0, minOf(tmp.size, i.available())) <= 0) break } }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireLowLatency() {
        if (wifiLock?.isHeld == true) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching { wm.createWifiLock(mode, "OBDMeter:adapter").apply { setReferenceCounted(false); acquire() } }.getOrNull()
    }

    companion object {
        /** つながっている Wi-Fi のネットワーク (無ければ null) */
        fun wifiNetwork(context: Context): Network? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            @Suppress("DEPRECATION")
            return cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }

        /** Wi-Fi のゲートウェイ (アダプターの IP であることが多い)。わからなければ null */
        fun wifiGateway(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val net = wifiNetwork(context) ?: return null
            val lp = cm.getLinkProperties(net) ?: return null
            return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null && it.gateway!!.address.size == 4 }?.gateway?.hostAddress
                ?: (if (android.os.Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress else null)
        }

        /** 端末の Wi-Fi 側の IP (調査用) */
        fun wifiAddress(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val net = wifiNetwork(context) ?: return null
            return cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address.address.size == 4 }?.toString()
        }

        /** 設定の接続先 (空なら Wi-Fi のゲートウェイ) */
        fun resolveHost(context: Context, configured: String): String? =
            configured.trim().ifEmpty { null } ?: wifiGateway(context)
    }
}
