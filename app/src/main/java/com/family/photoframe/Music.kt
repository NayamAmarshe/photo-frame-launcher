package com.family.photoframe

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Track(val id: String, val title: String, val channel: String, val thumb: String, val length: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("channel", channel)
        .put("thumb", thumb).put("length", length)
}

/**
 * Plays YouTube songs on the tablet. A tiny hidden web view runs YouTube's own player, so nothing is
 * hacked or scraped for the video itself. Phones (and the bar on the tablet) just tell this object
 * what to do. The queue lives here.
 */
object MusicHub {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = ArrayList<Track>()
    private var index = -1
    private var playing = false
    private var loading = false
    private var pos = 0.0
    private var dur = 0.0
    private var message = ""
    private var version = 0
    private var player: WebView? = null
    private var playerReady = false
    private var pendingLoad: String? = null
    private var playerHtml = ""
    private var pageLoaded = false
    private val unloadLater = Runnable { unloadPlayer() }
    private var audio: android.media.AudioManager? = null
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var boosted = false
    private val history = ArrayList<Track>()
    private var histFile: java.io.File? = null
    private var recorded = false

    /** Called once by the main window. Creates the hidden player. */
    @SuppressLint("SetJavaScriptEnabled")
    fun makePlayer(ctx: Context): WebView {
        player?.let { return it }
        audio = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        histFile = java.io.File(ctx.applicationContext.filesDir, "music_history.json")
        loadHistory()
        val w = WebView(ctx.applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(0)
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d("music", m.message())
                return true
            }
        }
        w.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                AdBlock.intercept(request)
        }
        w.addJavascriptInterface(Bridge(), "MusicBridge")
        playerHtml = ctx.assets.open("web/player.html").use { String(it.readBytes(), Charsets.UTF_8) }
        // The player page is only loaded when a song is asked for (see ensureLoaded), so an idle frame runs none of it.
        player = w
        return w
    }

    class Bridge {
        @JavascriptInterface fun ready() {
            synchronized(lock) { playerReady = true }
            pendingLoad?.let { p -> pendingLoad = null; val a = p.split("|"); runJs("loadId('${a[0]}', ${a.getOrElse(1) { "0" }})") }
        }

        @JavascriptInterface fun state(code: Int) {
            synchronized(lock) {
                when (code) {
                    1 -> {
                        playing = true; loading = false; message = ""
                        if (!recorded) { recorded = true; queue.getOrNull(index)?.let { t -> main.post { remember(t) } } }
                    }
                    2 -> { playing = false; loading = false }
                    3 -> loading = true
                    0 -> { playing = false }
                }
                version++
            }
            if (code == 0) main.post { next() }
        }

        @JavascriptInterface fun time(p: Double, d: Double) {
            synchronized(lock) { pos = p; dur = d }
        }

        @JavascriptInterface fun error(code: Int) {
            synchronized(lock) {
                playing = false; loading = false
                message = if (code == 100) "That video was removed."
                else "YouTube does not allow this one to play here. Skipped."
                version++
            }
            main.post { next() }
        }
    }

    // ---------- recently played (kept on the tablet, newest first) ----------
    private fun loadHistory() {
        try {
            val f = histFile ?: return
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            synchronized(lock) {
                history.clear()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    history.add(Track(o.optString("id"), o.optString("title"), o.optString("channel"), o.optString("thumb"), o.optString("length")))
                }
            }
        } catch (e: Exception) { }
    }

    private fun saveHistory() {
        try {
            val a = JSONArray()
            synchronized(lock) { for (t in history) a.put(t.toJson()) }
            histFile?.writeText(a.toString())
        } catch (e: Exception) { }
    }

    private fun remember(t: Track) {
        synchronized(lock) {
            history.removeAll { it.id == t.id }
            history.add(0, t)
            while (history.size > 60) history.removeAt(history.size - 1)
        }
        saveHistory()
    }

    fun historyJson(): String = synchronized(lock) {
        val a = JSONArray()
        for (t in history) a.put(t.toJson())
        JSONObject().put("results", a).toString()
    }

    fun clearHistory() {
        synchronized(lock) { history.clear() }
        saveHistory()
    }

    private fun lengthSeconds(s: String): Int {
        val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        return parts.fold(0) { acc, n -> acc * 60 + n }
    }

    /** Loads the YouTube player page the first time it is needed. */
    private fun ensureLoaded() {
        main.removeCallbacks(unloadLater)
        val p = player ?: return
        if (pageLoaded) return
        pageLoaded = true
        synchronized(lock) { playerReady = false }
        // WebViews may only be touched from the main thread, and phones call this from the server's threads.
        main.post {
            // A normal https address, so YouTube accepts it as a website that embeds its player.
            p.loadDataWithBaseURL("https://photoframe.example.com/", playerHtml, "text/html", "utf-8", null)
        }
    }

    /** Throws the player page away when nothing has played for a while, so it uses no memory or power. */
    private fun unloadPlayer() {
        val busy = synchronized(lock) { playing || loading }
        if (busy) return
        val p = player ?: return
        p.loadUrl("about:blank")
        pageLoaded = false
        synchronized(lock) { playerReady = false }
    }

    private fun runJs(js: String) {
        val p = player ?: return
        main.post { p.evaluateJavascript(js, null) }
    }

    private fun startCurrent() {
        val t = synchronized(lock) { queue.getOrNull(index) } ?: return
        synchronized(lock) { pos = 0.0; dur = 0.0; loading = true; recorded = false; version++ }
        ensureLoaded()
        val ready = synchronized(lock) { playerReady }
        val secs = lengthSeconds(t.length)
        if (ready) runJs("loadId('${t.id}', $secs)") else pendingLoad = t.id + "|" + secs
    }

    // ---------- what phones can ask for ----------

    fun play(t: Track) {
        synchronized(lock) {
            val at = (index + 1).coerceAtMost(queue.size)
            queue.add(at, t)
            index = at
            message = ""
        }
        startCurrent()
    }

    fun add(t: Track) {
        val start: Boolean
        synchronized(lock) {
            queue.add(t)
            start = index < 0 || (!playing && !loading && index >= queue.size - 1 && queue.size == 1)
            if (start) index = queue.size - 1
            version++
        }
        if (start) startCurrent()
    }

    fun next() {
        val go = synchronized(lock) {
            if (index + 1 < queue.size) { index++; true }
            else { playing = false; loading = false; pos = 0.0; version++; false }
        }
        if (go) startCurrent() else { runJs("stopIt()"); main.postDelayed(unloadLater, 60_000) }
    }

    fun prev() {
        val restart = synchronized(lock) { pos > 5 || index <= 0 }
        if (restart) { runJs("seekTo(0)"); return }
        synchronized(lock) { index-- }
        startCurrent()
    }

    fun toggle() {
        val p = synchronized(lock) { playing }
        runJs(if (p) "pauseIt()" else "resumeIt()")
    }

    fun stop() {
        synchronized(lock) { queue.clear(); index = -1; playing = false; loading = false; pos = 0.0; dur = 0.0; message = ""; version++ }
        runJs("stopIt()")
        main.postDelayed(unloadLater, 60_000)
    }

    fun removeAt(i: Int) {
        var restart = false
        synchronized(lock) {
            if (i < 0 || i >= queue.size) return
            queue.removeAt(i)
            if (i < index) index--
            else if (i == index) { index = if (index < queue.size) index else queue.size - 1; restart = index >= 0 }
            version++
        }
        if (restart) startCurrent() else if (synchronized(lock) { queue.isEmpty() }) runJs("stopIt()")
    }

    /** The tablet's own music volume, 0 to 100. */
    fun volume(): Int {
        val a = audio ?: return 100
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return Math.round(a.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100f / max)
    }

    fun setVolume(pct: Int) {
        val a = audio ?: return
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(pct.coerceIn(0, 100) * max / 100f), 0)
    }

    /** Extra loudness for quiet recordings (about +9 dB), using Android's built-in loudness effect. */
    fun setBoost(on: Boolean) {
        try {
            if (enhancer == null) enhancer = android.media.audiofx.LoudnessEnhancer(0)
            enhancer?.setTargetGain(if (on) 900 else 0)
            enhancer?.enabled = on
            boosted = on
        } catch (e: Exception) {
            android.util.Log.d("music", "boost failed: $e")
            boosted = false
        }
        synchronized(lock) { version++ }
    }

    fun stateJson(): String = synchronized(lock) {
        val o = JSONObject()
        o.put("playing", playing).put("loading", loading).put("pos", pos).put("dur", dur)
            .put("message", message).put("index", index).put("v", version).put("volume", volume()).put("boost", boosted)
        o.put("track", queue.getOrNull(index)?.toJson() ?: JSONObject.NULL)
        val q = JSONArray()
        for (t in queue) q.put(t.toJson())
        o.put("queue", q)
        o.toString()
    }
}

/** Looks songs up on YouTube's public search page (no account or key needed). */
object YtSearch {
    fun search(q: String): List<Track> {
        val url = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8") + "&sp=EgIQAQ%253D%253D")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        val body = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        val key = "ytInitialData = "
        val start = body.indexOf(key)
        if (start < 0) return emptyList()
        val from = start + key.length
        val end = body.indexOf(";</script>", from)
        if (end < 0) return emptyList()
        val root = JSONObject(body.substring(from, end))
        val out = ArrayList<Track>()
        walk(root, out)
        return out.take(20)
    }

    private fun walk(n: Any?, out: MutableList<Track>) {
        when (n) {
            is JSONObject -> {
                val v = n.optJSONObject("videoRenderer")
                if (v != null) parse(v)?.let { out.add(it) }
                val keys = n.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "videoRenderer") continue
                    walk(n.opt(k), out)
                }
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), out)
        }
    }

    private fun parse(v: JSONObject): Track? {
        val id = v.optString("videoId")
        if (id.isEmpty()) return null
        val title = v.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: return null
        val chan = v.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
        val len = v.optJSONObject("lengthText")?.optString("simpleText") ?: return null   // live streams have none
        return Track(id, title, chan, "https://i.ytimg.com/vi/$id/mqdefault.jpg", len)
    }
}
