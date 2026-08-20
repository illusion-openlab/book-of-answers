#!/usr/bin/env python3
"""从 art/icon-source-book.png 生成全部六个图标资源。

用法（仓库根目录）：  python3 art/build-icons.py
依赖：pillow、numpy、opencv-python

构图：**一层背景底图 + 一层书本抠图**，两张都是提供的素材，脚本不做任何色彩加工。
规范与 SDF 规则的依据见 art/README.md。生成物不要手改。
"""
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC_BOOK = ROOT / "art" / "icon-source-book.png"
SRC_BG = ROOT / "art" / "icon-source-bg.png"
RES = ROOT / "app" / "src" / "main" / "res"

N = 1024          # 3D 图标层与平面 launcher 的画布
M = 1424          # adaptive icon 前景的画布（沿用脚手架尺寸）
SDF_RANGE = 31.0  # 实测：SDF 是内部距离场，31px 处线性截断

# 背景来自 SRC_BG（1536² 不透明的星盘底图），整幅等比缩放到目标画布，不裁剪、不调色。
# 亮度中位约 23：偏暗但不是纯黑，系统自动生成的分层悬停高光/阴影仍有可依附的亮度。
# 金环落在归一化半径 0.70–0.92 的外环带，会在书本四边露出、被书的四角压住。

# 书本撑满，照设计稿观感：半对角约占内切圆半径 97%。
BOOK_H_1024 = 754    # 1024 画布，半对角 497 / 半径 512
BOOK_H_1424 = 700    # 1424 画布，半对角 461 / adaptive 安全区半径 475


def load_book() -> Image.Image:
    """读书本源图并按高 alpha 阈值裁到紧包围盒（低阈值会被边缘抗锯齿撑大）。"""
    im = Image.open(SRC_BOOK).convert("RGBA")
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


def background(canvas: int, alpha: np.ndarray) -> np.ndarray:
    """星盘底图等比缩放到 canvas 见方 + 给定 alpha。原图为方形，直接缩放不裁剪。"""
    im = Image.open(SRC_BG).convert("RGB")
    assert im.size[0] == im.size[1], f"背景底图应为方形，实际 {im.size}"
    rgb = np.array(im.resize((canvas, canvas), Image.LANCZOS)).astype(np.float32)
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

    layer_bg = background(N, circle_alpha(N)).astype(np.uint8)
    layer_fg = place(book, N, BOOK_H_1024)

    flat = over(layer_bg.astype(np.float32), layer_fg.astype(np.float32))

    # adaptive 前景没有独立 background 层，底色必须烤进这一张，且满幅不透明
    bg_full = background(M, np.full((M, M), 255, np.float32))
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
        # optimize=True 是无损的：像素不变，只是选更省的 PNG 过滤器/压缩参数。
        # 星盘底图是噪点纹理，压不动多少，但也不该白占体积。
        Image.fromarray(arr).save(path, optimize=True)
        print(f"写入 {path.relative_to(ROOT)}  {arr.shape[1]}x{arr.shape[0]}")


if __name__ == "__main__":
    main()
