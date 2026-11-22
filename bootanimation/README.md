# Boot animation (optional)

Replaces the tablet's start-up animation. Nothing here is needed to run the app.

## Option 1: draw a small logo animation

```bash
pip install pillow
python3 bootanimation/make_bootanim.py /tmp/bootanim      # makes the frames in /tmp/bootanim
cd /tmp/bootanim && zip -0 -r -D ../bootanimation.zip desc.txt part0 part1
```

## Option 2: make it from your own video

```bash
bash bootanimation/make_from_video.sh my-video.mp4 1280 800 6 10
#                                      video       width height fps seconds
```

Big, full-screen frames use a lot of memory while the tablet starts. On a 1 GB tablet,
1280x800 at 6 fps for 10 seconds worked. If the screen stays black, try a smaller size.

## Installing it

Where Android looks for the file depends on the tablet. Many look in `/system/media/bootanimation.zip`
(needs root to change). The tablet this was built on checks `/data/theme/overlay/` first and needs the
`system_file` label:

```bash
adb shell mkdir -p /data/theme/overlay
adb push bootanimation.zip /data/theme/overlay/bootanimation.zip
adb shell chmod 644 /data/theme/overlay/bootanimation.zip
adb shell chcon u:object_r:system_file:s0 /data/theme/overlay/bootanimation.zip
```

To go back to the original: `adb shell rm /data/theme/overlay/bootanimation.zip`.
Android only plays it at boot. The animation is only decoration, so a broken file cannot stop the tablet from starting.
