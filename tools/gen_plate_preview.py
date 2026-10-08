#!/usr/bin/env python3
"""渲染「无底色（BARE）vs 白底（PLATE）」在几种背景下的真实对比。

存在的理由：`SystemTools.ToolIconStyle.PLATE` 的整个卖点是一句反直觉的话 ——
**白底在白卡片上完全隐形**，只有轮盘那层遮罩下才显形。这话光靠嘴说不可信，
所以把两个样式并排画在同一块背景上，一眼看真假。

图形与线宽直接读 `app/src/main/res/drawable/ic_tool_*.xml`（与真机同一份数据），
配色抄自 `SystemTools.specs` 的 `colors`（★ 改配色记得同步下面这张 TOOLS 表）。

用法：
    python3 tools/gen_plate_preview.py            # 出 /tmp/tool_icons/plate/preview.html
    # 再用 headless Chrome 截成 PNG（见 --help 里那条命令，或直接看脚本尾部）
"""

from __future__ import annotations

import html
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/drawable"
OUT_DIR = Path("/tmp/tool_icons/plate")
AND = "{http://schemas.android.com/apk/res/android}"

# (drawable 名, 显示名, 色标) —— ★ 与 SystemTools.specs 保持一致
TOOLS = [
    ("ic_tool_screen_text", "识屏", ["#2DD4BF", "#22D3EE", "#3B82F6"]),
    ("ic_tool_screenshot", "截屏", ["#C084FC", "#A855F7", "#6366F1"]),
    ("ic_tool_flashlight", "手电筒", ["#FDE047", "#FBBF24", "#F97316"]),
    ("ic_tool_lock", "锁屏", ["#B6C2D4", "#8494AD", "#526179"]),
]

# (说明, 背景色, 文字色) —— 背景色 = 原始底 × (1 - 衬底浓度)
BACKGROUNDS = [
    ("白卡片（面板 / 管理页 / 图标页）", "#FFFFFF", "#1F2328"),
    ("轮盘 · 衬底 18% × 浅壁纸", "#BFC2C8", "#1F2328"),
    ("轮盘 · 衬底 18% × 深壁纸", "#23272E", "#F2F3F5"),
    ("轮盘 · 衬底 60% × 浅壁纸", "#5C5E62", "#F2F3F5"),
]


def read_icon(name: str):
    """读回一个 ic_tool_*.xml 的 <group> 变换、线宽与全部 pathData。"""
    group = ET.parse(RES / f"{name}.xml").getroot().find("group")
    return (
        float(group.get(AND + "translateX")),
        float(group.get(AND + "translateY")),
        float(group.get(AND + "scaleX")),
        float(group.find("path").get(AND + "strokeWidth")),
        [p.get(AND + "pathData") for p in group.findall("path")],
    )


def defs_for(name: str) -> str:
    tx, ty, sx, _, paths = read_icon(name)
    body = "".join(f'<path d="{html.escape(d, quote=True)}"/>' for d in paths)
    return (
        f'<g id="g-{name}" transform="translate({tx:.4f},{ty:.4f}) scale({sx:.4f})"'
        f' fill="none" stroke-linecap="round" stroke-linejoin="round">{body}</g>'
    )


def gradient_def(gid: str, colors: list[str]) -> str:
    stops = "".join(
        f'<stop offset="{i / (len(colors) - 1):.3f}" stop-color="{c}"/>'
        for i, c in enumerate(colors)
    )
    # 与 Android 侧一致：整块正方形、左上 → 右下（userSpaceOnUse，不是元素 bbox）。
    return (
        f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse"'
        f' x1="0" y1="0" x2="24" y2="24">{stops}</linearGradient>'
    )


def cell(name: str, plate: bool) -> str:
    circle = '<circle cx="12" cy="12" r="12" fill="#FFFFFF"/>' if plate else ""
    return (
        f'<svg class="ic" viewBox="0 0 24 24">'
        f"{circle}"
        f'<use href="#g-{name}" stroke="url(#p-{name})" stroke-width="{read_icon(name)[3]:.4f}"/>'
        f"</svg>"
    )


def main() -> None:
    defs = "".join(defs_for(n) for n, _, _ in TOOLS)
    grads = "".join(gradient_def(f"p-{n}", c) for n, _, c in TOOLS)

    rows = []
    for title, bg, fg in BACKGROUNDS:
        group = "".join(
            f'<div class="grp">'
            f'<div class="lbl">{"白底" if plate else "无底色"}</div>'
            f'<div class="row">'
            + "".join(
                f'<div class="cell"><div class="icbox">{cell(n, plate)}</div>'
                f'<div class="cap">{label}</div></div>'
                for n, label, _ in TOOLS
            )
            + "</div></div>"
            for plate in (False, True)
        )
        rows.append(
            f'<section class="bg" style="background:{bg};color:{fg}">'
            f'<div class="title">{title}</div>'
            f'<div class="cols">{group}</div>'
            f"</section>"
        )

    page = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><style>
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; padding: 22px; background: #EDEFF3;
         font-family: -apple-system, "PingFang SC", "Helvetica Neue", sans-serif; }}
  h1 {{ font-size: 17px; margin: 0 0 4px; color: #14181D; }}
  p.note {{ font-size: 12.5px; color: #5A6069; margin: 0 0 16px; line-height: 1.6; }}
  .bg {{ border-radius: 14px; padding: 16px 20px 18px; margin-bottom: 14px; }}
  .title {{ font-size: 13px; font-weight: 600; opacity: .85; margin-bottom: 12px; }}
  .cols {{ display: flex; align-items: flex-start; gap: 26px; }}
  .grp {{ display: flex; flex-direction: column; gap: 8px; }}
  .lbl {{ font-size: 11.5px; font-weight: 600; opacity: .55; letter-spacing: .04em; }}
  .row {{ display: flex; gap: 20px; }}
  .cell {{ display: flex; flex-direction: column; align-items: center; gap: 5px; }}
  .icbox {{ width: 72px; height: 72px; display: grid; place-items: center; }}
  .ic {{ width: 72px; height: 72px; display: block; }}
  .cap {{ font-size: 11px; opacity: .7; }}
</style></head><body>
  <h1>无底色 vs 白底 —— 同一块背景上的直接对照</h1>
  <p class="note">图形与线宽读自 ic_tool_*.xml（与真机同一份）；白色圆底 = 新增的「白底」样式。<br>
     第 1 行是重点：白卡片上两组应当<strong>完全一样</strong>，白底不可见。</p>
  <svg width="0" height="0" style="position:absolute"><defs>{grads}{defs}</defs></svg>
  {"".join(rows)}
</body></html>
"""
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    html_path = OUT_DIR / "preview.html"
    html_path.write_text(page, encoding="utf-8")

    png_path = OUT_DIR / "preview.png"
    subprocess.run(
        [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--window-size=880,1020",
            "--force-device-scale-factor=2",
            f"--screenshot={png_path}",
            "--virtual-time-budget=2500",
            html_path.as_uri(),
        ],
        check=False,
        capture_output=True,
    )
    print(html_path)
    print(png_path)


if __name__ == "__main__":
    main()
