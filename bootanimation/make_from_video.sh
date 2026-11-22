#!/bin/bash
# Turns a video into a boot animation zip.
#   bash bootanimation/make_from_video.sh input.mp4 [width] [height] [fps] [seconds]
# Needs ffmpeg and python3 with Pillow (pip install pillow).
set -euo pipefail
IN="${1:?give a video file}"; W="${2:-1280}"; H="${3:-800}"; FPS="${4:-6}"; SECS="${5:-10}"
OUT="${OUT:-bootanimation-from-video}"
rm -rf "$OUT" && mkdir -p "$OUT/part0" "$OUT/part1"
# Fill the screen (crop the edges if the shapes differ). Pull the video's dark background down to pure black,
# so no grey box shows around it on a black screen. Change 24 if your video's background is lighter or darker.
ffmpeg -v error -i "$IN" -t "$SECS" -an \
  -vf "fps=$FPS,scale=$W:$H:force_original_aspect_ratio=increase:flags=lanczos,crop=$W:$H,lutrgb=r='clip((val-24)*255/231,0,255)':g='clip((val-24)*255/231,0,255)':b='clip((val-24)*255/231,0,255)'" \
  "$OUT/part0/%05d.png"
# Smaller files: 192-colour PNGs. The last frame is held until Android has finished starting.
python3 - "$OUT" <<'PY'
import glob, shutil, sys
from PIL import Image
out = sys.argv[1]
files = sorted(glob.glob(out + "/part0/*.png"))
for f in files:
    Image.open(f).convert("RGB").quantize(colors=192, dither=Image.Dither.NONE).save(f, optimize=True)
shutil.copy(files[-1], out + "/part1/00000.png")
PY
printf '%s %s %s\np 1 0 part0\np 0 0 part1\n' "$W" "$H" "$FPS" > "$OUT/desc.txt"
(cd "$OUT" && zip -q -0 -D -r ../bootanimation.zip desc.txt part0 part1)
echo "made bootanimation.zip ($(du -h bootanimation.zip | cut -f1))"
