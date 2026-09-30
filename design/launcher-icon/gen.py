import math, os, json
OUT = "/home/user/AmazonPhotoPicker/design/launcher-icon"

def rrect(x0, y0, x1, y1, r):
    return (f"M{x0+r},{y0} H{x1-r} A{r},{r} 0 0 1 {x1},{y0+r} V{y1-r} A{r},{r} 0 0 1 {x1-r},{y1} "
            f"H{x0+r} A{r},{r} 0 0 1 {x0},{y1-r} V{y0+r} A{r},{r} 0 0 1 {x0+r},{y0} Z")

def circle(cx, cy, r):
    return f"M{cx-r},{cy} A{r},{r} 0 1 0 {cx+r},{cy} A{r},{r} 0 1 0 {cx-r},{cy} Z"

def fmt(v): return f"{v:.2f}".rstrip("0").rstrip(".")

def check(cx, cy, s, w):
    """太さ w のチェックマーク（ポリゴン）。s は大きさ。"""
    A = (cx - 0.46*s, cy + 0.02*s); B = (cx - 0.14*s, cy + 0.34*s); C = (cx + 0.48*s, cy - 0.28*s)
    h = w / 2
    def n(p, q):
        dx, dy = q[0]-p[0], q[1]-p[1]; l = math.hypot(dx, dy); return (-dy/l, dx/l)
    n1, n2 = n(A, B), n(B, C)
    m = (n1[0]+n2[0], n1[1]+n2[1]); ml = math.hypot(*m); m = (m[0]/ml, m[1]/ml)
    k = h / (m[0]*n1[0] + m[1]*n1[1])
    pts = [(A[0]+n1[0]*h, A[1]+n1[1]*h), (B[0]+m[0]*k, B[1]+m[1]*k), (C[0]+n2[0]*h, C[1]+n2[1]*h),
           (C[0]-n2[0]*h, C[1]-n2[1]*h), (B[0]-m[0]*k, B[1]-m[1]*k), (A[0]-n1[0]*h, A[1]-n1[1]*h)]
    return "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in pts) + " Z"

def P(d, fill=None, alpha=1.0, evenodd=False, stroke=None, sw=0, cap="round"):
    return dict(d=d, fill=fill, alpha=alpha, evenodd=evenodd, stroke=stroke, sw=sw, cap=cap)

FULL = "M0,0 H108 V108 H0 Z"
MONO = "#000000"
patterns = []

# ---------- A: フォトカード + チェック ----------
bgA = "#2F5BEA"
bx, by = 72, 68
patterns.append(dict(
    id="photo-check", name="Photo Check", ja="写真カード＋選択チェック",
    bg=[P(FULL, bgA)],
    fg=[P(rrect(32, 36, 76, 70, 5), "#FFFFFF"),
        P(rrect(35, 39, 73, 67, 1.5), "#DCE6FF"),
        P("M35,67 L46,52 L53,60 L58,55 L69,67 Z", bgA),
        P(circle(64, 46, 3.5), "#FFB020"),
        P(circle(bx, by, 11.5), bgA),
        P(circle(bx, by, 8.5), "#FF7A1A"),
        P(check(bx, by, 10, 2.4), "#FFFFFF")],
    mono=[P("M74.5,55 V41.5 A5,5 0 0 0 69.5,36.5 H38.5 A5,5 0 0 0 33.5,41.5 V63.5 A5,5 0 0 0 38.5,68.5 H59",
            None, stroke=MONO, sw=3),
          P("M38,65 L46,53.5 L52,60.5 L55.5,57 L59,61 V65 Z", MONO),
          P(circle(64, 46, 3.5), MONO),
          P(circle(bx, by, 8.5) + " " + check(bx, by, 10, 2.4), MONO, evenodd=True)],
))

# ---------- B: クラウド + 写真 ----------
bgB = "#0B7A75"
cloud = "M38,68 H68 A9,9 0 0 0 70,50 A15,15 0 0 0 42,48 A10.2,10.2 0 0 0 38,68 Z"
mtn = "M43,65 L51,54 L56,60 L59.5,56.5 L66,65 Z"
sun = circle(61, 50, 3)
patterns.append(dict(
    id="cloud-photo", name="Cloud Photo", ja="クラウド＋写真",
    bg=[P(FULL, bgB), P(circle(54, 118, 64), "#0E8F88")],
    fg=[P(cloud, "#FFFFFF"), P(mtn, bgB), P(sun, "#FFC24B")],
    mono=[P(cloud + " " + mtn + " " + sun, MONO, evenodd=True)],
))

# ---------- C: グリッド選択 ----------
bgC = "#1D2740"
t = [(34, 34), (56, 34), (34, 56), (56, 56)]
def tile(i, inset=0): x, y = t[i]; return rrect(x+inset, y+inset, x+18-inset, y+18-inset, 4-inset)
patterns.append(dict(
    id="grid-select", name="Grid Select", ja="写真グリッド＋1枚選択",
    bg=[P(FULL, bgC)],
    fg=[P(tile(0), "#5E7FD9"), P(tile(1), "#86A4F2"), P(tile(2), "#86A4F2"),
        P(tile(3), "#FFB547"), P(check(65, 65, 11, 2.6), bgC)],
    mono=[P(tile(0) + " " + tile(0, 2.5), MONO, evenodd=True),
          P(tile(1) + " " + tile(1, 2.5), MONO, evenodd=True),
          P(tile(2) + " " + tile(2, 2.5), MONO, evenodd=True),
          P(tile(3) + " " + check(65, 65, 11, 2.6), MONO, evenodd=True)],
))


# ---------- D: 段ボール箱から写真 ----------
NAVY, ORANGE = "#232F3E", "#FF9900"
patterns.append(dict(
    id="box-photo", name="Box Photo", ja="配送箱から出てくる写真（ネイビー×オレンジ）",
    bg=[P(FULL, NAVY)],
    fg=[P(rrect(40, 30, 68, 60, 3), "#FFFFFF"),
        P(rrect(43, 33, 65, 57, 1), "#DCE6FF"),
        P("M43,57 L51,44 L56,50 L59.5,46.5 L65,53 V57 Z", "#37475A"),
        P(circle(59.5, 38.5, 3), ORANGE),
        P("M33,56 L26,48 L42,48 L47,56 Z", "#A8713A"),
        P("M75,56 L82,48 L66,48 L61,56 Z", "#A8713A"),
        P(rrect(33, 56, 75, 77, 2), "#C98D52"),
        P("M33,62 H75 V64.5 H33 Z", ORANGE)],
    mono=[P("M41.5,45 V34.5 A3,3 0 0 1 44.5,31.5 H63.5 A3,3 0 0 1 66.5,34.5 V45", None, stroke=MONO, sw=3),
          P("M45,45 L51,37.5 L55,42 L58,39 L63,45 Z", MONO),
          P(circle(59.5, 35.5, 2.2), MONO),
          P("M33,56 L26,48 L42,48 L47,56 Z", MONO),
          P("M75,56 L82,48 L66,48 L61,56 Z", MONO),
          P(rrect(33, 58, 75, 77, 2) + " M36,62 H72 V64.5 H36 Z", MONO, evenodd=True)],
))

# ---------- E: 写真カード＋チェック（Amazon 系配色） ----------
patterns.append(dict(
    id="photo-check-navy", name="Photo Check Navy", ja="案Aをネイビー×オレンジ配色に",
    bg=[P(FULL, NAVY)],
    fg=[P(rrect(32, 36, 76, 70, 5), "#FFFFFF"),
        P(rrect(35, 39, 73, 67, 1.5), "#E3EAF3"),
        P("M35,67 L46,52 L53,60 L58,55 L69,67 Z", "#37475A"),
        P(circle(64, 46, 3.5), ORANGE),
        P(circle(bx, by, 11.5), NAVY),
        P(circle(bx, by, 8.5), ORANGE),
        P(check(bx, by, 10, 2.4), NAVY)],
    mono=patterns[0]["mono"],
))

# ---------- F: 頭文字 A ＝ 山 ----------
A_OUT = "M54,31 L77,75 H66 L54,50 L42,75 H31 Z"
A_BAR = "M45,62 H63 V67.5 H45 Z"
patterns.append(dict(
    id="letter-a", name="Letter A", ja="頭文字「A」を写真の山に見立てる",
    bg=[P(FULL, NAVY)],
    fg=[P(A_OUT, ORANGE), P(A_BAR, ORANGE), P(circle(71, 40, 4.5), "#FFFFFF")],
    mono=[P(A_OUT, MONO), P(A_BAR, MONO), P(circle(71, 40, 4.5), MONO)],
))

# ---------- 出力 ----------
def svg_paths(paths, color=None):
    out = []
    for p in paths:
        a = [f'd="{p["d"]}"']
        if p["stroke"]:
            a += ['fill="none"', f'stroke="{color or p["stroke"]}"', f'stroke-width="{p["sw"]}"',
                  f'stroke-linecap="{p["cap"]}"', 'stroke-linejoin="round"']
        else:
            a.append(f'fill="{color or p["fill"]}"')
            if p["evenodd"]: a.append('fill-rule="evenodd"')
        if p["alpha"] != 1: a.append(f'fill-opacity="{p["alpha"]}"')
        out.append("<path " + " ".join(a) + "/>")
    return "".join(out)

def vd(paths):
    s = ['<?xml version="1.0" encoding="utf-8"?>',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
         '    android:width="108dp"', '    android:height="108dp"',
         '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for p in paths:
        s.append("    <path")
        if p["stroke"]:
            s += [f'        android:strokeColor="{p["stroke"]}"', f'        android:strokeWidth="{p["sw"]}"',
                  f'        android:strokeLineCap="{p["cap"]}"', '        android:strokeLineJoin="round"']
        else:
            s.append(f'        android:fillColor="{p["fill"]}"')
            if p["evenodd"]: s.append('        android:fillType="evenOdd"')
        if p["alpha"] != 1: s.append(f'        android:fillAlpha="{p["alpha"]}"')
        s.append(f'        android:pathData="{p["d"]}" />')
    s.append("</vector>")
    return "\n".join(s) + "\n"

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

meta = []
for p in patterns:
    d = os.path.join(OUT, p["id"])
    res = os.path.join(d, "res")
    os.makedirs(os.path.join(res, "drawable"), exist_ok=True)
    os.makedirs(os.path.join(res, "mipmap-anydpi-v26"), exist_ok=True)
    os.makedirs(os.path.join(d, "svg"), exist_ok=True)
    for layer in ("bg", "fg", "mono"):
        name = {"bg": "background", "fg": "foreground", "mono": "monochrome"}[layer]
        open(os.path.join(res, "drawable", f"ic_launcher_{name}.xml"), "w").write(vd(p[layer]))
        open(os.path.join(d, "svg", f"{name}.svg"), "w").write(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">{svg_paths(p[layer])}</svg>\n')
    for n in ("ic_launcher", "ic_launcher_round"):
        open(os.path.join(res, "mipmap-anydpi-v26", f"{n}.xml"), "w").write(ADAPTIVE)
    meta.append(dict(id=p["id"], name=p["name"], ja=p["ja"],
                     bg=svg_paths(p["bg"]), fg=svg_paths(p["fg"]),
                     mono=svg_paths(p["mono"], "currentColor")))
json.dump(meta, open("meta.json", "w"), ensure_ascii=False)
