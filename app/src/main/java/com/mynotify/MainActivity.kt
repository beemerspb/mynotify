package com.mynotify

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Экран-мониторинг в стиле iOS: сгруппированные карточки на системном сером
 * фоне, крупные заголовки, акцентные цвета системы. Две независимые пары
 * действий: служба (фон, для HA) и окно (показ/скрытие). Камеру выбирают в HA.
 */
class MainActivity : Activity() {

    companion object { private const val REQ_SOUND = 41 }

    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var codeText: TextView
    private lateinit var urlInput: EditText
    private lateinit var extUrlInput: EditText
    private lateinit var soundText: TextView
    private lateinit var launchBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var windowBtn: Button
    private lateinit var updText: TextView
    private lateinit var suspendStatus: TextView
    private lateinit var cancelSuspendBtn: Button
    private var checkingUpdate = false
    private var permBtn: Button? = null

    // Семантические цвета (палитра — res/values/colors.xml)
    private val cLabel get() = ContextCompat.getColor(this, R.color.mn_fg)
    private val cSecondary get() = ContextCompat.getColor(this, R.color.mn_fg2)
    private val cBlue get() = ContextCompat.getColor(this, R.color.mn_accent)
    private val cGreen get() = ContextCompat.getColor(this, R.color.mn_live)
    private val cRed get() = ContextCompat.getColor(this, R.color.mn_danger)
    private val cOrange get() = ContextCompat.getColor(this, R.color.mn_warn)
    private val cGray get() = ContextCompat.getColor(this, R.color.mn_fg3)
    /** Залитые кнопки красим mn_accent_strong: белый на mn_accent даёт 2.5:1.
     *  Сам mn_accent остаётся на тексте, ссылках и кольце фокуса. */
    private val cAccentFill get() = ContextCompat.getColor(this, R.color.mn_accent_strong)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (isFinishing) return
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(OverlayService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        updateStatus()
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Аварийный запуск showing-команды (обычно всё приходит в службу из HA). */
    private fun handleIntent(src: Intent?) {
        val intent = src ?: return
        val isShow = intent.action == "com.mynotify.SHOW" ||
            intent.action == Intent.ACTION_VIEW
        if (!isShow) return
        val url = intent.getStringExtra(OverlayService.EXTRA_URL)
            ?: intent.data?.let { Uri.decode(it.encodedSchemeSpecificPart.removePrefix("//")) }
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Нет разрешения на оверлей", Toast.LENGTH_LONG).show()
            return
        }
        sendToService(OverlayService.ACTION_SHOW) {
            url?.let { putExtra(OverlayService.EXTRA_URL, it) }
            val sec = intent.getIntExtra(OverlayService.EXTRA_AUTOHIDE_SEC, 0)
            if (sec > 0) putExtra(OverlayService.EXTRA_AUTOHIDE_SEC, sec)
        }
        if (intent.action == "com.mynotify.SHOW") finish()
    }

    private fun sendToService(action: String, extra: Intent.() -> Unit = {}) {
        val si = Intent(this, OverlayService::class.java).apply {
            this.action = action
            extra()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            action != OverlayService.ACTION_STOP) {
            startForegroundService(si)
        } else {
            startService(si)
        }
    }

    /** Тот же prefs-файл, что у службы: код привязки виден до её запуска. */
    private fun pairCode(): String {
        val p = getSharedPreferences("settings", Context.MODE_PRIVATE)
        return p.getString("pair_code", null)
            ?: (100000 + java.util.Random().nextInt(900000)).toString().also {
                p.edit().putString("pair_code", it).apply()
            }
    }

    private fun updateStatus() {
        val running = OverlayService.running
        val shown = OverlayService.windowShown
        val haOk = OverlayService.lastHaOk > 0 &&
            System.currentTimeMillis() - OverlayService.lastHaOk < 10000
        statusDot.text = "●"
        statusDot.setTextColor(
            when {
                running && haOk && OverlayService.bound -> cGreen
                running && haOk -> cOrange
                running -> cGray
                else -> cGray
            })
        codeText.text = pairCode()
        statusText.text = when {
            !running -> "Служба остановлена"
            !haOk -> "Нет связи с Home Assistant"
            !OverlayService.bound -> "Служба в сети, устройство не привязано"
            shown -> "Подключено · окно показано"
            else -> "Подключено · окно скрыто"
        }
        statusBig?.text = statusText.text
        launchBtn.isEnabled = !running
        launchBtn.alpha = if (running) 0.35f else 1f
        stopBtn.isEnabled = running
        stopBtn.alpha = if (running) 1f else 0.35f
        windowBtn.isEnabled = running
        windowBtn.alpha = if (running) 1f else 0.35f
        windowBtn.text = if (shown) "Скрыть окно" else "Показать окно"
        if (::suspendStatus.isInitialized) suspendStatus.text = suspendLabel()
        if (::cancelSuspendBtn.isInitialized) {
            val active = suspendLabel() != "Пауза не активна"
            cancelSuspendBtn.visibility = if (active) View.VISIBLE else View.GONE
        }
        if (hasOverlayPermission()) // выдано — больше не показываем
            permBtn?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    // ---------- iOS-строители ----------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()


    /** Белая скруглённая карточка-секция. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = Design.cardBg(this@MainActivity)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(14); lp.rightMargin = dp(14)
        layoutParams = lp
    }

    // Кольцо фокуса для пульта: было мигающим красным, стало спокойным синим
    // (на тёмном холсте красное мигание кричало и спорило с акцентом).
    // Аниматор держим в замыкании слушателя — setTag(int) с ключом
    // generateViewId() ронял приложение на первом фокусе (краш v0.7.11).
    private fun attachFocusBlink(v: View, radiusDp: Int) =
        Design.attachFocusRing(v, radiusDp)

    // На Android TV у Settings нет страницы ACTION_MANAGE_OVERLAY_PERMISSION —
    // прямой startActivity кидал ActivityNotFoundException и ронял приложение.
    // Цепочка запасных экранов, а если их нет — подсказка с adb-командой.
    private fun openOverlayPermission() {
        val pkg = Uri.parse("package:$packageName")
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in attempts) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        val cmd = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
            Toast.makeText(
                this,
                "Нет страницы разрешения (ТВ). Команда adb скопирована в буфер:\n$cmd",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Нет страницы разрешения (ТВ). Выполните: $cmd",
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Кнопка-чип: ширина по тексту, а не на всё окно — растянутые полосы на
     * 1400 px юзер назвал неудобными. color == cGray трактуем как нейтральную
     * (поверхность + обычный текст), cOrange — тёмный текст поверх янтаря
     * (белый на янтаре не читается). Синий акцент льётся mn_accent_strong ради
     * контраста белого текста.
     */
    private fun uiButton(text: String, color: Int): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        letterSpacing = 0f
        val neutral = color == cGray
        val warn = color == cOrange
        val fill = when {
            neutral -> ContextCompat.getColor(this@MainActivity, R.color.mn_surface2)
            color == cBlue -> cAccentFill
            else -> color
        }
        setTextColor(
            when {
                neutral -> cLabel
                warn -> ContextCompat.getColor(this@MainActivity, R.color.mn_bg)
                else -> Color.WHITE
            }
        )
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(Design.manrope(this@MainActivity, 600))
        stateListAnimator = null
        background = Design.buttonBg(this@MainActivity, fill)
        attachFocusBlink(this, 12)
        minWidth = dp(104)
        setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
    }

    /** Ряд компактных кнопок с зазором; перенос строк делает вызывающий. */
    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        views.forEachIndexed { i, v ->
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = dp(10)
            addView(v, lp)
        }
    }

    /** «30 минут» -> «30 мин», «1 час» -> «1 ч»: чипы должны быть короткими. */
    private fun shortDur(label: String): String = label
        .replace(" минут", " мин").replace(" минута", " мин")
        .replace(" часов", " ч").replace(" час", " ч")

    private fun footer(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(14), dp(28), dp(24))
    }

    // ---------- навигация: экраны + рейка ----------
    //
    // Раньше всё лежало одним вертикальным списком на ~2300 px: чтобы добраться
    // до «Показать окно» или сроков паузы, по ТВ нужно было скроллить пультом.
    // Теперь мастер-деталь: слева рейка разделов, справа только выбранный
    // раздел, состояние службы — всегда в шапке.
    private val screens = mutableListOf<View>()
    private val railItems = mutableListOf<TextView>()
    private var selectedScreen = 0
    private var statusBig: TextView? = null

    private fun selectScreen(index: Int) {
        if (index !in screens.indices) return
        selectedScreen = index
        screens.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        railItems.forEachIndexed { i, v -> v.isSelected = i == index }
        (screens[index] as? ScrollView)?.fullScroll(ScrollView.FOCUS_UP)
    }

    /** Пункт рейки: выбор = мягкий акцент, фокус с пульта = залитый + белый штрих. */
    private fun railItem(title: String, index: Int): TextView = TextView(this).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(Design.manrope(this@MainActivity, 600))
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(16), dp(12), dp(12), dp(12))
        isFocusable = true
        isClickable = true
        setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf()
                ),
                intArrayOf(Color.WHITE, cBlue, cSecondary)
            )
        )
        val r = dp(Design.R_BUTTON).toFloat()
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(cAccentFill)
                cornerRadius = r
                setStroke(dp(3), Color.WHITE)
            })
            addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.mn_accent_soft))
                cornerRadius = r
                setStroke(dp(1), cBlue)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = r
            })
        }
        setOnClickListener { selectScreen(index) }
    }

    /** Экран: вертикальный список карточек внутри ScrollView (скрыт по умолчанию). */
    private fun screen(inner: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        visibility = View.GONE
        setPadding(0, 0, 0, dp(16))
        addView(inner, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun screenBody(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(2), 0)
    }

    /** Карточка-контейнер с уже расставленными отступами между блоками. */
    private fun block(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(10))
    }

    private fun bodyText(text: String, top: Int = 10): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(top), 0, 0)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.mn_bg))
            setPadding(dp(20), dp(20), dp(20), 0)
        }

        // --- шапка: заголовок + состояние (видно всегда, без скролла) ---
        root.addView(TextView(this).apply {
            text = "MyNotify"
            setTextColor(cLabel)
            setTypeface(Design.manrope(this@MainActivity, 800))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            letterSpacing = -0.02f
        })
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), 0, dp(14))
        }
        statusDot = TextView(this).apply {
            text = "●"
            setTextColor(cGray)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, dp(8), 0)
        }
        statusText = TextView(this).apply {
            text = "Служба остановлена"
            setTextColor(cSecondary)
            setTypeface(Design.mono(this@MainActivity, 500))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.04f
        }
        pill.addView(statusDot)
        pill.addView(statusText)
        root.addView(pill)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(body)

        val wide = resources.configuration.screenWidthDp >= 600
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        // --- экран 1: служба (главные действия на первом экране) ---
        val s1 = screenBody()
        val stCard = card()
        stCard.addView(TextView(this).apply {
            text = "Состояние"
            setTextColor(cLabel)
            setTypeface(Design.manrope(this@MainActivity, 700))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        statusBig = TextView(this).apply {
            text = "Служба остановлена"
            setTextColor(cLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(6), 0, 0)
        }
        stCard.addView(statusBig)
        stCard.addView(bodyText(
            "Служба сама подключается к Home Assistant и ждёт команды — " +
                "камеру и показ выбирают там. ADB не нужен."))
        s1.addView(block().apply { addView(stCard) })

        val actCard = card()
        windowBtn = uiButton("Показать окно", cBlue).apply {
            setOnClickListener {
                if (!OverlayService.running) {
                    Toast.makeText(
                        this@MainActivity, "Сначала запустите службу", Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                // только окно; служба продолжает работать в фоне
                sendToService(
                    if (OverlayService.windowShown) OverlayService.ACTION_HIDE
                    else OverlayService.ACTION_SHOW
                )
                windowHandler.postDelayed({ updateStatus() }, 400)
            }
        }
        actCard.addView(row(windowBtn))
        launchBtn = uiButton("Запустить службу", cGreen).apply {
            setOnClickListener {
                if (!hasOverlayPermission()) {
                    Toast.makeText(
                        this@MainActivity, "Сначала выдайте разрешение", Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                // только служба, без окна — окно показывает HA или кнопка выше
                sendToService(OverlayService.ACTION_START)
                updateStatus()
            }
        }
        stopBtn = uiButton("Остановить службу", cRed).apply {
            setOnClickListener {
                sendToService(OverlayService.ACTION_STOP)
                updateStatus()
            }
        }
        // два ряда вместо трёх растянутых полос: primary-действие отдельно
        actCard.addView(row(launchBtn, stopBtn))
        s1.addView(block().apply { addView(actCard) })
        screens.add(screen(s1))

        // --- экран 2: пауза службы ---
        val s2 = screenBody()
        val pauseCard = card()
        pauseCard.addView(TextView(this).apply {
            text = "Пауза службы"
            setTextColor(cLabel)
            setTypeface(Design.manrope(this@MainActivity, 700))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        pauseCard.addView(bodyText(
            "Пока пауза активна, камера не показывается — кино не прерывается. " +
                "По истечении срока всё работает как обычно.", 8))
        suspendStatus = TextView(this).apply {
            text = suspendLabel()
            setTextColor(cLabel)
            setTypeface(Design.mono(this@MainActivity, 500))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(8), 0, dp(4))
        }
        pauseCard.addView(suspendStatus)
        TextView(this).apply {
            text = "ДЛИТЕЛЬНОСТЬ ПАУЗЫ"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.mn_fg3))
            setTypeface(Design.mono(this@MainActivity, 600))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.14f
            setPadding(0, dp(6), 0, 0)
        }.also { pauseCard.addView(it) }
        suspendOptions.map { (min, label) ->
            uiButton(shortDur(label), cBlue).apply {
                setOnClickListener { applySuspendFromUi(min) }
            }
        }.chunked(3).forEach { pauseCard.addView(row(*it.toTypedArray())) }
        cancelSuspendBtn = uiButton("Снять паузу", cGray).apply {
            setOnClickListener {
                if (OverlayService.running) {
                    try {
                        sendToService(OverlayService.ACTION_SUSPEND_CANCEL)
                    } catch (_: Exception) {}
                }
                getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putLong("suspend_until", 0L).apply()
                suspendStatus.text = suspendLabel()
                updateStatus()
            }
        }
        pauseCard.addView(row(cancelSuspendBtn))
        s2.addView(block().apply { addView(pauseCard) })
        screens.add(screen(s2))

        // --- экран 3: окно камеры (звук, прозрачность, кнопки) ---
        val s3 = screenBody()
        val mutePrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val sndCard = card()
        sndCard.addView(TextView(this).apply {
            text = "Звук при показе окна"
            setTextColor(cLabel)
            setTypeface(Design.manrope(this@MainActivity, 700))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        soundText = TextView(this).apply {
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6), 0, 0)
        }
        updateSoundLabel()
        sndCard.addView(soundText)
        sndCard.addView(row(uiButton("Выбрать системный звук", cGray).apply {
            setOnClickListener {
                val cur = mutePrefs.getString("sound_uri", null)
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION
                    )
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Звук при показе окна")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                    if (cur != null && cur != "silent")
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur)
                        )
                }
                startActivityForResult(i, REQ_SOUND)
            }
        }))
        val muteCheck = android.widget.CheckBox(this).apply {
            text = "Заглушать звук видеопотока при старте"
            setTextColor(cLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(14), 0, 0)
            setButtonTintList(ColorStateList.valueOf(cBlue))
            isChecked = mutePrefs.getBoolean("mute_on_start", true)
            setOnCheckedChangeListener { _, checked ->
                mutePrefs.edit().putBoolean("mute_on_start", checked).apply()
            }
            attachFocusBlink(this, 10)
        }
        sndCard.addView(muteCheck)
        s3.addView(block().apply { addView(sndCard) })

        val viewCard = card()
        viewCard.addView(TextView(this).apply {
            text = "Вид окна"
            setTextColor(cLabel)
            setTypeface(Design.manrope(this@MainActivity, 700))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        viewCard.addView(alphaSlider("Прозрачность (окно и видео)", "overlay_alpha"))
        val hideUiCheck = android.widget.CheckBox(this).apply {
            text = "Не показывать кнопки в окне камеры"
            setTextColor(cLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(14), 0, 0)
            setButtonTintList(ColorStateList.valueOf(cBlue))
            isChecked = mutePrefs.getBoolean("hide_ui_buttons", false)
            setOnCheckedChangeListener { _, checked ->
                mutePrefs.edit().putBoolean("hide_ui_buttons", checked).apply()
                // применить на лету, если окно уже открыто
                if (OverlayService.running) {
                    try {
                        sendToService(OverlayService.ACTION_UI)
                    } catch (_: Exception) {}
                }
            }
            attachFocusBlink(this, 10)
        }
        viewCard.addView(hideUiCheck)
        s3.addView(block().apply { addView(viewCard) })
        screens.add(screen(s3))

        // --- экран 4: подключение (код привязки и адреса) ---
        val s4 = screenBody()
        val pairCard = card()
        pairCard.addView(TextView(this).apply {
            text = "Привязка к Home Assistant"
            setTextColor(cLabel)
            setTypeface(Design.manrope(this@MainActivity, 700))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        pairCard.addView(TextView(this).apply {
            text = "КОД ПРИВЯЗКИ"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.mn_fg3))
            setTypeface(Design.mono(this@MainActivity, 600))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        codeText = TextView(this).apply {
            text = "––– –––"
            setTextColor(cBlue)
            setTypeface(Design.mono(this@MainActivity, 700))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
            letterSpacing = 0.08f
        }
        pairCard.addView(codeText)
        pairCard.addView(bodyText(
            "Введите этот код при добавлении устройства в интеграции MyNotify", 0))
        urlInput = EditText(this).apply {
            setText(mutePrefs.getString("ha_url", "http://homeassistant.local:8123"))
            hint = "Адрес Home Assistant"
            setSingleLine()
            setTextColor(cLabel)
            setHintTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = Design.fieldBg(this@MainActivity)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // ТВ: поле должно брать фокус и с пульта, и в touch-режиме
            isFocusable = true
            isFocusableInTouchMode = true
            attachFocusBlink(this, 12)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        pairCard.addView(urlInput)
        extUrlInput = EditText(this).apply {
            setText(mutePrefs.getString("ha_url_ext", ""))
            hint = "Ссылка HA извне (необязательно): https://ha.example.com"
            setSingleLine()
            setTextColor(cLabel)
            setHintTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = Design.fieldBg(this@MainActivity)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            attachFocusBlink(this, 12)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        pairCard.addView(extUrlInput)
        pairCard.addView(row(uiButton("Сохранить адреса", cGray).apply {
            setOnClickListener {
                mutePrefs.edit()
                    .putString("ha_url", urlInput.text.toString().trim())
                    .putString("ha_url_ext", extUrlInput.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Адреса сохранены", Toast.LENGTH_SHORT).show()
            }
        }))
        if (!hasOverlayPermission()) {
            permBtn = uiButton("Разрешить показ поверх приложений", cOrange).apply {
                setOnClickListener { openOverlayPermission() }
            }
            pairCard.addView(row(permBtn!!))
        }
        s4.addView(block().apply { addView(pairCard) })
        screens.add(screen(s4))

        // --- экран 5: приложение (версия и обновления) ---
        val s5 = screenBody()
        val appCard = card()
        appCard.addView(TextView(this).apply {
            text = "Версия ${appVersion()}"
            setTextColor(cLabel)
            setTypeface(Design.manrope(this@MainActivity, 700))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        updText = TextView(this).apply {
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, 0)
        }
        appCard.addView(updText)
        appCard.addView(row(uiButton("Проверить обновления", cBlue).apply {
            setOnClickListener { checkUpdate() }
        }))
        appCard.addView(bodyText(
            "Обновление скачивается с GitHub и устанавливается обычным " +
                "системным диалогом."))
        s5.addView(block().apply { addView(appCard) })
        s5.addView(footer("MyNotify ${appVersion()} · Home Assistant"))
        screens.add(screen(s5))

        // --- рейка: слева на широком экране, полосой сверху на узком ---
        val titles = listOf("Служба", "Пауза", "Окно", "Подключение", "Приложение")
        titles.forEachIndexed { i, t -> railItems.add(railItem(t, i)) }
        if (wide) {
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    dp(216), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            railItems.forEach {
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                rail.addView(it)
            }
            body.addView(rail)
            body.addView(content)
        } else {
            val strip = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val rail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            railItems.forEach {
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                it.setPadding(dp(14), dp(10), dp(14), dp(10))
                rail.addView(it)
            }
            strip.addView(rail)
            // полоса обязана быть над контентом: body уже добавлен в root с весом,
            // поэтому вставляем по индексу, иначе вкладки уедут за низ экрана
            root.addView(strip, 2)
            body.addView(content)
        }

        val contentPad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        screens.forEach { contentPad.addView(it) }
        content.addView(contentPad)
        // колонка контента ограничена по ширине: на 10 футов широкие полосы
        // читаются плохо, а кнопки-чипы в растянутом контейнере выглядят глупо
        content.post {
            val max = dp(560)
            if (content.width > max) content.setPadding(0, 0, content.width - max, 0)
        }

        setContentView(root)
        selectScreen(0)
        updateStatus()
        // на ТВ первый фокус должен попадать на рейку, а не в «никуда»
        root.post { railItems.getOrNull(0)?.requestFocus() }
    }


    private val windowHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------- обновления ----------

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** latest release на GitHub; находит asset с APK и предлагает установить. */
    private fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        updText.text = "Проверяю обновления…"
        Thread {
            var msg: String
            var apkUrl = ""
            var tag = ""
            var update = false
            try {
                val con = java.net.URL(
                    "https://api.github.com/repos/beemerspb/mynotify/releases/latest"
                ).openConnection() as java.net.HttpURLConnection
                con.setRequestProperty("User-Agent", "MyNotify")
                con.connectTimeout = 8000
                con.readTimeout = 8000
                val j = org.json.JSONObject(
                    con.inputStream.bufferedReader().readText()
                )
                tag = j.optString("tag_name")
                val assets = j.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url")
                        break
                    }
                }
                when {
                    tag.removePrefix("v") == appVersion() ->
                        msg = "Версия ${appVersion()} — последняя"
                    apkUrl.isEmpty() -> msg = "В релизе $tag нет APK-файла"
                    else -> {
                        msg = "Доступна версия $tag (у вас ${appVersion()})"
                        update = true
                    }
                }
            } catch (e: Exception) {
                msg = "Не удалось проверить: ${e.javaClass.simpleName}"
            }
            val fin = msg
            windowHandler.post {
                checkingUpdate = false
                updText.text = fin
                if (update) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Обновление $tag")
                        .setMessage(fin)
                        .setPositiveButton("Скачать и установить") { _, _ ->
                            startInstall(apkUrl)
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            }
        }.start()
    }

    private fun startInstall(url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            updText.text = "Разреши установку из этого приложения и нажми «Проверить» снова"
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {}
            return
        }
        updText.text = "Скачиваю…"
        Thread {
            try {
                val out = java.io.File(getExternalFilesDir(null), "mynotify-update.apk")
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "MyNotify")
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(out).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = done * 100 / total
                                windowHandler.post {
                                    updText.text = "Скачиваю… $pct%"
                                }
                            }
                        }
                    }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", out
                )
                windowHandler.post {
                    updText.text = "Запускаю установщик…"
                    val pi = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(pi)
                    } catch (e: Exception) {
                        updText.text = "Установщик недоступен: ${e.javaClass.simpleName}"
                    }
                }
            } catch (e: Exception) {
                windowHandler.post {
                    updText.text = "Скачивание не удалось: ${e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    /**
     * Слайдер прозрачности (0..100 %) для настроек окна. Пишет prefs
     * «settings» — служба читает их при создании окна и переприменяет
     * на лету через ACTION_ALPHA, если окно уже открыто.
     */
    private fun alphaSlider(title: String, key: String): View {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        val valueText = TextView(this).apply {
            text = "${p.getInt(key, 0)}%"
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        // дебаунс: с пульта onProgressChanged приходит на каждую клавишу,
        // а onStopTrackingTouch для клавиш вообще не гарантирован
        val applyRunnable = Runnable {
            if (OverlayService.running) {
                try {
                    sendToService(OverlayService.ACTION_ALPHA)
                } catch (_: Exception) {}
            }
        }
        val seek = android.widget.SeekBar(this).apply {
            max = 100
            progress = p.getInt(key, 0)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?, value: Int, fromUser: Boolean
                ) {
                    if (!fromUser) return
                    valueText.text = "$value%"
                    p.edit().putInt(key, value).apply()
                    windowHandler.removeCallbacks(applyRunnable)
                    windowHandler.postDelayed(applyRunnable, 400)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    windowHandler.removeCallbacks(applyRunnable)
                    windowHandler.post(applyRunnable)
                }
            })
            attachFocusBlink(this, 8)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextColor(cLabel)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(valueText)
            })
            addView(seek)
        }
    }

    /** Сроки паузы службы — те же, что в окне. */
    private val suspendOptions = listOf(
        5 to "5 минут", 10 to "10 минут", 15 to "15 минут",
        30 to "30 минут", 60 to "1 час", 120 to "2 часа"
    )

    /** Активна ли пауза и до какого времени. */
    private fun suspendLabel(): String {
        val until = getSharedPreferences("settings", MODE_PRIVATE)
            .getLong("suspend_until", 0L) ?: 0L
        if (until <= System.currentTimeMillis()) return "Пауза не активна"
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return "Пауза до " + fmt.format(java.util.Date(until))
    }

    /** Включить паузу службы из настроек. */
    private fun applySuspendFromUi(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putLong("suspend_until", until).apply()
        if (OverlayService.running) {
            try {
                sendToService(OverlayService.ACTION_SUSPEND) {
                    putExtra(OverlayService.EXTRA_MINUTES, minutes)
                }
            } catch (_: Exception) {}
        }
        suspendStatus.text = suspendLabel()
        updateStatus()
    }

    private fun updateSoundLabel() {
        val saved = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("sound_uri", null)
        soundText.text = when {
            saved == null -> "Не выбран — окно появляется без звука"
            saved == "silent" -> "Без звука"
            else -> try {
                RingtoneManager.getRingtone(this, Uri.parse(saved))?.getTitle(this)
                    ?: "Выбран системный звук"
            } catch (_: Exception) { "Выбран системный звук" }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SOUND && resultCode == RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("sound_uri", uri?.toString() ?: "silent").apply()
            updateSoundLabel()
        }
    }

    override fun onDestroy() {
        windowHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
