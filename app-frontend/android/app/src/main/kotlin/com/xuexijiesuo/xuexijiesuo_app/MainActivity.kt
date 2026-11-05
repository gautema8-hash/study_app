package com.xuexijiesuo.xuexijiesuo_app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.app.ActivityManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 孩子端 Kiosk 守护（C2/C3）：
 * - 仅在本地 childMode 标记为真（孩子设备）时启用；家长设备完全不启用 → 家长端行为零改动。
 * - Device Owner + LockTask 模式：禁止 HOME/返回/最近任务/切应用；
 *   未成为 Device Owner 时降级为「前台守护 + 提醒级」，绝不锁死设备。
 * - 紧急呼叫通过 ACTION_DIAL 放行（FR-17 安全底线）。
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val CHANNEL = "xuexijiesuo/kiosk"
        const val PREFS = "FlutterSharedPreferences"
        const val KEY_CHILD = "flutter.childMode"
    }

    private var activationRequested = false
    private var kioskActive = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * HOME 键 / 最近任务 / 切应用时系统回调：孩子模式且守护已启用时立即拉回本应用，
     * 避免通过 HOME 直接退出绕过使用限制（LockTask 生效时系统层本身也会拦截）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!childMode() || !kioskActive) return
        handler.postDelayed({
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.moveTaskToFront(taskId, 0)
            } catch (_: Exception) {}
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (_: Exception) {}
        }, 250)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "enable" -> result.success(enableKiosk())
                "disable" -> { disableKiosk(); result.success(true) }
                "isActive" -> result.success(isDeviceOwner())
                "requestActivation" -> { requestActivation(); result.success(null) }
                "openDialer" -> {
                    val number = call.argument<String>("number") ?: "110"
                    openDialer(number)
                    result.success(null)
                }
                "openHomeSettings" -> { openHomeSettings(); result.success(null) }
                "openNotificationSettings" -> { openNotificationSettings(); result.success(null) }
                else -> result.notImplemented()
            }
        }
    }

    private fun childMode(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHILD, false)

    private fun dpm(): DevicePolicyManager =
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(): ComponentName = ComponentName(this, KioskAdminReceiver::class.java)

    private fun isDeviceOwner(): Boolean = dpm().isDeviceOwnerApp(packageName)

    /** C2：四个条件之一不满足即不启用系统级锁定 */
    private fun enableKiosk(): Boolean {
        if (!childMode()) return false // 家长设备：不启用
        kioskActive = true
        requestNotificationPermission() // Android 13+ 通知需运行时授权
        setKioskLauncher(true) // 系统 HOME 兜底（开机自启 + 按 HOME 回孩子端）
        startGuard()
        if (!isDeviceOwner()) return false // 未授权 → 提醒级
        return try {
            // 白名单放行拨号/紧急呼叫（FR-17）
            dpm().setLockTaskPackages(admin(), arrayOf(packageName, "com.android.dialer"))
            startLockTask()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun disableKiosk() {
        kioskActive = false
        setKioskLauncher(false) // 家长密码退出后恢复系统默认桌面
        try { stopLockTask() } catch (_: Exception) {}
        try { stopService(Intent(this, GuardService::class.java)) } catch (_: Exception) {}
    }

    /** 动态启用/禁用 HOME 别名：仅孩子设备启用，家长设备永不触碰 */
    private fun setKioskLauncher(enabled: Boolean) {
        try {
            val alias = ComponentName(this, "com.xuexijiesuo.xuexijiesuo_app.KioskLauncher")
            val state = if (enabled)
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            packageManager.setComponentEnabledSetting(alias, state,
                android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}
    }

    /** 首次绑定孩子设备时的一次性系统授权引导（家长在孩子设备上确认，家长端 UI 不改动） */
    private fun requestActivation() {
        if (!childMode() || isDeviceOwner() || activationRequested) return
        activationRequested = true
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin())
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "孩子端守护：开机自动启动、防止答题时退出或切换应用；家长端不受影响")
            startActivity(intent)
        } catch (_: Exception) {}
    }

    /** 申请通知权限：切离提醒通知在 Android 13+ 需要运行时授权 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001) } catch (_: Exception) {}
        }
    }

    /** 引导家长把孩子端设为默认桌面：系统级解决 HOME 键退出（一次性授权） */
    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** 引导家长开启守护通知（部分 ROM 默认关闭通知渠道） */
    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun openDialer(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun startGuard() {
        if (!childMode()) return
        try { startForegroundService(Intent(this, GuardService::class.java)) } catch (_: Exception) {}
    }
}
