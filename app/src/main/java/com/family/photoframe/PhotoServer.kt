package com.family.photoframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny web server that lives inside the app.
 *
 *   /            the slideshow page (shown on the tablet)
 *   /send        the upload page (opened on a phone)
 *   /api/...     small JSON helpers
 *   /photos/...  the saved pictures
 *
 * No outside libraries. One request = one short connection.
 */
class PhotoServer(private val context: Context, private val port: Int = 8080) {

    private val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
    private val bgDir = File(context.filesDir, "bg").apply { mkdirs() }
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val orderFile = File(context.filesDir, "order.json")
    private val settingsFile = File(context.filesDir, "settings.json")
    private val pool = Executors.newFixedThreadPool(6)
    private val counter = AtomicInteger(0)
    @Volatile private var server: ServerSocket? = null

    companion object {
        @Volatile private var instance: PhotoServer? = null

        /**
         * One server for the whole app process. The window (Activity) can be closed and re-opened by
         * Android many times; the server keeps running underneath, so it is never started twice.
         */
        @Synchronized
        fun ensureRunning(ctx: Context): PhotoServer {
            val s = instance ?: PhotoServer(ctx.applicationContext).also { instance = it }
            s.startInBackground()
            return s
        }
    }

    @Volatile private var starting = false

    /**
     * Start listening. If an older copy of the app is still shutting down and holds the port,
     * wait a little and try again, instead of crashing the whole app.
     */
    fun startInBackground() {
        if (server != null || starting) return
        starting = true
        Thread {
            try {
                var tries = 0
                while (server == null && tries < 40) {
                    try {
                        start()
                    } catch (e: java.net.BindException) {
                        tries++
                        try { Thread.sleep(500) } catch (i: InterruptedException) { return@Thread }
                    }
                }
            } finally {
                starting = false
            }
        }.apply { isDaemon = true; name = "photo-server-start" }.start()
    }

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(port))
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    if (s.isClosed) break
                }
            }
        }.apply { isDaemon = true; name = "photo-server" }.start()
    }

    fun stop() {
        server?.close()
        server = null
        pool.shutdownNow()
    }

    // ---------- request handling ----------

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val length: Long,
        val headers: Map<String, String>
    )

    /** True when the request comes from the tablet's own screen (not a phone). */
    private val fromTablet = ThreadLocal<Boolean>()

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = BufferedOutputStream(sock.getOutputStream())
            val req = readRequest(input)
            if (req == null) {
                sock.close()
                return
            }
            fromTablet.set(sock.inetAddress?.isLoopbackAddress == true)
            route(req, input, output)
            output.flush()
        } catch (e: Exception) {
            // A phone leaving mid-upload is normal. Ignore.
        } finally {
            try { sock.close() } catch (e: Exception) { }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val buf = ByteArrayOutputStream()
        var matched = 0 // how many bytes of "\r\n\r\n" we have seen in a row
        val end = byteArrayOf(13, 10, 13, 10)
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) break
        }
        if (matched != 4) return null
        val lines = buf.toString("ISO-8859-1").split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        var length = 0L
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) {
                val key = line.substring(0, i).trim().lowercase()
                val value = line.substring(i + 1).trim()
                headers[key] = value
                if (key == "content-length") length = value.toLongOrNull() ?: 0L
            }
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = HashMap<String, String>()
        if (q >= 0) {
            for (pair in target.substring(q + 1).split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv[0].isNotEmpty()) {
                    query[URLDecoder.decode(kv[0], "UTF-8")] =
                        URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
                }
            }
        }
        return Request(parts[0].uppercase(), path, query, length, headers)
    }

    private fun route(req: Request, input: InputStream, out: OutputStream) {
        when {
            req.method == "GET" && req.path == "/" && fromTablet.get() != true -> {
                // a phone that opens the plain address gets the dashboard, not the frame's own screen
                val h = "HTTP/1.1 302 Found\r\nLocation: /dashboard\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(h.toByteArray(Charsets.ISO_8859_1))
            }
            req.method == "GET" && req.path == "/" -> asset("web/frame.html", out)
            req.method == "GET" && req.path == "/dashboard" -> asset("web/dashboard.html", out)
            req.method == "GET" && req.path == "/send" -> asset("web/send.html", out)
            req.method == "GET" && req.path == "/debug" -> asset("web/debug.html", out)
            req.method == "GET" && req.path == "/music" -> asset("web/music.html", out)
            req.method == "GET" && req.path == "/api/music/state" -> json(out, 200, MusicHub.stateJson())
            req.method == "GET" && req.path == "/api/music/search" -> {
                try {
                    val arr = org.json.JSONArray()
                    val q = req.query["q"] ?: ""
                    var found = YtSearch.search(q)
                    var tries = 0
                    while (found.isEmpty() && tries < 2) { Thread.sleep(700); found = YtSearch.search(q); tries++ }   // YouTube sometimes answers empty once
                    for (t in found) arr.put(t.toJson())
                    json(out, 200, org.json.JSONObject().put("results", arr).toString())
                } catch (e: Exception) {
                    json(out, 502, org.json.JSONObject().put("error", "Could not reach YouTube. Check the photo frame's Wi-Fi.").toString())
                }
            }
            req.method == "POST" && req.path == "/api/music/play" -> {
                val body = readBody(req, input, 20_000)
                if (body == null) json(out, 400, """{"ok":false}""") else {
                    val o = org.json.JSONObject(body)
                    val t = Track(o.optString("id"), o.optString("title"), o.optString("channel"), o.optString("thumb"), o.optString("length"))
                    if (t.id.isEmpty() || !Regex("[A-Za-z0-9_-]{6,20}").matches(t.id)) json(out, 400, """{"ok":false}""")
                    else { if (o.optString("mode") == "queue") MusicHub.add(t) else MusicHub.play(t); json(out, 200, MusicHub.stateJson()) }
                }
            }
            req.method == "POST" && req.path == "/api/music/volume" -> {
                MusicHub.setVolume(req.query["v"]?.toIntOrNull() ?: 70)
                json(out, 200, MusicHub.stateJson())
            }
            req.method == "GET" && req.path == "/api/music/history" -> json(out, 200, MusicHub.historyJson())
            req.method == "POST" && req.path == "/api/music/history-clear" -> { MusicHub.clearHistory(); json(out, 200, MusicHub.historyJson()) }
            req.method == "POST" && req.path == "/api/music/boost" -> {
                MusicHub.setBoost(req.query["on"] == "1")
                json(out, 200, MusicHub.stateJson())
            }
            req.method == "POST" && req.path == "/api/music/cmd" -> {
                when (req.query["c"]) {
                    "toggle" -> MusicHub.toggle()
                    "next" -> MusicHub.next()
                    "prev" -> MusicHub.prev()
                    "stop" -> MusicHub.stop()
                    "remove" -> MusicHub.removeAt(req.query["i"]?.toIntOrNull() ?: -1)
                }
                json(out, 200, MusicHub.stateJson())
            }
            req.method == "GET" && req.path.startsWith("/api/icon/") -> icon(req.path.removePrefix("/api/icon/"), out)
            req.path.startsWith("/api/debug/") -> debugApi(req, input, out)
            req.method == "GET" && req.path == "/api/info" -> {
                val ip = localIp() ?: ""
                json(out, 200, """{"ip":"$ip","port":$port,"count":${listPhotos().size}}""")
            }
            req.method == "GET" && req.path == "/api/photos" -> {
                val names = listPhotos().joinToString(",") { "\"$it\"" }
                json(out, 200, "[$names]")
            }
            req.method == "GET" && req.path.startsWith("/photos/") -> {
                val f = safeFile(req.path.removePrefix("/photos/"))
                if (f == null || !f.exists()) text(out, 404, "Not found")
                else file(out, f, "image/jpeg", "public, max-age=86400")
            }
            req.method == "GET" && req.path.startsWith("/bg/") -> {
                val name = req.path.removePrefix("/bg/")
                val src = safeFile(name)
                val dest = safeFile(name, bgDir)
                if (src == null || dest == null || !src.exists()) text(out, 404, "Not found")
                else {
                    if (!dest.exists()) makeBlurredBackground(src, dest)
                    if (dest.exists()) file(out, dest, "image/jpeg", "public, max-age=86400")
                    else text(out, 404, "Not found")
                }
            }
            req.method == "GET" && req.path.startsWith("/thumb/") -> {
                val name = req.path.removePrefix("/thumb/")
                val src = safeFile(name)
                val dest = safeFile(name, thumbDir)
                if (src == null || dest == null || !src.exists()) text(out, 404, "Not found")
                else {
                    if (!dest.exists()) makeThumbnail(src, dest)
                    if (dest.exists()) file(out, dest, "image/jpeg", "public, max-age=86400")
                    else text(out, 404, "Not found")
                }
            }
            req.method == "POST" && req.path == "/api/order" -> saveOrder(req, input, out)
            req.method == "POST" && req.path == "/api/delete-many" -> deleteMany(req, input, out)
            req.method == "GET" && req.path.startsWith("/static/") -> {
                val name = req.path.removePrefix("/static/")
                if (!Regex("[A-Za-z0-9_.-]+").matches(name) || name.contains("..")) text(out, 404, "Not found")
                else try { asset("web/$name", out) } catch (e: java.io.IOException) { text(out, 404, "Not found") }
            }
            req.method == "GET" && req.path == "/api/settings" -> {
                json(out, 200, if (settingsFile.exists()) settingsFile.readText() else "{}")
            }
            req.method == "POST" && req.path == "/api/settings" -> saveSettings(req, input, out)
            req.method == "POST" && req.path == "/api/upload" -> upload(req, input, out)
            req.method == "POST" && req.path == "/api/delete" -> {
                if (deletePhoto(req.query["name"] ?: "")) json(out, 200, """{"ok":true}""")
                else json(out, 404, """{"ok":false}""")
            }
            else -> text(out, 404, "Not found")
        }
    }

    // ---------- upload ----------

    private fun upload(req: Request, input: InputStream, out: OutputStream) {
        val maxBytes = 40L * 1024 * 1024
        if (req.length <= 0 || req.length > maxBytes) {
            json(out, 413, """{"ok":false,"error":"That file is too big."}""")
            return
        }
        val tmp = File(photoDir, "upload-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                val chunk = ByteArray(64 * 1024)
                var left = req.length
                while (left > 0) {
                    val n = input.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException()
                    fos.write(chunk, 0, n)
                    left -= n
                }
            }
            val name = "${System.currentTimeMillis()}-${counter.incrementAndGet()}.jpg"
            val saved = File(photoDir, name)
            val ok = shrinkAndSave(tmp, saved)
            if (ok) {
                makeBlurredBackground(saved, File(bgDir, name))
                makeThumbnail(saved, File(thumbDir, name))
            }
            if (ok) json(out, 200, """{"ok":true,"name":"$name"}""")
            else json(out, 415, """{"ok":false,"error":"That file is not a photo I can show."}""")
        } finally {
            tmp.delete()
        }
    }

    /**
     * Phone photos are huge (12+ megapixels). This old tablet has little memory,
     * so we shrink each photo to about screen size, fix its rotation, and save as JPEG.
     */
    private fun shrinkAndSave(src: File, dest: File): Boolean {
        val maxSide = 1920
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp: Bitmap = BitmapFactory.decodeFile(src.path, opts) ?: return false

        val degrees = try {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) { 0 }
        if (degrees != 0) {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return true
    }

    /**
     * The soft, zoomed-in background. We make a tiny, blurred, slightly darker copy of the photo.
     * The web page stretches it to full screen, which looks smooth. This is much easier on an
     * old tablet than asking the screen to blur a big picture 60 times a second.
     */
    private fun makeBlurredBackground(src: File, dest: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            if (bounds.outWidth <= 0) return
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) sample *= 2
            val big = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val k = 64f / maxOf(big.width, big.height)
            val w = maxOf(2, (big.width * k).toInt())
            val h = maxOf(2, (big.height * k).toInt())
            val small = Bitmap.createScaledBitmap(big, w, h, true)
            if (small !== big) big.recycle()

            var px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(2) {
                px = boxBlur(px, w, h, 3, horizontal = true)
                px = boxBlur(px, w, h, 3, horizontal = false)
            }
            for (i in px.indices) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF) * 3 / 4
                val g = ((c shr 8) and 0xFF) * 3 / 4
                val b = (c and 0xFF) * 3 / 4
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            small.setPixels(px, 0, w, 0, 0, w, h)
            FileOutputStream(dest).use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            small.recycle()
        } catch (e: Exception) {
            dest.delete()
        }
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (d in -radius..radius) {
                val xx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
                val c = src[yy * w + xx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }

    private fun saveSettings(req: Request, input: InputStream, out: OutputStream) {
        if (req.length <= 0 || req.length > 200_000) {
            json(out, 413, """{"ok":false}""")
            return
        }
        val bytes = ByteArray(req.length.toInt())
        var got = 0
        while (got < bytes.size) {
            val n = input.read(bytes, got, bytes.size - got)
            if (n < 0) throw java.io.EOFException()
            got += n
        }
        val body = String(bytes, Charsets.UTF_8)
        try {
            org.json.JSONObject(body) // must be valid JSON
        } catch (e: Exception) {
            json(out, 400, """{"ok":false}""")
            return
        }
        val tmp = File(settingsFile.parentFile, "settings.json.tmp")
        tmp.writeText(body)
        tmp.renameTo(settingsFile)
        json(out, 200, """{"ok":true}""")
    }

    // ---------- app icons (for the app grid) ----------

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun icon(pkg: String, out: OutputStream) {
        if (!Regex("[A-Za-z0-9._]+").matches(pkg)) { text(out, 404, "Not found"); return }
        try {
            val png = iconCache.getOrPut(pkg) {
                val size = 96
                val drawable = context.packageManager.getApplicationIcon(pkg)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(android.graphics.Canvas(bmp))
                val bytes = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                bmp.recycle()
                bytes.toByteArray()
            }
            header(out, 200, "image/png", png.size.toLong(), "public, max-age=3600")
            out.write(png)
        } catch (e: Exception) {
            text(out, 404, "Not found")
        }
    }

    // ---------- debug terminal (open to the home Wi-Fi, no password: see Debug.kt) ----------

    private fun readBody(req: Request, input: InputStream, max: Int): String? {
        if (req.length < 0 || req.length > max) return null
        val bytes = ByteArray(req.length.toInt())
        var got = 0
        while (got < bytes.size) {
            val n = input.read(bytes, got, bytes.size - got)
            if (n < 0) throw java.io.EOFException()
            got += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun debugApi(req: Request, input: InputStream, out: OutputStream) {
        val action = req.path.removePrefix("/api/debug/")
        when {
            action == "open" && req.method == "POST" -> {
                val s = DebugShell.create(context.filesDir)
                json(out, 200, """{"ok":true,"id":"${s.id}"}""")
            }
            action == "input" && req.method == "POST" -> {
                val s = DebugShell.get(req.query["id"])
                val body = readBody(req, input, 20_000)
                if (s == null || body == null || !s.alive()) {
                    json(out, 404, """{"ok":false,"error":"Shell ended"}""")
                } else {
                    try { s.write(body); json(out, 200, """{"ok":true}""") }
                    catch (e: Exception) { json(out, 404, """{"ok":false,"error":"Shell ended"}""") }
                }
            }
            action == "output" && req.method == "GET" -> {
                val s = DebugShell.get(req.query["id"])
                if (s == null) json(out, 404, """{"ok":false,"error":"No such shell"}""")
                else {
                    val (text, next) = s.read(req.query["since"]?.toIntOrNull() ?: 0)
                    json(out, 200, org.json.JSONObject().put("ok", true).put("text", text)
                        .put("next", next).put("alive", s.alive()).toString())
                }
            }
            action == "kill" && req.method == "POST" -> {
                DebugShell.remove(req.query["id"])
                json(out, 200, """{"ok":true}""")
            }
            else -> text(out, 404, "Not found")
        }
    }

    // ---------- helpers ----------

    /** Photos in the order the family arranged them. New photos go to the end. */
    @Synchronized
    private fun listPhotos(): List<String> {
        val existing = (photoDir.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray())
            .map { it.name }
            .toSet()
        val saved = readOrder().filter { it in existing }
        val savedSet = saved.toSet()
        return saved + existing.filter { it !in savedSet }.sorted()
    }

    private fun readOrder(): List<String> = try {
        val a = org.json.JSONArray(orderFile.readText())
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun writeOrder(names: List<String>) {
        val tmp = File(orderFile.parentFile, "order.json.tmp")
        tmp.writeText(org.json.JSONArray(names).toString())
        tmp.renameTo(orderFile)
    }

    private fun parseNames(body: String?): List<String>? = try {
        val a = org.json.JSONArray(body ?: "")
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        null
    }

    private fun saveOrder(req: Request, input: InputStream, out: OutputStream) {
        val names = parseNames(readBody(req, input, 400_000))
        if (names == null) { json(out, 400, """{"ok":false}"""); return }
        val existing = listPhotos().toSet()
        // keep only real photos, once each; anything missing keeps its place at the end
        val clean = names.filter { it in existing }.distinct()
        writeOrder(clean)
        json(out, 200, """{"ok":true}""")
    }

    private fun deletePhoto(name: String): Boolean {
        val f = safeFile(name) ?: return false
        safeFile(name, bgDir)?.delete()
        safeFile(name, thumbDir)?.delete()
        return f.delete()
    }

    private fun deleteMany(req: Request, input: InputStream, out: OutputStream) {
        val names = parseNames(readBody(req, input, 400_000))
        if (names == null) { json(out, 400, """{"ok":false}"""); return }
        var deleted = 0
        for (n in names.distinct()) if (deletePhoto(n)) deleted++
        // forget their crop settings too
        try {
            if (settingsFile.exists()) {
                val s = org.json.JSONObject(settingsFile.readText())
                val crops = s.optJSONObject("crops")
                val fits = s.optJSONObject("fits")
                names.forEach { crops?.remove(it); fits?.remove(it) }
                settingsFile.writeText(s.toString())
            }
        } catch (e: Exception) {
        }
        json(out, 200, """{"ok":true,"deleted":$deleted}""")
    }

    /** Small picture (about 320 px) so the Manage screen loads fast on an old tablet and a phone. */
    private fun makeThumbnail(src: File, dest: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            if (bounds.outWidth <= 0) return
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 320 && bounds.outHeight / (sample * 2) >= 320) sample *= 2
            val big = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val k = 320f / maxOf(big.width, big.height)
            val small = if (k < 1f) Bitmap.createScaledBitmap(big, maxOf(1, (big.width * k).toInt()), maxOf(1, (big.height * k).toInt()), true) else big
            if (small !== big) big.recycle()
            FileOutputStream(dest).use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            small.recycle()
        } catch (e: Exception) {
            dest.delete()
        }
    }

    /** Only allow plain file names inside the photos folder. */
    private fun safeFile(name: String, dir: File = photoDir): File? {
        if (name.isEmpty() || name.contains('/') || name.contains("..")) return null
        return File(dir, name)
    }

    private fun localIp(): String? {
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in all) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
            }
        }
        return null
    }

    private fun header(out: OutputStream, code: Int, type: String, length: Long, cache: String) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; else -> "Error" }
        val h = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\n" +
            "Cache-Control: $cache\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.ISO_8859_1))
    }

    private fun json(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        header(out, code, "application/json; charset=utf-8", bytes.size.toLong(), "no-store")
        out.write(bytes)
    }

    private fun text(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        header(out, code, "text/plain; charset=utf-8", bytes.size.toLong(), "no-store")
        out.write(bytes)
    }

    private fun file(out: OutputStream, f: File, type: String, cache: String) {
        header(out, 200, type, f.length(), cache)
        f.inputStream().use { it.copyTo(out) }
    }

    private val DEFAULT_CREDIT = "Nayam Amarshe"

    private fun asset(path: String, out: OutputStream) {
        var bytes = context.assets.open(path).use { it.readBytes() }
        val type = when (path.substringAfterLast('.')) {
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            else -> "text/html; charset=utf-8"
        }
        // The pages say "Made by <default author>". A build can show a different name (see credit.name in app/build.gradle.kts).
        if (type.startsWith("text/html") && BuildConfig.CREDIT_NAME != DEFAULT_CREDIT) {
            bytes = String(bytes, Charsets.UTF_8).replace(DEFAULT_CREDIT, BuildConfig.CREDIT_NAME).toByteArray(Charsets.UTF_8)
        }
        header(out, 200, type, bytes.size.toLong(), "no-store")
        out.write(bytes)
    }
}
