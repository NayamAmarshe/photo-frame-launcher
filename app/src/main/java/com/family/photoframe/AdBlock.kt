package com.family.photoframe

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps ads out of the hidden music player.
 *  1. Ad web addresses are never loaded.
 *  2. The player page gets a small script added first. It removes the ad lists from YouTube's replies
 *     before the player can read them, so the player has no ads to play.
 */
object AdBlock {
    private val blockedHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "2mdn.net", "moatads.com", "imasdk.googleapis.com"
    )
    private val blockedPaths = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/youtubei/v1/log_event", "/api/stats/atr", "/get_midroll_", "/generate_204"
    )

    // runs inside the player page before anything else
    private const val HOOK = """<script>(function(){
var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams','adBreakParams'];
function clean(o,d){
  if(!o||typeof o!=='object'||d>3)return;
  for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o){ try{delete o[KEYS[i]];}catch(e){o[KEYS[i]]=undefined;} try{console.log('adblock: removed '+KEYS[i]);}catch(e){} } }
  if(o.playerResponse)clean(o.playerResponse,d+1);
  if(o.response)clean(o.response,d+1);
  if(Array.isArray(o)){for(var j=0;j<o.length&&j<6;j++)clean(o[j],d+1);}
}
var _p=JSON.parse;
JSON.parse=function(){var r=_p.apply(this,arguments);try{clean(r,0);}catch(e){}return r;};
if(window.Response&&Response.prototype.json){var _j=Response.prototype.json;
  Response.prototype.json=function(){return _j.apply(this,arguments).then(function(r){try{clean(r,0);}catch(e){}return r;});};}
try{var d=Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype,'response');
  if(d&&d.get){Object.defineProperty(XMLHttpRequest.prototype,'response',{configurable:true,get:function(){var v=d.get.call(this);try{if(v&&typeof v==='object')clean(v,0);}catch(e){}return v;}});}}catch(e){}
})();</script>"""

    private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url
        val host = url.host ?: return null
        val path = url.path ?: ""
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return empty()
        if ((host.endsWith("youtube.com") || host.endsWith("google.com")) && blockedPaths.any { path.contains(it) }) return empty()
        if (!req.isForMainFrame && req.method == "GET" && host.endsWith("youtube.com") && path.startsWith("/embed/")) {
            return try { patchEmbed(req) } catch (e: Exception) { null }
        }
        return null
    }

    /** Loads YouTube's player page ourselves and adds the hook at the top. */
    private fun patchEmbed(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url.toString()
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.instanceFollowRedirects = true
        for ((k, v) in req.requestHeaders) {
            if (k.equals("Accept-Encoding", true) || k.equals("Range", true)) continue
            c.setRequestProperty(k, v)
        }
        CookieManager.getInstance().getCookie(u)?.let { c.setRequestProperty("Cookie", it) }
        val code = c.responseCode
        val cm = CookieManager.getInstance()
        c.headerFields["Set-Cookie"]?.forEach { cm.setCookie(u, it) }
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return null
        val i = body.indexOf("<head>", ignoreCase = true)
        val out = if (i >= 0) body.substring(0, i + 6) + HOOK + body.substring(i + 6) else HOOK + body
        val headers = HashMap<String, String>()
        for ((k, v) in c.headerFields) {
            if (k == null || v.isEmpty()) continue
            val kl = k.lowercase()
            if (kl in setOf("content-security-policy", "content-security-policy-report-only", "content-length",
                    "content-encoding", "transfer-encoding", "set-cookie", "connection")) continue
            headers[k] = v.last()
        }
        return WebResourceResponse("text/html", "utf-8", code, c.responseMessage?.ifEmpty { "OK" } ?: "OK", headers,
            ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
    }
}
