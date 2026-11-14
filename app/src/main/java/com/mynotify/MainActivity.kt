package com.mynotify

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Экран-мониторинг. Две независимые пары действий:
 *  — «Запустить/Остановить службу»: служба живёт в фоне, к ней подключается HA;
 *  — «Показать/Скрыть окно»: только окно, на работу службы не влияет.
 * Камеру и трансляцию выбирают в Home Assistant (интеграция MyNotify).
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var launchBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var windowBtn: Button
    private var permBtn: Button? = null

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

    private fun updateStatus() {
        val running = OverlayService.running
        val shown = OverlayService.windowShown
        statusDot.text = if (running) "●" else "○"
        statusDot.setTextColor(
            if (running) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
        statusText.text = when {
            !running -> "Служба остановлена — нажмите «Запустить службу»"
            shown -> "Служба запущена — окно показано"
            else -> "Служба запущена — окно скрыто"
        }
        launchBtn.backgroundTintList = ColorStateList.valueOf(
            if (running) Color.parseColor("#4CAF50") else Color.parseColor("#2196F3"))
        launchBtn.isEnabled = !running
        stopBtn.isEnabled = running
        windowBtn.isEnabled = running
        windowBtn.text = if (shown) "Скрыть окно" else "Показать окно"
        if (hasOverlayPermission()) // выдано — больше не показываем
            permBtn?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun niceButton(text: String): Button = Button(this).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setAllCaps(false)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        layoutParams = lp
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val pad = dp(16)
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = TextView(this).apply {
            text = "○"
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, 0, dp(10), 0)
        }
        statusText = TextView(this).apply {
            text = "Служба остановлена — нажмите «Запустить службу»"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        header.addView(statusDot)
        header.addView(statusText)
        lay.addView(header)

        if (!hasOverlayPermission()) {
            permBtn = niceButton("Выдать разрешение «Поверх приложений»").apply {
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
                setOnClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            lay.addView(permBtn)
        }

        launchBtn = niceButton("▶  Запустить службу").apply {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
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
        lay.addView(launchBtn)

        stopBtn = niceButton("■  Остановить службу").apply {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
            setOnClickListener {
                sendToService(OverlayService.ACTION_STOP)
                updateStatus()
            }
        }
        lay.addView(stopBtn)

        windowBtn = niceButton("Показать окно").apply {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3F51B5"))
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
        lay.addView(windowBtn)

        lay.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            text = "Служба работает в фоне — к ней подключается Home Assistant " +
                "(интеграция MyNotify): там выбирается камера и показ окна."
        })

        setContentView(
            ScrollView(this).apply {
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
