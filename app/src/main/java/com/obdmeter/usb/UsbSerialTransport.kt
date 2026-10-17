package com.obdmeter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.obdmeter.core.Transport
import com.obdmeter.core.TransportException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * usb-serial-for-android を使った USB シリアル通信。
 * FTDI (OBDLink SX/EX, K+DCAN ケーブル) を主な対象とするが、CP210x / CH34x / CDC も動く。
 */
class UsbSerialTransport(
    private val context: Context,
    private val driver: UsbSerialDriver,
    private val baudRate: Int,
    private val dtr: Boolean,
    private val rts: Boolean,
) : Transport {

    private var port: UsbSerialPort? = null

    // USB は 64 バイト単位などのパケットで届くので、余った分を内部に溜めておき要求分だけ返す
    private val pending = ArrayDeque<Byte>()
    private val usbBuf = ByteArray(4096)

    override fun open() {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(driver.device)
            ?: throw TransportException("USB デバイスを開けません (権限がない可能性があります)")
        val p = driver.ports.firstOrNull() ?: throw TransportException("シリアルポートが見つかりません")
        try {
            p.open(connection)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.setDTR(dtr) }
            runCatching { p.setRTS(rts) }
            // FTDI の既定レイテンシ (16ms) を 1ms にして応答を速くする
            if (p is FtdiSerialDriver.FtdiSerialPort) runCatching { p.setLatencyTimer(1) }
        } catch (e: Exception) {
            runCatching { p.close() }
            throw TransportException("USB ポートの初期化に失敗: ${e.message}", e)
        }
        port = p
    }

    override fun write(data: ByteArray) {
        val p = port ?: throw TransportException("未接続です")
        try {
            p.write(data, 1000)
        } catch (e: Exception) {
            throw TransportException("USB 送信エラー: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (pending.isEmpty()) {
            val p = port ?: throw TransportException("未接続です")
            val n = try {
                p.read(usbBuf, timeoutMs.coerceAtLeast(1))
            } catch (e: Exception) {
                throw TransportException("USB 受信エラー: ${e.message}", e)
            }
            for (i in 0 until n) pending.addLast(usbBuf[i])
        }
        var count = 0
        while (count < buffer.size && pending.isNotEmpty()) {
            buffer[count++] = pending.removeFirst()
        }
        return count
    }

    override fun purge() {
        pending.clear()
        val p = port ?: return
        var guard = 0
        while (guard++ < 20) {
            val n = runCatching { p.read(usbBuf, 5) }.getOrDefault(0)
            if (n <= 0) break
        }
    }

    override fun close() {
        runCatching { port?.close() }
        port = null
        pending.clear()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.obdmeter.USB_PERMISSION"

        /** 接続されている USB シリアル機器を探す。 */
        fun findDrivers(context: Context): List<UsbSerialDriver> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        }

        fun describe(device: UsbDevice): String =
            listOfNotNull(device.manufacturerName, device.productName).joinToString(" ")
                .ifBlank { "VID=%04X PID=%04X".format(device.vendorId, device.productId) }

        /** USB 機器へのアクセス許可をユーザーに求め、結果を待つ。 */
        suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (manager.hasPermission(device)) return true
            return suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.action != ACTION_USB_PERMISSION) return
                        runCatching { context.unregisterReceiver(this) }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) cont.resume(granted)
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                // Android 14 以降: FLAG_MUTABLE の PendingIntent は明示的 Intent (パッケージ指定) が必須
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
                manager.requestPermission(device, pi)
            }
        }
    }
}
