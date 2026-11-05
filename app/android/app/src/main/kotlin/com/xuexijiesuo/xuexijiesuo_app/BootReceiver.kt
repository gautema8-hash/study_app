package com.xuexijiesuo.xuexijiesuo_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启（C3）：仅孩子设备（本地 childMode 标记）响应 BOOT_COMPLETED；
 * 家长设备不注册任何行为。国产 ROM 需用户手动授予「自启动」白名单，代码无法强制。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON") return

        val child = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_CHILD, false)
        if (!child) return // 家长设备：完全不启动

        // 启动即直达 /gate（Flutter 侧按本地标记选择初始路由）
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { context.startActivity(launch) } catch (_: Exception) {}

        val guard = Intent(context, GuardService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(guard)
            else context.startService(guard)
        } catch (_: Exception) {}
    }
}
