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
    private var checkingUpdate = false
    private var permBtn: Button? = null

    private val cLabel get() = ContextCompat.getColor(this, R.color.ios_label)
    private val cSecondary get() = ContextCompat.getColor(this, R.color.ios_secondary)
    private val cBlue get() = ContextCompat.getColor(this, R.color.ios_blue)
    private val cGreen get() = ContextCompat.getColor(this, R.color.ios_green)
    private val cRed get() = ContextCompat.getColor(this, R.color.ios_red)
    private val cOrange get() = ContextCompat.getColor(this, R.color.ios_orange)
    private val cGray get() = ContextCompat.getColor(this, R.color.ios_gray)

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
        launchBtn.isEnabled = !running
        launchBtn.alpha = if (running) 0.35f else 1f
        stopBtn.isEnabled = running
        stopBtn.alpha = if (running) 1f else 0.35f
        windowBtn.isEnabled = running
        windowBtn.alpha = if (running) 1f else 0.35f
        windowBtn.text = if (shown) "Скрыть окно" else "Показать окно"
        if (hasOverlayPermission()) // выдано — больше не показываем
            permBtn?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    // ---------- iOS-строители ----------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Заголовок-лист: серый капс поверх секции (iOS grouped table header). */
    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(cSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        letterSpacing = 0.06f
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(dp(20), dp(22), dp(20), dp(6))
    }

    /** Белая скруглённая карточка-секция. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, R.color.ios_card))
            cornerRadius = dp(16).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(16); lp.rightMargin = dp(16)
        layoutParams = lp
    }

    /** Кнопка iOS: сплошной акцент, скругление 14, medium 17sp. */
    // Мигающий красный контур фокуса (для пульта ТВ): foreground-кольцо,
    // фон элемента не трогает. Аниматор — в замыкании, не в setTag:
    // setTag(int) с ключом generateViewId() кидает IllegalArgumentException
    // и ронял приложение на первом же фокусе с пульта (краш на ТВ v0.7.11).
    private fun attachFocusBlink(v: View, radiusDp: Int) {
        v.isFocusable = true
        val ring = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(3), android.graphics.Color.parseColor("#FFFF3B30"))
            cornerRadius = dp(radiusDp).toFloat()
        }
        ring.alpha = 0
        if (android.os.Build.VERSION.SDK_INT >= 23) v.foreground = ring
        var anim: android.animation.ObjectAnimator? = null
        v.setOnFocusChangeListener { _, focused ->
            anim?.cancel()
            anim = null
            ring.alpha = 0
            if (focused && android.os.Build.VERSION.SDK_INT >= 23) {
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

    private fun iosButton(text: String, color: Int): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        stateListAnimator = null
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(3), Color.parseColor("#FFFFFFFF"))
                }
            )
            addState(
                intArrayOf(),
                GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(14).toFloat()
                }
            )
        }
        attachFocusBlink(this, 14)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        lp.topMargin = dp(10)
        layoutParams = lp
    }

    private fun footer(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(14), dp(28), dp(24))
    }

    private fun buildUi() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.ios_bg))
            setPadding(0, dp(28), 0, dp(12))
        }

        // Large title
        lay.addView(TextView(this).apply {
            text = "MyNotify"
            setTextColor(cLabel)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            letterSpacing = -0.02f
            setPadding(dp(20), 0, dp(20), dp(2))
        })
        lay.addView(TextView(this).apply {
            text = "Камера Home Assistant поверх любого приложения"
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(20), 0, dp(20), 0)
        })

        // --- Секция «Состояние» ---
        lay.addView(sectionLabel("Состояние"))
        val statusCard = card()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = TextView(this).apply {
            text = "●"
            setTextColor(cGray)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, dp(10), 0)
        }
        statusText = TextView(this).apply {
            text = "Служба остановлена"
            setTextColor(cLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        statusCard.addView(statusRow)
        statusCard.addView(TextView(this).apply {
            text = "Управление камерой и показом — в Home Assistant"
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
        })
        lay.addView(statusCard)

        // --- Секция «Привязка» ---
        lay.addView(sectionLabel("Привязка к Home Assistant"))
        val pairCard = card()
        pairCard.addView(TextView(this).apply {
            text = "КОД ПРИВЯЗКИ"
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        })
        codeText = TextView(this).apply {
            text = "––– –––"
            setTextColor(cBlue)
            setTypeface(Typeface.create("monospace", Typeface.BOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
            letterSpacing = 0.08f
        }
        pairCard.addView(codeText)
        pairCard.addView(TextView(this).apply {
            text = "Введите этот код при добавлении устройства\nв интеграции MyNotify"
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        })
        urlInput = EditText(this).apply {
            setText(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("ha_url", "http://homeassistant.local:8123"))
            hint = "Адрес Home Assistant"
            setSingleLine()
            setTextColor(cLabel)
            setHintTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.ios_field))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // ТВ: поле должно брать фокус и с пульта, и в touch-режиме,
            // плюс мигающий красный контур как у кнопок
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
            setText(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("ha_url_ext", ""))
            hint = "Ссылка HA извне (необязательно): https://ha.example.com"
            setSingleLine()
            setTextColor(cLabel)
            setHintTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.ios_field))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            attachFocusBlink(this, 12)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        pairCard.addView(extUrlInput)
        pairCard.addView(iosButton("Сохранить адреса", cGray).apply {
            setOnClickListener {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("ha_url", urlInput.text.toString().trim().trimEnd('/'))
                    .putString("ha_url_ext", extUrlInput.text.toString().trim().trimEnd('/'))
                    .apply()
                Toast.makeText(this@MainActivity, "Адреса сохранены", Toast.LENGTH_SHORT).show()
            }
        })
        pairCard.addView(TextView(this).apply {
            text = "Второе поле — внешний адрес HA (домен, Nabu Casa, Tailscale). " +
                "Дома приложение использует локальный адрес, а вне сети " +
                "автоматически переключается на внешний."
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        if (!hasOverlayPermission()) {
            permBtn = iosButton("Разрешить показ поверх приложений", cOrange).apply {
                setOnClickListener { openOverlayPermission() }
            }
            pairCard.addView(permBtn)
        }
        lay.addView(pairCard)

        // --- Секция «Служба» ---
        lay.addView(sectionLabel("Фоновая служба"))
        val svcCard = card()
        launchBtn = iosButton("Запустить службу", cGreen).apply {
            setOnClickListener {
                if (!hasOverlayPermission()) {
                    Toast.makeText(
                        this@MainActivity, "Сначала выдайте разрешение", Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                // только служба, без окна — окно показывает HA или кнопка ниже
                sendToService(OverlayService.ACTION_START)
                updateStatus()
            }
        }
        svcCard.addView(launchBtn)
        stopBtn = iosButton("Остановить службу", cRed).apply {
            setOnClickListener {
                sendToService(OverlayService.ACTION_STOP)
                updateStatus()
            }
        }
        svcCard.addView(stopBtn)
        svcCard.addView(TextView(this).apply {
            text = "Служба сама подключается к Home Assistant и ждёт команды — " +
                "там выбирается камера и показ окна. ADB не нужен."
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, 0)
        })
        lay.addView(svcCard)

        // --- Секция «Окно» ---
        lay.addView(sectionLabel("Окно камеры"))
        val winCard = card()
        windowBtn = iosButton("Показать окно", cBlue).apply {
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
        windowBtn.layoutParams = (windowBtn.layoutParams as LinearLayout.LayoutParams)
            .apply { topMargin = 0 }
        winCard.addView(windowBtn)
        winCard.addView(TextView(this).apply {
            text = "ЗВУК ПРИ ПОКАЗЕ ОКНА"
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.08f
            setPadding(0, dp(16), 0, 0)
        })
        soundText = TextView(this).apply {
            setTextColor(cLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(4), 0, 0)
        }
        updateSoundLabel()
        winCard.addView(soundText)
        winCard.addView(iosButton("Выбрать системный звук", cGray).apply {
            setOnClickListener {
                val cur = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("sound_uri", null)
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
        })
        val mutePrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val muteCheck = android.widget.CheckBox(this).apply {
            text = "Заглушать звук видеопотока при старте"
            setTextColor(cLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(16), 0, 0)
            setButtonTintList(
                android.content.res.ColorStateList.valueOf(cBlue)
            )
            isChecked = mutePrefs.getBoolean("mute_on_start", true)
            setOnCheckedChangeListener { _, checked ->
                mutePrefs.edit().putBoolean("mute_on_start", checked).apply()
            }
            // мигающий красный контур фокуса (виден с пульта ТВ)
            attachFocusBlink(this, 10)
        }
        winCard.addView(muteCheck)
        winCard.addView(alphaSlider("Прозрачность (окно и видео)", "overlay_alpha"))
        lay.addView(winCard)

        // --- Секция «Приложение»: версия и встроенное обновление ---
        lay.addView(sectionLabel("Приложение"))
        val appCard = card()
        appCard.addView(TextView(this).apply {
            text = "Версия ${appVersion()}"
            setTextColor(cLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        updText = TextView(this).apply {
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
        }
        appCard.addView(updText)
        appCard.addView(iosButton("Проверить обновления", cBlue).apply {
            setOnClickListener { checkUpdate() }
        })
        appCard.addView(TextView(this).apply {
            text = "Обновление скачивается с GitHub и устанавливается " +
                "обычным системным диалогом."
            setTextColor(cSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        lay.addView(appCard)

        lay.addView(footer("MyNotify ${appVersion()} · Home Assistant"))

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.ios_bg))
                addView(
                    lay,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )
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
