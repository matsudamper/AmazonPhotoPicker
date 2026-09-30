#!/usr/bin/env python3
"""Paparazzi のスナップショット画像を 1 枚の自己完結 HTML にまとめる。"""
import base64
import html
import sys
from pathlib import Path

images_dir = Path(sys.argv[1])
output_path = Path(sys.argv[2])

cards = []
for image in sorted(images_dir.rglob("*.png")):
    encoded = base64.b64encode(image.read_bytes()).decode("ascii")
    name = html.escape(image.relative_to(images_dir).as_posix())
    cards.append(
        f'<figure><img loading="lazy" src="data:image/png;base64,{encoded}" alt="{name}">'
        f"<figcaption>{name}</figcaption></figure>"
    )

output_path.write_text(
    "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
    "<title>Paparazzi snapshots</title><style>"
    "body{font-family:sans-serif;margin:16px;background:#f5f5f5}"
    ".grid{display:flex;flex-wrap:wrap;gap:16px}"
    "figure{margin:0;background:#fff;padding:8px;border:1px solid #ddd;max-width:360px}"
    "img{max-width:100%;height:auto;display:block}"
    "figcaption{font-size:12px;word-break:break-all;margin-top:4px}"
    f"</style></head><body><h1>Paparazzi snapshots ({len(cards)})</h1>"
    f'<div class="grid">{"".join(cards)}</div></body></html>',
    encoding="utf-8",
)
print(f"{len(cards)} images -> {output_path}")
