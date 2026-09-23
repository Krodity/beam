package uk.krodity.beam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import uk.krodity.beam.data.PcTab
import uk.krodity.beam.data.PlayerStatus
import uk.krodity.beam.ui.formatTime

/** Full remote for whatever the PC's Beam tab is playing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    status: PlayerStatus,
    hostName: String,
    pcTabs: List<PcTab>,
    onControl: (String, Any?) -> Unit,
    onSeek: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onLoadTabs: () -> Unit,
    onFocusTab: (PcTab) -> Unit,
    onChoosePc: () -> Unit,
    onBrowse: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    var showTabs by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Remote", style = MaterialTheme.typography.titleMedium)
                        Text(
                            hostName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onLoadTabs(); showTabs = true }) {
                        Icon(Icons.Default.Tab, "PC tabs")
                    }
                    IconButton(onClick = onChoosePc) { Icon(Icons.Default.Computer, "Choose PC") }
                },
            )
        },
        bottomBar = bottomBar,
    ) { pad ->
        if (!status.hasVideo) {
            Empty(status, Modifier.padding(pad), onBrowse, onPickTab = { onLoadTabs(); showTabs = true })
        } else {
            Controls(status, Modifier.padding(pad), onControl, onSeek, onVolume, onKeys = { showKeys = true })
        }
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            Text(
                "Tabs on $hostName",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "Pick one to control it from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(pcTabs, key = { it.id }) { t ->
                    ListItem(
                        headlineContent = { Text(t.title.ifBlank { t.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    "Beam tab".takeIf { t.beam },
                                    "playing sound".takeIf { t.audible },
                                    runCatching { java.net.URI(t.url).host }.getOrNull(),
                                ).joinToString(" · "),
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            AsyncImage(model = t.favicon.ifBlank { null }, contentDescription = null,
                                modifier = Modifier.size(24.dp))
                        },
                        trailingContent = {
                            if (t.audible) Icon(Icons.AutoMirrored.Filled.VolumeUp, null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { onFocusTab(t); showTabs = false },
                    )
                }
            }
        }
    }

    if (showKeys) {
        ModalBottomSheet(onDismissRequest = { showKeys = false }) {
            KeyPad(onKey = { onControl("key", it) })
        }
    }
}

@Composable
private fun Empty(status: PlayerStatus, modifier: Modifier, onBrowse: () -> Unit, onPickTab: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (status.loading) CircularProgressIndicator()
        else Icon(Icons.Default.CastConnected, null, Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            status.pageTitle?.takeIf { it.isNotBlank() } ?: "Nothing playing on the PC",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        status.reason?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBrowse) { Text("Find something to watch") }
        TextButton(onClick = onPickTab) { Text("Control a tab already open on the PC") }
    }
}

@Composable
private fun Controls(
    s: PlayerStatus,
    modifier: Modifier,
    onControl: (String, Any?) -> Unit,
    onSeek: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onKeys: () -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    val shown = if (scrubbing) scrub else s.progress
    var volDrag by remember { mutableStateOf<Float?>(null) }
    var rateMenu by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Artwork: media-session art when the site publishes it, else a
        // placeholder with the site's favicon.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            if (!s.artwork.isNullOrBlank()) {
                AsyncImage(
                    model = s.artwork, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    if (!s.favicon.isNullOrBlank()) {
                        AsyncImage(model = s.favicon, contentDescription = null, modifier = Modifier.size(48.dp))
                    } else {
                        Icon(if (s.kind == "audio") Icons.Default.MusicNote else Icons.Default.Movie, null,
                            Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            s.displayTitle.ifBlank { "Playing on the PC" },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (s.displaySubtitle.isNotBlank()) {
            Text(
                s.displaySubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (s.ad) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { onControl("skipAd", null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FastForward, null)
                Spacer(Modifier.width(8.dp))
                Text("Ad playing — Skip ad")
            }
        }

        Spacer(Modifier.height(12.dp))
        if (s.live) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(4.dp)) {
                    Text("LIVE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onError)
                }
            }
        } else {
            Slider(
                value = shown,
                onValueChange = { scrubbing = true; scrub = it },
                onValueChangeFinished = { scrubbing = false; onSeek(scrub) },
                enabled = s.duration > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(shown * s.duration), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("-" + formatTime(s.duration - shown * s.duration), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Btn(Icons.Default.SkipPrevious, "Previous") { onControl("prev", null) }
            Btn(Icons.Default.Replay10, "Back 10 seconds", !s.live) { onControl("back", 10.0) }
            FilledIconButton(onClick = { onControl("toggle", null) }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (s.playing) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
            Btn(Icons.Default.Forward10, "Forward 10 seconds", !s.live) { onControl("forward", 10.0) }
            Btn(Icons.Default.SkipNext, "Next") { onControl("next", null) }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onControl("mute", null) }) {
                Icon(
                    if (s.muted || s.volume == 0.0) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    "Mute",
                )
            }
            Slider(
                value = volDrag ?: if (s.muted) 0f else s.volume.toFloat(),
                onValueChange = { volDrag = it },
                onValueChangeFinished = { volDrag?.let(onVolume); volDrag = null },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Btn(if (s.fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen") {
                onControl("fullscreen", null)
            }
            Box {
                TextButton(onClick = { rateMenu = true }) {
                    Text(if (s.rate == 1.0) "1×" else "${"%.2f".format(s.rate).trimEnd('0').trimEnd('.')}×")
                }
                DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                    listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).forEach { r ->
                        DropdownMenuItem(
                            text = { Text("${r}×") },
                            onClick = { rateMenu = false; onControl("rate", r) },
                            trailingIcon = { if (r == s.rate) Icon(Icons.Default.Check, null) },
                        )
                    }
                }
            }
            Btn(Icons.Default.Keyboard, "Keyboard") { onKeys() }
            Btn(Icons.Default.TouchApp, "Dismiss “still watching?”") { onControl("dismiss", null) }
            Btn(Icons.Default.Close, "Close the tab on the PC") { onControl("close", null) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Btn(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, label, modifier = Modifier.size(28.dp))
    }
}

/** Real keypresses into the PC page, for players with their own shortcuts. */
@Composable
private fun KeyPad(onKey: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Send a key to the PC page", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        IconButton(onClick = { onKey("up") }) { Icon(Icons.Default.KeyboardArrowUp, "Up", Modifier.size(36.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onKey("left") }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", Modifier.size(36.dp))
            }
            FilledTonalButton(onClick = { onKey("space") }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Space")
            }
            IconButton(onClick = { onKey("right") }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", Modifier.size(36.dp))
            }
        }
        IconButton(onClick = { onKey("down") }) { Icon(Icons.Default.KeyboardArrowDown, "Down", Modifier.size(36.dp)) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("f" to "F", "m" to "M", "k" to "K", "enter" to "Enter", "escape" to "Esc").forEach { (k, l) ->
                OutlinedButton(onClick = { onKey(k) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(l) }
            }
        }
    }
}

/** One-line strip above the nav bar while browsing: what the PC is playing. */
@Composable
fun MiniPlayer(status: PlayerStatus, onOpen: () -> Unit, onToggle: () -> Unit) {
    if (!status.hasVideo) return
    Surface(tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(
                progress = { status.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen)
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CastConnected, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(status.displayTitle.ifBlank { "Playing on the PC" },
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (status.live) "Live" else "${formatTime(status.position)} / ${formatTime(status.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(if (status.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (status.playing) "Pause" else "Play")
                }
            }
        }
    }
}
