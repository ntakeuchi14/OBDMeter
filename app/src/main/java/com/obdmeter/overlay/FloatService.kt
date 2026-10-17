package com.obdmeter.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.obdmeter.AppCore
import com.obdmeter.R
import com.obdmeter.data.AppSettings
import com.obdmeter.data.Dashboard
import com.obdmeter.session.ConnectionState
import com.obdmeter.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/** フロート表示の開始・終了 */
object FloatController {
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 「ほかのアプリの上に重ねて表示」の許可画面 */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))

    /** フロート表示を始める。[pageId] を指定するとそのページを出す */
    fun start(context: Context, pageId: Long?): Boolean = runCatching {
        val i = Intent(context, FloatService::class.java)
        if (pageId != null) i.putExtra(FloatService.EXTRA_PAGE, pageId)
        ContextCompat.startForegroundService(context, i)
    }.isSuccess

    fun stop(context: Context) {
        context.stopService(Intent(context, FloatService::class.java))
    }
}

/** ページ内のメーターを囲む範囲 (x, y, 列数, 行数)。フロート表示ではこの範囲だけを出す */
fun Dashboard.floatBounds(): IntArray {
    if (gauges.isEmpty()) return intArrayOf(0, 0, 8, 6)
    val x0 = gauges.minOf { it.x }
    val y0 = gauges.minOf { it.y }
    val x1 = gauges.maxOf { it.x + it.w }
    val y1 = gauges.maxOf { it.y + it.h }
    return intArrayOf(x0, y0, (x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))
}

/**
 * メーターを、ほかのアプリやホーム画面の上に小さなウィンドウで表示するサービス。
 * - ドラッグで移動、2 本指で拡大縮小 (操作ボタン表示中は右下の ◢ でも可)
 * - タップで操作ボタン (ページ切替・アプリを開く・閉じる) を表示
 * - アプリの画面が前面にある間は隠れる
 */
class FloatService : LifecycleService(), SavedStateRegistryOwner {

    companion object {
        const val EXTRA_PAGE = "page"
        const val ACTION_STOP = "com.obdmeter.FLOAT_STOP"
        private const val CHANNEL = "float"
        private const val NOTIFICATION_ID = 21
    }

    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var core: AppCore
    private lateinit var wm: WindowManager
    private var frame: DragFrame? = null
    private var params: WindowManager.LayoutParams? = null

    /** 操作ボタンを表示中か (Compose から参照) */
    private val controls = mutableStateOf(false)
    private var hideJob: Job? = null

    /** 指で大きさを変えている間の幅 (px)。null = 保存済みの幅 */
    private var liveWidth: Int? = null

    override fun onCreate() {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate()
        core = AppCore.get(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        core.floatActive.value = true

        // アプリが前面にある間は隠す
        lifecycleScope.launch {
            core.appVisible.collect { visible ->
                if (visible) removeWindow()
                else {
                    // アプリで表示していたページをそのままフロート表示する
                    core.appPage.value?.let { id ->
                        if (id != core.settings.value.floatPageId) core.repo.updateSettings { it.copy(floatPageId = id) }
                    }
                    addWindow()
                }
            }
        }
        // 大きさ・画面の点灯・表示ページを設定に合わせる
        lifecycleScope.launch {
            combine(core.settings, core.dashboards, core.cellAspect, core.session.state) { s, d, a, st ->
                Triple(s, d, a) to st
            }.collect { (t, st) ->
                val (s, pages, aspect) = t
                val page = pages.firstOrNull { it.id == s.floatPageId } ?: pages.firstOrNull()
                if (frame != null) core.floatPage.value = page?.id
                applyLayout(s, page, aspect, keepOn = s.keepScreenOn && st !is ConnectionState.Disconnected)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.hasExtra(EXTRA_PAGE) == true) {
            val page = intent.getLongExtra(EXTRA_PAGE, 0L)
            lifecycleScope.launch { core.repo.updateSettings { it.copy(floatPageId = page) } }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeWindow()
        core.floatActive.value = false
        core.floatPage.value = null
        // アプリの画面も出ていないなら、誰も見ていないので切断する (アプリに戻ったら自動で再接続)
        if (!core.appVisible.value && core.session.state.value != ConnectionState.Disconnected) {
            core.session.disconnect()
            core.reconnectOnReturn = true
        }
        super.onDestroy()
    }

    // ---- 通知 (フォアグラウンドサービスに必要) ----

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "フロート表示", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("メーターをフロート表示中")
            .setContentText("タップでアプリを開きます")
            .setContentIntent(open)
            .addAction(0, "閉じる", stop)
            .setOngoing(true)
            .build()
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }
    }

    // ---- ウィンドウ ----

    private fun addWindow() {
        if (frame != null || !FloatController.canDraw(this)) return
        val s = core.settings.value
        val p = WindowManager.LayoutParams(
            dp(s.floatWidthDp), dp(s.floatWidthDp / 2),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = s.floatX
            y = s.floatY
        }
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                FloatContent(
                    core = core,
                    showControls = controls.value,
                    onToggleControls = { showControls(!controls.value) },
                    onPage = ::changePage,
                    onOpenApp = ::openApp,
                    onClose = { stopSelf() },
                )
            }
        }
        val f = DragFrame(this).apply {
            setViewTreeLifecycleOwner(this@FloatService)
            setViewTreeSavedStateRegistryOwner(this@FloatService)
            addView(compose)
        }
        runCatching { wm.addView(f, p) }.onSuccess {
            frame = f
            params = p
            val pages = core.dashboards.value
            val page = pages.firstOrNull { it.id == s.floatPageId } ?: pages.firstOrNull()
            core.floatPage.value = page?.id
            applyLayout(s, page, core.cellAspect.value, keepOn = s.keepScreenOn &&
                core.session.state.value !is ConnectionState.Disconnected)
        }
    }

    private fun removeWindow() {
        frame?.let { runCatching { wm.removeView(it) } }
        frame = null
        params = null
        core.floatPage.value = null
        controls.value = false
    }

    /** 幅とメーターの並びから、ウィンドウの大きさ・位置を決める */
    private fun applyLayout(s: AppSettings, page: Dashboard?, cellAspect: Float, keepOn: Boolean) {
        val f = frame ?: return
        val p = params ?: return
        val b = page?.floatBounds() ?: intArrayOf(0, 0, 8, 6)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val ratio = (b[2] * cellAspect) / b[3] // 幅 / 高さ
        var w = (liveWidth ?: dp(s.floatWidthDp)).coerceIn(dp(80), screenW)
        var h = (w / ratio).roundToInt()
        if (h > screenH) { h = screenH; w = (h * ratio).roundToInt() }
        p.width = w
        p.height = h
        if (liveWidth == null) {
            p.x = s.floatX.coerceIn(0, (screenW - w).coerceAtLeast(0))
            p.y = s.floatY.coerceIn(0, (screenH - h).coerceAtLeast(0))
        }
        p.flags = if (keepOn) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        runCatching { wm.updateViewLayout(f, p) }
    }

    private fun showControls(show: Boolean) {
        controls.value = show
        hideJob?.cancel()
        if (show) hideJob = lifecycleScope.launch { delay(5000); controls.value = false }
    }

    private fun changePage(delta: Int) {
        showControls(true)
        val pages = core.dashboards.value
        if (pages.isEmpty()) return
        val cur = pages.indexOfFirst { it.id == core.settings.value.floatPageId }.coerceAtLeast(0)
        val next = pages[(cur + delta).mod(pages.size)]
        lifecycleScope.launch { core.repo.updateSettings { it.copy(floatPageId = next.id) } }
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    /** 位置・大きさを保存する (指を離したとき) */
    private fun savePlacement() {
        val p = params ?: return
        val widthDp = (p.width / resources.displayMetrics.density).roundToInt()
        liveWidth = null
        lifecycleScope.launch {
            core.repo.updateSettings { it.copy(floatX = p.x, floatY = p.y, floatWidthDp = widthDp) }
        }
    }

    /**
     * ウィンドウの移動・拡大縮小を受け持つ枠。
     * 指が少し動いたらドラッグとみなして横取りし、動かなければ中のメーター (タップで操作ボタン) に渡す。
     */
    @SuppressLint("ViewConstructor")
    private inner class DragFrame(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var startW = 0
        private var pinchStart = 0f
        private var corner = false
        /** 0 = なし, 1 = 移動, 2 = ◢ で大きさ変更, 3 = 2 本指で拡大縮小 */
        private var mode = 0

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean = track(e)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            track(e)
            return true
        }

        private fun track(e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y; startW = p.width
                    corner = controls.value && e.x > width - dp(44) && e.y > height - dp(44)
                    mode = 0
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    pinchStart = spacing(e)
                    startW = p.width
                    mode = 3
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (mode == 0 && hypot(dx, dy) > slop) mode = if (corner) 2 else 1
                    when (mode) {
                        1 -> {
                            val screenW = resources.displayMetrics.widthPixels
                            val screenH = resources.displayMetrics.heightPixels
                            p.x = (startX + dx).roundToInt().coerceIn(0, (screenW - p.width).coerceAtLeast(0))
                            p.y = (startY + dy).roundToInt().coerceIn(0, (screenH - p.height).coerceAtLeast(0))
                            runCatching { wm.updateViewLayout(this, p) }
                        }
                        2 -> resizeTo((startW + dx).roundToInt())
                        3 -> if (e.pointerCount >= 2 && pinchStart > 0f) {
                            resizeTo((startW * spacing(e) / pinchStart).roundToInt())
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = mode
                    mode = 0
                    if (was != 0) savePlacement()
                    return was != 0
                }
            }
            return mode != 0
        }

        private fun resizeTo(w: Int) {
            liveWidth = w.coerceIn(dp(80), resources.displayMetrics.widthPixels)
            val s = core.settings.value
            val pages = core.dashboards.value
            val page = pages.firstOrNull { it.id == s.floatPageId } ?: pages.firstOrNull()
            applyLayout(s, page, core.cellAspect.value, keepOn = params?.flags?.and(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
        }

        private fun spacing(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }
}
