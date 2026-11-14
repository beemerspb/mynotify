package com.mynotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Автозапуск фоновой службы после перезагрузки телефона/планшета/ТВ.
 * Стартуем только если устройство уже привязано (есть pair_code) и
 * выдано разрешение на поверх других приложений — без окон и диалогов.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val paired = p.getString("pair_code", null) != null
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
        if (!paired || !overlay) return
        val si = Intent(context, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(si)
        } else {
            context.startService(si)
        }
    }
}
