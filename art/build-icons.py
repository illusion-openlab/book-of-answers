#!/usr/bin/env python3
"""从 art/icon-source-book.png 生成全部六个图标资源。

用法（仓库根目录）：  python3 art/build-icons.py
依赖：pillow、numpy、opencv-python

构图刻意保持最简：**一层纯色背景 + 一层源图抠图**，不加渐变、不加光效。
背景色是唯一可调项（[BG]）。规范与 SDF 规则的依据见 art/README.md。生成物不要手改。
"""
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "icon-source-book.png"
RES = ROOT / "app" / "src" / "main" / "res"

N = 1024          # 3D 图标层与平面 launcher 的画布
M = 1424          # adaptive icon 前景的画布（沿用脚手架尺寸）
SDF_RANGE = 31.0  # 实测：SDF 是内部距离场，31px 处线性截断

# 纯色背景。取自宣传图的深藏青 (3,9,35) 但提亮到亮度约 36 —— 规范要求避免纯黑/深黑，
# 否则系统自动附加的高光与阴影没有可依附的亮度。
BG = np.array([27, 36, 68], np.uint8)

BOOK_H_1024 = 660    # 主体层里书的高度；半对角 435 ≈ 内切圆半径的 85%
BOOK_H_1424 = 640    # adaptive 前景里书的高度；收在中央 66% 安全区内


def load_book() -> Image.Image:
    """读源图并按高 alpha 阈值裁到紧包围盒（低阈值会被边缘抗锯齿撑大）。"""
    im = Image.open(SRC).convert("RGBA")
    ys, xs = np.where(np.array(im)[..., 3] > 128)
    return im.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))


def place(book: Image.Image, canvas: int, target_h: int) -> np.ndarray:
    """把书按目标高度居中放到透明画布上。"""
    bw, bh = book.size
    h = int(round(target_h))
    w = int(round(bw * h / bh))
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    resized = book.resize((w, h), Image.LANCZOS)
    out.paste(resized, ((canvas - w) // 2, (canvas - h) // 2), resized)
    return np.array(out)


def circle_alpha(canvas: int) -> np.ndarray:
    """内切圆实心 alpha，边缘 1px 抗锯齿。圆外透明。"""
    yy, xx = np.mgrid[0:canvas, 0:canvas].astype(np.float32)
    c = (canvas - 1) / 2.0
    r = np.hypot(xx - c, yy - c)
    return np.clip((canvas / 2.0 - r) + 0.5, 0, 1) * 255.0


def solid(canvas: int, alpha: np.ndarray) -> np.ndarray:
    """纯色 RGB + 给定 alpha。"""
    rgb = np.broadcast_to(BG, (canvas, canvas, 3)).astype(np.float32)
    return np.dstack([rgb, alpha])


def make_sdf(rgba: np.ndarray) -> np.ndarray:
    """图层 -> SDF：内部欧氏距离场，31px 线性截断，RGB 恒白。"""
    mask = (rgba[..., 3] > 127).astype(np.uint8)
    dist = cv2.distanceTransform(mask, cv2.DIST_L2, 5)
    alpha = np.clip(dist / SDF_RANGE, 0, 1) * 255.0
    white = np.full(rgba.shape[:2] + (3,), 255, np.uint8)
    return np.dstack([white, alpha.astype(np.uint8)])


def over(dst: np.ndarray, src: np.ndarray) -> np.ndarray:
    """标准 source-over 合成，两者均为 float RGBA。"""
    da, sa = dst[..., 3:4] / 255.0, src[..., 3:4] / 255.0
    out_a = sa + da * (1 - sa)
    rgb = (src[..., :3] * sa + dst[..., :3] * da * (1 - sa)) / np.maximum(out_a, 1e-6)
    return np.dstack([np.clip(rgb, 0, 255), out_a * 255]).astype(np.uint8)


def main() -> None:
    book = load_book()
    print(f"源图紧包围盒 {book.size[0]}x{book.size[1]}")

    layer_bg = solid(N, circle_alpha(N)).astype(np.uint8)
    layer_fg = place(book, N, BOOK_H_1024)

    flat = over(layer_bg.astype(np.float32), layer_fg.astype(np.float32))

    # adaptive 前景没有独立 background 层，底色必须烤进这一张，且满幅不透明
    bg_full = solid(M, np.full((M, M), 255, np.float32))
    adaptive = over(bg_full, place(book, M, BOOK_H_1424).astype(np.float32))

    targets = {
        RES / "drawable" / "icon_3d_layer_0.png": layer_bg,
        RES / "drawable" / "icon_3d_layer_1.png": layer_fg,
        RES / "drawable" / "icon_3d_sdf_0.png": make_sdf(layer_bg),
        RES / "drawable" / "icon_3d_sdf_1.png": make_sdf(layer_fg),
        RES / "mipmap-xxxhdpi" / "ic_spatial_launcher.png": flat,
        RES / "drawable" / "ic_launcher_foreground.png": adaptive,
    }
    for path, arr in targets.items():
        Image.fromarray(arr).save(path)
        print(f"写入 {path.relative_to(ROOT)}  {arr.shape[1]}x{arr.shape[0]}")


if __name__ == "__main__":
    main()
