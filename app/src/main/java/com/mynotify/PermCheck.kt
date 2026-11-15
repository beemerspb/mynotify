package com.mynotify

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * «Разрешение поверх других приложений» с учётом TV-прошивок:
 * Settings.canDrawOverlays на части из них даёт false-negative даже при
 * Allow в appops — проверяем appops напрямую и по флагу службы о фактически
 * успешном wm.addView. Общая проверка для MainActivity и BootReceiver,
 * иначе после ребута на таких прошивках автозапуск службы молча не стартует.
 */
fun Context.hasOverlayPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    if (Settings.canDrawOverlays(this)) return true
    val opsAllowed = try {
        val aom = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = aom.checkOpNoThrow(
            "android.system.alert_window", Process.myUid(), packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
    return opsAllowed ||
        getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("overlay_ok", false)
}
