package uk.krodity.beam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.krodity.beam.data.Bookmark
import uk.krodity.beam.data.Broker
import uk.krodity.beam.data.BrokerException
import uk.krodity.beam.data.HostInfo
import uk.krodity.beam.data.PcTab
import uk.krodity.beam.data.PlayerStatus
import uk.krodity.beam.data.SettingsRepo

enum class Tab { BROWSER, REMOTE }

data class UiState(
    val paired: Boolean = false,
    val checkingPairing: Boolean = true,
    val hosts: List<HostInfo> = emptyList(),
    val activeHost: String = "",
    val autoSend: Boolean = true,
    val bookmarks: List<Bookmark> = Bookmark.DEFAULTS,

    val tab: Tab = Tab.BROWSER,
    val status: PlayerStatus = PlayerStatus(),
    val pcTabs: List<PcTab> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    /** A non-error one-liner for the snackbar ("Playing on Desktop Chromium"). */
    val info: String? = null,
) {
    val activeHostName: String
        get() = hosts.firstOrNull { it.id == activeHost }?.name
            ?: hosts.maxByOrNull { it.connected_at }?.name
            ?: "PC"
}

class BeamViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepo(app)
    private var cfg = SettingsRepo.Config()
    private val broker = Broker { cfg }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var pollJob: Job? = null
    /** After a seek or volume change, ignore the poll's stale value for a moment
     *  so the slider doesn't snap back while the PC catches up. */
    private var seekGuardUntil = 0L
    private var volumeGuardUntil = 0L

    init {
        viewModelScope.launch {
            settings.config.collect { c ->
                val wasPaired = cfg.paired
                cfg = c
                _ui.update {
                    it.copy(
                        paired = c.paired,
                        checkingPairing = false,
                        activeHost = c.host,
                        autoSend = c.autoSend,
                        bookmarks = c.bookmarks,
                    )
                }
                if (c.paired) {
                    if (!wasPaired) refreshHosts()
                    startPolling()
                } else {
                    stopPolling()
                }
            }
        }
    }

    // ── pairing / settings ───────────────────────────────────────────────────
    fun pair(broker: String, token: String) = viewModelScope.launch {
        settings.pair(broker, token)
        _ui.update { it.copy(error = null) }
    }

    fun unpair() = viewModelScope.launch {
        stopPolling()
        settings.unpair()
        _ui.update { UiState(checkingPairing = false, bookmarks = it.bookmarks, autoSend = it.autoSend) }
    }

    fun selectHost(id: String) = viewModelScope.launch { settings.selectHost(id) }

    fun setAutoSend(on: Boolean) = viewModelScope.launch {
        settings.setAutoSend(on)
        _ui.update { it.copy(info = if (on) "Videos will play on the PC" else "Videos will play on this phone") }
    }

    fun addBookmark(title: String, url: String) = viewModelScope.launch {
        val list = _ui.value.bookmarks.filterNot { it.url == url } + Bookmark(title.ifBlank { url }, url)
        settings.setBookmarks(list)
        _ui.update { it.copy(info = "Added to start page") }
    }

    fun removeBookmark(b: Bookmark) = viewModelScope.launch {
        settings.setBookmarks(_ui.value.bookmarks - b)
    }

    fun refreshHosts() = viewModelScope.launch {
        runCatching { broker.hosts() }
            .onSuccess { h -> _ui.update { it.copy(hosts = h) } }
            .onFailure { fail(it) }
    }

    fun showTab(t: Tab) {
        _ui.update { it.copy(tab = t) }
        if (t == Tab.REMOTE) viewModelScope.launch { pollOnce() }
    }

    // ── handing a page to the PC ─────────────────────────────────────────────
    /** Open [url] on the PC, resuming at [time]. Returns once the PC accepted it. */
    fun send(url: String, time: Double = 0.0, switchToRemote: Boolean = true) = viewModelScope.launch {
        if (_ui.value.sending) return@launch
        _ui.update { it.copy(sending = true, error = null) }
        runCatching { broker.open(url, time) }
            .onSuccess {
                if (_ui.value.hosts.isEmpty()) refreshHosts()
                _ui.update {
                    it.copy(
                        sending = false,
                        info = "Playing on ${it.activeHostName}",
                        tab = if (switchToRemote) Tab.REMOTE else it.tab,
                        // Blank the old video's state so the remote doesn't show
                        // the previous title while the new page loads.
                        status = PlayerStatus(reason = "Opening on the PC…", loading = true),
                    )
                }
                delay(2500)
                pollOnce()
            }
            .onFailure {
                _ui.update { s -> s.copy(sending = false) }
                fail(it)
            }
    }

    // ── remote control ───────────────────────────────────────────────────────
    fun control(action: String, value: Any? = null) = viewModelScope.launch {
        when (action) {
            "seek", "forward", "back", "restart" -> seekGuardUntil = System.currentTimeMillis() + 1500
            "volume" -> volumeGuardUntil = System.currentTimeMillis() + 1500
        }
        // Reflect play/pause at once; the next poll corrects us if it failed.
        when (action) {
            "play" -> optimistic(playing = true)
            "pause" -> optimistic(playing = false)
            "toggle" -> optimistic(playing = !_ui.value.status.playing)
        }
        runCatching { broker.control(action, value) }
            .onSuccess { s -> if (s.hasVideo) merge(s) }
            .onFailure { fail(it) }
        if (action in setOf("next", "prev", "skipAd", "fullscreen", "dismiss")) {
            delay(1200)
            pollOnce()
        }
    }

    fun seekTo(fraction: Float) {
        val d = _ui.value.status.duration
        if (d > 0) {
            _ui.update { it.copy(status = it.status.copy(position = d * fraction)) }
            control("seek", d * fraction)
        }
    }

    fun setVolume(v: Float) {
        _ui.update { it.copy(status = it.status.copy(volume = v.toDouble(), muted = false)) }
        control("volume", v.toDouble())
    }

    /** Hardware volume keys while the remote is showing. */
    fun nudgeVolume(delta: Double) {
        val v = (_ui.value.status.volume + delta).coerceIn(0.0, 1.0)
        setVolume(v.toFloat())
    }

    fun loadPcTabs() = viewModelScope.launch {
        runCatching { broker.tabs() }
            .onSuccess { t -> _ui.update { it.copy(pcTabs = t) } }
            .onFailure { fail(it) }
    }

    fun focusPcTab(t: PcTab) = viewModelScope.launch {
        runCatching { broker.focusTab(t.id) }
            .onSuccess {
                _ui.update { it.copy(tab = Tab.REMOTE) }
                pollOnce()
            }
            .onFailure { fail(it) }
    }

    private fun optimistic(playing: Boolean) {
        _ui.update { it.copy(status = it.status.copy(playing = playing, paused = !playing)) }
    }

    /** Take a fresh status, but keep values the user just changed until the PC
     *  has had time to apply them. */
    private fun merge(s: PlayerStatus) {
        val now = System.currentTimeMillis()
        _ui.update {
            var n = s
            if (now < seekGuardUntil && s.hasVideo) n = n.copy(position = it.status.position)
            if (now < volumeGuardUntil && s.hasVideo) n = n.copy(volume = it.status.volume)
            it.copy(status = n)
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                pollOnce()
                // Fast while the remote is on screen, slow while browsing (the
                // mini-player only needs the title and play state).
                val s = _ui.value
                delay(
                    when {
                        s.tab == Tab.REMOTE && s.status.hasVideo -> 1000
                        s.status.hasVideo -> 3000
                        else -> 5000
                    }
                )
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun pollNow() = viewModelScope.launch { pollOnce() }

    private suspend fun pollOnce() {
        if (!cfg.paired) return
        runCatching { broker.status() }
            .onSuccess { merge(it) }
            .onFailure { e ->
                if (e is BrokerException && !e.recoverable) fail(e)
                else _ui.update { it.copy(status = PlayerStatus(reason = e.message)) }
            }
    }

    fun dismissMessages() = _ui.update { it.copy(error = null, info = null) }

    private fun fail(e: Throwable) {
        _ui.update { it.copy(error = e.message ?: "Something went wrong") }
    }
}
