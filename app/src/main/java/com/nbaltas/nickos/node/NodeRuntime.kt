package com.nbaltas.nickos.node

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.nbaltas.nickos.BuildConfig
import com.nbaltas.nickos.cam.CamMessages
import com.nbaltas.nickos.cam.CamProfile
import com.nbaltas.nickos.cam.NodeCam
import com.nbaltas.nickos.data.LinkedMaster
import com.nbaltas.nickos.data.ResumeCard
import com.nbaltas.nickos.data.Store
import com.nbaltas.nickos.link.Conn
import com.nbaltas.nickos.link.NetMessages
import com.nbaltas.nickos.link.NetWatch
import com.nbaltas.nickos.link.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** What [Advertiser] needs from NSD, so the sequencing can be tested with a fake. */
interface AdvertGate {
    /** Starts a NEW registration (a reused listener throws) and reports once whether it took. Returns its handle. */
    fun register(port: Int, onResult: (Boolean) -> Unit): Any
    /** Stops [handle]; [onDone] runs when the system confirms or refuses. */
    fun unregister(handle: Any, onDone: () -> Unit)
}

/** The real thing: one fresh listener per registration. */
class NsdGate(private val ctx: Context, private val store: Store) : AdvertGate {
    private val nsd get() = ctx.getSystemService(NsdManager::class.java)

    override fun register(port: Int, onResult: (Boolean) -> Unit): Any {
        val info = NsdServiceInfo().apply {
            serviceName = Wire.serviceName(store.deviceId)
            serviceType = Wire.SERVICE_TYPE
            this.port = port
        }
        val l = Reg(port, onResult)
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w("NickOS.Node", "nsd unavailable", e); onResult(false) }
        return l
    }

    override fun unregister(handle: Any, onDone: () -> Unit) {
        val l = handle as Reg
        l.done = onDone
        try { nsd.unregisterService(l) } catch (e: Exception) { onDone() }
    }

    private class Reg(val port: Int, val onResult: (Boolean) -> Unit) : NsdManager.RegistrationListener {
        @Volatile var done: (() -> Unit)? = null
        override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { Log.w("NickOS.Node", "nsd register failed $code"); onResult(false) }
        override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) { done?.invoke() }
        override fun onServiceRegistered(si: NsdServiceInfo) { Log.i("NickOS.Node", "advertising ${si.serviceName}:$port"); onResult(true) }
        override fun onServiceUnregistered(si: NsdServiceInfo) { done?.invoke() }
    }
}

/**
 * Keeps this Node's mDNS record alive across network changes. [refresh] unregisters, WAITS for the system to
 * confirm (registering the same service name while the old one is still going makes Android rename the new one),
 * then registers with a brand new listener. Races it handles:
 *  - A burst of refreshes runs one at a time, and refreshes queued behind a running one collapse into one.
 *  - [stop] during a refresh: nothing registers after the unregister ack.
 *  - A registration that fails right after a network change (the daemon is still restarting) is retried.
 */
class Advertiser(
    private val scope: CoroutineScope,
    private val gate: AdvertGate,
    private val ackTimeoutMs: Long = 1500,
    private val resultTimeoutMs: Long = 5000,
    private val retryMs: Long = 2000,
    private val maxRetries: Int = 3,
) {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var stopped = true
    private var port = 0
    private var current: Any? = null

    fun start(port: Int) {
        stopped = false
        scope.launch { lock.withLock { this@Advertiser.port = port; if (!stopped) register() } }
    }

    fun refresh() {
        if (stopped || !queued.compareAndSet(false, true)) return
        scope.launch {
            lock.withLock {
                queued.set(false)
                if (stopped) return@withLock
                unregister()
                if (!stopped) register()
            }
        }
    }

    fun stop() {
        stopped = true
        scope.launch { lock.withLock { unregister() } }
    }

    private suspend fun register() {
        repeat(maxRetries + 1) { attempt ->
            val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val handle = gate.register(port) { result.complete(it) }
            val ok = withTimeoutOrNull(resultTimeoutMs) { result.await() }
            if (ok != false) { current = handle; return }   // registered, or no word yet: keep the handle so stop can undo it
            if (attempt < maxRetries) { delay(retryMs); if (stopped) return }
        }
    }

    private suspend fun unregister() {
        val h = current ?: return
        current = null
        val ack = kotlinx.coroutines.CompletableDeferred<Unit>()
        gate.unregister(h) { ack.complete(Unit) }
        withTimeoutOrNull(ackTimeoutMs) { ack.await() }
    }
}

/** "Dad's Tablet" → "Dad" for "Screen locked by Dad". */
fun parentName(masterName: String?): String {
    val n = masterName?.trim().orEmpty()
    return Regex("^(.+?)['’]s\\s+tablet$", RegexOption.IGNORE_CASE).find(n)?.groupValues?.get(1) ?: n.ifEmpty { "a grown-up" }
}

/**
 * The Node side of the link. Runs inside [NodeService] so the Master can still reach the tablet
 * while a kid is in Jellyfin or the screen is off.
 */
class NodeRuntime(private val ctx: Context, private val store: Store) {
    val device = NodeDevice(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = CopyOnWriteArrayList<Pair<String, Conn>>()
    private var server: ServerSocket? = null
    private val advertiser = Advertiser(scope, NsdGate(ctx, store))
    private var jobs = mutableListOf<Job>()

    /** Saves the car hotspot so Android joins it unaided; see [WifiJoin]. */
    val wifi = WifiJoin(ctx, { device.isDeviceOwner }, currentSsid = {
        @Suppress("DEPRECATION")
        val s = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.ssid
        s?.takeIf { it.isNotEmpty() && !it.contains("unknown ssid", ignoreCase = true) }
    })
    private var netWatch: NetWatch? = null
    private var approvalReceiver: android.content.BroadcastReceiver? = null

    /** Set by the Node activity while the kid home screen is showing. */
    @Volatile var homeVisible = false

    /** After the parent PIN: kiosk and "lock to app" are paused until this time. */
    @Volatile var parentUntil = 0L
    val parentMode get() = System.currentTimeMillis() < parentUntil

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode

    private val _paired = MutableSharedFlow<LinkedMaster>(extraBufferCapacity = 4)
    val paired: SharedFlow<LinkedMaster> = _paired

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    /** Master ids currently connected. */
    val connected: StateFlow<Set<String>> = _connected

    /** Seconds until bedtime while the 5-minute warning should show, else null. */
    private val _bedtimeWarning = MutableStateFlow<Int?>(null)
    val bedtimeWarning: StateFlow<Int?> = _bedtimeWarning

    val overlay = NodeOverlay(ctx, store, this)

    /** Watch together: the movie library, the synced player and the answers to its commands. */
    val together = NodeTogether(ctx, store, device, scope, { homeVisible }, ::broadcast)
    val videos get() = together.videos
    val syncPlayer get() = together.player

    /** The front camera, streamed to a Master that asks for it. */
    val cam = CamStreamer(ctx, this, device)
    fun broadcastCamState() = scope.launch { broadcast(cam.stateJson()) }

    @Synchronized
    fun start() {
        if (server != null) return
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Wire.PORT)) }
        } catch (e: Exception) {
            ServerSocket(0)
        }
        server = ss
        Wire.multicastLock(ctx)
        advertiser.start(ss.localPort)
        wifi.ensureOn()
        approvalReceiver = wifi.watchApproval()
        // Every network change: advertise again (the old mDNS record points at the old network), and turn WiFi
        // back on if it dropped. The ServerSocket binds the wildcard address, so it needs nothing.
        netWatch = NetWatch(ctx) { wifi.ensureOn(); wifi.refreshApproval(); advertiser.refresh() }.also { it.start() }
        jobs += scope.launch { acceptLoop(ss) }
        jobs += scope.launch { statusLoop() }
        jobs += scope.launch { bedtimeLoop() }
        together.start()
        applyLockdown()
    }

    fun openPairing(): String {
        val code = (SecureRandom().nextInt(900000) + 100000).toString()
        _pairingCode.value = code
        return code
    }

    fun closePairing() { _pairingCode.value = null }

    // ---- connections ----
    private fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive && !ss.isClosed) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            Thread { handle(Conn(s)) }.start()
        }
    }

    private fun handle(c: Conn) {
        var masterId: String? = null
        try {
            val first = c.read() ?: return c.close()
            when (first.optString("t")) {
                // The Master's sweep asking who is here. No auth: the id is already broadcast by mDNS.
                "who" -> {
                    c.send(NetMessages.iam(store.deviceId, store.name.value)); return c.close()
                }
                "pair" -> {
                    val code = _pairingCode.value
                    if (code == null || first.optString("code") != code) {
                        c.send(JSONObject().put("t", "pairFail")); return c.close()
                    }
                    val m = LinkedMaster(first.getString("masterId"), first.optString("masterName", "Parent's Tablet"), Store.randomHex(16))
                    store.addMaster(m)
                    // A fresh kid tablet starts with Jellyfin on its home screen, if it has it.
                    if (store.allowed.value.isEmpty()) device.jellyfinPackage()?.let { store.setAllowed(listOf(it)) }
                    first.optString("pin").takeIf { it.length == 4 }?.let { store.setPin(it) }
                    c.send(status().put("t", "paired").put("token", m.token).put("nodeId", store.deviceId))
                    masterId = m.id
                    _pairingCode.value = null
                    _paired.tryEmit(m)
                }
                "hello" -> {
                    val m = store.masters.value.firstOrNull { it.id == first.optString("masterId") && it.token == first.optString("token") }
                    if (m == null) { c.send(JSONObject().put("t", "denied")); return c.close() }
                    first.optString("masterName").takeIf { it.isNotEmpty() && it != m.name }?.let { store.addMaster(m.copy(name = it)) }
                    c.send(JSONObject().put("t", "welcome"))
                    // A Master that reached us FROM its car-network address proves this Node is on that network.
                    wifi.linkedFrom(c.socket.inetAddress?.hostAddress)
                    masterId = m.id
                }
                else -> return c.close()
            }
            val id = masterId!!
            sessions.removeAll { (mid, old) -> (mid == id).also { if (it) old.close() } }
            sessions += id to c
            _connected.value = sessions.map { it.first }.toSet()
            c.send(status())
            c.send(cam.stateJson()) // a Master that reconnects learns the camera is already on
            while (true) {
                val msg = c.read() ?: break
                if (msg.optString("t") == "cmd") try { command(id, msg) } catch (e: Exception) { Log.w(TAG, "cmd failed $msg", e) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            c.close()
            sessions.removeAll { it.second === c }
            _connected.value = sessions.map { it.first }.toSet()
            if (sessions.isEmpty()) cam.stop(NodeCam.LINK) // no parent tablet left to look
        }
    }

    fun broadcast(o: JSONObject) = sessions.forEach { it.second.send(o) }

    fun pushStatus() = scope.launch { broadcast(status()) }

    /** Kid tapped a "keep watching" poster: ask the Master(s) to start it in Jellyfin. */
    fun want(itemId: String) {
        device.jellyfinPackage()?.let { device.launch(it) }
        // Called from a tap: socket writes on the main thread throw, and Conn.send then closes the link.
        scope.launch { broadcast(JSONObject().put("t", "want").put("itemId", itemId)) }
    }

    /**
     * "Reset this tablet": tell the connected Masters first, so they unlink it instead of showing
     * it Offline forever. Best effort: a Master that's away has to unlink it by hand.
     */
    suspend fun sayGoodbye() = withContext(Dispatchers.IO) {
        withTimeoutOrNull(2000) { broadcast(JSONObject().put("t", "reset")) }
        delay(300)
    }

    // ---- commands from a Master ----
    private fun command(masterId: String, m: JSONObject) {
        val master = store.masters.value.firstOrNull { it.id == masterId }
        val c = m.getString("c")
        // Watch together answers the asking Master only, and never with a status broadcast (pings are frequent).
        if (together.command(c, m) { o -> sessions.firstOrNull { it.first == masterId }?.second?.send(o) }) return
        if (c in NodeTogether.LEAVES_PLAYER) syncPlayer.stop()
        when (c) {
            // The camera answers with its own camState, never with a status broadcast.
            "camStart" -> { CamMessages.decodeStart(m)?.let { cam.start(CamProfile.of(it.profile), it.maxMs, Store.randomHex(16)) }; return }
            "camStop" -> { cam.stop(NodeCam.STOPPED); return }
            "media" -> device.mediaKey(m.getString("key"))
            "volume" -> device.setVolume(m.getInt("v"))
            "brightness" -> device.setBrightness(m.getInt("v"))
            "screen" -> if (m.getBoolean("on")) device.wake() else device.screenOff()
            "lock" -> {
                if (m.getBoolean("on")) {
                    store.setLockedBy(master?.name ?: "Parent's Tablet")
                    device.wake()
                } else store.setLockedBy(null)
                overlay.refresh()
            }
            "bedtime" -> {
                val min = m.optInt("minutes")
                store.setBedtimeAt(if (min > 0) System.currentTimeMillis() + min * 60_000L else 0)
                bedtimeBy = master?.name
            }
            "open" -> {
                val pkg = m.getString("pkg")
                if (store.lockedApp.value != null && store.lockedApp.value != pkg) store.setLockedApp(null)
                applyLockdown(extra = pkg)
                device.launch(pkg)
            }
            "home" -> {
                store.setLockedApp(null)
                applyLockdown()
                device.goHome()
            }
            "allow" -> {
                val pkg = m.getString("pkg")
                val cur = store.allowed.value
                store.setAllowed(if (m.getBoolean("on")) (cur - pkg) + pkg else cur - pkg)
                applyLockdown()
            }
            "lockApp" -> {
                val pkg = m.optString("pkg").ifEmpty { null }
                store.setLockedApp(pkg)
                applyLockdown()
                if (pkg != null) device.launch(pkg) else device.goHome()
            }
            "rename" -> {
                m.optString("name").takeIf { it.isNotBlank() }?.let { store.setName(it) }
                if (m.has("color")) store.setColor(m.getInt("color"))
                m.optString("avatar").takeIf { it.isNotEmpty() }?.let { store.setAvatar(it) }
            }
            "pin" -> m.optString("pin").takeIf { it.length == 4 }?.let { store.setPin(it) }
            "resume" -> {
                val a = m.getJSONArray("cards")
                store.setResume((0 until a.length()).map { ResumeCard.from(a.getJSONObject(it)) })
            }
            "restart" -> m.optString("pkg").ifEmpty { device.jellyfinPackage() }?.let { device.restart(it) }
            "apps" -> sendApps()
            "update" -> scope.launch { com.nbaltas.nickos.update.Updater.updateIfNewer(ctx) }
            "unlink" -> store.removeMaster(masterId)
            // Save (or with an empty ssid forget) the car hotspot. Never reconnects: Android picks the network.
            "wifi" -> NetMessages.decodeWifiCmd(m)?.let { wifi.save(it.ssid, it.pass, it.gw) }
        }
        broadcast(status())
    }

    @Volatile private var bedtimeBy: String? = null

    private fun sendApps() {
        val list = JSONArray()
        device.installedApps().forEach { a ->
            list.put(JSONObject().put("pkg", a.pkg).put("label", a.label).put("icon", device.iconBase64(a.pkg)))
        }
        broadcast(JSONObject().put("t", "apps").put("list", list))
    }

    /** Re-applies kiosk lockdown with the current allowlist (plus [extra], a one-off parent "open"). */
    fun applyLockdown(extra: String? = null) {
        device.applyLockdown(store.allowed.value + listOfNotNull(store.lockedApp.value, extra, device.jellyfinPackage()))
    }

    fun status(): JSONObject {
        val (bat, charging) = device.battery()
        val fg = device.foregroundPackage(homeVisible)
        return JSONObject()
            .put("t", "status")
            .put("name", store.name.value).put("color", store.color.value).put("avatar", store.avatar.value)
            .put("battery", bat).put("charging", charging).put("screenOn", device.screenOn)
            .put("volume", device.volume()).put("brightness", device.brightness())
            .put("fg", fg).put("fgLabel", fg?.let { device.label(it) })
            .put("allowed", JSONArray(store.allowed.value))
            .put("lockedApp", store.lockedApp.value).put("lockedBy", store.lockedBy.value)
            .put("bedtimeAt", store.bedtimeAt.value)
            .put("owner", device.isDeviceOwner).put("usage", device.hasUsageAccess()).put("overlay", device.canOverlay()).put("admin", device.canLockScreen())
            .put("jf", device.jellyfinPackage())
            .put("names", JSONArray(device.deviceNames()))
            .put("version", BuildConfig.VERSION_NAME)
            .put("caps", JSONArray(NodeTogether.CAPS + CamMessages.CAP + NetMessages.CAP))
            .put("wifi", NetMessages.encodeWifi(wifi.state()))
            .put("now", System.currentTimeMillis())
    }

    private suspend fun statusLoop() {
        var last = ""
        var lastSent = 0L
        while (scope.isActive) {
            delay(1500)
            if (sessions.isEmpty()) continue
            val s = status()
            val key = s.toString().replace(Regex("\"now\":\\d+"), "")
            val now = System.currentTimeMillis()
            if (key != last || now - lastSent > 10_000) {
                broadcast(s); last = key; lastSent = now
            }
        }
    }

    private suspend fun bedtimeLoop() {
        while (scope.isActive) {
            delay(1000)
            val at = store.bedtimeAt.value
            if (at <= 0) { _bedtimeWarning.value = null; overlay.refresh(); continue }
            val left = ((at - System.currentTimeMillis()) / 1000).toInt()
            if (left <= 0) {
                _bedtimeWarning.value = null
                store.setBedtimeAt(0)
                store.setLockedBy(bedtimeBy ?: store.masters.value.firstOrNull()?.name ?: "Parent's Tablet")
                overlay.refresh()
                device.screenOff()
                broadcast(status())
            } else {
                _bedtimeWarning.value = if (left <= 300) left else null
            }
            overlay.refresh()
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        try { server?.close() } catch (_: Exception) {}
        server = null
        sessions.forEach { it.second.close() }; sessions.clear()
        advertiser.stop()
        netWatch?.stop(); netWatch = null
        approvalReceiver?.let { try { ctx.applicationContext.unregisterReceiver(it) } catch (_: Exception) {} }
        approvalReceiver = null
        together.release()
        cam.destroy()
        overlay.hideAll()
    }

    companion object { private const val TAG = "NickOS.Node" }
}
