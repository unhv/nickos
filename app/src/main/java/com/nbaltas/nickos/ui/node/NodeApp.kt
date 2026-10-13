package com.nbaltas.nickos.ui.node

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nbaltas.nickos.App
import com.nbaltas.nickos.node.AdminPolicy
import com.nbaltas.nickos.node.LocalVideos
import com.nbaltas.nickos.node.NodeService
import com.nbaltas.nickos.node.parentName
import com.nbaltas.nickos.ui.common.AppDrawer
import com.nbaltas.nickos.ui.setup.PairingCodeBody
import com.nbaltas.nickos.ui.theme.C
import com.nbaltas.nickos.ui.theme.SetupHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class NodeScreen { HOME, PIN, MENU, DRAWER, PAIR }

/** Everything the Node activity shows. Kiosk/lock-task itself is handled by MainActivity. */
@Composable
fun NodeApp(onKidMode: () -> Unit, onParentMode: () -> Unit) {
    val ctx = LocalContext.current
    val app = App.get(ctx)
    val store = app.store
    val rt = app.node
    val allowed by store.allowed.collectAsState()
    val masters by store.masters.collectAsState()
    val connected by rt.connected.collectAsState()
    val lockedBy by store.lockedBy.collectAsState()
    val warn by rt.bedtimeWarning.collectAsState()
    val pin by store.pin.collectAsState()
    val playerUi by rt.syncPlayer.ui.collectAsState()
    val player by rt.syncPlayer.player.collectAsState()
    var videosAllowed by remember { mutableStateOf(rt.videos.hasPermission()) }
    val videosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        videosAllowed = rt.videos.hasPermission()
    }
    // Leaving lock task makes Android start Home again in a new task, so this screen is rebuilt
    // right after the PIN: reopen the parent menu while parent mode is on.
    var screen by remember { mutableStateOf(if (rt.parentMode) NodeScreen.MENU else NodeScreen.HOME) }
    val scope = rememberCoroutineScope()

    BackHandler { if (screen != NodeScreen.HOME && screen != NodeScreen.MENU) screen = NodeScreen.HOME }

    // Re-checked when the allowlist changes: Jellyfin may be installed after NickOS started.
    val jfPkg = remember(allowed) { rt.device.jellyfinPackage() }
    val apps by produceState(emptyList<HomeApp>(), allowed) {
        value = withContext(Dispatchers.IO) { allowed.mapNotNull { loadHomeApp(ctx, it) } }
    }
    val jellyfin = apps.firstOrNull { it.pkg == jfPkg }
    val others = apps.filterNot { it.pkg == jfPkg }

    val canOverlay = remember(lockedBy, warn) { rt.device.canOverlay() }

    // Tablet cameras: the grown-up menu offers "Allow camera" until the permission is there.
    var cameraOk by remember(screen) { mutableStateOf(rt.device.hasCameraPermission()) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraOk = it || rt.device.hasCameraPermission() }

    // Device Admin (screen off): asked for here, in the grown-up menu, never in front of the kid.
    var adminOk by remember(screen) { mutableStateOf(rt.device.canLockScreen()) }
    val askAdmin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { adminOk = rt.device.canLockScreen() }
    fun requestAdmin() { rt.device.adminRequestIntent()?.let { try { askAdmin.launch(it) } catch (_: Exception) {} } }
    LaunchedEffect(screen) {
        if (AdminPolicy.shouldPromptHere(true, screen == NodeScreen.MENU, rt.device.isDeviceOwner, rt.device.canLockScreen(), rt.device.adminAsked)) {
            rt.device.adminAsked = true
            requestAdmin()
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        when (screen) {
            NodeScreen.DRAWER -> AppDrawer(onBack = { screen = NodeScreen.MENU }, onOpen = { rt.device.launch(it) })
            NodeScreen.PAIR -> {
                val code by rt.pairingCode.collectAsState()
                val name by store.name.collectAsState()
                val color by store.color.collectAsState()
                val avatar by store.avatar.collectAsState()
                LaunchedEffect(Unit) { rt.openPairing(); rt.paired.collect { screen = NodeScreen.HOME; onKidMode() } }
                Column(Modifier.fillMaxSize().background(C.bg)) {
                    SetupHeader("Add a parent tablet", { rt.closePairing(); screen = NodeScreen.MENU })
                    PairingCodeBody(name, color, avatar, code)
                }
            }
            else -> if (screen == NodeScreen.HOME && playerUi.fid != null) {
                TogetherScreen(
                    ui = playerUi,
                    player = player,
                    parent = parentName(masters.firstOrNull { it.id in connected }?.name ?: masters.firstOrNull()?.name),
                    onHold = { screen = NodeScreen.PIN },
                )
            } else NodeHome(
                store = store,
                apps = others,
                jellyfin = jellyfin,
                linkedTo = masters.firstOrNull { it.id in connected }?.name ?: masters.firstOrNull()?.name,
                linkedOnline = connected.isNotEmpty(),
                onOpen = { rt.device.launch(it) },
                onWant = { rt.want(it.itemId) },
                onHold = { screen = NodeScreen.PIN },
            )
        }

        if (screen == NodeScreen.PIN) PinPad(pin, onOk = { screen = NodeScreen.MENU; onParentMode() }, onCancel = { screen = NodeScreen.HOME })
        if (screen == NodeScreen.MENU) ParentMenu(
            lockdownOn = rt.device.isDeviceOwner,
            onKidMode = { screen = NodeScreen.HOME; onKidMode() },
            onAllApps = { screen = NodeScreen.DRAWER },
            onSettings = { ctx.startActivity(androidSettingsIntent()) },
            onPairAnother = { screen = NodeScreen.PAIR },
            onUpdate = { scope.launch { com.nbaltas.nickos.update.Updater.updateIfNewer(ctx) } },
            videosAllowed = videosAllowed,
            onAllowVideos = { videosLauncher.launch(LocalVideos.permissions().toTypedArray()) },
            onReset = {
                scope.launch {
                    rt.sayGoodbye()
                    rt.device.releaseLockdown()
                    (ctx as? Activity)?.let { try { it.stopLockTask() } catch (_: Exception) {} }
                    NodeService.stop(ctx)
                    store.resetAll()
                }
            },
            onAllowScreenOff = if (adminOk) null else ({ requestAdmin() }),
            onAllowCamera = if (cameraOk) null else ({ askCamera.launch(android.Manifest.permission.CAMERA) }),
        )

        // The overlay only covers other apps (Android forces pass-through overlays to 80% opacity),
        // so the toast always draws here on the home screen. The wall too without the overlay permission.
        warn?.let { if (lockedBy == null) BedtimeToastAtBottom(it) }
        if (!canOverlay && lockedBy != null) LockedScreen(store, lockedBy)
    }
}
