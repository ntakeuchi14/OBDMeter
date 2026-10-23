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
import androidx.compose.runtime.MutableState
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
import com.obdmeter.data.FloatPlacement
import com.obdmeter.data.GaugeConfig
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

/**
 * フロート表示のウィンドウ 1 つ分の単位のキー。
 * 単独のメーターはメーターの id、グループはページとグループ番号から作る負の値 (メーターの id と重ならない)
 */
fun floatUnitKey(page: Dashboard, g: GaugeConfig): Long = g.floatGroup?.let { -(page.id * 100 + it) } ?: g.id

/** ページのメーターを、フロート表示のウィンドウ単位にまとめる (並び順は保つ) */
fun Dashboard.floatUnits(): Map<Long, List<GaugeConfig>> =
    gauges.filter { it.floatShow }.groupBy { floatUnitKey(this, it) }

/** メーターを囲む範囲 (x, y, 列数, 行数) */
fun List<GaugeConfig>.cellBounds(): IntArray {
    if (isEmpty()) return intArrayOf(0, 0, 1, 1)
    val x0 = minOf { it.x }
    val y0 = minOf { it.y }
    val x1 = maxOf { it.x + it.w }
    val y1 = maxOf { it.y + it.h }
    return intArrayOf(x0, y0, (x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))
}

/**
 * メーターを 1 つずつ (グループにしたものはまとめて)、ほかのアプリやホーム画面の上に小さなウィンドウ (ガジェット) で表示するサービス。
 * - アプリを離れたときに表示していたページの、各メーターがそれぞれ独立したウィンドウになる
 * - ドラッグで移動、2 本指で拡大縮小 (操作ボタン表示中は右下の ◢ でも可)。位置と大きさはメーターごとに保存
 * - タップで操作ボタン (アプリを開く・このメーターを閉じる)、ダブルタップでアプリを開く
 * - アプリの画面が前面にある間は隠れる
 */
class FloatService : LifecycleService(), SavedStateRegistryOwner {

    companion object {
        const val EXTRA_PAGE = "page"
        const val ACTION_STOP = "com.obdmeter.FLOAT_STOP"
        private const val CHANNEL = "float"
        private const val NOTIFICATION_ID = 21
        /** 位置を保存していないメーターを並べるときの縮尺 (アプリのダッシュボードに対する比率) */
        private const val DEFAULT_SCALE = 0.45f
    }

    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var core: AppCore
    private lateinit var wm: WindowManager

    /** ウィンドウ 1 つ分 (単独のメーター、またはグループ)。[key] は [floatUnitKey] */
    private inner class Gadget(val key: Long) {
        var frame: DragFrame? = null
        var params: WindowManager.LayoutParams? = null
        val controls: MutableState<Boolean> = mutableStateOf(false)
        var hideJob: Job? = null
        /** 指で大きさを変えている間の幅 (px)。null = 保存済みの幅 */
        var liveWidth: Int? = null
        /** 指で動かしている最中 (設定の変化で位置を戻さない) */
        var dragging = false
    }

    private val gadgets = linkedMapOf<Long, Gadget>()
    /** ✕ で閉じたウィンドウ ([floatUnitKey])。次にアプリを離れたときはまた表示する */
    private val closed = mutableSetOf<Long>()
    /** アプリが隠れていて、ウィンドウを出す状態か */
    private var showing = false

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
                if (visible) {
                    showing = false
                    closed.clear()
                    removeAll()
                } else {
                    // アプリで表示していたページのメーターを出す
                    core.appPage.value?.let { id ->
                        if (id != core.settings.value.floatPageId) core.repo.updateSettings { it.copy(floatPageId = id) }
                    }
                    showing = true
                    sync()
                }
            }
        }
        // メーターの追加・削除、大きさ・デザイン・画面の点灯を設定に合わせる
        lifecycleScope.launch {
            combine(core.settings, core.dashboards, core.cellAspect, core.session.state) { _, _, _, _ -> }
                .collect { sync() }
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
        removeAll()
        core.floatActive.value = false
        core.floatPage.value = null
        // アプリの画面も出ていないなら、誰も見ていないので、しばらくして切断する (アプリに戻ったら再接続)
        if (!core.appVisible.value) core.scheduleIdleDisconnect()
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
            .setContentTitle("メーターのフロート表示")
            .setContentText("アプリを離れるとメーターを表示します。タップでアプリを開きます")
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

    private fun currentPage(): Dashboard? {
        val pages = core.dashboards.value
        return pages.firstOrNull { it.id == core.settings.value.floatPageId } ?: pages.firstOrNull()
    }

    /** 表示すべきメーターとウィンドウを合わせる */
    private fun sync() {
        if (!showing || !FloatController.canDraw(this)) {
            removeAll()
            return
        }
        val s = core.settings.value
        val page = currentPage()
        core.floatPage.value = page?.id
        val want = page?.floatUnits().orEmpty().filterKeys { it !in closed }
        gadgets.keys.filter { it !in want.keys }.forEach { k -> gadgets.remove(k)?.let { remove(it) } }
        if (want.isEmpty()) {
            // 全部閉じたらフロート表示を終える (次にアプリを開いたとき、また待機する)
            if (page != null && page.gauges.any { it.floatShow }) stopSelf()
            return
        }
        val keepOn = s.keepScreenOn && core.session.state.value !is ConnectionState.Disconnected
        want.forEach { (key, members) ->
            val gadget = gadgets.getOrPut(key) { Gadget(key) }
            if (gadget.frame == null) add(gadget)
            if (!gadget.dragging) layout(gadget, members, s, keepOn)
        }
    }

    private fun add(gadget: Gadget) {
        val p = WindowManager.LayoutParams(
            dp(120), dp(120),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                FloatGauge(
                    core = core,
                    unitKey = gadget.key,
                    showControls = gadget.controls.value,
                    onToggleControls = { showControls(gadget, !gadget.controls.value) },
                    onOpenApp = ::openApp,
                    onClose = { closeUnit(gadget.key) },
                )
            }
        }
        val f = DragFrame(this, gadget).apply {
            setViewTreeLifecycleOwner(this@FloatService)
            setViewTreeSavedStateRegistryOwner(this@FloatService)
            addView(compose)
        }
        runCatching { wm.addView(f, p) }.onSuccess {
            gadget.frame = f
            gadget.params = p
        }
    }

    private fun remove(gadget: Gadget) {
        gadget.hideJob?.cancel()
        gadget.frame?.let { runCatching { wm.removeView(it) } }
        gadget.frame = null
        gadget.params = null
    }

    private fun removeAll() {
        gadgets.values.forEach { remove(it) }
        gadgets.clear()
        core.floatPage.value = null
    }

    private fun closeUnit(key: Long) {
        closed += key
        sync()
    }

    /** 位置を保存していないウィンドウの既定の位置・幅: アプリのダッシュボードの並びを縮小したもの */
    private fun defaultPlacement(b: IntArray): FloatPlacement {
        val m = resources.displayMetrics
        val cellW = m.widthPixels / 24f * DEFAULT_SCALE
        val cellH = cellW / core.cellAspect.value
        return FloatPlacement(
            x = (dp(16) + b[0] * cellW).roundToInt(),
            y = (dp(80) + b[1] * cellH).roundToInt(),
            widthDp = (b[2] * cellW / m.density).roundToInt(),
        )
    }

    /** メーターの並びと保存済みの位置から、ウィンドウの大きさ・位置を決める */
    private fun layout(gadget: Gadget, members: List<GaugeConfig>, s: AppSettings, keepOn: Boolean) {
        val f = gadget.frame ?: return
        val p = gadget.params ?: return
        val b = members.cellBounds()
        val place = s.floatPlacements[gadget.key] ?: defaultPlacement(b)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val ratio = (b[2] * core.cellAspect.value) / b[3] // 幅 / 高さ
        var w = (gadget.liveWidth ?: dp(place.widthDp)).coerceIn(dp(48), screenW)
        var h = (w / ratio).roundToInt()
        if (h > screenH) { h = screenH; w = (h * ratio).roundToInt() }
        p.width = w
        p.height = h
        if (gadget.liveWidth == null) {
            p.x = place.x.coerceIn(0, (screenW - w).coerceAtLeast(0))
            p.y = place.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
        }
        p.flags = if (keepOn) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        runCatching { wm.updateViewLayout(f, p) }
    }

    private fun showControls(gadget: Gadget, show: Boolean) {
        gadget.controls.value = show
        gadget.hideJob?.cancel()
        if (show) gadget.hideJob = lifecycleScope.launch { delay(5000); gadget.controls.value = false }
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
    private fun savePlacement(gadget: Gadget) {
        val p = gadget.params ?: return
        val place = FloatPlacement(p.x, p.y, (p.width / resources.displayMetrics.density).roundToInt())
        lifecycleScope.launch {
            core.repo.updateSettings { st ->
                // 消えたメーター・グループの記録は残さない
                val alive = core.dashboards.value.flatMap { d -> d.floatUnits().keys }.toSet()
                st.copy(floatPlacements = (st.floatPlacements + (gadget.key to place)).filterKeys { it in alive })
            }
            gadget.liveWidth = null
            gadget.dragging = false
        }
    }

    /**
     * ウィンドウの移動・拡大縮小を受け持つ枠。
     * 指が少し動いたらドラッグとみなして横取りし、動かなければ中のメーター (タップで操作ボタン) に渡す。
     */
    @SuppressLint("ViewConstructor")
    private inner class DragFrame(context: Context, private val gadget: Gadget) : FrameLayout(context) {
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
            val p = gadget.params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y; startW = p.width
                    corner = gadget.controls.value && e.x > width - dp(40) && e.y > height - dp(40)
                    mode = 0
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    pinchStart = spacing(e)
                    startW = p.width
                    mode = 3
                    gadget.dragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (mode == 0 && hypot(dx, dy) > slop) {
                        mode = if (corner) 2 else 1
                        gadget.dragging = true
                    }
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
                    if (was != 0) savePlacement(gadget)
                    return was != 0
                }
            }
            return mode != 0
        }

        private fun resizeTo(w: Int) {
            val members = currentPage()?.floatUnits()?.get(gadget.key) ?: return
            gadget.liveWidth = w.coerceIn(dp(48), resources.displayMetrics.widthPixels)
            val keepOn = (gadget.params?.flags ?: 0) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            layout(gadget, members, core.settings.value, keepOn)
        }

        private fun spacing(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }
}
