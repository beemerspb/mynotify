package com.mynotify

import android.app.Activity
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
 * Экран-мониторинг: приложением управляет Home Assistant, здесь только
 * статус и аварийные кнопки. Кнопка разрешения исчезает после выдачи.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: TextView
    private lateinit var launchBtn: Button
    private lateinit var stopBtn: Button
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

    /** Аварийный запуск (обычно всё приходит напрямую в службу из HA). */
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
        startOverlay(url, intent.getIntExtra(OverlayService.EXTRA_AUTOHIDE_SEC, 0))
        if (intent.action == "com.mynotify.SHOW") finish()
    }

    private fun startOverlay(url: String?, autohideSec: Int) {
        val si = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
            url?.let { putExtra(OverlayService.EXTRA_URL, it) }
            if (autohideSec > 0) putExtra(OverlayService.EXTRA_AUTOHIDE_SEC, autohideSec)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si)
        else startService(si)
    }

    private fun stopOverlay() {
        startService(
            Intent(this, OverlayService::class.java)
                .apply { action = OverlayService.ACTION_HIDE }
        )
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun updateStatus() {
        val running = OverlayService.running
        statusDot.text = if (running) "●" else "○"
        statusDot.setTextColor(
            if (running) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
        statusText.text =
            if (running) "Служба запущена — окно показано"
            else "Служба остановлена — нажмите «Запустить службу»"
        launchBtn.backgroundTintList = ColorStateList.valueOf(
            if (running) Color.parseColor("#4CAF50") else Color.parseColor("#2196F3"))
        launchBtn.isEnabled = !running
        stopBtn.isEnabled = running
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
                startOverlay(null, 0)
                updateStatus()
            }
        }
        lay.addView(launchBtn)

        stopBtn = niceButton("■  Остановить службу").apply {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
            setOnClickListener {
                stopOverlay()
                updateStatus()
            }
        }
        lay.addView(stopBtn)

        lay.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            text = "Приложение — служба показа. Камеру и устройство выбирают " +
                "в Home Assistant (интеграция MyNotify)."
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
}
