package com.nbaltas.nickos.master

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import com.nbaltas.nickos.data.Hotspot
import com.nbaltas.nickos.data.LinkedNode
import com.nbaltas.nickos.data.Role
import com.nbaltas.nickos.data.Store
import com.nbaltas.nickos.data.optStr
import com.nbaltas.nickos.jellyfin.JfItem
import com.nbaltas.nickos.jellyfin.JfSession
import com.nbaltas.nickos.jellyfin.Jellyfin
import com.nbaltas.nickos.link.Conn
import com.nbaltas.nickos.link.LanAddress
import com.nbaltas.nickos.link.NetMessages
import com.nbaltas.nickos.link.NetWatch
import com.nbaltas.nickos.link.MediaServer
import com.nbaltas.nickos.link.WifiHold
import com.nbaltas.nickos.link.Resolver
import com.nbaltas.nickos.link.Wire
import com.nbaltas.nickos.node.LocalVideos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class NodeStatus(
    val battery: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val volume: Int,
    val brightness: Int,
    val fg: String?,
    val fgLabel: String?,
    val allowed: List<String>,
    val lockedApp: String?,
    val lockedBy: String?,
    /** Bedtime in this tablet's clock (the Node's clock offset removed), 0 when unset. */
    val bedtimeAt: Long,
    val owner: Boolean,
    val usage: Boolean,
    val overlay: Boolean,
    val jf: String?,
    val names: List<String>,
    val version: String,
    /** "sync1" when this Node understands Watch together. Old Nodes omit the field entirely. */
    val caps: List<String> = emptyList(),
    /** False when the Node cannot switch its screen off (no Device Admin). Old Nodes omit it, read as true. */
    val admin: Boolean = true,
) {
    /** Watch together needs the Node's "sync1" capability; absent on a pre-feature Node. */
    val supportsSync: Boolean get() = "sync1" in caps
    /** "del1": the Node deletes a movie when the Master asks. */
    val supportsDelete: Boolean get() = "del1" in caps
    /** Tablet cameras need the Node's "cam1" capability. */
    val supportsCam: Boolean get() = "cam1" in caps

    companion object {
        fun from(o: JSONObject): NodeStatus {
            val skew = System.currentTimeMillis() - o.optLong("now", System.currentTimeMillis())
            val bt = o.optLong("bedtimeAt")
            return NodeStatus(
                battery = o.optInt("battery"), charging = o.optBoolean("charging"), screenOn = o.optBoolean("screenOn"),
                volume = o.optInt("volume"), brightness = o.optInt("brightness"),
                fg = o.optStr("fg"), fgLabel = o.optStr("fgLabel"),
                allowed = o.optJSONArray("allowed").strings(), lockedApp = o.optStr("lockedApp"), lockedBy = o.optStr("lockedBy"),
                bedtimeAt = if (bt > 0) bt + skew else 0,
                owner = o.optBoolean("owner"), usage = o.optBoolean("usage"), overlay = o.optBoolean("overlay"),
                jf = o.optStr("jf"), names = o.optJSONArray("names").strings(), version = o.optString("version"),
                caps = o.optJSONArray("caps").strings(),
                admin = o.optBoolean("admin", true),
            )
        }

        private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

data class RemoteApp(val pkg: String, val label: String, val icon: Bitmap?)

/** A Node's Jellyfin session as last polled, with the time we polled it to extrapolate position. */
data class Playback(val session: JfSession, val at: Long, val remoteSessionId: String = session.id) {
    val item get() = session.item
    fun positionTicks(now: Long = System.currentTimeMillis()): Long {
        val run = session.item?.runTicks ?: 0
        val p = session.posTicks + if (session.paused) 0 else (now - at) * 10_000
        return if (run > 0) p.coerceAtMost(run) else p
    }
}

/**
 * The Master side: finds Nodes on the WiFi, keeps one connection per linked Node, and bridges
 * to Jellyfin so a tap on the Master plays in the kid's real Jellyfin app ("Play On").
 */
class MasterRuntime(private val ctx: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val resolver = Resolver(nsd)
    private val links = LinkTable<Conn>()
    private val loops = LinkLoops(scope) { id -> linkLoop(id) }
    private var started = false

    private val found = FoundTable()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val _discovered = MutableStateFlow<Set<String>>(emptySet())
    val discovered: StateFlow<Set<String>> = _discovered

    /** What each Node last said about its WiFi (hotspot saved, approval pending), for the "Bring tablets online" banner. */
    private val _net = MutableStateFlow<Map<String, NodeNet>>(emptyMap())
    val net: StateFlow<Map<String, NodeNet>> = _net

    /**
     * The car network this tablet hosts (a Wi-Fi Direct group with a fixed name and passphrase). Created here, not in
     * a service, so it also exists during Setup before the role is saved; [MasterService] only keeps the process alive.
     */
    val carNet = CarNet(
        port = AndroidP2pPort(ctx), scope = scope,
        wanted = { Hotspot(CarNetNames.ssid(store.deviceId), store.carNetPass(), direct = true) },
        manual = { store.hotspot.value?.direct == false },
        onUp = { store.adoptDirectHotspot(it) },
    )

    /** Subnet sweep: the second way to find a Node, with no multicast. */
    private val sweep = Sweep(
        scope, ownIps = { ownIps() },
        missing = { store.nodes.value.map { it.id }.filter { it !in _online.value && found[it] == null }.toSet() },
        prober = SocketProber(), table = found,
        onFound = { id, addr -> adopt(id, addr) },
    )
    private var netWatch: NetWatch? = null

    private val _status = MutableStateFlow<Map<String, NodeStatus>>(emptyMap())
    val status: StateFlow<Map<String, NodeStatus>> = _status

    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online

    private val _apps = MutableStateFlow<Map<String, List<RemoteApp>>>(emptyMap())
    val apps: StateFlow<Map<String, List<RemoteApp>>> = _apps

    private val _playback = MutableStateFlow<Map<String, Playback>>(emptyMap())
    val playback: StateFlow<Map<String, Playback>> = _playback

    /** Set while a Now Playing screen is open, for 1s polling instead of 3s. */
    @Volatile var fastPoll = false

    private val _toast = MutableStateFlow<String?>(null)
    /** One-line feedback for the UI ("Starting on Hailey's Tablet…"). */
    val toast: StateFlow<String?> = _toast
    fun clearToast() { _toast.value = null }
    fun say(msg: String) { _toast.value = msg }

    val displayName get() = store.name.value.ifBlank { "Parent's Tablet" }

    /** Watch together: this tablet's own movies, and the library, clocks and session across tablets. */
    val videos = LocalVideos(ctx)
    val sync = SyncController(this, store)
    /** Sending movies between tablets. This tablet hands out its own through [server]. */
    val server = MediaServer.of(videos, WifiHold(ctx))
    val transfer = TransferController(this, store)
    /** Tablet cameras: the parent's toggles, each tablet's camera state and its stream. */
    val cam = CamController(this, store)

    fun lanAddress(): String? = LanAddress.own(ctx)
    /** Every address this tablet has: the home WiFi and the car network it hosts can be up at the same time. */
    private fun ownIps(): List<String> =
        (LanAddress.all(ctx).map { it.second } + listOfNotNull((carNet.state.value as? CarNetState.On)?.ownIp)).distinct()

    /**
     * Where tablet [nodeId] fetches movie [fid]: this tablet's address on THAT tablet's link (the local end of its
     * socket), because with the home WiFi and the car network both up the single "own" address is wrong for one of them.
     */
    fun localUrl(fid: String, nodeId: String): String? =
        LanAddress.localFor(links[nodeId]?.socket?.localAddress?.hostAddress, lanAddress())?.takeIf { server.start() }?.let { server.url(it, fid) }

    fun jellyfin(): Jellyfin? = store.jellyfin.value?.let { Jellyfin(it, displayName, store.deviceId) }

    @Synchronized
    fun start() {
        // Idempotent, and outside the guard below: after a reset the Master starts again without a new process.
        carNet.start()
        if (started) return
        started = true
        Wire.multicastLock(ctx)
        restartDiscovery()
        // The tablet joined, left or re-addressed a network, or its own hotspot came up or went down:
        // what was found is stale, so start over and sweep straight away.
        netWatch = NetWatch(ctx) { onNetworkChanged() }.also { it.start() }
        sweep.start()
        scope.launch {
            store.nodes.collect { list ->
                val ids = list.map { it.id }.toSet()
                list.forEach { n -> loops.ensure(n.id) }
                loops.ids().filterNot { it in ids }.forEach { id -> loops.cancel(id); links.drop(id)?.close() }
            }
        }
        scope.launch {
            // The hotspot goes to every Node on connect (see session) and again whenever the parent changes it.
            var last = store.hotspot.value
            store.hotspot.collect { h ->
                carNet.poke() // typing a hotspot in switches the car network off; clearing it switches it back on
                if (h != last) { last = h; links.ids().forEach { sendHotspot(it) } }
            }
        }
        scope.launch {
            // The group came up (or moved): its address is now known, so tell the Nodes, and look for them on it.
            var last: CarNetState.On? = null
            carNet.state.collect { s ->
                val on = s as? CarNetState.On
                if (on != null && on != last) { links.ids().forEach { sendHotspot(it) }; onNetworkChanged() }
                last = on
            }
        }
        scope.launch {
            // Only a reset (Master to no role) removes the group. Setup runs with no role yet and must keep it.
            var prev = store.role.value
            store.role.collect { r -> if (prev == Role.MASTER && r != Role.MASTER) carNet.stop(); prev = r }
        }
        scope.launch { sessionLoop() }
        scope.launch { while (isActive) { com.nbaltas.nickos.update.Updater.check(); delay(6 * 60 * 60_000L) } }
        scope.launch { resumeLoop() }
        sync.startTicker()
        transfer.startTicker()
        cam.startTicker()
        scope.launch {
            var last = store.pin.value
            store.pin.collect { p -> if (p != last) { last = p; links.ids().forEach { send(it, cmd("pin").put("pin", p)) } } }
        }
    }

    // ---- discovery ----
    private fun onNetworkChanged() {
        restartDiscovery()
        sweep.now()
    }

    /**
     * Starts discovery over, with a NEW listener (a reused one throws) and an empty found table. Everything the
     * old listener or an old sweep pass still delivers carries the old epoch and is dropped by [FoundTable].
     */
    @Synchronized
    private fun restartDiscovery() {
        val epoch = found.reset()
        _discovered.value = emptySet()
        discoveryListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, code: Int) { Log.w(TAG, "discovery failed $code") }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                val id = Wire.idFromServiceName(si.serviceName) ?: return
                if (id == store.deviceId) return
                scope.launch {
                    val addr = resolver.resolve(si) ?: return@launch
                    adopt(id, addr, epoch)
                }
            }

            override fun onServiceLost(si: NsdServiceInfo) {
                val id = Wire.idFromServiceName(si.serviceName) ?: return
                found.remove(epoch, id)
                _discovered.value = found.ids()
            }
        }
        discoveryListener = l
        try { nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w(TAG, "discovery unavailable", e) }
    }

    /** A Node's address arrived from mDNS (tagged with its epoch) or from the sweep (already stored). */
    private fun adopt(id: String, addr: Pair<String, Int>, epoch: Long? = null) {
        if (epoch != null && !found.put(epoch, id, addr)) return
        _discovered.value = found.ids()
        if (store.nodes.value.any { it.id == id && (it.host != addr.first || it.port != addr.second) }) {
            store.updateNode(id) { it.copy(host = addr.first, port = addr.second) }
        }
    }

    /** "Look again": restart discovery, dial every offline tablet now and sweep once. */
    fun reconnectAll() {
        onNetworkChanged()
        store.nodes.value.filter { it.id !in _online.value }.forEach { loops.restart(it.id) }
    }

    // ---- pairing ----
    data class PairResult(val node: LinkedNode, val status: NodeStatus)

    /**
     * Tries the code against every NickOS tablet on the WiFi; only the one showing it accepts.
     * [manual] ("192.168.1.23" or "host:port") is tried too, for networks where mDNS doesn't get through.
     */
    suspend fun pair(code: String, manual: String? = null): PairResult? = withContext(Dispatchers.IO) {
        val typed = manual?.trim()?.takeIf { it.isNotEmpty() }?.let { a ->
            val host = a.substringBeforeLast(':').takeIf { ':' in a } ?: a
            host to (a.substringAfterLast(':', "").toIntOrNull() ?: Wire.PORT)
        }
        withTimeoutOrNull(15_000) {
            while (true) {
                val targets = found.entries() + listOfNotNull(typed?.let { "" to it })
                val tries = targets.map { (id, addr) -> async { tryPair(id, addr, code) } }
                tries.awaitAll().firstOrNull { it != null }?.let { return@withTimeoutOrNull it }
                delay(1500)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    private fun tryPair(id: String, addr: Pair<String, Int>, code: String): PairResult? = try {
        val c = Conn.open(addr.first, addr.second, 2500)
        c.socket.soTimeout = 4000
        c.send(JSONObject().put("t", "pair").put("code", code).put("masterId", store.deviceId)
            .put("masterName", displayName).put("pin", store.pin.value))
        val r = c.read()
        c.close()
        if (r?.optString("t") == "paired") {
            val node = LinkedNode(
                id = r.optString("nodeId", id), name = r.optString("name"), color = r.optInt("color"),
                avatar = r.optString("avatar", "initial"), token = r.getString("token"),
                host = addr.first, port = addr.second, lastSeen = System.currentTimeMillis(), lastBattery = r.optInt("battery"),
            )
            store.upsertNode(node)
            val st = NodeStatus.from(r)
            _status.update { it + (node.id to st) }
            PairResult(node, st)
        } else null
    } catch (e: Exception) { null }

    // ---- per-node link ----
    private suspend fun linkLoop(id: String) {
        val backoff = LinkBackoff()
        while (scope.isActive) {
            val n = store.nodes.value.firstOrNull { it.id == id } ?: return
            val seen = found[id]
            val addr = seen ?: n.host?.let { it to (if (n.port > 0) n.port else Wire.PORT) }
            if (addr != null) {
                val linked = try { session(n, addr) } catch (e: Exception) { Log.d(TAG, "link $id: ${e.message}"); false }
                if (linked) backoff.success()
                else if (backoff.failure() && seen != null) {
                    // Three misses in a row: onServiceLost does not fire on an abrupt WiFi change, so this address
                    // is probably dead. Drop it (only if nothing newer replaced it) and let discovery or the sweep re-find it.
                    found.removeIf(id, seen)
                    _discovered.value = found.ids()
                }
            }
            delay(3000)
        }
    }

    /** True when the Node welcomed us (even if the link later dropped); false when it could not be reached. */
    private fun session(n: LinkedNode, addr: Pair<String, Int>): Boolean {
        val c = Conn.open(addr.first, addr.second)
        var claimed = false
        try {
            c.socket.soTimeout = 25_000 // Node sends status at least every 10s
            c.send(JSONObject().put("t", "hello").put("masterId", store.deviceId).put("token", n.token).put("masterName", displayName))
            val hi = c.read() ?: return false
            if (hi.optString("t") != "welcome") return false
            links.claim(n.id, c)?.close() // a second link to the same Node (a reconnect racing discovery) replaces the old one
            claimed = true
            runLink(n, c)
            return true
        } finally {
            // Only the link that is still the current one may announce the Node offline; a replaced one must not
            // tear down its successor's state.
            if (claimed && links.release(n.id, c)) {
                _online.update { it - n.id }
                sync.onDisconnected(n.id)
                transfer.onDisconnected(n.id)
                cam.onDisconnected(n.id)
            }
            c.close()
        }
    }

    private fun sendHotspot(id: String) {
        val h = store.hotspot.value
        // The address a Node's Master link arrives from when it is on this network. Only for the network we host: a
        // typed-in hotspot's address is unknown here (the Master is usually on the home WiFi), and a wrong one would
        // mark a Node approved for a network it is not on. Unknown now means none: the Node keeps what it had.
        val gw = if (h?.direct == true) (carNet.state.value as? CarNetState.On)?.ownIp else null
        send(id, NetMessages.hotspotCmd(h, gw))
    }

    private fun runLink(n: LinkedNode, c: Conn) {
        _online.update { it + n.id }
        send(n.id, cmd("pin").put("pin", store.pin.value))
        // Always: a cleared hotspot is sent as "forget" so a Node that was offline when it was cleared still drops it.
        sendHotspot(n.id)
        scope.launch { pushResume(n.id) }
        send(n.id, cmd("apps")) // icons for the cards and the Apps tab
        sync.onConnected(n.id)
        transfer.onConnected(n.id)
        while (true) {
            val m = c.read() ?: break
            when (m.optString("t")) {
                "status" -> onStatus(n.id, m)
                "apps" -> onApps(n.id, m.optJSONArray("list") ?: JSONArray())
                "want" -> scope.launch { resumeOn(n.id, m.getString("itemId")) }
                "pong", "videos", "syncState" -> sync.onMessage(n.id, m)
                "serving", "jobs", "deleted" -> transfer.onMessage(n.id, m)
                "camState" -> cam.onMessage(n.id, m, c.socket.inetAddress?.hostAddress)
                "reset" -> { forget(n.id); say("${n.name} was reset, so it's unlinked"); break }
            }
        }
    }

    private fun onStatus(id: String, m: JSONObject) {
        val st = NodeStatus.from(m)
        _status.update { it + (id to st) }
        val net = NodeNet.from(m)
        _net.update { it + (id to net) }
        val n = store.nodes.value.firstOrNull { it.id == id } ?: return
        // Remembered so the banner has a reason for a tablet that is offline (and after a restart).
        val saved = OnlinePolicy.wifiSaved(net, n.wifiSaved)
        if (saved != n.wifiSaved) store.updateNode(id) { it.copy(wifiSaved = saved) }
        val name = m.optString("name")
        val now = System.currentTimeMillis()
        // Persist what the offline card shows, but not on every tick.
        if (now - n.lastSeen > 30_000 || n.lastBattery != st.battery || (name.isNotEmpty() && name != n.name)) {
            store.updateNode(id) { it.copy(lastSeen = now, lastBattery = st.battery, name = name.ifEmpty { it.name }, color = m.optInt("color", it.color), avatar = m.optString("avatar", it.avatar)) }
        }
    }

    private fun onApps(id: String, a: JSONArray) {
        val list = (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val icon = o.optStr("icon")?.let {
                try { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } } catch (e: Exception) { null }
            }
            RemoteApp(o.getString("pkg"), o.optString("label"), icon)
        }
        _apps.update { it + (id to list) }
    }

    // One sender thread keeps commands in order (volume 40 then 60 must arrive as 40, 60).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun send(id: String, o: JSONObject): Boolean = links[id]?.let { c -> scope.launch(sendDispatcher) { c.send(o) }; true } ?: false

    fun cmd(c: String) = JSONObject().put("t", "cmd").put("c", c)

    /** Drops the current link (its own loop cleans up) and dials again at once, with exactly one loop for the Node. */
    fun reconnect(id: String) {
        links[id]?.close()
        loops.restart(id)
    }

    /** The banner's reason for each offline tablet, using what the Node last said (or what was persisted). */
    fun offlineReasons(now: Long = System.currentTimeMillis()): Map<String, Reason> {
        val lan = lanAddress()
        val manual = store.hotspot.value?.direct == false
        val car = carNet.state.value
        return OnlinePolicy.offline(store.nodes.value, _online.value).associate { n ->
            n.id to OnlinePolicy.reason(car, manual, lan, OnlinePolicy.wifiSaved(_net.value[n.id], n.wifiSaved), n.lastSeen, now, carNet.startingSince)
        }
    }

    // ---- device actions ----
    fun setVolume(id: String, v: Int) { send(id, cmd("volume").put("v", v)); patch(id) { it.copy(volume = v) } }
    fun setBrightness(id: String, v: Int) { send(id, cmd("brightness").put("v", v)); patch(id) { it.copy(brightness = v) } }
    fun screen(id: String, on: Boolean) { send(id, cmd("screen").put("on", on)); patch(id) { it.copy(screenOn = on) } }
    fun bedtime(id: String, minutes: Int) {
        send(id, cmd("bedtime").put("minutes", minutes))
        patch(id) { it.copy(bedtimeAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0) }
    }
    fun open(id: String, pkg: String) = send(id, cmd("open").put("pkg", pkg))
    fun home(id: String) = send(id, cmd("home"))
    fun allow(id: String, pkg: String, on: Boolean) {
        send(id, cmd("allow").put("pkg", pkg).put("on", on))
        patch(id) { it.copy(allowed = if (on) (it.allowed - pkg) + pkg else it.allowed - pkg) }
    }
    fun lockToApp(id: String, pkg: String?) { send(id, cmd("lockApp").put("pkg", pkg ?: "")); patch(id) { it.copy(lockedApp = pkg) } }
    fun requestApps(id: String) = send(id, cmd("apps"))
    /** The Node checks the update track itself and installs (silently when it has the kid lock). */
    fun updateNode(id: String) = send(id, cmd("update"))
    fun restartJellyfin(id: String) = send(id, cmd("restart"))

    /** Lock also pauses whatever is playing, so the show doesn't carry on behind the wall. */
    fun lock(id: String, on: Boolean) {
        send(id, cmd("lock").put("on", on))
        patch(id) { it.copy(lockedBy = if (on) displayName else null) }
        if (on) _playback.value[id]?.let { pb -> if (!pb.session.paused) control(id, "Pause") }
    }

    fun rename(id: String, name: String) {
        send(id, cmd("rename").put("name", name))
        store.updateNode(id) { it.copy(name = name) }
    }

    fun unlink(id: String) {
        send(id, cmd("unlink"))
        scope.launch { delay(500); forget(id) }
        _status.update { it - id }
    }

    private fun forget(id: String) {
        // Dropped on purpose, so the link's own cleanup (which only runs for the current link) is skipped: do it here.
        if (links.drop(id)?.also { it.close() } != null) { sync.onDisconnected(id); transfer.onDisconnected(id); cam.onDisconnected(id) }
        store.removeNode(id)
        _net.update { it - id }
        _status.update { it - id }
        _online.update { it - id }
    }

    private fun patch(id: String, f: (NodeStatus) -> NodeStatus) = _status.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    // ---- Jellyfin bridge ----
    private suspend fun <T> jf(block: suspend (Jellyfin) -> T): T? {
        val j = jellyfin() ?: return null
        return try { block(j) } catch (e: Exception) { Log.w(TAG, "jellyfin: ${e.message}"); null }
    }

    private suspend fun sessionLoop() {
        while (scope.isActive) {
            delay(if (fastPoll) 1000 else 3000)
            if (store.jellyfin.value == null || store.nodes.value.isEmpty()) { _playback.value = emptyMap(); continue }
            val sessions = jf { it.sessions() } ?: continue
            val now = System.currentTimeMillis()
            _playback.value = store.nodes.value.mapNotNull { n ->
                matchSession(n, sessions)?.let { remote ->
                    playbackSession(remote, sessions).takeIf { it.item != null }?.let { n.id to Playback(it, now, remote.id) }
                }
            }.toMap()
        }
    }

    /**
     * Which Jellyfin session is this tablet? Best: the session's device name is the tablet's
     * (the Jellyfin app reports the Android device name). Otherwise the most recently active
     * remote-controllable session of the tablet's Jellyfin user.
     */
    private fun matchSession(n: LinkedNode, sessions: List<JfSession>): JfSession? {
        val names = _status.value[n.id]?.names.orEmpty().map(::normName)
        val remote = sessions.filter { it.remote && (n.jfUserId == null || it.userId == n.jfUserId) }
        remote.firstOrNull { normName(it.deviceName) in names }?.let { return it }
        if (n.jfUserId == null) return null
        // Don't claim a session another linked tablet owns by name.
        val claimed = store.nodes.value.filter { it.id != n.id }.flatMap { _status.value[it.id]?.names.orEmpty() }.map(::normName).toSet()
        return remote.filterNot { normName(it.deviceName) in claimed }
            .sortedWith(compareByDescending<JfSession> { it.item != null }.thenByDescending { it.lastActivity })
            .firstOrNull()
    }

    /** Android's integrated player reports playback in a separate session from its remote UI. */
    private fun playbackSession(remote: JfSession, sessions: List<JfSession>): JfSession =
        sessions.filter {
            (normName(remote.deviceName).isNotEmpty() && normName(it.deviceName) == normName(remote.deviceName)) ||
                (remote.deviceId.isNotEmpty() && it.deviceId.startsWith(remote.deviceId))
        }.sortedWith(compareByDescending<JfSession> { it.item != null }.thenByDescending { it.lastActivity })
            .firstOrNull() ?: remote

    /** "Jellyfin for Android" reports the device name form-encoded ("Nothing+Phone+(3)"). */
    private fun normName(s: String): String =
        (try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s.replace('+', ' ') }).trim().lowercase()

    suspend fun findSession(id: String): JfSession? {
        val n = store.nodes.value.firstOrNull { it.id == id } ?: return null
        return jf { j -> matchSession(n, j.sessions()) }
    }

    /**
     * "Play on Hailey's Tablet". Uses the tablet's live Jellyfin session; if Jellyfin isn't
     * open there, opens it and waits for its session to show up (up to 25s).
     */
    fun playOn(id: String, itemId: String, startTicks: Long, label: String) = scope.launch {
        val n = store.nodes.value.firstOrNull { it.id == id } ?: return@launch
        if (store.jellyfin.value == null) { _toast.value = "Connect Jellyfin in Settings first"; return@launch }
        _toast.value = "Starting $label on ${n.name}…"
        var s = findSession(id)
        if (s == null) {
            val pkg = _status.value[id]?.jf
            if (pkg == null) { _toast.value = "Jellyfin isn't installed on ${n.name}"; return@launch }
            open(id, pkg)
            s = withTimeoutOrNull(25_000) {
                var found: JfSession? = null
                while (found == null) { delay(1500); found = findSession(id) }
                found
            }
            if (s == null) { _toast.value = "Jellyfin on ${n.name} isn't signed in as ${n.jfUserName ?: "their user"}"; return@launch }
            delay(1500)
        } else {
            // Jellyfin in the background (or only just opened by the kid's poster tap) still lists its
            // session but drops remote commands until its page reconnects: bring it up and wait.
            val st = _status.value[id]
            if (st?.jf != null && st.fg != st.jf) { open(id, st.jf); delay(3000) }
        }
        val ok = jf { it.playNow(s.id, itemId, startTicks); true } == true
        _toast.value = if (ok) null else "Couldn't start it. Try again."
    }

    private suspend fun resumeOn(id: String, itemId: String) {
        val n = store.nodes.value.firstOrNull { it.id == id } ?: return
        val uid = n.jfUserId ?: return
        val item = jf { it.item(uid, itemId) } ?: return
        playOn(id, itemId, item.posTicks, item.displayTitle)
    }

    fun control(id: String, cmd: String, seekTicks: Long? = null) = scope.launch {
        val pb = _playback.value[id] ?: return@launch
        // The integrated player ignores Jellyfin playstate commands but accepts Android media keys.
        if (!pb.session.remote) {
            val key = when (cmd) {
                "Pause" -> "pause"
                "Unpause" -> "play"
                "PlayPause" -> if (pb.session.paused) "play" else "pause"
                "Stop" -> "stop"
                // Only relative skips exist as keys (its player jumps back 10 s, forward 30 s); scrubbing can't reach it.
                "Seek" -> if ((seekTicks ?: 0) < pb.positionTicks()) "rewind" else "forward"
                else -> null
            }
            if (key != null) send(id, this@MasterRuntime.cmd("media").put("key", key))
        }
        jf { it.playstate(pb.remoteSessionId, cmd, seekTicks) }
        // Reflect immediately; the next poll corrects it.
        val s = pb.session
        val now = System.currentTimeMillis()
        val next = when (cmd) {
            "Pause" -> s.copy(paused = true, posTicks = pb.positionTicks(now))
            "Unpause" -> s.copy(paused = false, posTicks = pb.positionTicks(now))
            "PlayPause" -> s.copy(paused = !s.paused, posTicks = pb.positionTicks(now))
            "Seek" -> s.copy(posTicks = seekTicks ?: s.posTicks)
            else -> null
        }
        if (next != null) _playback.update { it + (id to Playback(next, now, pb.remoteSessionId)) }
        if (cmd == "Stop") _playback.update { it - id }
    }

    /** Keeps each Node's "Keep watching" posters current (every minute and on connect). */
    private suspend fun resumeLoop() {
        while (scope.isActive) {
            delay(60_000)
            links.ids().forEach { pushResume(it) }
        }
    }

    private suspend fun pushResume(id: String) {
        val n = store.nodes.value.firstOrNull { it.id == id } ?: return
        val uid = n.jfUserId ?: return
        val j = jellyfin() ?: return
        val items: List<JfItem> = try { j.resume(uid, 3) } catch (e: Exception) { return }
        val cards = JSONArray()
        items.forEach { cards.put(JSONObject().put("itemId", it.id).put("title", it.displayTitle).put("image", j.poster(it, 340))) }
        send(id, cmd("resume").put("cards", cards))
    }

    fun refreshResume(id: String) = scope.launch { pushResume(id) }

    companion object { private const val TAG = "NickOS.Master" }
}

/**
 * The live connection per Node. The rule that stops two links to one Node (a reconnect racing mDNS discovery, a
 * "Look again" while a loop is mid-handshake) from tearing each other down: a link may only be released while it is
 * STILL the current one. The older link's cleanup then finds it was replaced and leaves its successor alone.
 */
class LinkTable<C : Any> {
    private val map = ConcurrentHashMap<String, C>()
    operator fun get(id: String): C? = map[id]
    fun ids(): Set<String> = map.keys.toSet()

    /** Makes [c] the current link for [id] and returns the one it replaced (the caller closes it). */
    fun claim(id: String, c: C): C? = map.put(id, c)

    /** True only if [c] was still current, in which case it is removed. */
    fun release(id: String, c: C): Boolean = map.remove(id, c)

    /** Removes whatever is current (the Node was unlinked). */
    fun drop(id: String): C? = map.remove(id)
}

/** Exactly one link loop per Node, however many threads ask for one. */
class LinkLoops(private val scope: CoroutineScope, private val body: suspend (String) -> Unit) {
    private val jobs = HashMap<String, Job>()

    fun ids(): Set<String> = synchronized(this) { jobs.keys.toSet() }

    /** Starts a loop unless one is already running. */
    fun ensure(id: String) = synchronized(this) {
        if (jobs[id]?.isActive != true) jobs[id] = scope.launch { body(id) }
    }

    /** Cancels the running loop, if any, and starts a fresh one. */
    fun restart(id: String) = synchronized(this) {
        jobs[id]?.cancel()
        jobs[id] = scope.launch { body(id) }
    }

    fun cancel(id: String) = synchronized(this) { jobs.remove(id)?.cancel(); Unit }
}

/**
 * Counts consecutive failed dials. [failure] is true once it reaches [limit], meaning the address in use is probably
 * dead (mDNS does not reliably say a Node went away) and should be forgotten. A success starts the count again.
 */
class LinkBackoff(private val limit: Int = 3) {
    private var misses = 0
    fun failure(): Boolean { misses++; return misses >= limit }
    fun success() { misses = 0 }
}
