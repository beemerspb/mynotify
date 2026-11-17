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
import android.widget.TextView
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
        /** Переприменить прозрачность окна/видео после правки в настройках. */
        const val ACTION_ALPHA = "com.mynotify.action.ALPHA"
        /** Переприменить настройку показа кнопок в окне (живое применение). */
        const val ACTION_UI = "com.mynotify.action.UI"
        /** Пауза службы: не показывать окна заданное число минут. */
        const val ACTION_SUSPEND = "com.mynotify.action.SUSPEND"
        /** Снять паузу службы. */
        const val ACTION_SUSPEND_CANCEL = "com.mynotify.action.SUSPEND_CANCEL"
        const val EXTRA_MINUTES = "minutes"
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

        /** Адрес HA, по которому служба сейчас успешно ходит (LAN или внешний). */
        @Volatile var activeBase = ""
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: OverlayRoot? = null
    private var playerView: PlayerView? = null
    private var playerErrText: TextView? = null
    private var player: ExoPlayer? = null
    private var params: WindowManager.LayoutParams? = null
    private var retries = 0

    private var uiBar: LinearLayout? = null
    private var uiIcons: List<ImageButton> = emptyList()
    private var fsBtn: ImageButton? = null
    private var volBtn: ImageButton? = null
    @Volatile private var muted = false
    private var baseWindowW = 0 // ширина окна при создании — эталон масштаба кнопок
    private var cornerViews: List<View> = emptyList()
    private val uiHideRunnable = Runnable { hideUi() }

    private var fullscreen = false
    private var savedRect: IntArray? = null // x,y,w,h до полноэкранного режима

    // ---------- пауза службы (окна не показываются заданное время) ----------

    private fun suspendUntil(): Long =
        prefs.getLong("suspend_until", 0L) ?: 0L

    private fun suspendActive(): Boolean =
        suspendUntil() > System.currentTimeMillis()

    private fun suspendLabel(): String {
        val until = suspendUntil()
        if (until <= System.currentTimeMillis()) return "Пауза не активна"
        return "Пауза до " + suspendTimeLabel()
    }

    /** Время окончания паузы в формате HH:mm. */
    private fun suspendTimeLabel(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(suspendUntil()))

    /** Включить паузу службы на N минут (окна не появляются). */
    private fun applySuspend(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        prefs.edit().putLong("suspend_until", until).apply()
        hideWindow()
        hideSuspendPicker()
        startForegroundNotify("Пауза службы — окна не показываются до " +
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(until)))
        try {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        } catch (_: Exception) {}
    }

    /** Снять паузу службы. */
    private fun cancelSuspend() {
        prefs.edit().putLong("suspend_until", 0L).apply()
        hideSuspendPicker()
        startForegroundNotify(
            if (windowShown) "Камера поверх экрана"
            else "Служба работает — окно скрыто"
        )
        try {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        } catch (_: Exception) {}
    }

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
                            .put(
                                "ver",
                                packageManager.getPackageInfo(packageName, 0).versionName
                                    ?: "?"
                            )
                            .toString()
                        http("POST", "$base/api/mynotify/register", body)
                        lastReg = System.currentTimeMillis()
                    }
                    val resp = JSONObject(
                        http(
                            "GET",
                            "$base/api/mynotify/poll?device=${deviceId()}&code=${pairCode()}&state=${if (windowShown) 1 else 0}",
                            null
                        )
                    )
                    ok = resp.optBoolean("ok")
                    if (ok) {
                        lastHaOk = System.currentTimeMillis()
                        bound = resp.optBoolean("bound")
                        // пауза истекла — возвращаем обычный режим
                        if (!suspendActive() &&
                            (prefs.getLong("suspend_until", 0L) ?: 0L) != 0L
                        ) {
                            prefs.edit().putLong("suspend_until", 0L).apply()
                            startForegroundNotify("Служба работает — окно скрыто")
                        }
                        // поток проксируется через HA — берём тот адрес HA,
                        // по которому сейчас реально ходим (LAN или внешний)
                        if (base != activeBase) {
                            activeBase = base
                            if (windowShown) handler.post { startStream(pickUrl()) }
                        }
                        when (resp.optString("cmd")) {
                            "show" -> handler.post {
                                if (suspendActive()) {
                                    // пауза службы — окна не показываем
                                    startForegroundNotify(
                                        "Пауза службы — окна не показываются до " +
                                            suspendTimeLabel()
                                    )
                                    return@post
                                }
                                startForegroundNotify("Камера поверх экрана")
                                val ed = prefs.edit()
                                    .putString("url", resp.optString("url", ""))
                                    .putString(
                                        "stream_path",
                                        resp.optString("path", "")
                                    )
                                // mute из HA-команды синхронизируется с настройкой
                                if (resp.has("mute"))
                                    ed.putBoolean("mute_on_start", resp.optInt("mute", 1) == 1)
                                ed.apply()
                                show(pickUrl())
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
            ACTION_ALPHA -> {
                // настройки прозрачности изменили, пока окно открыто
                startForegroundNotify(
                    if (windowShown) "Камера поверх экрана"
                    else "Служба работает — окно скрыто"
                )
                handler.post { applyAlpha() }
            }
            ACTION_UI -> {
                // опцию «не показывать кнопки» переключили, пока окно открыто
                startForegroundNotify(
                    if (windowShown) "Камера поверх экрана"
                    else "Служба работает — окно скрыто"
                )
                handler.post { applyUiVisibility() }
            }
            ACTION_SUSPEND -> {
                // пауза службы на N минут — окна не показываются
                val min = intent.getIntExtra(EXTRA_MINUTES, 60).coerceIn(1, 24 * 60)
                handler.post { applySuspend(min) }
            }
            ACTION_SUSPEND_CANCEL -> {
                handler.post { cancelSuspend() }
            }
            ACTION_SHOW -> {
                startForegroundNotify("Камера поверх экрана")
                if (suspendActive()) {
                    // пауза службы активна — окно не показываем
                    startForegroundNotify(
                        "Пауза службы — окна не показываются до " + suspendTimeLabel()
                    )
                    return START_STICKY
                }
                val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
                    ?: pickUrl()
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
        if (suspendActive()) {
            // пауза службы — окно не показываем
            startForegroundNotify(
                "Пауза службы — окна не показываются до " + suspendTimeLabel()
            )
            return
        }
        if (root == null) createWindow()
        startStream(url)
        playPopupSound()
    }

    /**
     * Адрес потока: через прокси активного HA (работает и из локальной сети,
     * и снаружи), иначе — последний прямой URL от интеграции (старая версия).
     */
    private fun pickUrl(): String {
        val path = prefs.getString("stream_path", "") ?: ""
        if (path.isNotEmpty()) {
            val base = activeBase.ifBlank { haBase() }
            if (base.isNotEmpty()) return base + path
        }
        return prefs.getString("url", "") ?: ""
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
    // Мигающий красный контур фокуса (видно на ТВ с пульта). Навешивается
    // как foreground — фон кнопки (пилюля/скругления) не трогает.
    // ВАЖНО: аниматор хранится в замыкании, НЕ в setTag: setTag(int) требует
    // ключ >= 0x01000000, а View.generateViewId() отдаёт значения ниже —
    // на фокус с пульта падал с IllegalArgumentException (краш на ТВ v0.7.11).
    private fun attachFocusBlink(v: View, radiusDp: Int) {
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), Color.parseColor("#FFFF3B30"))
            cornerRadius = dp(radiusDp).toFloat()
        }
        ring.alpha = 0
        if (Build.VERSION.SDK_INT >= 23) v.foreground = ring
        var anim: android.animation.ObjectAnimator? = null
        v.setOnFocusChangeListener { _, focused ->
            anim?.cancel()
            anim = null
            ring.alpha = 0
            if (focused) {
                if (Build.VERSION.SDK_INT >= 23) {
                    ring.alpha = 255
                    anim = android.animation.ObjectAnimator.ofInt(ring, "alpha", 255, 0, 255)
                        .apply {
                            duration = 900
                            repeatCount = android.animation.ObjectAnimator.INFINITE
                        }
                        .also { it.start() }
                }
            }
        }
    }

    private fun iconButton(resIcon: Int, desc: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(resIcon)
            setColorFilter(Color.parseColor("#FFFFFF"))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = desc
            isFocusable = true
            isClickable = true
            // Фокус для пульта ДУ: классический StateListDrawable — обе
            // состояния одна и та же скруглённая пилюля, фокус = синий
            // цвет + белая обводка. Без замены фона на лету и масштабов.
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#FF2979FF"))
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(3), Color.parseColor("#FFFFFFFF"))
                    }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#B31C1C1E"))
                        cornerRadius = dp(22).toFloat()
                    }
                )
                }
                attachFocusBlink(this, 22)
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
            setBackgroundColor(windowBgColor())
            setOnKey { ev -> handleKey(ev) }
            if (hasTouch) {
                // углы с треугольничками ресайза зарезервированы от перетаскивания
                setInterceptSkip { x, y ->
                    (x < dp(56) && y > height - dp(56)) ||
                        (x > width - dp(56) && y < dp(56))
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
        pv.setOnClickListener {
            // тап по видео закрывает открытый список сроков
            hideSuspendPicker()
            showUi()
        }
        // при выключенных кнопках обычный тап ничего не делает —
        // долгий тап принудительно показывает панель
        pv.setOnLongClickListener {
            showUi(force = true)
            true
        }
        pv.alpha = videoAlpha()
        playerView = pv
        container.addView(pv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // причина отсутствия картинки — прямо по центру окна (диагностика)
        val errText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        container.addView(errText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        playerErrText = errText

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
        val vol = iconButton(R.drawable.ic_volume_off, "Звук вкл/выкл") { toggleMute() }
        volBtn = vol
        bar.addView(vol)
        val timer = iconButton(R.drawable.ic_timer, "Пауза службы") { showSuspendPicker() }
        bar.addView(timer)
        uiIcons = (0 until bar.childCount).mapNotNull { bar.getChildAt(it) as? ImageButton }
        val barLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        barLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        barLp.bottomMargin = dp(8)
        // увеличенные при фокусе кнопки не должны обрезаться панелью
        bar.clipChildren = false
        bar.clipToPadding = false
        container.clipChildren = false
        container.clipToPadding = false
        container.addView(bar, barLp)
        uiBar = bar

        // ручки ресайза: маленькие треугольнички в левом нижнем и правом
        // верхнем углах (маркеры тянутся за сам угол)
        fun mkCorner(gravity: Int, rot: Float): View {
            val v = View(this)
            v.background = resources.getDrawable(R.drawable.ic_resize_corner, null).mutate()
            v.rotation = rot
            v.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(dp(16), dp(16))
            lp.gravity = gravity
            container.addView(v, lp)
            return v
        }
        val cBL = mkCorner(Gravity.BOTTOM or Gravity.START, 0f)
        val cTR = mkCorner(Gravity.TOP or Gravity.END, 180f)
        if (hasTouch) {
            attachResize(cBL, flip = true)   // левый нижний: тянем влево — шире
            attachResize(cTR, flip = false)  // правый верхний: тянем вправо — шире
        }
        cornerViews = listOf(cBL, cTR)

        root = container
        try {
            wm!!.addView(container, p)
            // факт успешного показа окна — главный признак разрешения
            // (на ТВ canDrawOverlays может врать в обе стороны)
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putBoolean("overlay_ok", true).apply()
            baseWindowW = w
            windowShown = true
            applyUiScale()
            showUi() // при показе панель видна, затем скроется через 6 с
        } catch (e: Exception) {
            // нет реального разрешения (BadToken) — не класть сервис,
            // окно просто не покажем; настройки честно потребуют разрешение
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putBoolean("overlay_ok", false).apply()
            root = null
            windowShown = false
        }
        try {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        } catch (_: Exception) {}
    }

    // ---------- прозрачность (настройка «Окно камеры») ----------
    // Prefs: overlay_alpha — процент прозрачности 0..100 на окно и видео
    // одним ползунком; 0 = как раньше (фон #CC000000, видео полностью видно).

    private fun transparencyPct(): Int =
        (prefs.getInt("overlay_alpha", 0) ?: 0).coerceIn(0, 100)

    /** Множитель альфы 1..0 из процента прозрачности. */
    private fun alphaFromPct(pct: Int): Float = 1f - pct / 100f

    private fun windowBgColor(): Int =
        Color.argb((204 * alphaFromPct(transparencyPct())).toInt(), 0, 0, 0)

    private fun videoAlpha(): Float = alphaFromPct(transparencyPct())

    /** Применить прозрачность к открытому окну (или проигнорировать). */
    private fun applyAlpha() {
        root?.setBackgroundColor(windowBgColor())
        playerView?.alpha = videoAlpha()
    }

    // ---------- показ кнопок в окне ----------
    // Prefs: hide_ui_buttons — не показывать панель кнопок в окне.
    // Кнопки тогда всё равно доступны: ОК с пульта (или долгий тап) —
    // принудительно показывает панель, BACK — закрывает окно.

    private fun uiHidden(): Boolean =
        prefs.getBoolean("hide_ui_buttons", false)

    /** Применить настройку «не показывать кнопки» к открытому окну. */
    private fun applyUiVisibility() {
        if (uiHidden()) {
            hideUi()
        } else if (windowShown) {
            showUi(force = true)
        }
    }

    private fun hideWindow() {
        windowShown = false
        handler.removeCallbacksAndMessages(null)
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null
        playerView = null
        playerErrText = null
        uiBar = null
        uiIcons = emptyList()
        fsBtn = null
        cornerViews = emptyList()
        pausePicker = null
        player?.release()
        player = null
        try {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        } catch (_: Exception) {}
    }

    // ---------- UI по тапу, автоскрытие 6 с ----------

    private fun setCornersVisible(v: Int) {
        cornerViews.forEach { it.visibility = v }
    }

    private fun showUi(force: Boolean = false) {
        // кнопки скрыты настройкой «не показывать кнопки» — панель не
        // показываем по тапу/стрелкам, только принудительно (ОК с пульта)
        if (uiHidden() && !force) {
            uiBar?.visibility = View.GONE
            setCornersVisible(View.GONE)
            return
        }
        uiBar?.visibility = View.VISIBLE
        setCornersVisible(if (hasTouch && !fullscreen) View.VISIBLE else View.GONE)
        // фокус с пульта нельзя перехватывать, пока он в списке паузы
        val pickerFocused = pausePicker?.hasFocus() == true
        if (!hasTouch && !pickerFocused && uiBar?.hasFocus() != true) {
            uiIcons.firstOrNull()?.requestFocus()
        }
        // Таймер автоскрытия ставится ВСЕГДА. Раньше при фокусе в панели он
        // не ставился вовсе — на ТВ (фокус берётся автоматически) панель
        // не скрывалась никогда. Любая клавиша пульта зовёт showUi() и
        // перезапускает отсчёт, так что при навигации панель не исчезает.
        handler.removeCallbacks(uiHideRunnable)
        handler.postDelayed(uiHideRunnable, UI_TIMEOUT_MS)
    }

    private fun hideUi() {
        // снять фокус с кнопки перед скрытием — иначе он «зависает» на
        // невидимом view и следующая навигация стартует из ниоткуда
        uiBar?.clearFocus()
        uiBar?.visibility = View.GONE
        setCornersVisible(View.GONE)
    }

    // ---------- выбор паузы прямо в окне ----------
    // Небольшая панель со сроками: 5/10/15/30/60/120 минут. Работает и
    // с тапом, и с пульта (кнопки фокусируемые).
    // ВАЖНО: оверлей перехватывает D-pad под перемещение окна, поэтому
    // навигацию по списку делаем сами (movePicker/selectPicker) и рисуем
    // свой акцент — системный фокус в оверлее ненадёжен.

    private val suspendOptions = listOf(
        5 to "5 минут", 10 to "10 минут", 15 to "15 минут",
        30 to "30 минут", 60 to "1 час", 120 to "2 часа"
    )

    private var pickerItems: List<TextView> = emptyList()
    private var pickerIndex = 0
    private var pickerScroll: android.widget.ScrollView? = null
    private var pausePicker: View? = null

    private fun showSuspendPicker() {
        if (root == null) return
        // старый список убираем, чтобы не наслаивать два
        pausePicker?.let { p ->
            (p.parent as? android.view.ViewGroup)?.removeView(p)
        }
        // список строим заново — в нём есть пункт «Снять паузу»,
        // который зависит от текущего состояния
        val box = buildSuspendPicker()
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#CC1C1C1E"))
            isFillViewport = true
            addView(box)
        }
        pickerScroll = scroll
        pausePicker = scroll
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = dp(58)
        root?.addView(scroll, lp)
        scroll.visibility = View.VISIBLE
        // пока открыт выбор срока — панель не прячем по таймеру
        handler.removeCallbacks(uiHideRunnable)
        // акцент ставим после лайаута — иначе requestFocus не сработает
        scroll.post { highlightPicker() }
    }

    private fun hideSuspendPicker() {
        pausePicker?.let { p ->
            (p.parent as? android.view.ViewGroup)?.removeView(p)
        }
        pausePicker = null
        pickerScroll = null
        pickerItems = emptyList()
        // панель снова прячется по таймеру
        if (windowShown) {
            handler.removeCallbacks(uiHideRunnable)
            handler.postDelayed(uiHideRunnable, UI_TIMEOUT_MS)
        }
    }

    private fun buildSuspendPicker(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.parseColor("#CC1C1C1E"))
        }
        val items = mutableListOf<TextView>()
        for ((min, label) in suspendOptions) {
            val item = pickerItem(label, Color.WHITE) { applySuspend(min) }
            items.add(item)
            box.addView(item)
        }
        if (suspendActive()) {
            val item = pickerItem("Снять паузу", Color.parseColor("#FFD166")) {
                cancelSuspend()
            }
            items.add(item)
            box.addView(item)
        }
        pickerItems = items
        pickerIndex = 0
        return box
    }

    /** Пункт списка: компактный, фокусируемый, с обработкой тапа. */
    private fun pickerItem(
        label: String,
        color: Int,
        onPick: () -> Unit
    ): TextView =
        TextView(this).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onPick() }
        }

    /** Акцент на выбранном пункте (синяя пилюля) — свой, не системный фокус. */
    private fun highlightPicker() {
        pickerItems.forEachIndexed { i, v ->
            if (i == pickerIndex) {
                v.setTextColor(Color.WHITE)
                v.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF2979FF"))
                    cornerRadius = dp(8).toFloat()
                }
            } else {
                v.setTextColor(
                    if (v.text.toString() == "Снять паузу")
                        Color.parseColor("#FFD166") else Color.parseColor("#FFDDDDDD")
                )
                v.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#331C1C1E"))
                    cornerRadius = dp(8).toFloat()
                }
            }
        }
        pickerItems.getOrNull(pickerIndex)?.requestFocus()
    }

    /** Движение по списку (D-pad вверх/вниз). */
    private fun movePicker(delta: Int) {
        if (pickerItems.isEmpty()) return
        pickerIndex = (pickerIndex + delta + pickerItems.size) % pickerItems.size
        highlightPicker()
        // подкручиваем список, чтобы выбранный пункт был виден
        val v = pickerItems.getOrNull(pickerIndex) ?: return
        val top = v.top
        val bottom = top + v.height
        val sc = pickerScroll
        if (sc != null) {
            if (top < sc.scrollY) sc.scrollTo(0, top)
            else if (bottom > sc.scrollY + sc.height) {
                sc.scrollTo(0, bottom - sc.height)
            }
        }
    }

    /** Выбор пункта (ОК с пульта). */
    private fun selectPicker() {
        val v = pickerItems.getOrNull(pickerIndex) ?: return
        v.performClick()
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
        cornerViews.forEach {
            val lp = it.layoutParams
            lp.width = dpf(16f * k); lp.height = dpf(16f * k)
            it.layoutParams = lp
        }
    }

    // ---------- плеер ----------

    /**
     * Если железный AVC-декодер на ТВ занят другим приложением (например,
     * идёт фильм), его не удаётся инициализировать — плеер падает с
     * ERROR_CODE_DECODER_INIT_FAILED и окно остаётся чёрным. На многих ТВ
     * железный AVC-декодер один (concurrent-instances=1), поэтому нужен
     * запасной путь: media3 умеет откатиться на следующий декодер из списка
     * (в т.ч. на программный OMX.google.h264.decoder, у него лимита нет).
     */
    private var preferSoftwareDecoder = false

    private fun buildPlayer(): ExoPlayer {
        // Буферизация для живого HLS с короткими (0.5 c) сегментами go2rtc:
        // иначе каждый стык сегмента = приседание звука
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val factory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            // пробовать следующий декодер, если текущий не инициализировался
            .setEnableDecoderFallback(true)
        if (preferSoftwareDecoder) {
            // последний рубеж: софтовый декодер (медленнее, зато всегда есть)
            factory.setMediaCodecSelector(SoftwareFirstCodecSelector)
        }
        return ExoPlayer.Builder(this)
            .setRenderersFactory(factory)
            .setLoadControl(load)
            .build()
    }

    /** Декодеры по MIME: сначала программные, потом остальные. */
    private object SoftwareFirstCodecSelector :
        androidx.media3.exoplayer.mediacodec.MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            return all.sortedByDescending { it.softwareOnly }
        }
    }

    private fun startStream(url: String) {
        player?.release()
        val ex = buildPlayer()
        player = ex
        playerView?.player = ex
        // «Mute при старте» из настроек: окно может открываться без звука,
        // включается иконкой динамика в панели окна
        muted = prefs.getBoolean("mute_on_start", true)
        ex.volume = if (muted) 0f else 1f
        handler.post {
            volBtn?.setImageResource(
                if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
        }
        ex.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Железный AVC-декодер на ТВ один (concurrent-instances=1) и
                // может быть занят другим приложением (например, фильмом).
                // Тогда init падает с DECODER_INIT_FAILED. media3 уже пробует
                // следующий декодер сам; если не помогло — пересобираем плеер
                // с приоритетом программного декодера (лимита на него нет).
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                    !preferSoftwareDecoder
                ) {
                    preferSoftwareDecoder = true
                    handler.post { restartStream(currentUrl()) }
                    return
                }
                handler.post {
                    playerErrText?.visibility = View.VISIBLE
                    playerErrText?.text =
                        "Нет потока: ${error.errorCodeName}\n${currentUrl()}"
                }
                retries++
                if (retries <= 30) handler.postDelayed({
                    try {
                        ex.setMediaItem(mediaItem(currentUrl()))
                        ex.prepare()
                    } catch (_: Exception) {}
                }, 2000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    handler.post { playerErrText?.visibility = View.GONE }
                }
            }
        })
        ex.setMediaItem(mediaItem(url))
        ex.playWhenReady = true
        ex.prepare()
        retries = 0
    }

    /**
     * Пересобрать плеер (например, после смены приоритета декодеров) и
     * запустить поток заново.
     */
    private fun restartStream(url: String) {
        try {
            startStream(url)
        } catch (_: Exception) {}
    }

    /**
     * Для ссылок на прокси HA MIME задаётся явно: URL без расширения .m3u8,
     * а часть плееров не верит Content-Type с charset.
     */
    private fun mediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMimeType(
                if (url.contains("/api/mynotify/stream"))
                    "application/x-mpegURL"
                else null
            )
            .build()

    private fun currentUrl(): String = pickUrl().ifBlank { DEFAULT_URL }


    // ---------- действия панели ----------

    /** Звук вкл/выкл текущего потока (иконка динамика в панели окна). */
    private fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        volBtn?.setImageResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
        // синхронизация с настройкой «Mute при старте окна»
        prefs.edit().putBoolean("mute_on_start", muted).apply()
    }

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
        setCornersVisible(
            if (hasTouch && !fullscreen && uiBar?.visibility == View.VISIBLE)
                View.VISIBLE else View.GONE
        )
    }

    // ---------- управление ----------

    private fun handleKey(ev: KeyEvent): Boolean {
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        // открыт список сроков паузы — навигацию делаем сами:
        // оверлей перехватывает D-pad под перемещение окна
        if (pausePicker != null) {
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    movePicker(1); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    movePicker(-1); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    selectPicker(); return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    hideSuspendPicker(); return true
                }
            }
            // остальные клавиши (громкость и т.п.) — отдаём системе
            return false
        }
        val barWasVisible = uiBar?.visibility == View.VISIBLE
        val barHasFocus = uiBar?.hasFocus() == true
        // любая кнопка пульта показывает панель — но если в настройках
        // выбрано «не показывать кнопки», панель появляется только по ОК
        if (!uiHidden()) showUi()
        val p = params ?: return false
        val step = dp(24)
        when (ev.keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideWindow(); return true }
            // ОК: панель скрыта — показать и фокус на первую кнопку;
            // видна без фокуса — вернуть фокус на первую;
            // видна с фокусом — «нажать» сфокусированную кнопку (не
            // перехватываем — событие уйдёт в super и кнопка кликнулась)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!barWasVisible) {
                    // при выключенных кнопках ОК — единственный способ
                    // достать панель (BACK при этом закрывает окно)
                    if (uiHidden()) showUi(force = true)
                    return true
                }
                if (!barHasFocus) {
                    uiIcons.firstOrNull()?.requestFocus()
                    return true
                }
                return false
            }
            // стрелки: при видимой панели с фокусом — навигация по кнопкам
            // (системный focus-search), иначе — перемещение окна
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (barWasVisible && barHasFocus) return false
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> p.y -= step
                    KeyEvent.KEYCODE_DPAD_DOWN -> p.y += step
                    KeyEvent.KEYCODE_DPAD_LEFT -> p.x -= step
                    else -> p.x += step
                }
            }
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

    /**
     * Ресайз перетаскиванием за треугольник в углу (пропорции 16:9).
     * flip=true — ручка слева (тянем влево, окно шире).
     */
    private fun attachResize(v: View, flip: Boolean) {
        var sx = 0f
        var startW = 0
        v.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; startW = p.width; true }
                MotionEvent.ACTION_MOVE -> {
                    val maxW = resources.displayMetrics.widthPixels
                    val d = if (flip) sx - e.rawX else e.rawX - sx
                    val nw = (startW + d).toInt().coerceIn(dp(160), maxW)
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

        // ВАЖНО: invoke() возвращает Boolean, а не Boolean? — «?:» бы не
        // сработал и необработанные клавиши (ОК по кнопке, стрелки фокуса)
        // молча проглатывались бы. Явно: не обработали — отдаём системе.
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            keyHandler?.invoke(event) == true || super.dispatchKeyEvent(event)

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
