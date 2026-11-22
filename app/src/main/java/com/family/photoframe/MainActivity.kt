package com.family.photoframe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/** Full-screen window that shows our own web page. The page does all the fancy looks. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var wakeLock: PowerManager.WakeLock? = null
    private val bt by lazy { BtController(applicationContext) }

    // The radar on the front of this tablet writes 1 here while it sees movement, and 0 when it does not.
    private val motionFile = java.io.File("/sys/devices/radar_input.28/motion_sensor")
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastMotion = System.currentTimeMillis()
    @Volatile private var motionReadable = false
    private val motionPoll = object : Runnable {
        override fun run() {
            try {
                if (motionFile.readText().trim() == "1") lastMotion = System.currentTimeMillis()
                motionReadable = true
            } catch (e: Exception) {
                motionReadable = false
            }
            ticker.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        PhotoServer.ensureRunning(applicationContext)
        NavService.ensureEnabled(applicationContext)
        bt.start()

        // Page inspection over adb, for debug builds only.
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(0xFF000000.toInt())
            webViewClient = object : WebViewClient() {
                // If the page is asked for a moment before the server is ready, just try again.
                override fun onReceivedError(
                    view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) view.postDelayed({ view.reload() }, 800)
                }
            }
            // Send the page's console messages to Android's log, so we can debug it.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d("frame", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            // Only OUR page inside this window can use these buttons. Phones on the Wi-Fi cannot.
            addJavascriptInterface(Bridge(), "FrameBridge")
            loadUrl("http://127.0.0.1:8080/")
        }
        // The hidden music player. Only its sound is used.
        val player = MusicHub.makePlayer(this)
        (player.parent as? android.view.ViewGroup)?.removeView(player)
        val root = android.widget.FrameLayout(this)
        // The player sits on screen (so YouTube treats it as visible) but is covered by the photo page.
        root.addView(player, android.widget.FrameLayout.LayoutParams(96, 54))
        root.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        goFullScreen()

        // Keep wifi awake too, so uploads still work at 3am.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photoframe:server").apply { acquire() }
    }

    /** The frame is on screen again, so the floating "Back to Photo Frame" button is not needed. */
    override fun onResume() {
        super.onResume()
        lastMotion = System.currentTimeMillis()
        ticker.removeCallbacks(motionPoll)
        ticker.post(motionPoll)
        stopService(Intent(this, BackOverlayService::class.java))
    }

    /** Pressing Home while we are the launcher comes here. Nothing to do: we are already the frame. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        web.evaluateJavascript("window.__home && window.__home()", null)
    }

    /** The frame is the home screen, so Back must never close it. It closes pop-ups instead. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__back && window.__back()", null)
    }

    override fun onPause() {
        ticker.removeCallbacks(motionPoll)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    @Suppress("DEPRECATION")
    private fun goFullScreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    override fun onDestroy() {
        DebugShell.closeAll()
        bt.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        // The photo server is NOT stopped here: it lives as long as the app process does.
        (web.parent as? android.view.ViewGroup)?.let { it.removeView(MusicHub.makePlayer(this)) }
        web.destroy()
        super.onDestroy()
    }

    private fun showBackButton() {
        if (NavService.instance != null && NavService.isOn(this)) return   // our own bar already has Home
        if (Settings.canDrawOverlays(this)) startService(Intent(this, BackOverlayService::class.java))
    }

    /** The few things the page may ask Android to do. */
    inner class Bridge {
        @JavascriptInterface
        fun getApps(): String {
            val pm = packageManager
            // Normal apps, plus "TV apps" (like SmartTube) that only list themselves for TV remotes.
            val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val tv = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val apps = (pm.queryIntentActivities(normal, 0) + pm.queryIntentActivities(tv, 0))
                .filter { it.activityInfo.packageName != packageName }
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            val arr = JSONArray()
            for ((label, pkg) in apps) arr.put(JSONObject().put("label", label).put("pkg", pkg))
            return arr.toString()
        }

        @JavascriptInterface
        fun launchApp(pkg: String) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
                ?: packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runOnUiThread {
                showBackButton()
                startActivity(launch)
            }
        }

        @JavascriptInterface
        fun openAndroidSettings() = openSystem(Settings.ACTION_SETTINGS)

        @JavascriptInterface
        fun openWifiSettings() = openSystem(Settings.ACTION_WIFI_SETTINGS)

        /** Is the floating back button allowed on this tablet? */
        @JavascriptInterface
        fun canOverlay(): Boolean = Settings.canDrawOverlays(this@MainActivity)

        /** Opens the one Android screen where the user switches "Draw over other apps" on for us. */
        @JavascriptInterface
        fun requestOverlay() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
            }
        }

        private fun openSystem(action: String) {
            runOnUiThread {
                try {
                    showBackButton()
                    startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                }
            }
        }

        /**
         * Screen brightness, 5 to 100 percent. This changes only OUR window, so it needs no special
         * permission. The frame is always on screen, so that is the one that matters.
         */
        /** Seconds since the radar last saw movement. -1 means this tablet has no readable motion sensor. */
        @JavascriptInterface
        fun idleSeconds(): Int = if (!motionReadable) -1 else ((System.currentTimeMillis() - lastMotion) / 1000).toInt()

        @JavascriptInterface
        fun setBrightness(percent: Int) {
            val level = percent.coerceIn(1, 100) / 100f
            runOnUiThread {
                val lp = window.attributes
                lp.screenBrightness = level
                window.attributes = lp
            }
        }

        // ---------- Bluetooth speakers ----------

        @JavascriptInterface fun btStatus(): String = bt.status()
        @JavascriptInterface fun btScan() = bt.scan()
        @JavascriptInterface fun btSetEnabled(on: Boolean) = bt.setEnabled(on)
        @JavascriptInterface fun btConnect(address: String): String = bt.connect(address)
        @JavascriptInterface fun btDisconnect(address: String): String = bt.disconnect(address)
        @JavascriptInterface fun btForget(address: String): String = bt.forget(address)

        /** The first music app we know about that is installed, or "" if there is none. */
        /** Credits pressed four times: turns the Back/Home bar on or off. Returns "on", "off" or "setup". */
        @JavascriptInterface
        fun toggleNav(): String {
            if (NavService.instance == null) {
                runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return "setup"
            }
            return if (NavService.toggle(applicationContext)) "on" else "off"
        }

        @JavascriptInterface
        fun musicApp(): String {
            val known = listOf(
                "net.programmierecke.radiodroid2", // internet radio: light, works on this tablet
                "org.smarttube.stable", "org.schabi.newpipe", "me.knighthat.kreate", "org.polymorphicshade.tubular"
            )
            return known.firstOrNull {
                packageManager.getLaunchIntentForPackage(it) != null ||
                    packageManager.getLeanbackLaunchIntentForPackage(it) != null
            } ?: ""
        }

        // ---------- Wi-Fi (so nobody needs Android's own Wi-Fi screen) ----------

        private val wifi: WifiManager
            get() = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        private fun cleanSsid(s: String?): String {
            val t = (s ?: "").trim('"')
            return if (t == "<unknown ssid>") "" else t
        }

        @JavascriptInterface
        fun wifiStatus(): String {
            val wm = wifi
            val info = wm.connectionInfo
            val connected = info != null && info.supplicantState == SupplicantState.COMPLETED && info.networkId >= 0
            val ip = info?.ipAddress ?: 0
            return JSONObject()
                .put("enabled", wm.isWifiEnabled)
                .put("connected", connected)
                .put("ssid", if (connected) cleanSsid(info?.ssid) else "")
                .put("ip", if (connected && ip != 0) "${ip and 255}.${(ip shr 8) and 255}.${(ip shr 16) and 255}.${(ip shr 24) and 255}" else "")
                .toString()
        }

        @JavascriptInterface
        fun wifiScan() {
            try { wifi.startScan() } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun wifiResults(): String {
            val wm = wifi
            val hasPerm = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val locationOn = try {
                Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
            } catch (e: Exception) { true }
            val arr = JSONArray()
            if (hasPerm && wm.isWifiEnabled) {
                try {
                    val saved = (wm.configuredNetworks ?: emptyList()).map { cleanSsid(it.SSID) }.toSet()
                    val current = cleanSsid(wm.connectionInfo?.ssid)
                    val best = HashMap<String, android.net.wifi.ScanResult>()
                    for (r in wm.scanResults ?: emptyList()) {
                        val name = cleanSsid(r.SSID)
                        if (name.isEmpty()) continue
                        val old = best[name]
                        if (old == null || r.level > old.level) best[name] = r
                    }
                    val rows = best.values.map { r ->
                        val name = cleanSsid(r.SSID)
                        val caps = r.capabilities ?: ""
                        JSONObject()
                            .put("ssid", name)
                            .put("level", WifiManager.calculateSignalLevel(r.level, 5))
                            .put("secured", caps.contains("WPA") || caps.contains("WEP") || caps.contains("PSK") || caps.contains("EAP"))
                            .put("eap", caps.contains("EAP"))
                            .put("saved", saved.contains(name))
                            .put("connected", name == current)
                    }.sortedWith(compareByDescending<JSONObject> { it.getBoolean("connected") }
                        .thenByDescending { it.getInt("level") })
                    rows.forEach { arr.put(it) }
                } catch (e: SecurityException) {
                }
            }
            return JSONObject().put("perm", hasPerm).put("locationOn", locationOn).put("list", arr).toString()
        }

        @JavascriptInterface
        fun requestLocation() {
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7) }
        }

        @JavascriptInterface
        fun openLocationSettings() = openSystem(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

        @JavascriptInterface
        fun wifiSetEnabled(on: Boolean) {
            wifi.isWifiEnabled = on
        }

        /** Join a network. Leave the password empty for open networks, or ones this tablet already knows. */
        @JavascriptInterface
        fun wifiConnect(ssid: String, password: String): String {
            val wm = wifi
            if (!wm.isWifiEnabled) wm.isWifiEnabled = true
            val quoted = "\"$ssid\""
            val existing = wm.configuredNetworks?.firstOrNull { it.SSID == quoted }
            val caps = try {
                wm.scanResults?.firstOrNull { cleanSsid(it.SSID) == ssid }?.capabilities ?: ""
            } catch (e: SecurityException) { "" }

            var netId = -1
            if (existing != null && password.isEmpty()) {
                netId = existing.networkId
            } else {
                val cfg = WifiConfiguration().apply {
                    SSID = quoted
                    when {
                        password.isEmpty() -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        caps.contains("WEP") && !caps.contains("WPA") -> {
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                            wepKeys[0] = if (password.matches(Regex("[0-9A-Fa-f]{10}|[0-9A-Fa-f]{26}"))) password else "\"$password\""
                            wepTxKeyIndex = 0
                        }
                        else -> preSharedKey = "\"$password\""
                    }
                }
                if (existing != null) {
                    cfg.networkId = existing.networkId
                    netId = wm.updateNetwork(cfg)
                }
                if (netId < 0) netId = wm.addNetwork(cfg)
                if (netId < 0 && existing != null) netId = existing.networkId
            }
            if (netId < 0) return "error:Android would not save this network"
            wm.disconnect()
            val ok = wm.enableNetwork(netId, true)
            wm.reconnect()
            return if (ok) "ok" else "error:Android would not connect"
        }

        @JavascriptInterface
        fun wifiDisconnect(): String {
            val wm = wifi
            val id = wm.connectionInfo?.networkId ?: -1
            if (id >= 0) wm.disableNetwork(id)
            wm.disconnect()
            return "ok"
        }

        @JavascriptInterface
        fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
