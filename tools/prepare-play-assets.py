#!/usr/bin/env python3
"""Export Play listing graphics and the public policy from repository-owned sources.

Requires Pillow. Does not publish anything or read signing credentials.
"""
from html import escape
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
METADATA = ROOT / "fastlane/metadata/android/en-US"
IMAGES = METADATA / "images"


def main():
    policy = (ROOT / "app/src/main/res/raw/privacy_policy.txt").read_text()
    blocks = policy.strip().split("\n\n")
    html = ["<!doctype html><html lang='en'><meta charset='utf-8'>",
            "<meta name='viewport' content='width=device-width, initial-scale=1'>",
            "<title>Vulcan Privacy Policy</title>",
            "<style>body{margin:48px auto;padding:0 24px;max-width:760px;background:#e9e7e2;"
            "color:#2a2a28;font:17px/1.65 system-ui,sans-serif}h1{font-size:36px}h2{font-size:23px;"
            "margin-top:36px}a{color:inherit}p{white-space:pre-line}</style><main>"]
    title, date = blocks[0].split("\n", 1)
    html += [f"<h1>{escape(title)}</h1><p>{escape(date)}</p>"]
    for block in blocks[1:]:
        if "\n" in block:
            heading, body = block.split("\n", 1)
            html.append(f"<h2>{escape(heading)}</h2><p>{escape(body)}</p>")
        else:
            html.append(f"<p>{escape(block)}</p>")
    html.append("<p><a href='./'>Vulcan home</a></p></main></html>")
    (ROOT / "docs/privacy.html").write_text("\n".join(html) + "\n")

    # Store icons must be opaque; keep the approved artwork's colour and geometry.
    icon = Image.open(IMAGES / "icon.png").convert("RGBA")
    opaque = Image.new("RGB", icon.size, "#2A2A28")
    opaque.paste(icon, mask=icon.getchannel("A"))
    opaque.save(IMAGES / "play-icon.png")

    # Small native vector-style composition, drawn at 2x and downsampled.
    scale = 2
    graphic = Image.new("RGB", (1024 * scale, 500 * scale), "#E9E7E2")
    d = ImageDraw.Draw(graphic)
    regular = Path("/System/Library/Fonts/Supplemental/Arial.ttf")
    bold = Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf")
    def text(x, y, value, size, color="#2A2A28", strong=False):
        font = ImageFont.truetype(str(bold if strong else regular), size * scale)
        d.text((x * scale, y * scale), value, fill=color, font=font)
    def box(bounds, radius, fill, outline=None):
        pixels = tuple(int(v * scale) for v in bounds)
        if radius == 24:
            d.ellipse(pixels, fill=fill)
        else:
            d.rounded_rectangle(pixels, radius * scale, fill=fill, outline=outline)
    text(64, 74, "Vulcan", 64, strong=True)
    text(67, 166, "Your home screen.", 31)
    text(67, 209, "Your way.", 31)
    d.line((68 * scale, 298 * scale, 115 * scale, 298 * scale), fill="#E8721C", width=5 * scale)
    text(67, 328, "No ads. No trackers.", 23)
    text(67, 363, "No internet permission.", 23)
    # A simplified launcher grid conveys customization without simulating a screenshot.
    box((626, 48, 936, 452), 28, "#2A2A28")
    box((646, 69, 916, 431), 16, "#363632")
    for row in range(3):
        for col in range(3):
            x, y = 675 + col * 80, 104 + row * 86
            box((x, y, x + 49, y + 49), 12 if col != 1 else 24,
                ("#E8721C", "#DEDBD5", "#A9A69F")[(col + row) % 3])
            d.line((x * scale, (y + 59) * scale, (x + 49) * scale, (y + 59) * scale),
                   fill="#A9A69F", width=2 * scale)
    box((668, 370, 894, 406), 18, "#595752")
    graphic.resize((1024, 500), Image.Resampling.LANCZOS).save(IMAGES / "featureGraphic.png")

    limits = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
    for name, limit in limits.items():
        value = (METADATA / name).read_text().strip()
        assert len(value) <= limit, f"{name}: {len(value)} > {limit}"
        print(f"{name}: {len(value)}/{limit} characters")
    assert opaque.size == (512, 512)
    for name, expected in (("play-icon.png", (512, 512)), ("featureGraphic.png", (1024, 500))):
        with Image.open(IMAGES / name) as image:
            assert image.size == expected and image.mode == "RGB", name
    screenshots = ROOT / "docs/play-store/phone-screenshots"
    screenshots.mkdir(parents=True, exist_ok=True)
    for image_path in (IMAGES / "phoneScreenshots").glob("*"):
        with Image.open(image_path) as image:
            # Preserve the original screenshot. Add neutral side margins for Play's 2:1 limit.
            image = image.convert("RGB")
            w, h = image.size
            if max(w, h) > 2 * min(w, h):
                size = (max(w, (h + 1) // 2), max(h, (w + 1) // 2))
                canvas = Image.new("RGB", size, "#2A2A28")
                canvas.paste(image, ((size[0] - w) // 2, (size[1] - h) // 2))
                image = canvas
            image.save(screenshots / image_path.name, quality=95)
            w, h = image.size
            assert min(w, h) >= 320 and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h), image_path
    print("Public policy and Play graphics exported; metadata limits verified.")


if __name__ == "__main__":
    main()
