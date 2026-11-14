package com.mynotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONObject

/**
 * Показывает окно с потоком камеры поверх всех приложений.
 * UI окна (панель кнопок-иконок + ручка ресайза) появляется по тапу/нажатию
 * кнопки пульта и скрывается через 6 секунд. Кнопки: Закрыть | Во весь экран.
 * Кнопки — иконки, размер масштабируется вместе с размером окна.
 * Ресайз — перетаскиванием за правый нижний угол.
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
        /** Последний успешный ответ HA (мс) и привязан ли девайс. */
        @Volatile var lastHaOk = 0L
        @Volatile var bound = false
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
    private var uiIcons: List<ImageButton> = emptyList()
    private var fsBtn: ImageButton? = null
    private var baseWindowW = 0 // ширина окна при создании — эталон масштаба кнопок
    private var cornerView: View? = null
    private val uiHideRunnable = Runnable { hideUi() }

    private var fullscreen = false
    private var savedRect: IntArray? = null // x,y,w,h до полноэкранного режима

    private val hasTouch: Boolean by lazy {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        running = true
        startHaLoop()
    }

    // ---------- связь с Home Assistant ----------
    // Приложение САМО подключается к HA: регистрируется по коду и опрашивает
    // очередь команд. Никакого ADB не нужно — достаточно запуска службы.

    private var haThread: Thread? = null

    fun haBase(): String =
        (prefs.getString("ha_url", "http://homeassistant.local:8123") ?: "").trimEnd('/')

    /** Необязательный внешний адрес HA (https-домен/порт). Пусто — только LAN. */
    fun haExt(): String =
        (prefs.getString("ha_url_ext", "") ?: "").trim().trimEnd('/')

    /**
     * Активный базовый адрес: локальный, а если он недоступен (устройство
     * вне домашней сети) — внешний. Кандидаты перебираются по кругу.
     */
    private fun haCandidates(): List<String> =
        listOf(haBase(), haExt()).filter { it.isNotBlank() }

    fun deviceId(): String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun pairCode(): String = prefs.getString("pair_code", null)
        ?: (100000 + java.util.Random().nextInt(900000)).toString().also {
            prefs.edit().putString("pair_code", it).apply()
        }

    private fun http(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            .also { conn.disconnect() }
    }

    private fun startHaLoop() {
        if (haThread?.isAlive == true) return
        haThread = Thread {
            var lastReg = 0L
            var idx = 0 // индекс активного кандидата в haCandidates()
            while (!Thread.currentThread().isInterrupted) {
                val bases = haCandidates()
                if (bases.isEmpty()) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    continue
                }
                if (idx >= bases.size) idx = 0
                val base = bases[idx]
                var ok = false
                try {
                    if (System.currentTimeMillis() - lastReg > 10000) {
                        val body = JSONObject()
                            .put("device_id", deviceId())
                            .put("code", pairCode())
                            .put("name", Build.MODEL)
                            .toString()
                        http("POST", "$base/api/mynotify/register", body)
                        lastReg = System.currentTimeMillis()
                    }
                    val resp = JSONObject(
                        http(
                            "GET",
                            "$base/api/mynotify/poll?device=${deviceId()}&code=${pairCode()}",
                            null
                        )
                    )
                    ok = resp.optBoolean("ok")
                    if (ok) {
                        lastHaOk = System.currentTimeMillis()
                        bound = resp.optBoolean("bound")
                        when (resp.optString("cmd")) {
                            "show" -> handler.post {
                                startForegroundNotify("Камера поверх экрана")
                                show(resp.getString("url"))
                                val sec = resp.optInt("autohide", 0)
                                if (sec > 0) handler.postDelayed(
                                    { hideWindow() }, sec * 1000L
                                )
                            }
                            "hide" -> handler.post {
                                hideWindow()
                                startForegroundNotify("Служба работает — окно скрыто")
                            }
                            "fullscreen" -> handler.post {
                                if (windowShown) toggleFullscreen()
                            }
                        }
                    }
                } catch (_: Exception) {
                    lastHaOk = 0L
                    bound = false
                }
                if (!ok && bases.size > 1) {
                    // текущий адрес недоступен — пробуем следующий (LAN <-> вне сети)
                    idx = (idx + 1) % bases.size
                    lastReg = 0L
                }
                try {
                    sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; name = "mynotify-ha"; start() }
    }

    /**
     * Логика: служба фоновая и живёт, пока её явно не остановят
     * (ACTION_STOP или кнопка «Остановить службу»).
     * SHOW/HIDE только показывают и скрывают окно, на службу не влияют.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                haThread?.interrupt()
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
        haThread?.interrupt()
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
        playPopupSound()
    }

    /** Звук из системных рингтонов — играется при показе окна. */
    private fun playPopupSound() {
        val saved = prefs.getString("sound_uri", null) ?: return
        if (saved.isBlank() || saved == "silent") return
        try {
            RingtoneManager.getRingtone(applicationContext, Uri.parse(saved))?.play()
        } catch (_: Exception) {}
    }

    /** Круглая полупрозрачная кнопка-иконка для панели окна. */
    private fun iconButton(resIcon: Int, desc: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(resIcon)
            setColorFilter(Color.parseColor("#FFFFFF"))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = desc
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B31C1C1E"))
                cornerRadius = dp(22).toFloat()
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
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#4D000000"))
            visibility = View.GONE
        }
        bar.addView(iconButton(R.drawable.ic_close, "Закрыть") { hideWindow() })
        val fs = iconButton(R.drawable.ic_fullscreen, "Во весь экран") { toggleFullscreen() }
        fsBtn = fs
        bar.addView(fs)
        uiIcons = (0 until bar.childCount).mapNotNull { bar.getChildAt(it) as? ImageButton }
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
        handler.removeCallbacksAndMessages(null)
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null
        playerView = null
        uiBar = null
        uiIcons = emptyList()
        fsBtn = null
        cornerView = null
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

    /**
     * Кнопки-иконки масштабируются вместе с размером окна: при маленьком
     * окне они уменьшаются, чтобы панель влезала целиком.
     */
    private fun applyUiScale() {
        val w = params?.width ?: return
        if (baseWindowW == 0) return
        val k = (w.toFloat() / baseWindowW).coerceIn(0.45f, 3f)
        val size = dpf((44f * k).coerceIn(26f, 72f))
        val pad = (size * 0.24f).toInt()
        for (b in uiIcons) {
            val lp = b.layoutParams as LinearLayout.LayoutParams
            lp.width = size; lp.height = size
            b.layoutParams = lp
            b.setPadding(pad, pad, pad, pad)
            (b.background as? GradientDrawable)?.cornerRadius = size / 2f
        }
        uiBar?.apply {
            val lp = layoutParams
            lp.height = size + dpf(10f * k)
            layoutParams = lp
            setPadding(dpf(8f * k), dpf(5f * k), dpf(8f * k), dpf(5f * k))
        }
        (uiBar?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.height = size + dpf(10f * k)
            uiBar?.layoutParams = it
        }
        val gap = dpf(6f * k)
        for (i in 1 until (uiBar?.childCount ?: 0)) {
            val lp = uiBar!!.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            lp.marginStart = gap
            uiBar!!.getChildAt(i).layoutParams = lp
        }
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
        fsBtn?.setImageResource(
            if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        wm?.updateViewLayout(root, p)
        applyUiScale()
        cornerView?.visibility =
            if (hasTouch && !fullscreen && uiBar?.visibility == View.VISIBLE)
                View.VISIBLE else View.GONE
    }

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
