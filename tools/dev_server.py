#!/usr/bin/env python3
"""
Runs the Photo Frame web pages on your computer, with pretend data. No tablet needed.

It is handy for working on the pages, and for taking screenshots and videos.
Only Python 3 is required. Install Pillow (pip install pillow) for blurred backgrounds and thumbnails.

    python3 tools/dev_server.py                      # uses the pictures in demo/wallpapers
    python3 tools/dev_server.py --photos ~/Pictures  # or your own folder
    then open http://localhost:8080/            (the frame's own screen)
              http://localhost:8080/dashboard   (what a phone sees)

Photos and settings changed in the pages are kept in memory (and in .dev-data/), never in your picture folder.
"""
import argparse
import io
import json
import mimetypes
import os
import random
import re
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from PIL import Image, ImageFilter
except ImportError:  # works without it, just less pretty
    Image = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "app", "src", "main", "assets", "web")
DATA = os.path.join(ROOT, ".dev-data")

ARGS = None
LOCK = threading.Lock()
ORDER = []          # photo names, in play order
SETTINGS = {}
CACHE = {}


# ---------------------------------------------------------------- pretend phone/tablet features
BRIDGE_JS = """<script>
// Stand-in for the Android side of the app (the real one lives in MainActivity.kt)
window.FrameBridge = (function () {
  var apps = ['Phone','Messages','Mail','Contacts','Calendar','Clock','Calculator','Notes','Tasks','Files','Gallery','Camera','Videos','Music','Podcasts','Recorder','Maps','Compass','Weather','Health','Browser','Settings'];
  var log = function (m) { try { console.log('[demo bridge] ' + m); } catch (e) {} };
  var wifi = true, bt = true, connectedNet = 'Home Wi-Fi';
  var devices = [
    { address: 'AA:01', name: 'Living Room Speaker', bonded: true, bonding: false, connected: true, speaker: true },
    { address: 'AA:02', name: 'Kitchen Soundbar', bonded: true, bonding: false, connected: false, speaker: true },
    { address: 'AA:03', name: 'Headphones', bonded: false, bonding: false, connected: false, speaker: true }
  ];
  return {
    getApps: function () { return JSON.stringify(apps.map(function (a) { return { label: a, pkg: 'demo.' + a.toLowerCase() }; })); },
    launchApp: function (p) { log('launch ' + p); },
    openAndroidSettings: function () { log('open Android settings'); },
    openWifiSettings: function () {}, openLocationSettings: function () {}, requestLocation: function () {},
    canOverlay: function () { return true; }, requestOverlay: function () {},
    setBrightness: function (p) { log('brightness ' + p); },
    toggleNav: function () { return 'on'; },
    idleSeconds: function () { return typeof window.__demoIdle === 'number' ? window.__demoIdle : 2; },
    musicApp: function () { return ''; },
    version: function () { return 'Photo Frame \\u00b7 demo'; },
    wifiStatus: function () { return JSON.stringify({ enabled: wifi, connected: wifi && !!connectedNet, ssid: wifi ? connectedNet : '', ip: wifi ? '192.168.1.20' : '' }); },
    wifiScan: function () {},
    wifiResults: function () {
      return JSON.stringify({ perm: true, locationOn: true, list: wifi ? [
        { ssid: 'Home Wi-Fi', level: 4, secured: true, eap: false, saved: true, connected: connectedNet === 'Home Wi-Fi' },
        { ssid: 'Neighbour 5G', level: 3, secured: true, eap: false, saved: false, connected: false },
        { ssid: 'Cafe Guest', level: 2, secured: false, eap: false, saved: false, connected: false },
        { ssid: 'Office-Net', level: 1, secured: true, eap: true, saved: false, connected: false }] : [] });
    },
    wifiSetEnabled: function (on) { wifi = !!on; },
    wifiConnect: function (ssid) { connectedNet = ssid; return 'ok'; },
    wifiDisconnect: function () { connectedNet = ''; return 'ok'; },
    btStatus: function () { return JSON.stringify({ supported: true, enabled: bt, scanning: false, message: '', devices: bt ? devices : [] }); },
    btScan: function () {}, btSetEnabled: function (on) { bt = !!on; },
    btConnect: function (a) { devices.forEach(function (d) { if (d.address === a) { d.connected = true; d.bonded = true; } }); return 'ok'; },
    btDisconnect: function (a) { devices.forEach(function (d) { if (d.address === a) d.connected = false; }); return 'ok'; },
    btForget: function (a) { devices.forEach(function (d) { if (d.address === a) { d.bonded = false; d.connected = false; } }); return 'ok'; }
  };
})();
</script>"""

TRACKS = [
    ("Slow Morning", "Pine & Pixel", "3:42", "ocean-glow"),
    ("Golden Hour Drive", "Neon Harbor", "4:05", "sunset-dunes"),
    ("Northern Lights", "Quiet Static", "5:18", "aurora"),
    ("Late Night Coding", "Lo-Fi Lantern", "2:56", "synthwave"),
    ("Mist Over the Valley", "Aster Field", "6:11", "misty-forest"),
    ("Moonlit Walk", "Pine & Pixel", "3:33", "moonlit-lake"),
    ("Blue Hour", "Soft Circuit", "4:27", "blue-hour-peaks"),
    ("Paper Planes", "Lo-Fi Lantern", "3:08", "low-poly"),
    ("Sunday Slow Jam", "Neon Harbor", "4:44", "mesh-gradient"),
    ("Calm Waters", "Aster Field", "7:02", "ocean-glow"),
]


class Player:
    """Pretends to play music so the music pages have something to show."""
    def __init__(self):
        self.queue, self.index, self.playing = [], -1, False
        self.pos, self.started = 0.0, 0.0
        self.volume, self.boost, self.history, self.v = 60, False, [], 0

    def _tick(self):
        if self.playing:
            now = time.time()
            self.pos += now - self.started
            self.started = now
            t = self.queue[self.index] if 0 <= self.index < len(self.queue) else None
            if t and self.pos >= secs(t["length"]):
                self.next()

    def start(self):
        self.pos, self.started, self.playing = 0.0, time.time(), True
        t = self.queue[self.index]
        self.history = [t] + [h for h in self.history if h["id"] != t["id"]][:59]
        self.v += 1

    def play(self, t, mode):
        if mode == "queue":
            self.queue.append(t)
            if self.index < 0:
                self.index = 0
                self.start()
        else:
            self.queue.insert(self.index + 1, t)
            self.index += 1
            self.start()

    def next(self):
        if self.index + 1 < len(self.queue):
            self.index += 1
            self.start()
        else:
            self.playing = False

    def cmd(self, c, i=None):
        self._tick()
        if c == "toggle":
            if self.playing:
                self.pos += time.time() - self.started
            self.playing = not self.playing
            self.started = time.time()
        elif c == "next":
            self.next()
        elif c == "prev":
            if self.pos > 5 or self.index <= 0:
                self.pos, self.started = 0.0, time.time()
            else:
                self.index -= 1
                self.start()
        elif c == "stop":
            self.queue, self.index, self.playing, self.pos = [], -1, False, 0.0
        elif c == "remove" and i is not None and 0 <= i < len(self.queue):
            self.queue.pop(i)
            if i < self.index:
                self.index -= 1
            elif i == self.index:
                self.index = min(self.index, len(self.queue) - 1)
                if self.index >= 0:
                    self.start()
        self.v += 1

    def state(self):
        self._tick()
        t = self.queue[self.index] if 0 <= self.index < len(self.queue) else None
        return {"playing": self.playing, "loading": False, "pos": round(self.pos + (time.time() - self.started if self.playing else 0), 1),
                "dur": secs(t["length"]) if t else 0, "message": "", "index": self.index, "v": self.v,
                "volume": self.volume, "boost": self.boost, "track": t, "queue": self.queue}


def secs(s):
    n = 0
    for p in s.split(":"):
        n = n * 60 + int(p)
    return n


def track_json(i):
    title, chan, length, _ = TRACKS[i % len(TRACKS)]
    files = ordered() or photo_files()
    cover = "/thumb/" + urllib.parse.quote(files[i % len(files)]) if files else ""   # the cover is one of the loaded photos
    return {"id": "demo%02d" % (i % len(TRACKS)), "title": title, "channel": chan, "length": length, "thumb": cover}


PLAYER = Player()


# ---------------------------------------------------------------- photos
def photo_files():
    d = ARGS.photos
    if not os.path.isdir(d):
        return []
    return sorted(f for f in os.listdir(d) if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")))


def ordered():
    files = photo_files()
    names = [n for n in ORDER if n in files] + [n for n in files if n not in ORDER]
    return [n for n in names if n not in DELETED]


DELETED = set()


def resized(name, width, blur=0):
    key = (name, width, blur)
    if key in CACHE:
        return CACHE[key]
    path = os.path.join(ARGS.photos, name)
    if Image is None:
        data = open(path, "rb").read()
    else:
        im = Image.open(path).convert("RGB")
        im.thumbnail((width, width * 4))
        if blur:
            im = im.filter(ImageFilter.GaussianBlur(blur))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=82)
        data = buf.getvalue()
    CACHE[key] = data
    return data


ICONS = {
    # name: (background colour, drawing on a 96x96 canvas)
    "calendar": ("#4f8cff", '<rect x="22" y="24" width="52" height="50" rx="9" fill="#fff"/><rect x="22" y="24" width="52" height="15" rx="9" fill="#ff5a5a"/>'
                 '<rect x="22" y="32" width="52" height="7" fill="#ff5a5a"/><text x="48" y="64" font-family="Helvetica,Arial,sans-serif" font-size="25" font-weight="700" text-anchor="middle" fill="#2b3a55">23</text>'
                 '<rect x="33" y="17" width="5" height="12" rx="2.5" fill="#fff"/><rect x="58" y="17" width="5" height="12" rx="2.5" fill="#fff"/>'),
    "camera": ("#5b6270", '<rect x="18" y="32" width="60" height="40" rx="10" fill="#fff"/><rect x="34" y="24" width="20" height="10" rx="4" fill="#fff"/>'
               '<circle cx="48" cy="52" r="14" fill="#5b6270"/><circle cx="48" cy="52" r="8.5" fill="#9fb4d6"/><circle cx="67" cy="40" r="3" fill="#5b6270"/>'),
    "clock": ("#ff8a3d", '<circle cx="48" cy="48" r="28" fill="#fff"/><path d="M48 30v19l13 8" fill="none" stroke="#ff8a3d" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/><circle cx="48" cy="48" r="3.4" fill="#ff8a3d"/>'),
    "contacts": ("#35b6a4", '<circle cx="48" cy="38" r="13" fill="#fff"/><path d="M22 76c0-15 11-24 26-24s26 9 26 24z" fill="#fff"/>'),
    "files": ("#8e7bff", '<path d="M18 30a6 6 0 0 1 6-6h16l8 9h24a6 6 0 0 1 6 6v28a6 6 0 0 1-6 6H24a6 6 0 0 1-6-6z" fill="#fff"/><path d="M18 42h60" stroke="#8e7bff" stroke-width="4"/>'),
    "gallery": ("#ff6b8b", '<rect x="19" y="22" width="58" height="52" rx="9" fill="#fff"/><circle cx="36" cy="38" r="6" fill="#ffc94d"/>'
                '<path d="M19 66l17-18 13 12 12-14 16 18v6a9 9 0 0 1-9 9H28a9 9 0 0 1-9-9z" fill="#ff6b8b"/>'),
    "maps": ("#43b868", '<path d="M48 18c-11 0-19 8-19 19 0 14 19 38 19 38s19-24 19-38c0-11-8-19-19-19z" fill="#fff"/><circle cx="48" cy="37" r="7.5" fill="#43b868"/>'),
    "music": ("#ff5a73", '<path d="M40 64V32l30-7v32" fill="none" stroke="#fff" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/><circle cx="33" cy="65" r="9" fill="#fff"/><circle cx="63" cy="58" r="9" fill="#fff"/>'),
    "notes": ("#f5b800", '<rect x="23" y="18" width="50" height="60" rx="8" fill="#fff"/><path d="M33 36h30M33 48h30M33 60h18" stroke="#f5b800" stroke-width="5" stroke-linecap="round"/>'),
    "mail": ("#3d8bff", '<rect x="18" y="28" width="60" height="42" rx="8" fill="#fff"/><path d="M22 34l26 20 26-20" fill="none" stroke="#3d8bff" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>'),
    "weather": ("#4aa8f0", '<circle cx="38" cy="38" r="13" fill="#ffd34d"/><path d="M32 70a14 14 0 0 1 2-28 18 18 0 0 1 34 6 11 11 0 0 1-2 22z" fill="#fff"/>'),
    "browser": ("#6677ff", '<circle cx="48" cy="48" r="28" fill="none" stroke="#fff" stroke-width="5"/><ellipse cx="48" cy="48" rx="11" ry="28" fill="none" stroke="#fff" stroke-width="4"/><path d="M20 48h56M25 34h46M25 62h46" stroke="#fff" stroke-width="4" fill="none"/>'),
}


ICONS.update({
    "phone": ("#34c759", '<path d="M30 24c-3 1-6 4-5 9 3 16 18 33 34 38 5 1 8-2 9-5l1-4c0-2-1-3-3-4l-9-4c-2-1-4 0-5 1l-3 3c-8-4-13-9-17-17l3-3c1-1 2-3 1-5l-4-9c-1-2-2-3-4-2z" fill="#fff"/>'),
    "messages": ("#5e8bff", '<path d="M24 26h48a8 8 0 0 1 8 8v22a8 8 0 0 1-8 8H48L32 78V64h-8a8 8 0 0 1-8-8V34a8 8 0 0 1 8-8z" fill="#fff"/><circle cx="36" cy="45" r="4" fill="#5e8bff"/><circle cx="48" cy="45" r="4" fill="#5e8bff"/><circle cx="60" cy="45" r="4" fill="#5e8bff"/>'),
    "calculator": ("#ff9f0a", '<rect x="22" y="18" width="52" height="60" rx="9" fill="#fff"/><rect x="29" y="25" width="38" height="14" rx="4" fill="#ff9f0a"/>'
                   '<g fill="#6b7280"><circle cx="35" cy="50" r="4.5"/><circle cx="48" cy="50" r="4.5"/><circle cx="61" cy="50" r="4.5"/><circle cx="35" cy="63" r="4.5"/><circle cx="48" cy="63" r="4.5"/><circle cx="61" cy="63" r="4.5" fill="#ff9f0a"/></g>'),
    "tasks": ("#7c6cf0", '<circle cx="48" cy="48" r="28" fill="#fff"/><path d="M35 49l9 9 18-20" fill="none" stroke="#7c6cf0" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>'),
    "videos": ("#ff453a", '<rect x="18" y="26" width="60" height="44" rx="10" fill="#fff"/><path d="M42 37v22l19-11z" fill="#ff453a"/>'),
    "podcasts": ("#a259ff", '<circle cx="48" cy="42" r="8" fill="#fff"/><path d="M33 52a20 20 0 1 1 30 0" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round"/><path d="M26 56a29 29 0 1 1 44 0" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round" opacity=".6"/><path d="M48 52v24" stroke="#fff" stroke-width="9" stroke-linecap="round"/>'),
    "recorder": ("#59606e", '<rect x="39" y="20" width="18" height="34" rx="9" fill="#ff5a5a"/><path d="M30 46a18 18 0 0 0 36 0M48 64v10M38 74h20" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round"/>'),
    "compass": ("#30b0c7", '<circle cx="48" cy="48" r="28" fill="#fff"/><path d="M60 36L52 52 36 60l8-16z" fill="#30b0c7"/><circle cx="48" cy="48" r="3.5" fill="#fff"/>'),
    "health": ("#ff375f", '<path d="M48 74S22 58 22 40a13 13 0 0 1 26-4 13 13 0 0 1 26 4c0 18-26 34-26 34z" fill="#fff"/>'),
    "settings": ("#8e8e93", '<g fill="#fff"><rect x="43" y="16" width="10" height="64" rx="5"/><rect x="43" y="16" width="10" height="64" rx="5" transform="rotate(45 48 48)"/><rect x="43" y="16" width="10" height="64" rx="5" transform="rotate(90 48 48)"/><rect x="43" y="16" width="10" height="64" rx="5" transform="rotate(135 48 48)"/><circle cx="48" cy="48" r="23"/></g><circle cx="48" cy="48" r="10" fill="#8e8e93"/>'),
})


def icon_svg(pkg):
    name = pkg.split(".")[-1].lower()
    bg, art = ICONS.get(name, ("#6d7cff", '<circle cx="48" cy="48" r="20" fill="#fff"/>'))
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 96 96"><rect width="96" height="96" rx="24" fill="%s"/>%s</svg>' % (bg, art)).encode()


# ---------------------------------------------------------------- the server
class Handler(BaseHTTPRequestHandler):
    server_version = "PhotoFrameDev"

    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def page(self, name, inject=False):
        html = open(os.path.join(WEB, name), encoding="utf-8").read()
        if inject:
            html = html.replace("<head>", "<head>" + BRIDGE_JS, 1)
        self.send(200, html, "text/html; charset=utf-8")

    def body_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw.decode() or "null")
        except Exception:
            return None

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        p, q = u.path, {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        with LOCK:
            self.route_get(p, q)

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        p, q = u.path, {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        with LOCK:
            self.route_post(p, q)

    def route_get(self, p, q):
        if p == "/":
            return self.page("frame.html", inject=True)
        if p in ("/dashboard", "/send", "/music", "/debug"):
            return self.page(p[1:] + ".html")
        if p == "/api/info":
            return self.send(200, json.dumps({"ip": "192.168.1.20", "port": ARGS.port, "count": len(ordered())}))
        if p == "/api/photos":
            return self.send(200, json.dumps(ordered()))
        if p.startswith("/photos/"):
            f = os.path.join(ARGS.photos, os.path.basename(p))
            if os.path.isfile(f):
                return self.send(200, open(f, "rb").read(), mimetypes.guess_type(f)[0] or "image/jpeg")
        if p.startswith("/bg/"):
            n = urllib.parse.unquote(os.path.basename(p))
            if os.path.isfile(os.path.join(ARGS.photos, n)):
                return self.send(200, resized(n, 48, 2), "image/jpeg")
        if p.startswith("/thumb/"):
            n = urllib.parse.unquote(os.path.basename(p))
            if os.path.isfile(os.path.join(ARGS.photos, n)):
                return self.send(200, resized(n, 360), "image/jpeg")
        if p.startswith("/static/demo-art/"):   # cover art for the pretend songs
            n = os.path.basename(p)[:-4] + ".jpg"
            if os.path.isfile(os.path.join(ARGS.photos, n)):
                return self.send(200, resized(n, 360), "image/jpeg")
        if p.startswith("/static/"):
            f = os.path.join(WEB, os.path.basename(p))
            if os.path.isfile(f):
                return self.send(200, open(f, "rb").read(), mimetypes.guess_type(f)[0] or "text/plain")
        if p.startswith("/api/icon/"):
            return self.send(200, icon_svg(p.rsplit("/", 1)[-1]), "image/svg+xml")
        if p == "/api/settings":
            return self.send(200, json.dumps(SETTINGS))
        if p == "/api/music/state":
            return self.send(200, json.dumps(PLAYER.state()))
        if p == "/api/music/history":
            return self.send(200, json.dumps({"results": PLAYER.history}))
        if p == "/api/music/search":
            term = (q.get("q") or "").lower()
            res = [track_json(i) for i in range(len(TRACKS))]
            hit = [t for t in res if term in (t["title"] + " " + t["channel"]).lower()]
            return self.send(200, json.dumps({"results": hit or res}))
        self.send(404, "Not found", "text/plain")

    def route_post(self, p, q):
        if p == "/api/settings":
            body = self.body_json()
            if isinstance(body, dict):
                SETTINGS.clear()
                SETTINGS.update(body)
            return self.send(200, '{"ok":true}')
        if p == "/api/order":
            body = self.body_json()
            if isinstance(body, list):
                ORDER[:] = [str(n) for n in body]
            return self.send(200, '{"ok":true}')
        if p == "/api/delete-many":
            body = self.body_json() or []
            DELETED.update(str(n) for n in body)
            return self.send(200, json.dumps({"ok": True, "deleted": len(body)}))
        if p == "/api/delete":
            DELETED.add(q.get("name", ""))
            return self.send(200, '{"ok":true}')
        if p == "/api/upload":      # pretend it worked; nothing is stored
            n = int(self.headers.get("Content-Length") or 0)
            self.rfile.read(n)
            return self.send(200, json.dumps({"ok": True, "name": "demo-%d.jpg" % int(time.time())}))
        if p == "/api/music/play":
            t = self.body_json() or {}
            PLAYER.play({k: t.get(k, "") for k in ("id", "title", "channel", "thumb", "length")}, t.get("mode", "now"))
            return self.send(200, json.dumps(PLAYER.state()))
        if p == "/api/music/cmd":
            i = q.get("i")
            PLAYER.cmd(q.get("c", ""), int(i) if i and i.lstrip("-").isdigit() else None)
            return self.send(200, json.dumps(PLAYER.state()))
        if p == "/api/music/volume":
            PLAYER.volume = max(0, min(100, int(q.get("v", "60"))))
            return self.send(200, json.dumps(PLAYER.state()))
        if p == "/api/music/boost":
            PLAYER.boost = q.get("on") == "1"
            return self.send(200, json.dumps(PLAYER.state()))
        if p == "/api/music/history-clear":
            PLAYER.history = []
            return self.send(200, '{"results":[]}')
        self.send(404, "Not found", "text/plain")


def main():
    global ARGS
    ap = argparse.ArgumentParser(description="Photo Frame pages with pretend data")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--photos", default=os.path.join(ROOT, "demo", "wallpapers"))
    ap.add_argument("--shuffle", action="store_true", help="start with the photos in random order")
    ARGS = ap.parse_args()
    if not photo_files():
        sys.exit("No pictures found in %s. Run: python3 tools/make_demo_wallpapers.py demo/wallpapers" % ARGS.photos)
    if ARGS.shuffle:
        ORDER[:] = random.sample(photo_files(), len(photo_files()))
    os.makedirs(DATA, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", ARGS.port), Handler)
    print("Photo Frame demo server on http://localhost:%d/   (Ctrl+C to stop)" % ARGS.port)
    print("  frame screen : http://localhost:%d/" % ARGS.port)
    print("  phone pages  : http://localhost:%d/dashboard" % ARGS.port)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
