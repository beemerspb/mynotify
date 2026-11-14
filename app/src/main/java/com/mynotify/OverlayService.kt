package com.mynotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Показывает окно с потоком камеры поверх всех приложений.
 * UI окна (панель кнопок + ручка ресайза) появляется по тапу/нажатию кнопки
 * пульта и скрывается через 6 секунд. Кнопки: Закрыть | Во весь экран |
 * Скриншот | Запись. Ресайз — перетаскиванием за правый нижний угол.
 */
@androidx.media3.common.util.UnstableApi
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.mynotify.action.START"
        const val ACTION_SHOW = "com.mynotify.action.SHOW"
        const val ACTION_HIDE = "com.mynotify.action.HIDE"
        const val ACTION_STOP = "com.mynotify.action.STOP"
        const val ACTION_STATE = "com.mynotify.action.STATE"
        const val EXTRA_URL = "url"
        const val EXTRA_AUTOHIDE_SEC = "autohide_sec"
        const val DEFAULT_URL =
            "http://homeassistant.local:1984/api/stream.m3u8?src=Cam1"
        private const val UI_TIMEOUT_MS = 6000L

        /** Служба живёт в фоне (к ней подключается HA). */
        @Volatile var running = false
        /** Окно сейчас показано (не равно «служба запущена»). */
        @Volatile var windowShown = false
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: OverlayRoot? = null
    private var playerView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var params: WindowManager.LayoutParams? = null
    private var retries = 0

    private var uiBar: LinearLayout? = null
    private var uiButtons: List<TextView> = emptyList()
    private var baseWindowW = 0 // ширина окна при создании — эталон масштаба кнопок
    private var cornerView: View? = null
    private val uiHideRunnable = Runnable { hideUi() }

    private var fullscreen = false
    private var savedRect: IntArray? = null // x,y,w,h до полноэкранного режима

    private var recordThread: Thread? = null
    @Volatile private var recording = false
    private var recordBtn: TextView? = null

    private val hasTouch: Boolean by lazy {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        running = true
    }

    /**
     * Логика: служба фоновая и живёт, пока её явно не остановят
     * (ACTION_STOP или кнопка «Остановить службу»).
     * SHOW/HIDE только показывают и скрывают окно, на службу не влияют.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                hideWindow()
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> {
                hideWindow()
                startForegroundNotify("Служба работает — окно скрыто")
                return START_STICKY
            }
            ACTION_SHOW -> {
                startForegroundNotify("Камера поверх экрана")
                val url = intent.getStringExtra(EXTRA_URL)
                    ?: prefs.getString("url", null) ?: ""
                if (url.isBlank()) {
                    startForegroundNotify("Нет адреса потока — отправьте камеру из HA")
                    return START_STICKY
                }
                show(url)
                val sec = intent.getIntExtra(EXTRA_AUTOHIDE_SEC, 0)
                if (sec > 0) handler.postDelayed({ hideWindow() }, sec * 1000L)
            }
            else -> {
                // запуск службы (из приложения или рестарт системой) —
                // окно НЕ показываем, служба просто ждёт команд из HA
                startForegroundNotify("Служба запущена — ожидание команд из HA")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideWindow()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- окно ----------

    private fun show(url: String) {
        if (root == null) createWindow()
        prefs.edit().putString("url", url).apply()
        startStream(url)
    }

    /** Тёмная текстовая кнопка (без эмодзи) для полупрозрачной панели. */
    private fun toolButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#E6E6E6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.DEFAULT)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC2B2B2B"))
                cornerRadius = dp(6).toFloat()
            }
            isFocusable = !hasTouch
            isClickable = true
            setOnClickListener { showUi(); action() }
        }

    private fun createWindow() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.45f).toInt()
        val h = (w * 9f / 16f).toInt()

        val flags = if (hasTouch)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else
            0 // ТВ: окно в фокусе — нужен для D-pad
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            w, h, type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - w).coerceAtLeast(0) - dp(16)
            y = dp(16)
        }
        params = p

        val container = OverlayRoot(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            setOnKey { ev -> handleKey(ev) }
            if (hasTouch) {
                // правый нижний угол зарезервирован под ручку ресайза
                setInterceptSkip { x, y ->
                    x > width - dp(64) && y > height - dp(64)
                }
                val origin = IntArray(2)
                setOnDrag(
                    start = { params?.let { origin[0] = it.x; origin[1] = it.y } },
                    move = { dx, dy ->
                        val pp = params ?: return@setOnDrag
                        pp.x = origin[0] + dx; pp.y = origin[1] + dy
                        wm?.updateViewLayout(root, pp)
                    }
                )
            }
        }

        val pv = android.view.LayoutInflater.from(this)
            .inflate(R.layout.player_view_overlay, container, false) as PlayerView
        pv.setOnClickListener { showUi() }
        playerView = pv
        container.addView(pv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // панель кнопок внизу: полупрозрачный фон, скрыта по умолчанию
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(Color.parseColor("#66000000"))
            visibility = View.GONE
        }
        bar.addView(toolButton("Закрыть") { hideWindow() })
        bar.addView(toolButton("Во весь экран") { toggleFullscreen() })
        bar.addView(toolButton("Скриншот") { takeScreenshot() })
        val rec = toolButton("Запись") { toggleRecord() }
        recordBtn = rec
        bar.addView(rec)
        uiButtons = (0 until bar.childCount).mapNotNull { bar.getChildAt(it) as? TextView }
        val barLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        barLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        barLp.bottomMargin = dp(8)
        container.addView(bar, barLp)
        uiBar = bar

        // ручка ресайза за правый нижний угол
        val corner = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66FFFFFF"))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, dp(10).toFloat(), dp(10).toFloat())
            }
            visibility = View.GONE
        }
        val cLp = FrameLayout.LayoutParams(dp(28), dp(28))
        cLp.gravity = Gravity.BOTTOM or Gravity.END
        container.addView(corner, cLp)
        if (hasTouch) attachResize(corner)
        cornerView = corner

        root = container
        wm!!.addView(container, p)
        baseWindowW = w
        windowShown = true
        applyUiScale()
        showUi() // при показе панель видна, затем скроется через 6 с
        try {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        } catch (_: Exception) {}
    }

    private fun hideWindow() {
        windowShown = false
        recording = false
        handler.removeCallbacksAndMessages(null)
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null
        playerView = null
        uiBar = null
        uiButtons = emptyList()
        cornerView = null
        recordBtn = null
        player?.release()
        player = null
        try {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        } catch (_: Exception) {}
    }

    // ---------- UI по тапу, автоскрытие 6 с ----------

    private fun showUi() {
        uiBar?.visibility = View.VISIBLE
        cornerView?.visibility = if (hasTouch && !fullscreen) View.VISIBLE else View.GONE
        handler.removeCallbacks(uiHideRunnable)
        handler.postDelayed(uiHideRunnable, UI_TIMEOUT_MS)
    }

    private fun hideUi() {
        uiBar?.visibility = View.GONE
        cornerView?.visibility = View.GONE
    }

    /** Кнопки панели масштабируются вместе с размером окна. */
    private fun applyUiScale() {
        val w = params?.width ?: return
        if (baseWindowW == 0) return
        val k = (w.toFloat() / baseWindowW).coerceIn(1f, 3f)
        for (b in uiButtons) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * k)
            b.setPadding(dpf(14f * k), dpf(8f * k), dpf(14f * k), dpf(8f * k))
        }
        uiBar?.setPadding(dpf(6f * k), dpf(5f * k), dpf(6f * k), dpf(5f * k))
        cornerView?.let {
            val lp = it.layoutParams
            lp.width = dpf(28f * k); lp.height = dpf(28f * k)
            it.layoutParams = lp
        }
    }

    // ---------- плеер ----------

    private fun startStream(url: String) {
        player?.release()
        val ex = ExoPlayer.Builder(this).build()
        player = ex
        playerView?.player = ex
        ex.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                retries++
                if (retries <= 30) handler.postDelayed({
                    try {
                        ex.setMediaItem(MediaItem.fromUri(currentUrl()))
                        ex.prepare()
                    } catch (_: Exception) {}
                }, 2000)
            }
        })
        ex.setMediaItem(MediaItem.fromUri(url))
        ex.playWhenReady = true
        ex.prepare()
        retries = 0
    }

    private fun currentUrl(): String =
        prefs.getString("url", null) ?: DEFAULT_URL


    // ---------- действия панели ----------

    private fun toggleFullscreen() {
        val p = params ?: return
        if (!fullscreen) {
            savedRect = intArrayOf(p.x, p.y, p.width, p.height)
            val dm = resources.displayMetrics
            p.gravity = Gravity.TOP or Gravity.START
            p.x = 0; p.y = 0
            p.width = dm.widthPixels
            p.height = dm.heightPixels
            fullscreen = true
        } else {
            savedRect?.let { (x, y, w, h) ->
                p.x = x; p.y = y; p.width = w; p.height = h
            }
            fullscreen = false
        }
        wm?.updateViewLayout(root, p)
        applyUiScale()
        cornerView?.visibility =
            if (hasTouch && !fullscreen && uiBar?.visibility == View.VISIBLE)
                View.VISIBLE else View.GONE
    }

    private fun takeScreenshot() {
        val v = root ?: return
        if (v.width == 0 || v.height == 0) return
        val wasVisible = uiBar?.visibility == View.VISIBLE
        hideUi() // не снимать саму панель
        try {
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val f = File(dir, "shot_${stamp()}.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            flash(Color.parseColor("#66FFFFFF"))
            startForegroundNotify("Скриншот сохранён: ${f.name}")
        } catch (e: Exception) {
            startForegroundNotify("Скриншот не удался: ${e.message}")
        } finally {
            if (wasVisible) showUi()
        }
    }

    private fun toggleRecord() {
        if (recording) { recording = false; return }
        val hls = currentUrl()
        val mp4 = hls.replace("/api/stream.m3u8", "/api/stream.mp4")
        if (mp4 == hls) {
            startForegroundNotify("Запись доступна только для потоков go2rtc (HLS)")
            return
        }
        recording = true
        recordBtn?.apply { text = "Стоп"; setTextColor(Color.parseColor("#FF6B6B")) }
        val f = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "rec_${stamp()}.mp4")
        val t = Thread {
            var err: String? = null
            try {
                val conn = URL(mp4).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 15000
                conn.inputStream.use { ins ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (recording) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                err = e.message
            }
            handler.post {
                recording = false
                recordBtn?.apply {
                    text = "Запись"; setTextColor(Color.parseColor("#E6E6E6"))
                }
                startForegroundNotify(
                    if (err != null) "Запись прервана: $err"
                    else "Видео сохранено: ${f.name} (${f.length() / 1024} КБ)")
            }
        }
        t.isDaemon = true
        recordThread = t
        t.start()
    }

    private fun flash(color: Int) {
        val v = root ?: return
        val old = (v.background as? android.graphics.drawable.ColorDrawable)?.color
            ?: Color.TRANSPARENT
        v.setBackgroundColor(color)
        handler.postDelayed({ v.setBackgroundColor(old) }, 120)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ---------- управление ----------

    private fun handleKey(ev: KeyEvent): Boolean {
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        showUi() // любая кнопка пульта показывает панель
        val p = params ?: return false
        val step = dp(24)
        when (ev.keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideWindow(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> p.y -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> p.y += step
            KeyEvent.KEYCODE_DPAD_LEFT -> p.x -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> p.x += step
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> resize(1.15f)
            KeyEvent.KEYCODE_MINUS -> resize(1f / 1.15f)
            else -> return false
        }
        wm?.let { it.updateViewLayout(root, p) }
        return true
    }

    private fun resize(factor: Float) {
        val p = params ?: return
        p.width = max(dp(160), (p.width * factor).toInt())
        p.height = (p.width * 9f / 16f).toInt()
        wm?.updateViewLayout(root, p)
        applyUiScale()
    }

    /** Ресайз перетаскиванием за правый нижний угол (пропорции 16:9). */
    private fun attachResize(v: View) {
        var sx = 0f
        var startW = 0
        v.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; startW = p.width; true }
                MotionEvent.ACTION_MOVE -> {
                    val maxW = resources.displayMetrics.widthPixels
                    val nw = (startW + (e.rawX - sx)).toInt()
                        .coerceIn(dp(160), maxW)
                    p.width = nw
                    p.height = (nw * 9f / 16f).toInt()
                    wm?.updateViewLayout(root, p)
                    applyUiScale()
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun dpf(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    // ---------- уведомление FGS ----------

    private fun startForegroundNotify(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("cam", "Камера", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "cam")
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        val n: Notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MyNotify")
            .setContentText(text)
            .setContentIntent(pi)
            .build()
        startForeground(1, n)
    }

    /**
     * Корневой view: перехват кнопок пульта (ТВ) и перетаскивание (тач).
     * Движение выше slop перехватывается у детей; зона ручки ресайза — исключение.
     */
    private class OverlayRoot(ctx: Context) : FrameLayout(ctx) {
        var keyHandler: ((KeyEvent) -> Boolean)? = null
        fun setOnKey(h: (KeyEvent) -> Boolean) { keyHandler = h }

        private var dragStart: (() -> Unit)? = null
        private var dragMove: ((dx: Int, dy: Int) -> Unit)? = null
        fun setOnDrag(start: () -> Unit, move: (Int, Int) -> Unit) {
            dragStart = start; dragMove = move
        }

        private var interceptSkip: ((x: Float, y: Float) -> Boolean)? = null
        fun setInterceptSkip(skip: (Float, Float) -> Boolean) { interceptSkip = skip }

        private var sx = 0f
        private var sy = 0f
        private var intercepting = false
        private var skipThisGesture = false

        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            keyHandler?.invoke(event) ?: super.dispatchKeyEvent(event)

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; intercepting = false
                    skipThisGesture = interceptSkip?.invoke(ev.x, ev.y) ?: false
                    dragStart?.invoke()
                }
                MotionEvent.ACTION_MOVE -> if (!intercepting && !skipThisGesture &&
                    (abs(ev.rawX - sx) > 14 || abs(ev.rawY - sy) > 14)) intercepting = true
            }
            return intercepting
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_MOVE -> dragMove?.let {
                    it((ev.rawX - sx).toInt(), (ev.rawY - sy).toInt())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> intercepting = false
            }
            return intercepting
        }
    }
}
