#!/usr/bin/env python3
"""
「⑤ 无底色 + 彩色图形」的**渐变描边**方案预览。

背景是真实观感：工具图标在真机上落在 **白卡片 (#FFFFFF)** 与 **页面底 (#F5F6F8)** 上，
不是深色。所以线条色必须在**浅底**上够清楚 —— 手电筒的亮黄、识屏的亮青在白底上是最先垮的两条。

渲染三行做对照：
  A 现状（纯色，取 Spec.color 的亮端）—— 暴露问题
  B 两色渐变（同色相 亮→深）
  C 三色渐变（亮→中→深）

图形路径与那层 <group> 缩放全部**照抄 res/drawable/ic_tool_*.xml**，线宽也是反算过的真值；
渐变用 userSpaceOnUse (0,0)->(24,24)，与 Kotlin 侧 LinearGradient(0,0,size,size) 完全对齐。

用法：python tools/gen_bare_gradient_preview.py     # 写 /tmp/tool_icons/bare_grad.html
"""
import html as htmlmod
import xml.etree.ElementTree as ET
from pathlib import Path

AND = "{http://schemas.android.com/apk/res/android}"
RES = Path("/Users/her/Desktop/FlymeFreeform/app/src/main/res/drawable")
OUT = Path("/tmp/tool_icons")
OUT.mkdir(parents=True, exist_ok=True)

# (key, 图形资源, 标签, 现状纯色, 两色(亮,深), 三色(亮,中,深))
TOOLS = [
    ("screen", "ic_tool_screen_text", "识屏", "#22D3EE",
     ("#06B6D4", "#0E7490"), ("#22D3EE", "#06B6D4", "#0E7490")),
    ("shot", "ic_tool_screenshot", "截屏", "#B388FF",
     ("#8B5CF6", "#6D28D9"), ("#A78BFA", "#8B5CF6", "#6D28D9")),
    ("bulb", "ic_tool_flashlight", "手电筒", "#FFD54F",
     ("#F59E0B", "#B45309"), ("#FBBF24", "#F59E0B", "#B45309")),
    ("wscan", "ic_tool_scan", "微信扫一扫", "#34D399",
     ("#10B981", "#047857"), ("#34D399", "#10B981", "#047857")),
    ("wpay", "ic_tool_paycode", "微信付款码", "#34D399",
     ("#10B981", "#047857"), ("#34D399", "#10B981", "#047857")),
    ("ascan", "ic_tool_scan", "支付宝扫一扫", "#4E9BFF",
     ("#1677FF", "#0B4ECC"), ("#4E9BFF", "#1677FF", "#0B4ECC")),
    ("apay", "ic_tool_paycode", "支付宝付款码", "#4E9BFF",
     ("#1677FF", "#0B4ECC"), ("#4E9BFF", "#1677FF", "#0B4ECC")),
    ("lock", "ic_tool_lock", "锁屏", "#94A3B8",
     ("#64748B", "#334155"), ("#94A3B8", "#64748B", "#334155")),
]


def read_vector(name):
    g = ET.parse(RES / f"{name}.xml").getroot().find("group")
    sx = float(g.get(f"{AND}scaleX"))
    tx, ty = float(g.get(f"{AND}translateX")), float(g.get(f"{AND}translateY"))
    w = float(g.find("path").get(f"{AND}strokeWidth"))
    paths = [p.get(f"{AND}pathData") for p in g.findall("path")]
    return tx, ty, sx, w, paths


def defs():
    out = []
    for key, res, *_ in TOOLS:
        tx, ty, sx, w, paths = read_vector(res)
        body = "".join(f'<path d="{htmlmod.escape(d, quote=True)}"/>' for d in paths)
        out.append(
            f'<g id="ic-{key}" transform="translate({tx:.4f},{ty:.4f}) scale({sx:.4f})"'
            f' fill="none" stroke-linecap="round" stroke-linejoin="round">{body}</g>'
        )
    return "".join(out)


def grads():
    out = []
    for key, _, _, _, two, three in TOOLS:
        stops2 = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ((0, two[0]), (1, two[1])))
        out.append(
            f'<linearGradient id="g2-{key}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="24" y2="24">{stops2}</linearGradient>'
        )
        stops3 = "".join(
            f'<stop offset="{o}" stop-color="{c}"/>'
            for o, c in ((0, three[0]), (0.5, three[1]), (1, three[2]))
        )
        out.append(
            f'<linearGradient id="g3-{key}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="24" y2="24">{stops3}</linearGradient>'
        )
    return "".join(out)


def cell(key, res, label, paint, px):
    _, _, _, w, _ = read_vector(res)
    stroke = paint if paint.startswith("url") else paint
    return (
        f'<div class="cell">'
        f'<svg width="{px}" height="{px}" viewBox="0 0 24 24">'
        f'<use href="#ic-{key}" stroke="{stroke}" stroke-width="{w:.4f}"/></svg>'
        f'<div class="nm">{label}</div></div>'
    )


ROWS = [
    ("A", "现状 · 纯色线条（用圆底那套亮端色）", "#22D3EE 类亮色在白底上发虚，手电筒几乎看不见",
     lambda t: t[3]),
    ("B", "⑤ + 两色渐变（同色相 亮→深）", "亮端收进中明度，深端压住轮廓，白底上每条都立得住",
     lambda t: f"url(#g2-{t[0]})"),
    ("C", "⑤ + 三色渐变（亮→中→深）", "线条有明暗层次，比两色更「活」，代价是小尺寸只剩平均色",
     lambda t: f"url(#g3-{t[0]})"),
]

head = f'<svg width="0" height="0" style="position:absolute"><defs>{defs()}{grads()}</defs></svg>'

blocks = []
for tag, title, note, pick in ROWS:
    for px, tag2 in ((48, ""), (24, " · 24dp")):
        if px == 24 and tag != "B":
            continue
        cells = "".join(cell(t[0], t[1], t[2], pick(t), px) for t in TOOLS)
        blocks.append(
            f'<div class="row"><div class="hd"><b>{tag} · {title}{tag2}</b>'
            f'<span>{note if px == 48 else "缩到底栏那种尺寸，两色渐变仍然分得出色相"}</span></div>'
            f'<div class="cells {"" if px == 48 else "small"}">{cells}</div></div>'
        )

page = (
    head
    + '<div class="wrap">' + "".join(blocks) + "</div>"
)

CSS = """
.wrap{display:flex;flex-direction:column;gap:14px;font-family:-apple-system,'PingFang SC',sans-serif}
.row{background:#FFFFFF;border:1px solid #E6E9EE;border-radius:14px;padding:14px 16px}
.hd{display:flex;flex-direction:column;gap:3px;margin-bottom:10px}
.hd b{font-size:13px;color:#111827;font-weight:600}
.hd span{font-size:11.5px;color:#6B7280}
.cells{display:flex;gap:20px;align-items:flex-start;flex-wrap:wrap}
.cells.small{gap:14px}
.cell{text-align:center;width:58px}
.nm{font-size:10.5px;color:#374151;margin-top:5px;white-space:nowrap}
"""

(OUT / "bare_grad.html").write_text(f"<style>{CSS}</style>{page}", encoding="utf-8")
print("html:", OUT / "bare_grad.html", len(page), "chars")
