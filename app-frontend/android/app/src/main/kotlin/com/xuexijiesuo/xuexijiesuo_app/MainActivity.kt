package com.xuexijiesuo.xuexijiesuo_app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
