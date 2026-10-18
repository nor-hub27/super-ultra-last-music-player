"""Replaces the Android app icon with assets/icon.png (the sparkle image)."""
import glob
import os
from PIL import Image, ImageDraw

SRC = Image.open("assets/icon.png").convert("RGB")
RES = "android/app/src/main/res"
# density -> (legacy icon size, adaptive foreground size)
SIZES = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216),
         "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}

changed = 0
for density, (legacy, fg) in SIZES.items():
    folder = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(folder, exist_ok=True)
    square = SRC.resize((legacy, legacy), Image.LANCZOS)
    square.save(os.path.join(folder, "ic_launcher.png"))
    round_img = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
    mask = Image.new("L", (legacy * 4, legacy * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, legacy * 4 - 1, legacy * 4 - 1), fill=255)
    round_img.paste(square, (0, 0), mask.resize((legacy, legacy), Image.LANCZOS))
    round_img.save(os.path.join(folder, "ic_launcher_round.png"))
    # adaptive icon: image sits in the safe middle area on a transparent canvas
    canvas = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
    inner = int(fg * 0.66)
    canvas.paste(SRC.resize((inner, inner), Image.LANCZOS), ((fg - inner) // 2, (fg - inner) // 2))
    canvas.save(os.path.join(folder, "ic_launcher_foreground.png"))
    changed += 1

# Black background behind the adaptive icon (matches the image's black)
values = os.path.join(RES, "values")
os.makedirs(values, exist_ok=True)
with open(os.path.join(values, "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <color name="ic_launcher_background">#000000</color>\n</resources>\n')

# Make the adaptive icon definitions point at our pictures, whatever the template had before
ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
            '</adaptive-icon>\n')
anydpi = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(anydpi, exist_ok=True)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
        f.write(ADAPTIVE)

# Old template drawables are no longer used - remove them so nothing can pick them up
for path in glob.glob(os.path.join(RES, "drawable*", "ic_launcher_*.xml")):
    os.remove(path)

print(f"Icon written for {changed} screen densities.")
