"""生成 Android 应用图标（设计稿配色：绿底 + 白色勺子）。

用法：.venv\\Scripts\\python.exe android\\make_icons.py   （使用 .venv 里的 Pillow）
输出：android/app/src/main/res/mipmap-*/ic_launcher.png
      —— 必须是 Gradle 工程的资源目录（app/src/main/res）；
      老的 android/res/ 已经废弃、不参与构建，写进去不会有任何效果。
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
RES = HERE / "app" / "src" / "main" / "res"

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

BG = (166, 214, 64, 255)      # #A6D640 设计稿描边绿
BG_DARK = (140, 186, 44, 255)
WHITE = (255, 255, 255, 255)
BOWL = (245, 245, 245, 255)


def draw_icon(size: int) -> Image.Image:
    scale = 8  # 先放大再缩小，得到平滑边缘
    s = size * scale
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    radius = int(s * 0.22)
    draw.rounded_rectangle([0, 0, s - 1, s - 1], radius=radius, fill=BG)
    draw.rounded_rectangle([0, 0, s - 1, int(s * 0.5)], radius=radius, fill=BG)

    # 勺柄：右上到左下的一根斜杆
    handle_w = int(s * 0.085)
    draw.line(
        [(int(s * 0.68), int(s * 0.2)), (int(s * 0.42), int(s * 0.62))],
        fill=WHITE,
        width=handle_w,
    )
    # 勺头：左下角的椭圆
    head_r = int(s * 0.17)
    cx, cy = int(s * 0.35), int(s * 0.72)
    draw.ellipse([cx - head_r, cy - int(head_r * 1.15), cx + head_r, cy + int(head_r * 1.15)],
                 fill=BOWL, outline=WHITE, width=int(s * 0.02))
    # 勺头高光
    draw.ellipse([cx - int(head_r * 0.45), cy - int(head_r * 0.55),
                  cx + int(head_r * 0.05), cy - int(head_r * 0.05)], fill=BG_DARK)

    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    for name, size in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        icon = draw_icon(size)
        icon.save(folder / "ic_launcher.png")
        print(f"mipmap-{name}/ic_launcher.png {size}x{size}")


if __name__ == "__main__":
    main()
