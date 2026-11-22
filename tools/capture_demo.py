#!/usr/bin/env python3
"""
Takes the README screenshots and short demo videos from the demo server, in landscape AND portrait.

    pip install playwright pillow numpy
    python3 tools/dev_server.py --port 8080 --photos <a folder of nice pictures> &
    python3 tools/capture_demo.py --url http://localhost:8080 --out docs

Playwright needs a Chromium-based browser. Either run `playwright install chromium`,
or point at one you already have:  --browser "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"
Needs ffmpeg for the videos.

Which picture is used where is set with --pick, for example:
    --pick wide=mt-rainier --pick square=krishna
A name only has to appear inside the file name. If it isn't found, the first pictures are used instead.
"""
import argparse
import json
import os
import shutil
import subprocess
import time
import urllib.request

from playwright.sync_api import sync_playwright

LANDSCAPE = {"width": 1280, "height": 800}
PORTRAIT = {"width": 800, "height": 1280}
PHONE = {"width": 390, "height": 844}
CLOCK = "2026-11-22T09:41:00"          # a calm, steady time for every picture

# which picture goes where (a part of the file name is enough)
PICKS = {"wide": "mt-rainier", "square": "krishna", "warm": "aerial-colors", "tall": "night-moon",
         "alt": "copper-ridges", "alt2": "desert-dunes"}


def api(base, path, data=None):
    body = None if data is None else (data if isinstance(data, bytes) else json.dumps(data).encode())
    req = urllib.request.Request(base + path, data=body, method="POST" if data is not None else "GET")
    return urllib.request.urlopen(req, timeout=10).read()


def photos(base):
    return json.loads(api(base, "/api/photos"))


def pick(base, key):
    names = photos(base)
    want = PICKS.get(key, key)
    for n in names:
        if want in n:
            return n
    order = list(PICKS).index(key) if key in PICKS else 0
    return names[order % len(names)]


def show_first(base, name, **settings):
    names = photos(base)
    api(base, "/api/order", [name] + [n for n in names if n != name])
    s = {"bg": "blur", "clock": True, "clockStyle": "card", "interval": 60, "play": "order", "nightMode": False}
    s.update(settings)
    api(base, "/api/settings", s)


def reset_music(base):
    api(base, "/api/music/cmd?c=stop", b"")
    api(base, "/api/music/history-clear", b"")


def launch(p, args):
    if args.browser:
        return p.chromium.launch(executable_path=args.browser, headless=True)
    return p.chromium.launch(headless=True)


def new_page(browser, size, **kw):
    pg = browser.new_page(viewport=size, **kw)
    try:
        pg.clock.set_fixed_time(CLOCK)
    except Exception:
        pass
    return pg


def tablet_shots(p, args, shots, size, prefix):
    base = args.url
    browser = launch(p, args)
    pg = new_page(browser, size)
    shot = lambda name: pg.screenshot(path=os.path.join(shots, prefix + name + ".png"))
    wait = pg.wait_for_timeout
    jc = lambda sel: pg.evaluate("(s) => document.querySelector(s).click()", sel)   # no sideways scrolling
    portrait = size["height"] > size["width"]
    # where to tap to open settings: on the picture, clear of anything that slides in
    tap = (size["width"] // 2, 90) if portrait else (size["width"] // 4, size["height"] // 3)
    reset_music(base)
    # the photo that fills this screen shape, and one that doesn't (so the blurred background shows)
    fills, odd = (pick(base, "tall"), pick(base, "wide")) if portrait else (pick(base, "wide"), pick(base, "square"))

    show_first(base, fills, clockStyle="lock")
    pg.goto(base + "/")
    wait(3200)
    shot("01-slideshow")

    show_first(base, odd, clockStyle="card")        # blurred, zoomed copy fills the empty space
    pg.goto(base + "/")
    wait(3200)
    shot("02-portrait-photo" if not portrait else "02-blurred-background")

    show_first(base, pick(base, "warm"), clockStyle="lock" if portrait else "stack")
    pg.goto(base + "/")
    wait(2800)
    pg.mouse.click(*tap)
    wait(1000)
    shot("03-settings")
    pg.evaluate("document.getElementById('panel').scrollTop = 800")
    wait(500)
    shot("04-settings-more")
    pg.evaluate("document.getElementById('panel').scrollTop = 0")
    jc("#closePanel")
    wait(500)

    show_first(base, pick(base, "wide"), clockStyle="card")
    pg.goto(base + "/")
    wait(2800)
    pg.mouse.click(*tap)
    wait(800)
    jc("#btnCrop")
    wait(700)
    pg.evaluate("var z=document.getElementById('zoom'); z.value=1.6; z.dispatchEvent(new Event('input'));")
    wait(600)
    shot("05-crop")
    jc("#cropCancel")
    wait(500)

    pg.mouse.click(*tap)
    wait(700)
    jc("#btnManage")
    wait(2200)
    shot("06-manage-photos")
    jc("#manageBack")
    wait(500)

    pg.mouse.click(*tap)
    wait(500)
    jc("#addbox")
    wait(800)
    shot("07-qr")
    jc("#qrBack")
    wait(400)

    for tool, back, name in (("#toolWifi", "#wifiBack", "08-wifi"), ("#toolBt", "#btBack", "09-bluetooth"), ("#toolApps", "#appsBack", "10-apps")):
        jc(tool)
        wait(1800)
        shot(name)
        jc(back)
        wait(400)

    reset_music(base)
    jc("#toolMusic")
    wait(1200)
    frame = pg.frame_locator("#muFrame")
    frame.locator("#q").fill("chill")
    frame.locator("#f button").click()
    wait(1500)
    shot("11-music-search")
    frame.locator("[data-play='1']").click()
    wait(800)
    frame.locator("[data-play='3']").click()
    wait(900)
    jc("#muBack")
    wait(800)
    shot("12-music-bar")
    jc("#miniVolBtn")
    wait(500)
    shot("13-music-volume")
    reset_music(base)
    browser.close()


def clock_styles(p, args, shots, size, prefix):
    """The three clock looks side by side (the text turns dark on light pictures by itself)."""
    base = args.url
    browser = launch(p, args)
    pg = new_page(browser, size)
    files = []
    plan = [("card", pick(base, "alt2" if size["height"] > size["width"] else "alt")), ("lock", pick(base, "wide")), ("stack", pick(base, "warm"))]
    for i, (style, photo) in enumerate(plan):
        show_first(base, photo, clockStyle=style)
        pg.goto(base + "/")
        pg.wait_for_timeout(3000)
        f = os.path.join(shots, "_clock%d.png" % i)
        pg.screenshot(path=f)
        files.append(f)
    browser.close()
    out = os.path.join(shots, prefix + "14-clock-styles.png")
    w = 640 if size["width"] > size["height"] else 420
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", files[0], "-i", files[1], "-i", files[2], "-filter_complex",
                    "[0]scale=%d:-1[a];[1]scale=%d:-1[b];[2]scale=%d:-1[c];[a][b][c]hstack=3" % (w, w, w), "-frames:v", "1", out], check=True)
    for f in files:
        os.remove(f)


def phone_shots(p, args, shots):
    base = args.url
    browser = launch(p, args)
    ctx = browser.new_context(viewport=PHONE, device_scale_factor=2, is_mobile=True, has_touch=True)
    pg = ctx.new_page()
    shot = lambda name: pg.screenshot(path=os.path.join(shots, name + ".png"))
    wait = pg.wait_for_timeout
    reset_music(base)
    pg.goto(base + "/dashboard")
    wait(800)
    shot("phone-01-dashboard")
    pg.goto(base + "/send")
    wait(800)
    if args.sample_dir:                     # choose a few pictures, like a real person would
        files = sorted(f for f in os.listdir(args.sample_dir) if f.lower().endswith((".jpg", ".jpeg", ".png")))
        chosen = [os.path.join(args.sample_dir, files[i % len(files)]) for i in (0, 2, 3, 4, 6, 7)]
        pg.set_input_files("#pick", chosen)
        wait(2500)
    shot("phone-02-send")
    pg.click("#tabBtnManage")
    wait(1800)
    shot("phone-03-manage")
    pg.goto(base + "/music")
    wait(800)
    pg.fill("#q", "chill")
    pg.press("#q", "Enter")
    wait(1200)
    pg.click("[data-play='2']")
    wait(600)
    pg.click("[data-add='5']")
    wait(900)
    pg.evaluate("window.scrollTo(0, 0)")
    shot("phone-04-music")
    browser.close()


def video(p, args, out, size, name):
    base = args.url
    tmp = os.path.join(out, "_video")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    browser = launch(p, args)
    pg = new_page(browser, size)
    frames = []     # (time, jpeg bytes): we film the page by grabbing screenshots as fast as we can
    jc = lambda sel: pg.evaluate("(s) => document.querySelector(s).click()", sel)
    portrait = size["height"] > size["width"]
    tap = (size["width"] // 2, 90) if portrait else (size["width"] // 4, size["height"] // 3)

    def wait(ms):
        end = time.time() + ms / 1000.0
        while True:
            frames.append((time.time(), pg.screenshot(type="jpeg", quality=88)))
            if time.time() >= end:
                break

    reset_music(base)
    show_first(base, pick(base, "tall" if portrait else "wide"), clockStyle="lock")
    pg.goto(base + "/")
    wait(2500)
    for _ in range(4):                      # photos slide and fade into each other
        pg.keyboard.press("ArrowRight")
        wait(2300)
    pg.mouse.click(*tap)
    wait(1500)
    pg.evaluate("document.getElementById('panel').scrollTo({top: 800, behavior: 'smooth'})")
    wait(1700)
    pg.evaluate("document.getElementById('panel').scrollTo({top: 0, behavior: 'smooth'})")
    wait(1000)
    jc("#btnManage")
    wait(2300)
    jc("#manageBack")
    wait(700)
    jc("#btnCrop")
    wait(900)
    for z in (1.2, 1.5, 1.8, 2.0):
        pg.evaluate("var z=document.getElementById('zoom'); z.value=%s; z.dispatchEvent(new Event('input'));" % z)
        wait(350)
    jc("#cropSave")
    wait(1500)
    pg.mouse.click(*tap)
    wait(700)
    jc("#toolApps")
    wait(2200)
    jc("#appsBack")
    wait(600)
    pg.mouse.click(*tap)
    wait(600)
    jc("#toolMusic")
    wait(1500)
    fr = pg.frame_locator("#muFrame")
    fr.locator("#q").type("chill", delay=90)
    fr.locator("#f button").click()
    wait(1600)
    fr.locator("[data-play='0']").click()
    wait(1200)
    jc("#muBack")
    wait(1200)
    jc("#miniVolBtn")
    wait(1800)
    pg.mouse.click(*tap)
    for _ in range(2):
        pg.keyboard.press("ArrowRight")
        wait(2300)
    browser.close()
    lines = []
    for i, (t, data) in enumerate(frames):
        path = os.path.join(tmp, "f%05d.jpg" % i)
        open(path, "wb").write(data)
        dur = (frames[i + 1][0] - t) if i + 1 < len(frames) else 0.5
        lines.append("file '%s'\nduration %.3f" % (os.path.abspath(path), dur))
    lines.append("file '%s'" % os.path.abspath(os.path.join(tmp, "f%05d.jpg" % (len(frames) - 1))))
    lst = os.path.join(tmp, "frames.txt")
    open(lst, "w").write("\n".join(lines))
    mp4 = os.path.join(out, name + ".mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-vf", "fps=30", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-crf", "24", "-an", mp4], check=True)
    if not portrait:
        gif = os.path.join(out, name + ".gif")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-vf",
                        "fps=10,scale=800:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=4",
                        gif], check=True)
    shutil.rmtree(tmp, ignore_errors=True)
    print("video:", mp4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--out", default="docs")
    ap.add_argument("--browser", default="", help="path to a Chromium-based browser (optional)")
    ap.add_argument("--pick", action="append", default=[], help="key=part-of-file-name, e.g. wide=mt-rainier")
    ap.add_argument("--skip-video", action="store_true")
    ap.add_argument("--sample-dir", default="", help="a folder of pictures to 'upload' in the phone screenshot")
    ap.add_argument("--only", default="tablet,phone,video", help="which parts to run: tablet, phone, video")
    args = ap.parse_args()
    for kv in args.pick:
        k, _, v = kv.partition("=")
        PICKS[k] = v
    shots = os.path.join(args.out, "screenshots")
    os.makedirs(shots, exist_ok=True)
    parts = args.only.split(",")
    with sync_playwright() as p:
        if "tablet" in parts:
            tablet_shots(p, args, shots, LANDSCAPE, "")
            clock_styles(p, args, shots, LANDSCAPE, "")
            tablet_shots(p, args, shots, PORTRAIT, "portrait-")
            clock_styles(p, args, shots, PORTRAIT, "portrait-")
        if "phone" in parts:
            phone_shots(p, args, shots)
        if "video" in parts and not args.skip_video:
            video(p, args, args.out, LANDSCAPE, "demo")
            video(p, args, args.out, PORTRAIT, "demo-portrait")
    print("done:", shots)


if __name__ == "__main__":
    main()
