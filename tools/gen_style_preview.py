#!/usr/bin/env python3
"""
工具图标「样式」候选预览：把 res/drawable/ic_tool_*.xml 里的**真实图形**
（含那层 <group> 缩放与反算过的线宽）套进几种不同的**底 + 图形**方案里并排渲染。

为什么要有这个：改样式要 clean + assemble + 装机 + 截图，一轮十几分钟；
这个脚本几秒钟就能把几种方向摆出来对着看。**改样式前先跑这里。**

用法：python tools/gen_style_preview.py [--out xxx.png]
"""
import html as htmlmod
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

AND = "{http://schemas.android.com/apk/res/android}"
RES = Path("/Users/her/Desktop/FlymeFreeform/app/src/main/res/drawable")
OUT = Path("/tmp/tool_icons")
CH = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# 每行的样例工具（挑能代表色相的五个）
SAMPLES = [
    ("识屏", "ic_tool_screen_text", "cyan"),
    ("截屏", "ic_tool_screenshot", "purple"),
    ("手电筒", "ic_tool_flashlight", "amber"),
    ("微信扫一扫", "ic_tool_scan", "wechat"),
    ("锁屏", "ic_tool_lock", "slate"),
]

# 色相族：(亮端, 品牌/基色, 深端)  —— 深端给「淡彩底 + 同色图形」那套用
HUES = {
    "cyan": ("#38D3E6", "#12A5B8", "#0B7C8C"),
    "purple": ("#A78BFA", "#7C4DFF", "#5B21B6"),
    "amber": ("#FCD34D", "#F59E0B", "#B45309"),
    "wechat": ("#34D399", "#07C160", "#05803F"),
    "alipay": ("#4E9BFF", "#1677FF", "#0B4ECC"),
    "slate": ("#A6B2C2", "#5A6270", "#3A4049"),
}


def read_vector(name: str):
    root = ET.parse(RES / f"{name}.xml").getroot()
    g = root.find("group")
    sx = float(g.get(f"{AND}scaleX"))
    tx = float(g.get(f"{AND}translateX"))
    ty = float(g.get(f"{AND}translateY"))
    tf = f"translate({tx:.4f},{ty:.4f}) scale({sx:.4f})"
    paths = [(p.get(f"{AND}pathData"), float(p.get(f"{AND}strokeWidth"))) for p in g.findall("path")]
    return tf, paths


def rgba(hexcolor: str, a: float) -> str:
    h = hexcolor.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return f"rgba({r},{g},{b},{a})"


def glyph(tf, paths, color, shrink=1.0):
    """shrink < 1 时绕画布中心 (12,12) 再缩一圈 —— 给「外圈描边」那套留出呼吸。"""
    body = "".join(f'<path d="{htmlmod.escape(d, quote=True)}"/>' for d, _ in paths)
    w = paths[0][1]
    outer = f"translate(12,12) scale({shrink}) translate(-12,-12) " if shrink != 1.0 else ""
    return (
        f'<g transform="{outer}{tf}" fill="none" stroke="{color}" stroke-width="{w:.4f}"'
        f' stroke-linecap="round" stroke-linejoin="round">{body}</g>'
    )


def svg(style, tf, paths, hue, px, uid):
    light, base, deep = HUES[hue]
    o = [f'<svg width="{px}" height="{px}" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">']
    o.append(
        f'<defs><linearGradient id="g{uid}" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{light}"/><stop offset="1" stop-color="{base}"/>'
        f"</linearGradient></defs>"
    )
    if style == "circle_grad":  # ① 现状
        o.append(f'<circle cx="12" cy="12" r="12" fill="url(#g{uid})"/>')
        o.append(glyph(tf, paths, "#ffffff"))
    elif style == "squircle_grad":  # ② 圆角方形 + 渐变 + 白图形
        o.append(f'<rect x="0" y="0" width="24" height="24" rx="6.4" fill="url(#g{uid})"/>')
        o.append(glyph(tf, paths, "#ffffff"))
    elif style == "squircle_tonal":  # ③ 圆角方形 + 淡彩底 + 同色图形
        o.append(f'<rect x="0" y="0" width="24" height="24" rx="6.4" fill="{rgba(base, 0.14)}"/>')
        o.append(glyph(tf, paths, deep))
    elif style == "circle_tonal":  # ④ 圆形 + 淡彩底 + 同色图形
        o.append(f'<circle cx="12" cy="12" r="12" fill="{rgba(base, 0.14)}"/>')
        o.append(glyph(tf, paths, deep))
    elif style == "bare":  # ⑤ 无底色 + 彩色图形
        o.append(glyph(tf, paths, base))
    elif style == "ring":  # ⑥ 外圈描边 + 彩色图形
        o.append(f'<circle cx="12" cy="12" r="11.2" fill="none" stroke="{base}" stroke-width="0.9"/>')
        o.append(glyph(tf, paths, base, 0.90))
    elif style == "ring_tint":  # ⑦ 外圈描边 + 极淡底 + 彩色图形
        o.append(f'<circle cx="12" cy="12" r="11.2" fill="{rgba(base, 0.10)}" stroke="{base}" stroke-width="0.9"/>')
        o.append(glyph(tf, paths, base, 0.90))
    o.append("</svg>")
    return "".join(o)


STYLES = [
    ("circle_grad", "① 圆形 + 渐变底 + 白图形", "现在这一版"),
    ("squircle_grad", "② 圆角方形 + 渐变底 + 白图形", "iOS 图标那种圆角方块，最有「应用图标」感"),
    ("squircle_tonal", "③ 圆角方形 + 淡彩底 + 同色图形", "Material You / 系统设置那种浅底深图形"),
    ("circle_tonal", "④ 圆形 + 淡彩底 + 同色图形", "同上，但底保持圆形"),
    ("bare", "⑤ 无底色 + 彩色图形", "极简，只留线条，最轻"),
    ("ring", "⑥ 外圈描边 + 彩色图形", "⑤ 加一圈同色细描边，把范围框住（图形缩到 0.9 留呼吸）"),
    ("ring_tint", "⑦ 外圈描边 + 极淡底 + 彩色图形", "同⑥，再垫一层几乎看不见的同色底（10% 不透明度）"),
]

vect = {res: read_vector(res) for _, res, _ in SAMPLES}

rows = []
uid = 0
for style, title, note in STYLES:
    cells = []
    for label, res, hue in SAMPLES:
        tf, paths = vect[res]
        uid += 1
        cells.append(
            f'<div class="cell">{svg(style, tf, paths, hue, 144, uid)}'
            f'{svg(style, tf, paths, hue, 72, uid + 5000)}'
            f'<div class="name">{label}</div></div>'
        )
    rows.append(
        f'<div class="row"><div class="head"><div class="t">{title}</div>'
        f'<div class="n">{note}</div></div><div class="cells">{"".join(cells)}</div></div>'
    )

page = (
    '<!DOCTYPE html><html><head><meta charset="utf-8"><style>'
    "body{background:#f4f5f7;font-family:-apple-system,'PingFang SC',sans-serif;"
    "margin:0;padding:20px 24px}"
    ".row{margin-bottom:24px}.head{margin-bottom:6px}"
    ".t{font-size:15px;font-weight:600;color:#111827}"
    ".n{font-size:12px;color:#6b7280;margin-top:2px}"
    ".cells{display:flex;gap:22px}"
    ".cell{text-align:center;width:148px}"
    ".name{font-size:12px;color:#374151;margin-top:4px}"
    "svg{margin-top:4px}"
    "</style></head><body>" + "".join(rows) + "</body></html>"
)
h = OUT / "style_preview.html"
h.write_text(page, encoding="utf-8")
out = Path(sys.argv[sys.argv.index("--out") + 1]) if "--out" in sys.argv else OUT / "style_preview.png"
subprocess.run(
    [CH, "--headless=new", "--disable-gpu", "--hide-scrollbars",
     "--window-size=1000,2400", "--force-device-scale-factor=2",
     f"--screenshot={out}", "--virtual-time-budget=2500", f"file://{h}"],
    check=True, capture_output=True,
)
print("预览:", out)
