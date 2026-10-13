package com.nbaltas.nickos.node

import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.nbaltas.nickos.MainActivity
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Needed for Device Owner. Set by the QR setup or once over adb: see SETUP.md. */
class AdminReceiver : DeviceAdminReceiver() {
    /** Pre-Android-12 QR setup finishes here: remember the role and open NickOS's own setup. */
    override fun onProfileProvisioningComplete(ctx: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        Provisioning.remember(ctx, intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE))
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

data class InstalledApp(val pkg: String, val label: String)

/** Everything a Node can do to its own tablet. */
class NodeDevice(private val ctx: Context) {
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val admin = ComponentName(ctx, AdminReceiver::class.java)

    /** Package most recently launched by NickOS, the fallback when usage access isn't granted. */
    @Volatile var lastLaunched: String? = null

    val isDeviceOwner get() = dpm.isDeviceOwnerApp(ctx.packageName)
    private val isAdmin get() = dpm.isAdminActive(admin)

    /** True when the screen can be switched off: Device Owner or an active Device Admin. Sent to the Master in the status. */
    fun canLockScreen() = AdminPolicy.canLockScreen(isDeviceOwner, isAdmin)

    /** Set once the parent has been shown the Device Admin screen this run, so it is not repeated at every menu. */
    @Volatile var adminAsked = false

    /** The system "Activate device admin app" screen for NickOS, or null when it is not needed. */
    fun adminRequestIntent(): Intent? =
        if (!AdminPolicy.needsAdmin(isDeviceOwner, isAdmin)) null
        else Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, AdminPolicy.EXPLANATION)

    // ---- battery / screen ----
    fun battery(): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return (if (level < 0) 0 else level * 100 / scale) to charging
    }

    /** Battery temperature in tenths of a degree C, or -1 when the tablet does not say. */
    fun batteryTemperatureTenths(): Int =
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

    fun hasCameraPermission() = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    val screenOn get() = power.isInteractive

    fun screenOff() {
        if (canLockScreen()) try { dpm.lockNow() } catch (e: Exception) { Log.w(TAG, "lockNow", e) }
        else Log.w(TAG, "screen off ignored: Device Admin is not active (grown-up menu, Allow screen off)")
    }

    @Suppress("DEPRECATION")
    fun wake() {
        val wl = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "nickos:wake",
        )
        wl.acquire(3000)
    }

    // ---- volume / brightness (0-100) ----
    fun volume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    fun setVolume(pct: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (pct.coerceIn(0, 100) * max / 100f).roundToInt(), 0)
    }

    /** Reach the integrated player's media session when Jellyfin remote commands are ignored. */
    fun mediaKey(key: String) {
        val code = when (key) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            else -> return
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun brightness(): Int = try {
        (Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100f / 255f).roundToInt()
    } catch (e: Exception) { 50 }

    fun setBrightness(pct: Int) {
        val v = (pct.coerceIn(1, 100) * 255f / 100f).roundToInt().toString()
        try {
            if (isDeviceOwner && Build.VERSION.SDK_INT >= 28) {
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString())
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, v)
            } else if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.toInt())
            }
        } catch (e: Exception) { Log.w(TAG, "brightness", e) }
    }

    // ---- apps ----
    fun installedApps(): List<InstalledApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun label(pkg: String): String? = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { null }

    fun iconBase64(pkg: String, px: Int = 96): String? = try {
        val bmp = ctx.packageManager.getApplicationIcon(pkg).toBitmap(px, px, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { null }

    fun launch(pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        lastLaunched = pkg
        return try { ctx.startActivity(i); true } catch (e: Exception) { Log.w(TAG, "launch $pkg", e); false }
    }

    fun goHome() {
        lastLaunched = null
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /**
     * Home, stop Jellyfin, reopen. killBackgroundProcesses can't stop it while its playback service
     * runs, but hiding an app stops all its processes, so the kid lock (Device Owner) uses that.
     */
    fun restart(pkg: String) {
        goHome()
        Thread {
            Thread.sleep(800)
            if (isDeviceOwner) try {
                dpm.setApplicationHidden(admin, pkg, true)
                dpm.setApplicationHidden(admin, pkg, false)
            } catch (e: Exception) { Log.w(TAG, "restart $pkg", e) }
            else ctx.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
            Thread.sleep(1200)
            launch(pkg)
        }.start()
    }

    fun jellyfinPackage(): String? = JELLYFIN_PACKAGES.firstOrNull { label(it) != null }

    private var usageCursor = 0L
    private var usageLast: String? = null

    /**
     * Foreground package, via usage access when granted (SETUP.md), else what NickOS last opened.
     * Reads usage events incrementally: 6h back the first time, then only what's new.
     */
    @Synchronized
    fun foregroundPackage(homeVisible: Boolean): String? {
        if (homeVisible) return null
        if (hasUsageAccess()) {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val from = if (usageCursor == 0L) now - 6 * 60 * 60 * 1000L else usageCursor - 2000
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) usageLast = e.packageName
            }
            usageCursor = now
            val last = usageLast
            if (last != null) return if (last == ctx.packageName || isLauncher(last)) null else last
        }
        return lastLaunched
    }

    private fun isLauncher(pkg: String): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    }

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun canOverlay() = Settings.canDrawOverlays(ctx)

    // ---- lockdown ----
    /**
     * Kiosk mode, the same Android feature Fully Kiosk Plus uses. Only works as Device Owner.
     * [packages] are the apps the kid may enter; everything else (home, recents, settings,
     * notifications) is blocked while the Node activity runs in lock task.
     */
    fun applyLockdown(packages: List<String>) {
        if (!isDeviceOwner) return
        try {
            dpm.setLockTaskPackages(admin, (listOf(ctx.packageName) + packages).distinct().toTypedArray())
            if (Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, MainActivity::class.java))
            dpm.setKeyguardDisabled(admin, true)
            if (Build.VERSION.SDK_INT >= 33) dpm.setPermissionGrantState(
                admin, ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            // Watch together reads the tablet's video files (and writes them, before Android 10).
            LocalVideos.permissions().forEach {
                dpm.setPermissionGrantState(admin, ctx.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
            // Tablet cameras. Android 12+ can refuse this (sensor permissions opted out at setup, some
            // makers' builds), so the answer is never assumed: hasCameraPermission() is what counts.
            if (!hasCameraPermission() && !dpm.setPermissionGrantState(
                    admin, ctx.packageName, android.Manifest.permission.CAMERA, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            ) Log.w(TAG, "device policy did not grant the camera permission")
            dpm.setStatusBarDisabled(admin, true)
        } catch (e: Exception) { Log.w(TAG, "lockdown", e) }
    }

    /** Undo everything [applyLockdown] did and give up Device Owner, so the tablet is normal again. */
    /**
     * Parent mode: end lock task for every task. stopLockTask() only releases the caller's task, so
     * Jellyfin (or any app the kid opened) kept the tablet locked. Kid mode re-applies the allowlist.
     */
    fun pauseLockdown() {
        if (!isDeviceOwner) return
        try { dpm.setLockTaskPackages(admin, arrayOf()) } catch (e: Exception) { Log.w(TAG, "pause lockdown", e) }
    }

    fun releaseLockdown() {
        if (!isDeviceOwner) return
        try {
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
            dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
            dpm.setLockTaskPackages(admin, arrayOf())
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(ctx.packageName)
        } catch (e: Exception) { Log.w(TAG, "release", e) }
    }

    fun deviceNames(): List<String> = listOfNotNull(
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME),
        Build.MODEL,
    ).distinct()

    companion object {
        private const val TAG = "NickOS.Device"
        val JELLYFIN_PACKAGES = listOf("org.jellyfin.mobile", "org.jellyfin.androidtv")
    }
}
