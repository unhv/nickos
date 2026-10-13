package com.nbaltas.nickos.ui.node

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.nbaltas.nickos.App
import com.nbaltas.nickos.data.ResumeCard
import com.nbaltas.nickos.data.Store
import com.nbaltas.nickos.node.parentName
import com.nbaltas.nickos.ui.theme.Artwork
import com.nbaltas.nickos.ui.theme.Avatar
import com.nbaltas.nickos.ui.theme.Battery
import com.nbaltas.nickos.ui.theme.C
import com.nbaltas.nickos.ui.theme.Solid
import com.nbaltas.nickos.ui.theme.Chip
import com.nbaltas.nickos.ui.theme.Dot
import com.nbaltas.nickos.ui.theme.Keypad
import com.nbaltas.nickos.ui.theme.NodePalette
import com.nbaltas.nickos.ui.theme.OnAvatar
import com.nbaltas.nickos.ui.theme.PinDots
import com.nbaltas.nickos.ui.theme.avatarColor
import com.nbaltas.nickos.ui.theme.body
import com.nbaltas.nickos.ui.theme.disp
import com.nbaltas.nickos.ui.theme.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NodeText = Color(0xFFEEF1F6)

/**
 * The time the screens draw (clock, date, "seen N min ago", countdowns). Always the real clock in the app;
 * the screenshot tests set it to one fixed instant so every render comes out the same.
 */
internal var screenNow: () -> Long = { System.currentTimeMillis() }

/** Ticks every [periodMs]; drives clocks and countdowns. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(screenNow()) }
    LaunchedEffect(periodMs) { while (true) { now = screenNow(); delay(periodMs - now % periodMs) } }
    return now
}

fun clockText(ms: Long): String = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(ms))

/** "Hailey's Tablet" → "Hailey". */
fun kidName(tabletName: String) = parentName(tabletName).ifEmpty { tabletName }

/** Long-press for [ms] (3s by default) with no hint on screen: the hidden parent exit. */
fun Modifier.hiddenHold(ms: Long = 3000, onHold: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(ms) { waitForUpOrCancellation() }
        if (up == null) onHold()
    }
}

/** An app on the Node's home screen. */
data class HomeApp(val pkg: String, val label: String, val icon: android.graphics.Bitmap?)

/**
 * The kid's home screen. [apps] are the allowed apps (Jellyfin excluded; it gets the big tile
 * when [jellyfin] is set). [onHold] opens the PIN pad.
 */
@Composable
fun NodeHome(
    store: Store,
    apps: List<HomeApp>,
    jellyfin: HomeApp?,
    linkedTo: String?,
    linkedOnline: Boolean,
    onOpen: (String) -> Unit,
    onWant: (ResumeCard) -> Unit,
    onHold: () -> Unit,
) {
    val name by store.name.collectAsState()
    val color by store.color.collectAsState()
    val avatar by store.avatar.collectAsState()
    val resume by store.resume.collectAsState()
    val ctx = LocalContext.current
    val now = rememberNow(1000)
    var battery by remember { mutableStateOf(App.get(ctx).node.device.battery()) }
    LaunchedEffect(now / 30_000) { battery = App.get(ctx).node.device.battery() }
    val portrait = LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }

    Column(Modifier.fillMaxSize().background(NodePalette.bg)) {
        // Header
        val chip: @Composable () -> Unit = {
            if (linkedTo != null) Chip(height = 44.dp, bg = Color.White.copy(alpha = 0.08f)) {
                Dot(if (linkedOnline) C.online else C.offline, 10.dp)
                Icon(Lucide.Link, null, tint = NodeText, modifier = Modifier.size(16.dp))
                Text("Linked to ${fixChip(linkedTo)}", style = body(16.sp, 600), color = NodeText, maxLines = 1)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(112.dp).padding(horizontal = if (portrait) 32.dp else 40.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.hiddenHold(onHold = onHold)) { Avatar(name, color, avatar, 68.dp) }
            // One weighted item: a second weighted Spacer would split the free space and leave a gap at the right edge.
            Text("Hi ${kidName(name)}", style = disp(40.sp), color = NodeText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!portrait) chip()
            Battery(battery.first, battery.second, 30, 26.dp, color = NodeText)
            Text(clockText(now), style = disp(48.sp), color = NodeText)
        }
        if (portrait && linkedTo != null) Box(Modifier.padding(start = 32.dp, bottom = 20.dp)) { chip() }

        // Grid
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pad = if (portrait) 32.dp else 40.dp
            val gap = 24.dp
            val cols = if (portrait) 2 else 4
            val fullW = maxWidth
            val rowH: Dp = if (portrait) 248.dp else ((maxHeight - 4.dp - 40.dp - gap) / 2).coerceAtLeast(200.dp)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = pad, end = pad, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                if (apps.isEmpty() && jellyfin == null) {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No apps yet", style = disp(40.sp), color = NodeText)
                        Text("Ask a grown-up to add some from their tablet.", style = body(20.sp), color = NodePalette.soft)
                    }
                    return@Column
                }
                val colW = (fullW - pad * 2 - gap * (cols - 1)) / cols
                if (jellyfin != null && portrait) {
                    JellyfinTile(jellyfin, resume, Modifier.fillMaxWidth().height(520.dp), onOpen, onWant)
                }
                var rest = apps
                if (jellyfin != null && !portrait) {
                    // Big tile spans 2×2; the first four apps fill the right half.
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        JellyfinTile(jellyfin, resume, Modifier.width(colW * 2 + gap).height(rowH * 2 + gap), onOpen, onWant)
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            rest.take(4).chunked(2).forEachIndexed { r, pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    pair.forEachIndexed { i, a -> AppTile(a, r * 2 + i, Modifier.width(colW).height(rowH), onOpen) }
                                }
                            }
                        }
                    }
                    rest = rest.drop(4)
                }
                val offset = if (jellyfin != null && !portrait) 4 else 0
                rest.chunked(cols).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEachIndexed { i, a -> AppTile(a, offset + r * cols + i, Modifier.width(colW).height(rowH), onOpen) }
                    }
                }
            }
        }
    }
}

private fun fixChip(master: String): String {
    // "Dad's Tablet" reads "Linked to Dad's tablet" in the design.
    return master.replace(Regex("(?i)tablet$"), "tablet")
}

@Composable
private fun JellyfinTile(app: HomeApp, resume: List<ResumeCard>, modifier: Modifier, onOpen: (String) -> Unit, onWant: (ResumeCard) -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(36.dp)).background(NodePalette.surface).tap { onOpen(app.pkg) }.padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            resume.take(3).forEach { r ->
                Box(Modifier.shadow(12.dp, RoundedCornerShape(16.dp)).tap { onWant(r) }) {
                    Artwork(r.image, r.title, Modifier.size(150.dp, 225.dp), radius = 16.dp, titleSize = 22)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AppIconBox(app.icon, 76.dp, 21.dp, bg = C.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, style = disp(40.sp), color = NodeText, maxLines = 1)
                Text(
                    resume.firstOrNull()?.let { "Keep watching ${it.title}" } ?: "Shows and movies",
                    style = body(19.sp, 600), color = NodePalette.soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(C.accent)
                    .tap { resume.firstOrNull()?.let(onWant) ?: onOpen(app.pkg) },
                contentAlignment = Alignment.Center,
            ) { Icon(Solid.Play, "Play", tint = C.onAccent, modifier = Modifier.size(30.dp)) }
        }
    }
}

@Composable
private fun AppTile(app: HomeApp, index: Int, modifier: Modifier, onOpen: (String) -> Unit) {
    val bg = NodePalette.tiles[index % NodePalette.tiles.size]
    Column(
        modifier.clip(RoundedCornerShape(36.dp)).background(bg).tap { onOpen(app.pkg) }.padding(26.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        AppIconBox(app.icon, 88.dp, 26.dp, bg = Color.White.copy(alpha = 0.35f))
        Text(app.label, style = disp(36.sp), color = OnAvatar, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A real app icon on a rounded square, like the design's glyph tiles. */
@Composable
fun AppIconBox(icon: android.graphics.Bitmap?, size: Dp, radius: Dp, bg: Color) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(bg), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(size * 0.68f))
    }
}

fun loadHomeApp(ctx: android.content.Context, pkg: String): HomeApp? = try {
    val pm = ctx.packageManager
    val info = pm.getApplicationInfo(pkg, 0)
    HomeApp(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(pkg).toBitmap(144, 144))
} catch (e: Exception) { null }

// ---------------- PIN pad ----------------

/** "Grown-ups only" card. Calls [onOk] when the 4 digits match [pin]. */
@Composable
fun PinPad(pin: String, onOk: () -> Unit, onCancel: () -> Unit, subtitle: String = "Enter the parent PIN to leave kid mode") {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xC7080918)).tap {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(500.dp).shadow(40.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp))
                .background(NodePalette.pinCard).padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Lucide.Lock, null, tint = NodeText, modifier = Modifier.size(30.dp))
                Text("Grown-ups only", style = disp(32.sp), color = NodeText)
            }
            Text(if (wrong) "That's not it. Try again." else subtitle, style = body(18.sp), color = if (wrong) C.danger else NodePalette.soft)
            PinDots(entered.length, color = NodeText)
            Keypad(
                onDigit = { d ->
                    if (entered.length < 4) {
                        entered += d; wrong = false
                        if (entered.length == 4) { if (entered == pin) onOk() else { wrong = true; entered = "" } }
                    }
                },
                onDelete = { entered = entered.dropLast(1) },
                modifier = Modifier.width(420.dp),
                keyHeight = 80.dp,
                keyColor = NodePalette.key,
                leftLabel = "Cancel",
                onLeft = onCancel,
            )
        }
    }
}

// ---------------- Locked ----------------

/**
 * "Screen locked by Dad". Drawn by the overlay over any app, or by the Node activity when the
 * overlay permission is missing. A hidden 3s hold on the name chip + PIN unlocks it locally,
 * in case the Master tablet isn't around.
 */
@Composable
fun LockedScreen(store: Store, by: String?) {
    val ctx = LocalContext.current
    val name by store.name.collectAsState()
    val color by store.color.collectAsState()
    val avatar by store.avatar.collectAsState()
    val pin by store.pin.collectAsState()
    val now = rememberNow(1000)
    var showPin by remember { mutableStateOf(false) }
    val battery = remember(now / 30_000) { App.get(ctx).node.device.battery() }
    Box(Modifier.fillMaxSize().background(NodePalette.lockedBg).tap {}) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.hiddenHold { showPin = true }) {
                    Chip(height = 52.dp, bg = Color.White.copy(alpha = 0.08f), padding = PaddingValues(start = 6.dp, end = 20.dp)) {
                        Avatar(name, color, avatar, 40.dp)
                        Text(name, style = body(18.sp, 600), color = NodeText)
                    }
                }
                Spacer(Modifier.weight(1f))
                Battery(battery.first, battery.second, 30, 26.dp, color = NodeText)
            }
            Column(
                Modifier.fillMaxSize().padding(start = 80.dp, end = 80.dp, bottom = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(168.dp).clip(CircleShape).background(avatarColor(color)), contentAlignment = Alignment.Center) {
                    Icon(Lucide.Lock, null, tint = OnAvatar, modifier = Modifier.size(76.dp))
                }
                Text("Screen locked by ${parentName(by)}", style = disp(68.sp, 800, 1f), color = NodeText, textAlign = TextAlign.Center)
                Text("Ask a grown-up to unlock it.", style = body(26.sp), color = NodePalette.soft)
                Text(clockText(now), style = disp(44.sp), color = NodePalette.soft, modifier = Modifier.padding(top = 20.dp))
            }
        }
        if (showPin) PinPad(pin, onOk = {
            showPin = false
            store.setLockedBy(null)
            App.get(ctx).node.let { it.pushStatus(); it.overlay.refresh() }
        }, onCancel = { showPin = false }, subtitle = "Enter the parent PIN to unlock")
    }
}

// ---------------- Bedtime ----------------

/** In-app placement: bottom centre of the screen, clear of the header. */
@Composable
fun BedtimeToastAtBottom(secondsLeft: Int) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp),
        contentAlignment = Alignment.BottomCenter,
    ) { BedtimeToast(secondsLeft) }
}

/** Just the pill. The caller decides where it goes. */
@Composable
fun BedtimeToast(secondsLeft: Int) {
    Box {
        Row(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).shadow(24.dp, RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp)).background(NodePalette.toastBg).padding(horizontal = 26.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                Icon(Lucide.Moon, null, tint = C.onAccent, modifier = Modifier.size(34.dp))
            }
            val mins = com.nbaltas.nickos.ui.theme.minutesLeft(secondsLeft * 1000L)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bedtime in $mins minute${if (mins == 1) "" else "s"}", style = disp(32.sp), color = C.onAccent)
                Text("Find a good spot to stop.", style = body(19.sp, 600), color = NodePalette.toastSub)
            }
            Text("%d:%02d".format(secondsLeft / 60, secondsLeft % 60), style = disp(36.sp).copy(fontFeatureSettings = "tnum"), color = C.onAccent)
        }
    }
}

// ---------------- Parent menu (after the PIN) ----------------

/** Not in the design handoff; built from the same PIN-card language. */
@Composable
fun ParentMenu(
    lockdownOn: Boolean,
    onKidMode: () -> Unit,
    onAllApps: () -> Unit,
    onSettings: () -> Unit,
    onPairAnother: () -> Unit,
    onUpdate: () -> Unit,
    onReset: () -> Unit,
    /** Non-null while the camera permission is missing: shows the "Allow camera" row. */
    onAllowCamera: (() -> Unit)? = null,
    videosAllowed: Boolean = true,
    onAllowVideos: () -> Unit = {},
    /** Non-null while Device Admin is missing: shows the "Allow screen off" row. */
    onAllowScreenOff: (() -> Unit)? = null,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val update by com.nbaltas.nickos.update.Updater.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Color(0xC7080918)).tap {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(40.dp)).background(NodePalette.pinCard).padding(horizontal = 40.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Grown-up menu", style = disp(32.sp), color = NodeText)
            Text(
                if (lockdownOn) "Kid lock is on." else "Kid lock is off: the kid can leave with the home button. See SETUP.md to switch it on.",
                style = body(17.sp), color = if (lockdownOn) NodePalette.soft else C.danger,
            )
            Spacer(Modifier.height(6.dp))
            MenuButton("Back to kid mode", primary = true, onClick = onKidMode)
            MenuButton("All apps", onClick = onAllApps)
            MenuButton("Android settings", onClick = onSettings)
            if (onAllowCamera != null) MenuButton("Allow camera", onClick = onAllowCamera)
            if (!videosAllowed) MenuButton("Allow videos", onClick = onAllowVideos)
            if (onAllowScreenOff != null) MenuButton("Allow screen off", onClick = onAllowScreenOff)
            MenuButton("Pair another parent tablet", onClick = onPairAnother)
            MenuButton(
                when (val u = update) {
                    com.nbaltas.nickos.update.UpdateState.Checking -> "Checking for updates…"
                    com.nbaltas.nickos.update.UpdateState.UpToDate -> "Up to date (${com.nbaltas.nickos.BuildConfig.VERSION_NAME})"
                    is com.nbaltas.nickos.update.UpdateState.Downloading -> "Downloading ${u.pct}%…"
                    is com.nbaltas.nickos.update.UpdateState.Installing -> "Installing…"
                    is com.nbaltas.nickos.update.UpdateState.Failed -> u.message
                    else -> "Check for updates"
                },
                onClick = onUpdate,
            )
            MenuButton(if (confirmReset) "Tap again to reset this tablet" else "Reset this tablet", danger = true) {
                if (confirmReset) onReset() else confirmReset = true
            }
        }
    }
}

@Composable
private fun MenuButton(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp))
            .background(if (primary) C.accent else NodePalette.key).tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = body(19.sp, 700), color = when { primary -> C.onAccent; danger -> C.danger; else -> NodeText })
    }
}

fun androidSettingsIntent() = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

