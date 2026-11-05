package com.xuexijiesuo.xuexijiesuo_app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 孩子端前台守护（提醒级降级方案）：
 * - 仅孩子设备启用；每 3 秒检查本应用是否在前台，被切离时拉回（LockTask 生效时系统原生拦截，此为兜底）。
 * - 被杀后 START_STICKY 自动重启；childMode 被家长密码清除时自动停止。
 * - 强管控例外（fail-open / 会员到期）由 Flutter 调 disable 停止本服务，绝不锁死。
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checker = object : Runnable {
        override fun run() {
            if (!childMode()) { stopSelf(); return }
            if (!isSelfForeground()) {
                bringToFront()
                notifyLeave() // 切离时发醒目提醒，点击通知即可回到学习界面
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotification("学习解锁 · 孩子端守护", "正在保持学习界面"))
        handler.post(checker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 被从最近任务移除后自恢复（仅孩子设备）
        if (childMode()) {
            val restart = Intent(applicationContext, GuardService::class.java)
            try { startForegroundService(restart) } catch (_: Exception) {}
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(checker)
        super.onDestroy()
    }

    private fun childMode(): Boolean =
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_CHILD, false)

    private fun isSelfForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any {
            it.processName == packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    private fun bringToFront() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(i) } catch (_: Exception) {}
    }

    private var lastLeaveNotice = 0L

    /** 切离提醒（60 秒节流，避免刷屏），点击通知直接回到孩子端 */
    private fun notifyLeave() {
        val now = System.currentTimeMillis()
        if (now - lastLeaveNotice < 60_000) return
        lastLeaveNotice = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2, buildNotification("请回到学习解锁", "检测到切离，点击返回学习界面"))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "孩子端守护",
            NotificationManager.IMPORTANCE_LOW))
    }

    companion object { const val CHANNEL_ID = "kiosk_guard" }
}
