"""Animated GIFs of exhibits for the README, from the tour's frames.

Run the tour on the chosen exhibits with frame capture on, then this script:

    ./gradlew runShowcaseClient -Demergence.showcaseOnly=governed,engine -Demergence.gifs=governed,engine
    python tools/make_gifs.py [run/screenshots/gif] [docs/gifs] [id:first:last ...]

Each exhibit's frames (screenshots/gif/<id>/f0000.png ...) become docs/gifs/<id>.gif: every frame after the first
second (the camera settling), thinned to at most MAX_FRAMES (a long one plays as a time-lapse), scaled to WIDTH
pixels, one shared adaptive palette, looping at 10 frames a second.
"""
import os
import sys

from PIL import Image

WIDTH = 400
MAX_FRAMES = 120
FRAME_MS = 100
SKIP = 10  # the first second: the camera settling on the view


def make(frames_dir, out_path, first=SKIP, last=None):
    names = sorted(f for f in os.listdir(frames_dir) if f.endswith(".png"))[first:last]
    if not names:
        return False
    step = max(1, -(-len(names) // MAX_FRAMES))
    names = names[::step]
    frames = []
    for n in names:
        im = Image.open(os.path.join(frames_dir, n)).convert("RGB")
        h = round(im.height * WIDTH / im.width)
        frames.append(im.resize((WIDTH, h), Image.LANCZOS))
    # one palette for all frames (from a strip of samples), so colours do not shimmer frame to frame
    sample = Image.new("RGB", (WIDTH, frames[0].height * min(len(frames), 8)))
    for k, f in enumerate(frames[:: max(1, len(frames) // 8)][:8]):
        sample.paste(f, (0, k * frames[0].height))
    palette = sample.quantize(colors=96, method=Image.Quantize.MEDIANCUT)
    quantized = [f.quantize(palette=palette, dither=Image.Dither.NONE) for f in frames]
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    quantized[0].save(out_path, save_all=True, append_images=quantized[1:], duration=FRAME_MS, loop=0, optimize=True)
    print(f"{out_path}: {len(quantized)} frames ({step}x time-lapse), {os.path.getsize(out_path) / 1e6:.1f} MB")
    return True


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join("run", "screenshots", "gif")
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join("docs", "gifs")
    # id:first:last — the stretch of an exhibit's frames to use (frame numbers; either may be empty)
    ranges = {}
    for arg in sys.argv[3:]:
        ex, a, b = (arg.split(":") + ["", ""])[:3]
        ranges[ex] = (int(a) if a else SKIP, int(b) if b else None)
    for exhibit in sorted(os.listdir(src)):
        d = os.path.join(src, exhibit)
        if os.path.isdir(d) and (not ranges or exhibit in ranges):
            first, last = ranges.get(exhibit, (SKIP, None))
            make(d, os.path.join(dst, exhibit + ".gif"), first, last)


if __name__ == "__main__":
    main()
