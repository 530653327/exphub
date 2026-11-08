"""生成 PWA 图标（纯本地，不联网）。

用法（Windows / macOS 通用）：
    python tools/gen_pwa_icons.py

产出到 src/main/resources/static/images/pwa/：
    icon-192.png / icon-512.png / icon-maskable-512.png / apple-touch-icon.png / favicon-32.png

为什么自己画而不是找现成图：PWA 的 maskable 图标要求内容落在中心 80% 的安全区内，
随便一张品牌图直接用，装到桌面上会被圆形/方形裁掉边角。这里按规范留边重画。
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/static/images/pwa"

# 与移动端主题色一致（暗色，跟后台风格同源）
BG = (30, 30, 46)
FG = (255, 255, 255)
ACCENT = (99, 102, 241)


def load_font(size: int):
    """找一个能显示中文的粗体；找不到就用默认字体（英文场景仍可用）。"""
    for name in ("msyhbd.ttc", "msyh.ttc", "PingFang.ttc", "NotoSansCJK-Bold.ttc"):
        for root in ("C:/Windows/Fonts", "/System/Library/Fonts", "/usr/share/fonts/opentype/noto"):
            p = Path(root) / name
            if p.exists():
                try:
                    return ImageFont.truetype(str(p), size)
                except Exception:
                    continue
    return ImageFont.load_default()


def fit_font(draw, text: str, size: int, start_ratio: float, max_width_ratio: float):
    """从 start_ratio 起逐档缩小字号，直到文字宽度落进 max_width_ratio 的安全宽度。

    单字（如「阁」）用固定字号就够，但 "EXP" 是三个字母，同样字号会横向撑出安全区、
    装到桌面上被圆形裁切切掉两边。这里按实际 textbbox 量出宽度再退档，不靠拍脑袋写死字号。
    """
    fs = int(size * start_ratio)
    for _ in range(12):
        font = load_font(fs)
        box = draw.textbbox((0, 0), text, font=font)
        if (box[2] - box[0]) <= size * max_width_ratio:
            return font
        fs = int(fs * 0.92)
    return load_font(int(size * 0.2))


def rounded_bg(size: int, radius_ratio: float, safe_ratio: float):
    """画底：全出血圆角方块 + 中间内容留安全区。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=BG)

    # 一条品牌色斜带，避免纯黑看起来像加载失败
    band = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    bd.polygon([(0, size * 0.72), (size, size * 0.36), (size, size * 0.5), (0, size * 0.86)],
               fill=ACCENT + (70,))
    img.alpha_composite(band)

    # 中心字：EXP（越哥 2026-11-08 定：桌面图标不放汉字，用 EXP）
    # 用英文全大写而不是「经验阁」缩写 —— 手机主屏图标只有 48~64px，
    # 三个字母比三个汉字在这个尺寸下辨识度高得多。
    text = "EXP"
    measure = ImageDraw.Draw(img)
    # 普通图标留 70%（贴到 78% 时两端离边缘只剩几个像素，圆角裁切看着很挤），
    # maskable 留 62%（规范只保证中心 80% 不被裁，再往里收一点更安全）
    font = fit_font(measure, text, size, safe_ratio, 0.70 if safe_ratio < 0.48 else 0.62)
    box = measure.textbbox((0, 0), text, font=font)
    tw, th = box[2] - box[0], box[3] - box[1]
    # 字母没有下伸部，纯按 bbox 居中视觉上会偏上，往下补 2% 更稳
    d.text(((size - tw) / 2 - box[0], (size - th) / 2 - box[1] + size * 0.02),
           text, font=font, fill=FG)
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    # 普通图标：内容可以到边，圆角 22%
    rounded_bg(192, 0.22, 0.46).save(OUT / "icon-192.png")
    rounded_bg(512, 0.22, 0.46).save(OUT / "icon-512.png")

    # maskable：内容必须落在中心 80% 内，否则装到桌面会被裁
    mask = Image.new("RGBA", (512, 512), BG)
    inner = rounded_bg(int(512 * 0.8), 0.18, 0.5)
    mask.alpha_composite(inner, (int(512 * 0.1), int(512 * 0.1)))
    mask.save(OUT / "icon-maskable-512.png")

    # iOS 主屏图标：不透明背景，iOS 自己加圆角
    ios = Image.new("RGBA", (180, 180), BG)
    ios.alpha_composite(rounded_bg(180, 0.22, 0.46))
    ios.convert("RGB").save(OUT / "apple-touch-icon.png")

    rounded_bg(32, 0.22, 0.5).save(OUT / "favicon-32.png")

    for f in sorted(OUT.glob("*.png")):
        print(f"{f.name}: {f.stat().st_size} bytes")


if __name__ == "__main__":
    main()
