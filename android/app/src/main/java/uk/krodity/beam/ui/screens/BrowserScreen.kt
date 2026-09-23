package uk.krodity.beam.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import uk.krodity.beam.data.Bookmark

/**
 * The in-app browser. Browse any site on the phone; the moment a video starts
 * playing here it is paused and the page is sent to the PC instead.
 *
 * Two triggers, because neither alone is enough:
 *  - a capture-phase `play` listener injected into every page catches any
 *    <video>/<audio> on any site (top frame only -- cross-origin iframes are
 *    out of reach of an injected script);
 *  - known "watch page" URLs are sent as soon as they are navigated to, which
 *    also covers sites whose player lives in an iframe, and SPA navigations.
 * Either way the page URL is what travels: the PC loads it in its own
 * logged-in browser, so DRM and account-only content play fine there.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    bookmarks: List<Bookmark>,
    autoSend: Boolean,
    sending: Boolean,
    hostName: String,
    onSend: (url: String, time: Double) -> Unit,
    onAutoSend: (Boolean) -> Unit,
    onAddBookmark: (String, String) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    onChoosePc: () -> Unit,
    onUnpair: () -> Unit,
    bottomBar: @Composable () -> Unit,
    active: Boolean,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current

    var home by rememberSaveable { mutableStateOf(true) }
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    val autoSendNow by rememberUpdatedState(autoSend)
    val send by rememberUpdatedState(onSend)

    // Which page we already handed off. Further play events for it (sites retry
    // autoplay) are just paused again; navigating elsewhere clears it so coming
    // back to the same video sends it again.
    val handed = remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Let pages autoplay: an autoplay that the WebView silently refused
            // would never fire the `play` event we hand off on.
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    fun pauseAll() {
        webView.evaluateJavascript("window.__beamHook && window.__beamHook.pauseAll()", null)
    }

    fun handoff(pageUrl: String, time: Double) {
        if (!autoSendNow) return
        val key = WatchPages.key(pageUrl)
        pauseAll()
        if (handed.value == key) return
        handed.value = key
        send(pageUrl, time)
    }

    DisposableEffect(webView) {
        val main = Handler(Looper.getMainLooper())
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPlay(pageUrl: String, time: Double) {
                main.post { handoff(pageUrl, time) }
            }
        }, "BeamBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                // intent:, market:, mailto: etc. -- nothing for a media browser to do.
                return u.scheme != "http" && u.scheme != "https"
            }

            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                url = u
            }

            override fun onPageFinished(view: WebView, u: String) {
                view.evaluateJavascript(HOOK_JS, null)
                canBack = view.canGoBack()
                canForward = view.canGoForward()
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                // Fires for pushState navigations too (YouTube, Twitch are SPAs).
                url = u
                canBack = view.canGoBack()
                canForward = view.canGoForward()
                val key = WatchPages.key(u)
                if (handed.value != null && handed.value != key) handed.value = null
                view.evaluateJavascript(HOOK_JS, null)
                if (WatchPages.isWatch(u)) handoff(u, WatchPages.startTime(u))
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress = p
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                title = t.orEmpty()
            }
        }
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    fun go(input: String) {
        val target = toUrl(input) ?: return
        home = false
        editing = false
        focus.clearFocus()
        webView.loadUrl(target)
    }

    /** "Play on PC" by hand: send the current page with the phone's position. */
    fun sendCurrent() {
        if (url.isBlank()) return
        webView.evaluateJavascript(TIME_JS) { r ->
            pauseAll()
            handed.value = WatchPages.key(url)
            send(url, r?.toDoubleOrNull() ?: 0.0)
        }
    }

    BackHandler(enabled = active && (editing || !home)) {
        when {
            editing -> { editing = false; focus.clearFocus() }
            webView.canGoBack() -> webView.goBack()
            else -> home = true
        }
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { home = true; editing = false; focus.clearFocus() }) {
                            Icon(Icons.Default.Home, "Start page")
                        }
                        val shown = if (editing) address else if (home) "" else (title.ifBlank { url })
                        OutlinedTextField(
                            value = shown,
                            onValueChange = { address = it },
                            placeholder = { Text("Search or type a URL") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(onGo = { go(address) }),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .onFocusChanged {
                                    if (it.isFocused && !editing) {
                                        editing = true
                                        address = if (home) "" else url
                                    } else if (!it.isFocused) {
                                        editing = false
                                    }
                                },
                        )
                        if (!home) {
                            IconButton(onClick = {
                                if (progress < 100) webView.stopLoading() else webView.reload()
                            }) {
                                Icon(if (progress < 100) Icons.Default.Close else Icons.Default.Refresh, "Reload")
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Back") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                                    enabled = canBack && !home,
                                    onClick = { menu = false; webView.goBack() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Forward") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                                    enabled = canForward,
                                    onClick = { menu = false; home = false; webView.goForward() },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Auto-send videos to PC") },
                                    leadingIcon = { Icon(Icons.Default.CastConnected, null) },
                                    trailingIcon = { Switch(checked = autoSend, onCheckedChange = null) },
                                    onClick = { menu = false; onAutoSend(!autoSend) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to start page") },
                                    leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                                    enabled = !home && url.isNotBlank(),
                                    onClick = { menu = false; onAddBookmark(title, url) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Choose PC ($hostName)") },
                                    leadingIcon = { Icon(Icons.Default.Computer, null) },
                                    onClick = { menu = false; onChoosePc() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Unpair") },
                                    leadingIcon = { Icon(Icons.Default.LinkOff, null) },
                                    onClick = { menu = false; onUnpair() },
                                )
                            }
                        }
                    }
                    if (!home && progress < 100) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!home && url.isNotBlank()) {
                ExtendedFloatingActionButton(
                    onClick = { sendCurrent() },
                    icon = {
                        if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Cast, null)
                    },
                    text = { Text("Play on PC") },
                    expanded = !autoSend,
                )
            }
        },
        bottomBar = bottomBar,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // The WebView stays attached even on the start page, so going home
            // and back again never reloads the page underneath.
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (home) {
                StartPage(
                    bookmarks = bookmarks,
                    autoSend = autoSend,
                    hostName = hostName,
                    onOpen = { go(it.url) },
                    onRemove = onRemoveBookmark,
                    resume = if (url.isNotBlank()) title.ifBlank { url } else null,
                    onResume = { home = false },
                )
            }
        }
    }
}

@Composable
private fun StartPage(
    bookmarks: List<Bookmark>,
    autoSend: Boolean,
    hostName: String,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    resume: String?,
    onResume: () -> Unit,
) {
    var confirm by remember { mutableStateOf<Bookmark?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("Beam", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (autoSend) "Browse anywhere — videos you start play on $hostName."
                else "Auto-send is off: tap Play on PC to send a page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (resume != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedCard(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tab, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Back to", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(resume, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(bookmarks, key = { it.url }) { b ->
                    BookmarkTile(b, onClick = { onOpen(b) }, onLongClick = { confirm = b })
                }
            }
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${b.title}?") },
            text = { Text(b.url, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { onRemove(b); confirm = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookmarkTile(b: Bookmark, onClick: () -> Unit, onLongClick: () -> Unit) {
    val host = Uri.parse(b.url).host.orEmpty()
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Google's favicon service: one request, sized, works for any host.
                AsyncImage(
                    model = "https://www.google.com/s2/favicons?sz=64&domain=$host",
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            b.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Typed text -> URL: keep URLs, add a scheme to bare hosts, search the rest. */
private fun toUrl(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (s.startsWith("http://") || s.startsWith("https://")) return s
    if (!s.contains(' ') && s.contains('.') && !s.endsWith('.')) return "https://$s"
    return "https://www.google.com/search?q=" + Uri.encode(s)
}

/** Injected into every page. Capture-phase listeners see `play` on any media
 *  element even though the event doesn't bubble. Muted/looping clips (hover
 *  previews, background video) are ignored: they aren't what the user chose. */
private const val HOOK_JS = """
(function () {
  if (window.__beamHook) { window.__beamHook.scan(); return; }
  var decor = function (m) { return m.muted || (m.loop && m.duration && m.duration < 30); };
  var report = function (m) {
    if (decor(m)) return;
    try { BeamBridge.onPlay(location.href, m.currentTime || 0); } catch (e) {}
  };
  document.addEventListener('play', function (e) {
    if (e.target instanceof HTMLMediaElement) report(e.target);
  }, true);
  window.__beamHook = {
    scan: function () {
      document.querySelectorAll('video,audio').forEach(function (m) { if (!m.paused) report(m); });
    },
    pauseAll: function () {
      document.querySelectorAll('video,audio').forEach(function (m) { try { m.pause(); } catch (e) {} });
    }
  };
  window.__beamHook.scan();
})();
"""

/** The phone's position in the most relevant media element, for resuming on the PC. */
private const val TIME_JS = """
(function () {
  var best = 0, t = 0;
  document.querySelectorAll('video,audio').forEach(function (m) {
    var d = isFinite(m.duration) ? m.duration : 0;
    if (d > best) { best = d; t = m.currentTime; }
  });
  return t;
})();
"""
