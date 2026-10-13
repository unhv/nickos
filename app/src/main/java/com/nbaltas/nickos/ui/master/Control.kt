package com.nbaltas.nickos.ui.master

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.House
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.MonitorOff
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Tablet
import com.composables.icons.lucide.Unlink
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.WifiOff
import com.nbaltas.nickos.App
import com.nbaltas.nickos.jellyfin.JfItem
import com.nbaltas.nickos.ui.node.kidName
import com.nbaltas.nickos.ui.node.rememberNow
import com.nbaltas.nickos.ui.setup.rotate90
import com.nbaltas.nickos.ui.theme.Artwork
import com.nbaltas.nickos.ui.theme.Avatar
import com.nbaltas.nickos.ui.theme.BackButton
import com.nbaltas.nickos.ui.theme.Battery
import com.nbaltas.nickos.ui.theme.BtnStyle
import com.nbaltas.nickos.ui.theme.C
import com.nbaltas.nickos.ui.theme.Solid
import com.nbaltas.nickos.ui.theme.Chip
import com.nbaltas.nickos.ui.theme.EyebrowText
import com.nbaltas.nickos.ui.theme.Muted
import com.nbaltas.nickos.ui.theme.NickButton
import com.nbaltas.nickos.ui.theme.NickField
import com.nbaltas.nickos.ui.theme.NickSlider
import com.nbaltas.nickos.ui.theme.NickSwitch
import com.nbaltas.nickos.ui.theme.ProgressBar
import com.nbaltas.nickos.ui.theme.Segmented
import com.nbaltas.nickos.ui.theme.Title
import com.nbaltas.nickos.ui.theme.body
import com.nbaltas.nickos.ui.theme.dashed
import com.nbaltas.nickos.ui.theme.disp
import com.nbaltas.nickos.ui.theme.tap
import kotlin.math.roundToInt

const val TAB_NOW = 0
const val TAB_WATCH = 1
const val TAB_APPS = 2
const val TAB_DEVICE = 3

/** Tablet Control: header + Now Playing / Watch / Apps / Device. [onSettings] takes a Settings section. */
@Composable
fun ControlScreen(nodeId: String, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit, onSettings: (Int) -> Unit) {
    val app = App.get(LocalContext.current)
    val v = rememberNodeViews().firstOrNull { it.node.id == nodeId }
    if (v == null) { LaunchedEffect(Unit) { onBack() }; return }

    DisposableEffect(tab) {
        app.master.fastPoll = tab == TAB_NOW
        onDispose { app.master.fastPoll = false }
    }

    val session by app.master.sync.session.collectAsState()
    val inTogether = session?.nodes?.contains(nodeId) == true

    Column(Modifier.fillMaxSize()) {
        ControlHeader(v, tab, onTab, onBack)
        Box(Modifier.fillMaxSize()) {
            when {
                tab == TAB_WATCH -> TabletLibrary(nodeId, v, onPlayed = { onTab(TAB_NOW) }, onSettings = { onSettings(1) })
                tab == TAB_NOW && inTogether -> TabletRemote(nodeId)
                !v.online -> OfflineBody(v, onBack)
                tab == TAB_NOW -> if (v.playback?.item != null) NowPlaying(v) else NothingPlaying(v) { onTab(TAB_WATCH) }
                tab == TAB_APPS -> AppsTab(v)
                tab == TAB_DEVICE -> DeviceTab(v, onUnlinked = onBack)
            }
        }
    }
}

@Composable
private fun ControlHeader(v: NodeView, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit) {
    val phone = LocalConfiguration.current.screenWidthDp < 600
    val tabs = listOf<Pair<String, ImageVector?>>("Now Playing" to Solid.Play, "Watch" to Lucide.Film, "Apps" to Lucide.LayoutGrid, "Device" to Lucide.Tablet)
    BoxWithConstraints(Modifier.fillMaxWidth().background(C.bg)) {
        val narrow = maxWidth < 1100.dp
        val who: @Composable (Modifier) -> Unit = { modifier ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 16.dp)) {
                BackButton(onBack)
                Avatar(v.node.name, v.node.color, v.node.avatar, 52.dp)
                Column(if (phone) Modifier.weight(1f) else Modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Title(v.node.name, 25, maxLines = 1)
                    StatusLine(v, 15, 10.dp)
                }
            }
        }
        val battery: @Composable () -> Unit = {
            Battery(v.battery, v.status?.charging == true && v.online, if (phone) 24 else 38, if (phone) 24.dp else 30.dp)
        }
        Column {
            if (narrow) {
                Row(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (phone) { who(Modifier.weight(1f)); Spacer(Modifier.width(8.dp)) }
                    else { who(Modifier); Spacer(Modifier.weight(1f)) }
                    battery()
                }
                Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 16.dp)) {
                    Segmented(tabs, tab, onTab, modifier = if (phone) Modifier.fillMaxWidth() else Modifier, compact = phone)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    who(Modifier)
                    Segmented(tabs, tab, onTab)
                    Spacer(Modifier.weight(1f))
                    battery()
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.surface2))
        }
    }
}

// ---------------- Now Playing ----------------

@Composable
private fun NowPlaying(v: NodeView) {
    val app = App.get(LocalContext.current)
    val m = app.master
    val pb = v.playback ?: return
    val item = pb.item ?: return
    val account by app.store.jellyfin.collectAsState()
    val jf = remember(account) { m.jellyfin() }
    val now = rememberNow(500)
    val pos = pb.positionTicks(now)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val next by produceState<JfItem?>(null, item.id) {
        val uid = v.node.jfUserId
        value = if (item.isEpisode && uid != null) try { jf?.nextEpisode(uid, item) } catch (e: Exception) { null } else null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Backdrop at 35%, fading into the page towards the right-hand column.
        jf?.backdrop(item)?.let { AsyncImage(it, null, Modifier.fillMaxSize().alpha(0.35f), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.bg.copy(alpha = 0.55f), 0.62f to C.bg.copy(alpha = 0.92f), 0.78f to C.bg)))
        val narrow = maxWidth < 1000.dp
        val side: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                EyebrowText("On ${v.node.name}")
                DeviceSliders(v)
                next?.let { n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(C.surface)
                            .clickable { m.playOn(v.node.id, n.id, 0, n.displayTitle) }.padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(jf?.landscape(n, 300), "", Modifier.size(128.dp, 72.dp), radius = 12.dp, showTitle = false)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            EyebrowText("Up next")
                            Text("E${n.index ?: ""} · ${n.name}", style = body(18.sp, 700), color = C.text, maxLines = 2)
                        }
                    }
                }
            }
        }
        val nextAndStop: @Composable () -> Unit = {
            next?.let { n -> NickButton("Next episode", { m.playOn(v.node.id, n.id, 0, n.displayTitle) }, Modifier.height(68.dp), leading = Solid.Next) }
            NickButton("Stop", { m.control(v.node.id, "Stop") }, Modifier.height(68.dp), leading = Solid.Stop)
        }
        val main: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                // No weight when narrow: inside the scrolling column it would collapse the poster and title to nothing.
                Row(if (narrow) Modifier else Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(if (narrow) 20.dp else 32.dp)) {
                    val h = if (narrow) 210.dp else 375.dp
                    Artwork(jf?.poster(item, 600), item.displayTitle, Modifier.size(h * 2 / 3, h), radius = 20.dp, titleSize = 35)
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Solid.Play, null, tint = C.accent, modifier = Modifier.size(12.dp))
                            EyebrowText(if (pb.session.paused) "Paused" else "Playing now", C.accent)
                        }
                        Title(item.displayTitle, if (narrow) 40 else 64, lineHeight = 0.98f, maxLines = 3)
                        if (item.isEpisode) {
                            if (item.season != null && item.index != null) Text("Season ${item.season} · Episode ${item.index}", style = body(24.sp, 700), color = C.text)
                            Muted(item.name, 22, maxLines = 2)
                        } else Muted(episodeLine(item), 22)
                    }
                }
                // Jellyfin's integrated player only takes media keys (back 10 s, forward 30 s), so it
                // can't be scrubbed: show progress only. The Web player setting gives a real scrub bar.
                val integrated = !pb.session.remote
                val fwdSecs = if (integrated) 30 else 10
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val frac = scrub ?: if (item.runTicks > 0) pos.toFloat() / item.runTicks else 0f
                    if (integrated) ProgressBar(frac, Modifier.padding(vertical = 12.dp), height = 14.dp, track = C.line)
                    else NickSlider(
                        value = frac,
                        onChange = { scrub = it },
                        onCommit = { f -> scrub = null; m.control(v.node.id, "Seek", (f * item.runTicks).toLong()) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtTicks((frac * item.runTicks).toLong()), style = body(18.sp, 700).copy(fontFeatureSettings = "tnum"), color = C.text)
                        Text(fmtTicks(item.runTicks), style = body(18.sp, 700).copy(fontFeatureSettings = "tnum"), color = C.muted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SkipButton(back = true) { m.control(v.node.id, "Seek", (pos - 10 * JfItem.TICKS_PER_SEC).coerceAtLeast(0)) }
                    Box(
                        Modifier.size(104.dp).clip(CircleShape).background(C.accent)
                            .clickable { m.control(v.node.id, if (pb.session.paused) "Unpause" else "Pause") },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (pb.session.paused) Solid.Play else Solid.Pause, if (pb.session.paused) "Play" else "Pause", tint = C.onAccent, modifier = Modifier.size(44.dp)) }
                    SkipButton(back = false, secs = fwdSecs) { m.control(v.node.id, "Seek", (pos + fwdSecs * JfItem.TICKS_PER_SEC).coerceAtMost(item.runTicks)) }
                    if (!narrow) { Spacer(Modifier.weight(1f)); nextAndStop() }
                }
                if (narrow) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { nextAndStop() }
            }
        }
        if (narrow) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                main(Modifier.fillMaxWidth()); side(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 32.dp, top = 32.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                main(Modifier.weight(1f).fillMaxHeight()); side(Modifier.width(360.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun SkipButton(back: Boolean, secs: Int = 10, onClick: () -> Unit) {
    Box(Modifier.size(76.dp).clip(CircleShape).background(C.surface2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(if (back) Lucide.RotateCcw else Lucide.RotateCw, if (back) "Back $secs seconds" else "Forward $secs seconds", tint = C.text, modifier = Modifier.size(34.dp))
        Text("$secs", style = body(10.sp, 800), color = C.text, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Volume + Brightness cards. They act on the tablet itself, one command per release. */
@Composable
private fun DeviceSliders(v: NodeView) {
    val m = App.get(LocalContext.current).master
    SliderCard(Lucide.Volume2, "Volume", v.status?.volume ?: 0) { m.setVolume(v.node.id, it) }
    SliderCard(Lucide.Sun, "Brightness", v.status?.brightness ?: 0) { m.setBrightness(v.node.id, it) }
}

@Composable
private fun SliderCard(icon: ImageVector, label: String, value: Int, big: Boolean = false, modifier: Modifier = Modifier, onCommit: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: (value / 100f)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(if (big) 28.dp else 26.dp)).background(C.surface)
            .padding(horizontal = if (big) 24.dp else 24.dp, vertical = if (big) 24.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(if (big) 18.dp else 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = C.text, modifier = Modifier.size(22.dp))
            Text(label, style = body(if (big) 20.sp else 19.sp, if (big) 800 else 700), color = C.text, modifier = Modifier.weight(1f))
            if (!big) Text("${(shown * 100).roundToInt()}%", style = disp(34.sp), color = C.text)
        }
        if (big) Text("${(shown * 100).roundToInt()}%", style = disp(56.sp, 800, 1f), color = C.text)
        Box(Modifier.padding(vertical = 12.dp)) {
            NickSlider(shown, onChange = { dragging = it }, onCommit = { dragging = null; onCommit((it * 100).roundToInt()) })
        }
    }
}

@Composable
private fun NothingPlaying(v: NodeView, onFind: () -> Unit) {
    val m = App.get(LocalContext.current).master
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 1000.dp
        val box: @Composable (Modifier) -> Unit = { mod ->
            Column(
                mod.dashed(C.line, 30.dp).padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
            ) {
                val st = v.status
                AppTileIcon(v.fgApp, if (st?.fg == null) Lucide.House else Lucide.LayoutGrid, 112.dp, 31.dp, v.node.color)
                Title("Nothing playing", 44, lineHeight = 1.05f)
                Text(
                    buildAnnotatedString {
                        append(kidName(v.node.name))
                        if (st?.fg != null) {
                            append(" is using ")
                            withStyle(SpanStyle(color = C.text, fontWeight = FontWeight.Bold)) { append(st.fgLabel ?: "an app") }
                            append(".")
                        } else append(" is on the home screen.")
                    },
                    style = body(21.sp), color = C.muted,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (st?.fg != null) NickButton("Bring back to home", { m.home(v.node.id) }, style = BtnStyle.GHOST, big = true, leading = Lucide.House)
                    NickButton("Find something to watch", onFind, style = BtnStyle.PRIMARY, big = true, leading = Lucide.Film)
                }
            }
        }
        if (narrow) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                box(Modifier.fillMaxWidth().heightIn(min = 420.dp))
                EyebrowText("On ${v.node.name}")
                DeviceSliders(v)
            }
        } else {
            Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 32.dp, top = 32.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                box(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    EyebrowText("On ${v.node.name}")
                    DeviceSliders(v)
                }
            }
        }
    }
}

@Composable
private fun OfflineBody(v: NodeView, onAll: () -> Unit) {
    val m = App.get(LocalContext.current).master
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 80.dp, end = 80.dp, top = 40.dp, bottom = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(136.dp).clip(CircleShape).background(C.surface), contentAlignment = Alignment.Center) {
            Icon(Lucide.WifiOff, null, tint = C.muted, modifier = Modifier.size(56.dp))
        }
        Title("${v.node.name} is offline", 52, lineHeight = 1.05f)
        val seen = seenAgo(v.node.lastSeen).replaceFirstChar { it.uppercase() }.replace("Seen", "Last seen")
        Muted(listOfNotNull(seen.ifEmpty { null }, v.node.lastBattery?.let { "battery was $it%" }).joinToString(" · "), 20)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tip(Lucide.Power, "Make sure it's switched on and charged")
            Tip(Lucide.Wifi, "Check it's on the same WiFi as this tablet")
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NickButton("Try again", { m.reconnect(v.node.id) }, style = BtnStyle.PRIMARY, big = true, leading = Lucide.RefreshCw)
            NickButton("All tablets", onAll, style = BtnStyle.GHOST, big = true)
        }
        // A tablet reset while this one was away never said goodbye: let the parent forget it here.
        var confirmUnlink by remember { mutableStateOf(false) }
        Muted("Reset or gave it away? Unlink it so it stops showing here.", 17, Modifier.padding(top = 10.dp))
        NickButton(if (confirmUnlink) "Tap again to unlink" else "Unlink tablet", {
            if (confirmUnlink) { m.unlink(v.node.id); onAll() } else confirmUnlink = true
        }, style = BtnStyle.DANGER, leading = Lucide.Unlink)
    }
}

@Composable
private fun Tip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = C.text, modifier = Modifier.size(22.dp))
        Text(text, style = body(20.sp, 600), color = C.text)
    }
}

// ---------------- Apps ----------------

@Composable
private fun AppsTab(v: NodeView) {
    val m = App.get(LocalContext.current).master
    LaunchedEffect(v.node.id) { m.requestApps(v.node.id) }
    val apps = v.apps
    val st = v.status
    val allowed = st?.allowed.orEmpty()
    val open = st?.fg
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 26.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        val intro: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tap an app to open it on ${v.node.name} now.", style = body(20.sp, 700), color = C.text)
                Text(
                    buildAnnotatedString {
                        append("Switch on ")
                        withStyle(SpanStyle(color = C.text, fontWeight = FontWeight.Bold)) { append("Home") }
                        append(" to show it on the home screen")
                        if (apps != null) append(" · ${allowed.count { a -> apps.any { it.pkg == a } }} of ${apps.size} shown")
                    },
                    style = body(17.sp), color = C.muted,
                )
            }
        }
        val buttons: @Composable () -> Unit = {
            NickButton("Bring back to home", { m.home(v.node.id) }, leading = Lucide.House)
            val locked = st?.lockedApp
            when {
                locked != null -> NickButton("Unlock ${apps?.firstOrNull { it.pkg == locked }?.label ?: "app"}", { m.lockToApp(v.node.id, null) }, style = BtnStyle.PRIMARY, leading = Lucide.LockOpen)
                open != null -> NickButton("Lock to ${st.fgLabel ?: "this app"}", { m.lockToApp(v.node.id, open) }, style = BtnStyle.PRIMARY, leading = Lucide.Lock)
            }
        }
        // Phone-width Master: text above the buttons instead of squeezed beside them.
        if (LocalConfiguration.current.screenWidthDp < 600) {
            intro(Modifier.fillMaxWidth())
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            intro(Modifier.weight(1f))
            buttons()
        }
        if (apps == null) {
            Muted("Getting the app list from ${v.node.name}…", 19)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            items(apps, key = { it.pkg }) { a ->
                val isOpen = a.pkg == open
                val shape = RoundedCornerShape(24.dp)
                Box(Modifier.clip(shape).background(C.surface).border(3.dp, if (isOpen) C.accent else Color.Transparent, shape)) {
                    Column {
                        Column(
                            Modifier.fillMaxWidth().clickable { m.open(v.node.id, a.pkg) }.padding(top = 20.dp, bottom = 10.dp, start = 8.dp, end = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AppTileIcon(a, Lucide.LayoutGrid, 76.dp, 21.dp, v.node.color)
                            Text(a.label, style = body(17.sp, 700), color = C.text, maxLines = 1)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.surface2))
                        Row(
                            // The whole row is the target, not just the switch: a reach from the driver's seat must not open the app by mistake.
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .toggleable(a.pkg in allowed, role = androidx.compose.ui.semantics.Role.Switch) { m.allow(v.node.id, a.pkg, it) }
                                .padding(start = 14.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Home", style = body(14.sp, 600), color = C.muted)
                            NickSwitch(a.pkg in allowed) { m.allow(v.node.id, a.pkg, it) }
                        }
                    }
                    if (isOpen) Box(
                        Modifier.align(Alignment.TopEnd).padding(10.dp).height(24.dp).clip(RoundedCornerShape(12.dp)).background(C.accent).padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("OPEN", style = body(12.sp, 800), color = C.onAccent) }
                }
            }
        }
    }
}

// ---------------- Device ----------------

@Composable
private fun DeviceTab(v: NodeView, onUnlinked: () -> Unit) {
    val m = App.get(LocalContext.current).master
    val st = v.status
    var renaming by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }
    val now = rememberNow(1000)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = if (maxWidth < 1000.dp) 1 else 3
        val gap = 20.dp
        val cardH: Dp = if (cols == 3) (maxHeight - 26.dp - 32.dp - gap) / 2 else 300.dp
        val cards: List<@Composable (Modifier) -> Unit> = listOf(
            { mod ->
                DeviceCard(mod, Lucide.Tablet, "Screen", rotateIcon = true) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surface2).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val on = st?.screenOn != false
                        SegBtn(Lucide.MonitorOff, "Off", !on, Modifier.weight(1f)) { m.screen(v.node.id, false) }
                        SegBtn(Lucide.Sun, "On", on, Modifier.weight(1f)) { m.screen(v.node.id, true) }
                    }
                    if (st?.lockedBy != null) NickButton("Unlock tablet", { m.lock(v.node.id, false) }, Modifier.fillMaxWidth(), BtnStyle.PRIMARY, big = true, leading = Lucide.LockOpen)
                    else NickButton("Lock tablet now", { m.lock(v.node.id, true) }, Modifier.fillMaxWidth(), big = true, leading = Lucide.Lock)
                }
            },
            { mod ->
                DeviceCard(mod, Lucide.Moon, "Bedtime") {
                    val at = st?.bedtimeAt ?: 0
                    val left = if (at > 0) com.nbaltas.nickos.ui.theme.minutesLeft(at - now) else 0
                    var chosen by remember { mutableStateOf<Int?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60).forEach { min ->
                            val sel = at > 0 && chosen == min
                            Box(
                                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (sel) C.accent else C.surface2)
                                    .clickable { chosen = min; m.bedtime(v.node.id, min) },
                                contentAlignment = Alignment.Center,
                            ) { Text("$min min", style = body(18.sp, 600), color = if (sel) C.onAccent else C.text) }
                        }
                    }
                    if (at > 0) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface2).padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("SCREEN TURNS OFF IN", style = body(14.sp, 700), color = C.muted)
                                Text("$left min", style = disp(34.sp), color = C.text)
                            }
                            NickButton("Cancel", { chosen = null; m.bedtime(v.node.id, 0) }, Modifier.height(52.dp), BtnStyle.GHOST)
                        }
                    } else Muted("Pick a time. The tablet warns 5 minutes before, then turns its screen off.", 16)
                }
            },
            { mod ->
                DeviceCard(mod, com.nbaltas.nickos.ui.theme.batteryIcon(v.battery ?: 100, st?.charging == true), "Battery") {
                    val b = v.battery ?: 0
                    Text("$b%", style = disp(88.sp, 800, 1f), color = if (b < 25) C.danger else C.text)
                    ProgressBar(b / 100f, height = 14.dp, fill = if (b < 25) C.danger else C.online)
                    Muted(if (st?.charging == true) "Charging" else "Not charging", 17, weight = 600)
                }
            },
            { mod -> SliderCard(Lucide.Volume2, "Volume", st?.volume ?: 0, big = true, modifier = mod) { m.setVolume(v.node.id, it) } },
            { mod -> SliderCard(Lucide.Sun, "Brightness", st?.brightness ?: 0, big = true, modifier = mod) { m.setBrightness(v.node.id, it) } },
            { mod ->
                DeviceCard(mod, Lucide.Settings, "Tablet", trailing = {
                    if (st != null && !st.owner) Chip(height = 30.dp, bg = C.dangerLine) { Text("Kid lock off", style = body(13.sp, 700), color = C.danger) }
                    else if (st != null && !st.admin) Chip(height = 30.dp, bg = C.dangerLine) { Text("Screen off needs permission", style = body(13.sp, 700), color = C.danger) }
                }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StartButton(Lucide.RefreshCw, "Restart Jellyfin") { m.restartJellyfin(v.node.id) }
                        StartButton(Lucide.Pencil, "Rename tablet") { renaming = true }
                        StartButton(Lucide.Unlink, if (confirmUnlink) "Tap again to unlink" else "Unlink tablet", danger = true) {
                            if (confirmUnlink) { m.unlink(v.node.id); onUnlinked() } else confirmUnlink = true
                        }
                    }
                }
            },
        )
        Column(
            Modifier.fillMaxSize().then(if (cols == 1) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = 40.dp, end = 40.dp, top = 26.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            cards.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { row.forEach { it(Modifier.weight(1f).height(cardH)) } }
            }
        }
    }
    if (renaming) RenameDialog(v.node.name, onDone = { name -> renaming = false; if (name != null) m.rename(v.node.id, name) })
}

@Composable
private fun DeviceCard(
    modifier: Modifier, icon: ImageVector, title: String, rotateIcon: Boolean = false,
    trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(C.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = C.text, modifier = Modifier.size(22.dp).then(if (rotateIcon) Modifier.rotate90() else Modifier))
            Text(title, style = body(20.sp, 800), color = C.text, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@Composable
private fun SegBtn(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(68.dp).clip(RoundedCornerShape(17.dp)).background(if (on) C.accent else Color.Transparent).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        val col = if (on) C.onAccent else C.muted
        Icon(icon, null, tint = col, modifier = Modifier.size(22.dp))
        Text(label, style = body(20.sp, 800), color = col)
    }
}

@Composable
private fun StartButton(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(shape)
            .then(if (danger) Modifier.border(2.dp, C.dangerLine, shape) else Modifier.background(C.surface2))
            .clickable(onClick = onClick).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val col = if (danger) C.danger else C.text
        Icon(icon, null, tint = col, modifier = Modifier.size(20.dp))
        Text(label, style = body(19.sp, 700), color = col, maxLines = 1)
    }
}

/** Name editor used by the Device tab and Settings. [onDone] gets null on cancel. */
@Composable
fun RenameDialog(current: String, title: String = "Rename tablet", onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(current) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).tap { onDone(null) }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(30.dp)).background(C.surface).tap {}.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Title(title, 36)
            NickField(name, { name = it.take(28) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End)) {
                NickButton("Cancel", { onDone(null) }, style = BtnStyle.GHOST)
                NickButton("Save", { onDone(name.trim()) }, style = BtnStyle.PRIMARY, enabled = name.isNotBlank())
            }
        }
    }
}
