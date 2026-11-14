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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
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

    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var codeText: TextView
    private lateinit var urlInput: EditText
    private lateinit var launchBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var windowBtn: Button
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

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

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
    private fun iosButton(text: String, color: Int): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
        }
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
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        pairCard.addView(urlInput)
        pairCard.addView(iosButton("Сохранить адрес", cGray).apply {
            setOnClickListener {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("ha_url", urlInput.text.toString().trim().trimEnd('/'))
                    .apply()
                Toast.makeText(this@MainActivity, "Адрес сохранён", Toast.LENGTH_SHORT).show()
            }
        })
        if (!hasOverlayPermission()) {
            permBtn = iosButton("Разрешить показ поверх приложений", cOrange).apply {
                setOnClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
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
        lay.addView(winCard)

        lay.addView(footer("MyNotify · Home Assistant"))

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

    override fun onDestroy() {
        windowHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
