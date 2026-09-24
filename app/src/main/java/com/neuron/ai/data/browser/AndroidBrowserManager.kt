package com.neuron.ai.data.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.neuron.ai.core.integration.BrowserManager
import com.neuron.ai.core.integration.BrowserPage
import com.neuron.ai.core.integration.BrowserResult
import com.neuron.ai.core.integration.BrowserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Real WebView-backed browser engine (Milestone 3). Runs on the Android main
 * thread (WebView's requirement); all JS bridges are injected by the engine
 * itself. Web content is UNTRUSTED: the bridge only extracts text/links —
 * page content can never call tools or change permissions.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewBrowserSession(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    override val sessionId: String = "browser-" + UUID.randomUUID().toString().take(8)
) : BrowserSession {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var webView: WebView? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private val _page = MutableStateFlow<BrowserPage?>(null)
    override val page: StateFlow<BrowserPage?> = _page.asStateFlow()

    /** JS bridge the page's OWN scripts can reach — extraction only. */
    private class ExtractionBridge(val onText: (String) -> Unit) {
        @JavascriptInterface
        fun onPage(text: String) = onText(text)
    }

    private fun ensureWebView() {
        if (webView != null) return
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        handler.post {
            try {
                val wv = WebView(context)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.blockNetworkImage = true
                wv.settings.loadsImagesAutomatically = false
                wv.settings.mediaPlaybackRequiresUserGesture = true
                wv.addJavascriptInterface(ExtractionBridge { text ->
                    _extractedText = text
                }, "_neuronExtract")
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        // Block third-party requests lightly: same-host only,
                        // everything else (trackers/ads) is denied.
                        val reqHost = request?.url?.host
                        val pageHost = view?.url?.host
                            ?: webView?.url?.host
                        return if (reqHost != null && pageHost != null && reqHost != pageHost) {
                            WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        } else null
                    }
                }
                webView = wv
                deferred.complete(true)
            } catch (t: Throwable) {
                deferred.complete(false)
            }
        }
    }

    private var _extractedText: String? = null

    private suspend fun evaluateExtract(maxChars: Int): String? = withContext(dispatchers.main) {
        val wv = webView ?: return@withContext null
        _extractedText = null
        withTimeoutOrNull(8_000) {
            handler.post {
                wv.evaluateJavascript(
                    "window._neuronExtract && " +
                        "window._neuronExtract.onPage(document.body ? " +
                        "document.body.innerText.substring(0, $maxChars) : '');",
                    null
                )
            }
            // Poll briefly — the JS interface delivers asynchronously.
            var waited = 0
            while (_extractedText == null && waited < 8_000) {
                kotlinx.coroutines.delay(100)
                waited += 100
            }
            _extractedText
        }
    }

    private suspend fun currentTitle(): String? = withContext(dispatchers.main) {
        webView?.title
    }

    private suspend fun currentUrl(): String? = withContext(dispatchers.main) {
        webView?.url
    }

    private suspend fun snapshot(maxChars: Int): BrowserPage? {
        val text = evaluateExtract(maxChars) ?: return null
        val links = extractLinks()
        return BrowserPage(
            url = currentUrl() ?: "",
            title = currentTitle(),
            readableText = text,
            truncated = text.length >= maxChars,
            links = links
        )
    }

    private suspend fun extractLinks(): List<String> = withContext(dispatchers.main) {
        val wv = webView ?: return@withContext emptyList()
        var links: List<String> = emptyList()
        val latch = CompletableDeferred<Unit>()
        handler.post {
            wv.evaluateJavascript(
                "(function(){var out=[];document.querySelectorAll('a[href]').forEach(function(a){" +
                    "out.push(a.href)});return out.slice(0,80).join('\\n')})()",
                { value -> links = value.removeSurrounding("\"").split("\\n").filter { it.startsWith("http") }; latch.complete(Unit) }
            )
        }
        withTimeoutOrNull(4_000) { latch.await() }
        links.distinct().take(80)
    }

    // ---- BrowserSession ------------------------------------------------------------------

    override suspend fun navigate(url: String): BrowserResult {
        if (!Regex("(?i)^https?://").containsMatchIn(url)) {
            return BrowserResult.Failure("Only http(s) URLs can be opened.")
        }
        ensureWebView()
        val created = ready?.await() ?: false
        if (!created) return BrowserResult.Failure("Browser engine unavailable on this device.")

        val navDeferred = CompletableDeferred<Boolean>()
        _pendingNav = navDeferred
        withContext(dispatchers.main) {
            handler.post {
                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, viewUrl: String?) {
                        navDeferred.complete(true)
                    }
                    override fun onReceivedError(
                        view: WebView?, req: WebResourceRequest?, err: android.webkit.WebResourceError?
                    ) {
                        if (req?.isForMainFrame == true) navDeferred.complete(false)
                    }
                }
                webView?.loadUrl(url)
            }
        }
        val ok = withTimeoutOrNull(20_000) { navDeferred.await() } ?: false
        if (!ok) return BrowserResult.Failure("Page failed to load or timed out.")
        val snap = snapshot(8_000)
        snap?.let { _page.value = it }
        return BrowserResult.Success(snap)
    }

    private var _pendingNav: CompletableDeferred<Boolean>? = null

    override suspend fun readPage(maxChars: Int): BrowserResult {
        val snap = snapshot(maxChars) ?: return BrowserResult.Failure("No page is loaded.")
        _page.value = snap
        return BrowserResult.Success(snap)
    }

    override suspend fun findOnPage(query: String): BrowserResult {
        val snap = snapshot(16_000) ?: return BrowserResult.Failure("No page is loaded.")
        val q = query.lowercase()
        val hits = snap.readableText.lines().filter { q in it.lowercase() }.take(10)
        return if (hits.isEmpty()) {
            BrowserResult.Failure("\"$query\" not found on the current page.")
        } else {
            BrowserResult.Success(
                snap,
                "Found ${hits.size} matching line(s):\n" + hits.joinToString("\n") { it.take(200) }
            )
        }
    }

    // ---- Side-effect actions (permission-gated by the caller/tool) -------------------------

    override suspend fun clickElement(description: String): BrowserResult =
        withContext(dispatchers.main) {
            val wv = webView ?: return@withContext BrowserResult.Failure("No page is loaded.")
            // Click via DOM text search — NEVER an eval of page-provided JS.
            val escaped = description.replace("\\", "\\\\").replace("'", "\\'")
            var result: String? = null
            val latch = CompletableDeferred<Unit>()
            handler.post {
                wv.evaluateJavascript(
                    "(function(){var t='$escaped';var els=[].slice.call(document.querySelectorAll('a,button,[role=button],input[type=submit]'));" +
                        "var el=els.find(function(e){return (e.innerText||e.value||'').toLowerCase().includes(t.toLowerCase())});" +
                        "if(!el)return 'NOT_FOUND';el.click();return 'CLICKED';})()",
                    { value -> result = value?.removeSurrounding("\""); latch.complete(Unit) }
                )
            }
            withTimeoutOrNull(6_000) { latch.await() }
            when (result) {
                "CLICKED" -> BrowserResult.Success(snapshot(2_000), "Clicked element matching \"$description\".")
                "NOT_FOUND" -> BrowserResult.Failure("No clickable element matching \"$description\".")
                null -> BrowserResult.Failure("Click timed out.")
                else -> BrowserResult.Failure("Click failed.")
            }
        }

    override suspend fun typeText(description: String, text: String): BrowserResult =
        withContext(dispatchers.main) {
            val wv = webView ?: return@withContext BrowserResult.Failure("No page is loaded.")
            val escapedDesc = description.replace("\\", "\\\\").replace("'", "\\'")
            val escapedText = text.replace("\\", "\\\\").replace("'", "\\'")
            var result: String? = null
            val latch = CompletableDeferred<Unit>()
            handler.post {
                wv.evaluateJavascript(
                    "(function(){var t='$escapedDesc';var els=[].slice.call(document.querySelectorAll('input,textarea,[contenteditable]'));" +
                        "var el=els.find(function(e){return (e.name||e.id||e.placeholder||'').toLowerCase().includes(t.toLowerCase())});" +
                        "if(!el)return 'NOT_FOUND';el.focus();el.value='$escapedText';" +
                        "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "return 'TYPED';})()",
                    { value -> result = value?.removeSurrounding("\""); latch.complete(Unit) }
                )
            }
            withTimeoutOrNull(6_000) { latch.await() }
            when (result) {
                "TYPED" -> BrowserResult.Success(null, "Typed into element matching \"$description\".")
                "NOT_FOUND" -> BrowserResult.Failure("No input matching \"$description\".")
                null -> BrowserResult.Failure("Type timed out.")
                else -> BrowserResult.Failure("Type failed.")
            }
        }

    override suspend fun selectOption(description: String, value: String): BrowserResult =
        withContext(dispatchers.main) {
            val wv = webView ?: return@withContext BrowserResult.Failure("No page is loaded.")
            val escapedDesc = description.replace("\\", "\\\\").replace("'", "\\'")
            val escapedValue = value.replace("\\", "\\\\").replace("'", "\\'")
            var result: String? = null
            val latch = CompletableDeferred<Unit>()
            handler.post {
                wv.evaluateJavascript(
                    "(function(){var t='$escapedDesc';var sel=[].slice.call(document.querySelectorAll('select'))" +
                        ".find(function(s){return (s.name||s.id||'').toLowerCase().includes(t.toLowerCase())});" +
                        "if(!sel)return 'NOT_FOUND';var opt=[].slice.call(sel.options)" +
                        ".find(function(o){return o.value==='$escapedValue'||o.text==='$escapedValue'});" +
                        "if(!opt)return 'NO_OPTION';sel.value=opt.value;" +
                        "sel.dispatchEvent(new Event('change',{bubbles:true}));return 'SELECTED';})()",
                    { value -> result = value?.removeSurrounding("\""); latch.complete(Unit) }
                )
            }
            withTimeoutOrNull(6_000) { latch.await() }
            when (result) {
                "SELECTED" -> BrowserResult.Success(null, "Selected \"$value\".")
                "NO_OPTION" -> BrowserResult.Failure("Option \"$value\" not found.")
                "NOT_FOUND" -> BrowserResult.Failure("No select element matching \"$description\".")
                null -> BrowserResult.Failure("Select timed out.")
                else -> BrowserResult.Failure("Select failed.")
            }
        }

    override suspend fun scroll(direction: String): BrowserResult =
        withContext(dispatchers.main) {
            val wv = webView ?: return@withContext BrowserResult.Failure("No page is loaded.")
            val dir = if (direction.equals("up", true)) "-window.innerHeight*0.8" else "window.innerHeight*0.8"
            handler.post {
                wv.evaluateJavascript("window.scrollBy(0, $dir)", null)
            }
            kotlinx.coroutines.delay(400)
            val snap = snapshot(4_000)
            BrowserResult.Success(snap, "Scrolled $direction.")
        }

    override fun close() {
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

/**
 * Owns one WebViewBrowserSession per conversation context key. Sessions are
 * closed when the conversation closes — no WebView leaks.
 */
class AndroidBrowserManager(
    private val context: Context,
    private val dispatchers: DispatcherProvider
) : BrowserManager {

    private val sessions = ConcurrentHashMap<String, WebViewBrowserSession>()

    override suspend fun sessionFor(contextKey: String): WebViewBrowserSession =
        sessions.getOrPut(contextKey) {
            WebViewBrowserSession(context, dispatchers)
        }

    override fun closeSession(contextKey: String) {
        sessions.remove(contextKey)?.close()
    }

    override fun closeAll() {
        sessions.keys.toList().forEach { closeSession(it) }
    }
}
