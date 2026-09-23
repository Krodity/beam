package uk.krodity.beam

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.krodity.beam.data.HostInfo
import uk.krodity.beam.data.PairLink
import uk.krodity.beam.ui.BeamViewModel
import uk.krodity.beam.ui.Tab
import uk.krodity.beam.ui.screens.BrowserScreen
import uk.krodity.beam.ui.screens.MiniPlayer
import uk.krodity.beam.ui.screens.PairScreen
import uk.krodity.beam.ui.screens.RemoteScreen
import uk.krodity.beam.ui.theme.BeamTheme

class MainActivity : ComponentActivity() {

    private val vm: BeamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { BeamTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.pollNow()
    }

    /** beam://pair deep links pair the app; a shared link ("Share → Beam" from
     *  YouTube or any browser) goes straight to the PC. */
    private fun handleIntent(intent: Intent?) {
        PairLink.parse(intent?.data)?.let { vm.pair(it.broker, it.token); return }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("""https?://\S+""").find(text)?.value?.let { vm.send(it) }
        }
    }

    /** Volume keys drive the PC's volume while the remote is on screen. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = vm.ui.value
        if (s.tab == Tab.REMOTE && s.status.hasVideo &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                vm.nudgeVolume(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05 else -0.05)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun App(vm: BeamViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showHosts by remember { mutableStateOf(false) }

    LaunchedEffect(ui.error, ui.info) {
        (ui.error ?: ui.info)?.let {
            vm.dismissMessages()
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Short)
        }
    }

    if (ui.checkingPairing) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (!ui.paired) {
        PairScreen("", "") { b, t -> vm.pair(b, t) }
        return
    }

    val nav: @Composable () -> Unit = {
        Column {
            if (ui.tab == Tab.BROWSER) {
                MiniPlayer(ui.status, onOpen = { vm.showTab(Tab.REMOTE) }, onToggle = { vm.control("toggle") })
            }
            NavigationBar {
                NavigationBarItem(
                    selected = ui.tab == Tab.BROWSER,
                    onClick = { vm.showTab(Tab.BROWSER) },
                    icon = { Icon(Icons.Default.Language, null) },
                    label = { Text("Browse") },
                )
                NavigationBarItem(
                    selected = ui.tab == Tab.REMOTE,
                    onClick = { vm.showTab(Tab.REMOTE) },
                    icon = {
                        BadgedBox(badge = { if (ui.status.playing) Badge() }) {
                            Icon(Icons.Default.SettingsRemote, null)
                        }
                    },
                    label = { Text("Remote") },
                )
            }
        }
    }

    BackHandler(enabled = ui.tab == Tab.REMOTE) { vm.showTab(Tab.BROWSER) }

    Box(Modifier.fillMaxSize()) {
        // The browser is never removed from composition -- its WebView (and the
        // page in it) must survive a trip to the remote and back.
        BrowserScreen(
            bookmarks = ui.bookmarks,
            autoSend = ui.autoSend,
            sending = ui.sending,
            hostName = ui.activeHostName,
            onSend = { u, t -> vm.send(u, t) },
            onAutoSend = vm::setAutoSend,
            onAddBookmark = vm::addBookmark,
            onRemoveBookmark = vm::removeBookmark,
            onChoosePc = { vm.refreshHosts(); showHosts = true },
            onUnpair = vm::unpair,
            bottomBar = nav,
            active = ui.tab == Tab.BROWSER,
        )
        if (ui.tab == Tab.REMOTE) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                RemoteScreen(
                    status = ui.status,
                    hostName = ui.activeHostName,
                    pcTabs = ui.pcTabs,
                    onControl = vm::control,
                    onSeek = vm::seekTo,
                    onVolume = vm::setVolume,
                    onLoadTabs = vm::loadPcTabs,
                    onFocusTab = vm::focusPcTab,
                    onChoosePc = { vm.refreshHosts(); showHosts = true },
                    onBrowse = { vm.showTab(Tab.BROWSER) },
                    bottomBar = nav,
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp),
        )
    }

    if (showHosts) {
        HostPicker(
            hosts = ui.hosts,
            active = ui.activeHost,
            onPick = { vm.selectHost(it); showHosts = false },
            onUnpair = { showHosts = false; vm.unpair() },
            onDismiss = { showHosts = false },
        )
    }
}

@Composable
private fun HostPicker(
    hosts: List<HostInfo>,
    active: String,
    onPick: (String) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play on which PC?") },
        text = {
            Column {
                if (hosts.isEmpty()) {
                    Text(
                        "No PC is connected. Open a browser that has the Beam extension installed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    HostRow("Most recent", active.isBlank()) { onPick("") }
                    hosts.forEach { h -> HostRow(h.name.ifBlank { h.id }, h.id == active) { onPick(h.id) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onUnpair) { Text("Unpair") } },
    )
}

@Composable
private fun HostRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
