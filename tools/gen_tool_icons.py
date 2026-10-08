#!/usr/bin/env python3
"""

与 gen_icons.py 的差别：库图标是 **stroke** 而不是 fill，所以

  1. 图形改用 android:strokeColor / strokeWidth / strokeLineCap=round；
  2. 缩放时线宽会跟着缩，所以要按 W_xml = W_final / scale 反算回去，
     让六个图标在真机上的**线宽完全一致**（否则缩放比不同的图标线会一粗一细）。

目标：内容（含描边）外接圆半径 = TARGET_R；真机线宽 = W_FINAL（24 视口单位）。
  → scale = (TARGET_R - W_FINAL/2) / R_geo
  → Android 里的 strokeWidth = W_FINAL / scale

用法：python gen_lucide.py [--write]
"""
import html as htmlmod
import math
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

AND = "{http://schemas.android.com/apk/res/android}"
SRC = Path("/tmp/tool_icons/lucide")
OUT = Path("/tmp/tool_icons")
DST = Path("/Users/her/Desktop/FlymeFreeform/app/src/main/res/drawable")
CH = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PX = 960
TARGET_R = 10.0   # 内容外接圆半径（24 视口）；圆底半径 12 → 四周留 1.8 呼吸
W_FINAL = 1.70    # 最终线宽（24 视口单位）→ 真机上 27dp 时约 1.9dp

# (资源名, lucide 名, 显示名, 描述)
ICONS = [
    ("ic_tool_screen_text", "text-search", "识屏", "三条文字线 + 一枚放大镜"),
    ("ic_tool_screenshot", "scissors", "截屏", "一把剪刀"),
    ("ic_tool_scan", "scan-qr-code", "扫一扫", "取景框 + 二维码"),
    ("ic_tool_paycode", "barcode", "付款码", "五根竖线"),
    ("ic_tool_flashlight", "lightbulb", "手电筒", "一只灯泡"),
    ("ic_tool_lock", "lock", "一键锁屏", "一把挂锁"),
]
UNTOUCHED = []
COLORS = {
    "ic_tool_screen_text": "#0E9AA7", "ic_tool_screenshot": "#7C4DFF",
    "ic_tool_flashlight": "#F5A623", "ic_tool_scan": "#07C160",
    "ic_tool_paycode": "#1677FF", "ic_tool_lock": "#5A6270",
}
UNTOUCHED = [(k, v, COLORS[k]) for k, v, _, _ in ICONS]
UNTOUCHED = []  # 六个全换，风格才统一


def shapes_of(name: str):
    """返回 [(pathData, 是否需要闭合)] —— circle/rect 一律折成 path。"""
    root = ET.parse(SRC / f"{name}.svg").getroot()
    out = []
    for el in root:
        tag = el.tag.split("}")[-1]
        if tag == "path":
            out.append(el.get("d"))
        elif tag == "circle":
            cx, cy, r = (float(el.get(k)) for k in ("cx", "cy", "r"))
            out.append(f"M{cx - r},{cy}a{r},{r} 0 1 0 {2 * r},0a{r},{r} 0 1 0 {-2 * r},0")
        elif tag == "rect":
            x, y = float(el.get("x", 0)), float(el.get("y", 0))
            w, h = float(el.get("width")), float(el.get("height"))
            rx = float(el.get("rx") or 0)
            ry = float(el.get("ry") or rx)
            if rx <= 0:
                out.append(f"M{x},{y}h{w}v{h}h{-w}Z")
            else:
                out.append(
                    f"M{x + rx},{y}h{w - 2 * rx}a{rx},{ry} 0 0 1 {rx},{ry}v{h - 2 * ry}"
                    f"a{rx},{ry} 0 0 1 {-rx},{ry}h{-(w - 2 * rx)}a{rx},{ry} 0 0 1 {-rx},{-ry}"
                    f"v{-(h - 2 * ry)}a{rx},{ry} 0 0 1 {rx},{-ry}Z"
                )
    return out


def measure(name: str, paths):
    """把描边调成极细（0.02）渲染，量出**中心线**的外接圆半径 R_geo。"""
    body = "".join(f'<path d="{htmlmod.escape(d, quote=True)}"/>' for d in paths)
    page = (
        '<!DOCTYPE html><html><head><meta charset="utf-8"><style>'
        "html,body{margin:0;padding:0;background:#000}svg{display:block}</style></head><body>"
        f'<svg width="{PX}" height="{PX}" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">'
        f'<rect width="24" height="24" fill="#000"/>'
        f'<g fill="none" stroke="#fff" stroke-width="0.06">{body}</g></svg></body></html>'
    )
    h = OUT / f"_L_{name}.html"
    h.write_text(page, encoding="utf-8")
    png = OUT / f"_L_{name}.png"
    subprocess.run(
        [CH, "--headless=new", "--disable-gpu", "--hide-scrollbars",
         f"--window-size={PX},{PX}", f"--screenshot={png}",
         "--virtual-time-budget=2000", f"file://{h}"],
        check=True, capture_output=True,
    )
    im = Image.open(png).convert("L")
    w, hh = im.size
    px = im.load()
    s = 24.0 / w
    pts = [((x + .5) * s, (y + .5) * s) for y in range(hh) for x in range(w) if px[x, y] > 80]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    R = max(math.hypot(ux - cx, uy - cy) for ux, uy in pts)
    return cx, cy, R, (min(xs), max(xs), min(ys), max(ys))


TPL = """<?xml version="1.0" encoding="utf-8"?>
<!--
  「{label}」工具的图形：{desc}。

  路径来源：Lucide `{slug}`（ISC License，见 NOTICE.md）
    https://cdn.jsdelivr.net/npm/lucide-static/icons/{slug}.svg
  ★ 是**描边（stroke）**而不是填充：Lucide 是线性图标库，path 只描述中心线。
    别把这些 path 改成 fillColor 填实 —— 那是另一种观感（实心块，2026-10-08 前几版就是这么做的，
    用户否掉：「不像一个现代软件功能该有的样子」）。circle/rect 已由脚本折成等价的 path。

  外面这层 <group> 把中心线缩放到「外接圆半径 = {TARGET_R} - 线宽/2」，再摆到画布正中：
    scale = {scale:.4f}     平移 ({tx:+.3f},{ty:+.3f}) → 中心线外接圆中心落在 (12,12)
    strokeWidth = {w:.4f}   ← 反算过的值，为的是**六个图标在真机上线宽一模一样**
                              （线宽也吃 scale，直接写 {W_FINAL} 会变成一粗一细）

  圆底是内切圆（半径 12），内容含描边的最外沿恰好 {TARGET_R}，四周留 1.8 呼吸，永不戳出圆边。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <group
        android:pivotX="0"
        android:pivotY="0"
        android:scaleX="{scale:.4f}"
        android:scaleY="{scale:.4f}"
        android:translateX="{tx:.4f}"
        android:translateY="{ty:.4f}">
{paths}</group>
</vector>
"""

PATH_TPL = """        <path
            android:fillColor="#00000000"
            android:strokeColor="#FFFFFFFF"
            android:strokeWidth="{w:.4f}"
            android:strokeLineCap="round"
            android:strokeLineJoin="round"
            android:pathData="{d}" />
"""

write = "--write" in sys.argv
measured = []
for res, slug, label, desc in ICONS:
    paths = shapes_of(slug)
    cx, cy, R, bbox = measure(res, paths)
    scale = (TARGET_R - W_FINAL / 2) / R
    tx, ty = 12 - cx * scale, 12 - cy * scale
    w = W_FINAL / scale
    measured.append((res, slug, label, desc, paths, scale, tx, ty, w, R, bbox))

if write:
    for res, slug, label, desc, paths, scale, tx, ty, w, R, bbox in measured:
        body = "".join(PATH_TPL.format(w=w, d=d) for d in paths)
        DST.joinpath(f"{res}.xml").write_text(
            TPL.format(label=label, desc=desc, slug=slug, TARGET_R=TARGET_R, W_FINAL=W_FINAL,
                       scale=scale, tx=tx, ty=ty, w=w, paths=body),
            encoding="utf-8",
        )
    print("→ 已写入", DST)

for res, slug, label, desc, paths, scale, tx, ty, w, R, bbox in measured:
    print(f"{label:5} 几何范围 x{bbox[0]:5.1f}~{bbox[1]:5.1f} y{bbox[2]:5.1f}~{bbox[3]:5.1f}  "
          f"几何外接R={R:5.2f}  scale={scale:.4f}  线与线宽={W_FINAL} (xml {w:.3f})  平移({tx:+.2f},{ty:+.2f})")


def svg_of(paths, scale, tx, ty, w, color, px):
    body = "".join(f'<path d="{d}"/>' for d in paths)
    return (
        f'<svg width="{px}" height="{px}" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">'
        f'<circle cx="12" cy="12" r="12" fill="{color}"/>'
        f'<g transform="translate({tx:.4f},{ty:.4f}) scale({scale:.4f})" fill="none" stroke="#ffffff"'
        f' stroke-width="{w:.4f}" stroke-linecap="round" stroke-linejoin="round">{body}</g></svg>'
    )


cells = "".join(
    f'<div class="cell">{svg_of(p, s, tx, ty, w, COLORS[r], 176)}{svg_of(p, s, tx, ty, w, COLORS[r], 80)}'
    f'<div class="name">{l}</div></div>'
    for r, slug, l, desc, p, s, tx, ty, w, R, bbox in measured
)
OUT.joinpath("lucide_preview.html").write_text(
    '<!DOCTYPE html><html><head><meta charset="utf-8"><style>'
    "body{background:#eef0f3;font-family:-apple-system,'PingFang SC',sans-serif;margin:0;padding:16px}"
    ".row{display:flex;flex-wrap:wrap;gap:16px}.cell{text-align:center;width:182px}"
    ".name{font-size:12px;color:#374151;margin-top:3px}</style></head><body>"
    f"<div class='row'>{cells}</div></body></html>",
    encoding="utf-8",
)
print("预览:", OUT / "lucide_preview.html")
