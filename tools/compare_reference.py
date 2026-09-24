"""実機の録画（adb shell screenrecord）から島の寸法を 1 フレームずつ測り、Apple の見本と重ねる。

    python3 tools/compare_reference.py <録画.mp4> <test-data/reference-wwdc23-10194.csv> <出力フォルダ> [見本のフレーム画像フォルダ]

- 見本と同じ方法で測る: 背景と黒の中間の明るさを境目とし、島の中心を通る行・列で縁の位置を線形補間する
  （動きのぼけがあっても中点で打ち消される）。連結成分で本体と右の島を分ける
- 寸法は島の高さ H（待機時の高さ）で割ってそろえる。時刻は最初の「待機 → コンパクト」の動き出しで合わせる
- 出力: measured.csv（実機の値）、compare.png（重ねたグラフ）、report.txt（区間ごとの誤差）、
  見本のフレーム画像フォルダを渡したときは frames.png（同じ時刻のコマを並べたもの）
"""
import collections
import csv
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

CROP_H = 520  # 画面上端からこの高さだけ測る（展開した島まで入る）

# 見本の区間（670s からの秒）。どの遷移かの名前と、見る範囲
SEGMENTS = [
    ("待機→コンパクト", 0.80, 2.95),
    ("コンパクト→待機", 2.95, 3.33),
    ("待機→展開", 3.33, 5.55),
    ("展開→待機", 5.55, 6.25),
    ("待機→2つ同時", 6.25, 7.90),
]


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
         "-of", "csv=p=0", path], capture_output=True, text=True, check=True).stdout.strip()
    w, h = map(int, out.split(",")[:2])
    ts = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time",
         "-of", "csv=p=0", path], capture_output=True, text=True, check=True).stdout.split()
    return w, h, [float(t.strip(",")) for t in ts if t.strip(",")]


def frames(path, w, crop_h):
    p = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-fps_mode", "passthrough", "-vf", f"crop={w}:{crop_h}:0:0",
         "-f", "rawvideo", "-pix_fmt", "gray", "-"], stdout=subprocess.PIPE)
    n = w * crop_h
    while True:
        b = p.stdout.read(n)
        if len(b) < n:
            break
        yield np.frombuffer(b, np.uint8).reshape(crop_h, w).astype(np.float32)


def components(mask):
    h, w = mask.shape
    lab = np.zeros(mask.shape, np.int32)
    n = 0
    out = []
    for y0, x0 in zip(*np.nonzero(mask)):
        if lab[y0, x0]:
            continue
        n += 1
        q = collections.deque([(y0, x0)])
        lab[y0, x0] = n
        ys = [y0]; xs = [x0]
        while q:
            y, x = q.popleft()
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                yy, xx = y + dy, x + dx
                if 0 <= yy < h and 0 <= xx < w and mask[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    q.append((yy, xx)); ys.append(yy); xs.append(xx)
        out.append((len(ys), min(ys), min(xs), max(ys), max(xs)))
    return sorted(out, reverse=True)


def crossing(profile, mid, i0, step, limit):
    """i0 から step 向きに、明るさが mid を超える位置を線形補間で。limit（塊の外接 ±3px）を越えたら limit で止める"""
    i = i0
    while 0 <= i + step < len(profile) and profile[i + step] < mid and (i + step - limit) * step <= 0:
        i += step
    if (i - limit) * step >= 0 or not (0 <= i + step < len(profile)):
        return float(limit)
    a = profile[i]
    b = profile[i + step]
    frac = (mid - a) / (b - a) if b != a else 0.5
    return i + step * frac


def measure(g):
    bg = np.percentile(g, 75)
    dark = g < 60
    lo = np.percentile(g[dark], 10) if dark.any() else 0.0
    mid = (bg + lo) / 2
    mask = (g < mid)[::2, ::2]
    comps = [c for c in components(mask) if c[0] > 40]
    rec = {}
    for name, c in zip(("main", "bub"), comps[:2]):
        _, y0, x0, y1, x1 = c
        cy, cx = y0 + y1, x0 + x1
        row, col = g[cy, :], g[:, cx]
        left, right = crossing(row, mid, cx, -1, 2 * x0 - 3), crossing(row, mid, cx, +1, 2 * x1 + 4)
        top, bottom = crossing(col, mid, cy, -1, 2 * y0 - 3), crossing(col, mid, cy, +1, 2 * y1 + 4)
        rec[name] = (right - left, bottom - top, (left + right) / 2, (top + bottom) / 2)
    return rec


def load_reference(path):
    rows = [r for r in csv.reader(line for line in open(path) if not line.startswith("#"))]
    head = rows[0]
    out = []
    for r in rows[1:]:
        d = dict(zip(head, r))
        out.append({k: (float(v) if v else None) for k, v in d.items()})
    return out


def onset(ts, ws, base, after=0.0):
    for t, w in zip(ts, ws):
        if t >= after and w is not None and w > base * 1.02:
            return t
    return None


def main():
    rec_path, ref_path, outdir = sys.argv[1:4]
    ref_frames = sys.argv[4] if len(sys.argv) > 4 else None
    os.makedirs(outdir, exist_ok=True)
    w, _, times = probe(rec_path)
    ours = []
    for t, g in zip(times, frames(rec_path, w, CROP_H)):
        m = measure(g)
        ours.append((t, m))
    # 待機時の島（最初の 0.3 秒）の高さを H とする
    idle = [m["main"] for t, m in ours if "main" in m and t - ours[0][0] < 0.3]
    H = float(np.median([x[1] for x in idle]))
    Wi = float(np.median([x[0] for x in idle]))
    with open(os.path.join(outdir, "measured.csv"), "w") as f:
        f.write("t,main_w,main_h,main_cx,bub_w,bub_h,bub_cx\n")
        for t, m in ours:
            a = m.get("main"); b = m.get("bub")
            f.write(f"{t:.4f}," + (",".join(f"{v / H:.4f}" for v in (a[0], a[1], a[2])) if a else ",,") + "," +
                    (",".join(f"{v / H:.4f}" for v in (b[0], b[1], b[2])) if b else ",,") + "\n")

    ref = load_reference(ref_path)
    rt = [r["t"] for r in ref]
    # 時刻合わせ: 最初の動き出し
    ref_on = onset(rt, [r["main_w"] for r in ref], ref[0]["main_w"], 0.5)
    our_t = [t for t, _ in ours]
    our_w = [m["main"][0] / H if "main" in m else None for _, m in ours]
    our_on = onset(our_t, our_w, Wi / H)
    shift = ref_on - our_on
    ot = np.array(our_t) + shift

    def series(key, idx):
        return np.array([m[key][idx] / H if key in m else np.nan for _, m in ours])

    ours_s = {"main_w": series("main", 0), "main_h": series("main", 1), "bub_w": series("bub", 0)}
    lines = [f"H: 実機 {H:.1f}px / 見本 99.1px（= 37.33pt）  待機時の幅: 実機 {Wi / H:.3f}H / 見本 {ref[0]['main_w']:.3f}H",
             f"時刻合わせ: 見本の動き出し {ref_on:.3f}s ← 実機 {our_on:.3f}s", ""]
    # 区間ごとに ±0.15 秒の範囲で最も合う時刻のずれを探す（出来事の時刻は台本側の都合なので、動きそのものを比べる）
    seg_shift = {}
    for name, a, b in SEGMENTS:
        best = None
        for ds in np.arange(-0.15, 0.1501, 0.004):
            e = 0.0; n = 0
            for key in ("main_w", "main_h"):
                rs = [(r["t"], r[key]) for r in ref if a <= r["t"] <= b and r[key] is not None]
                sel = ~np.isnan(ours_s[key])
                ov = np.interp([t for t, _ in rs], ot[sel] + ds, ours_s[key][sel])
                e += ((ov - np.array([v for _, v in rs])) ** 2).sum(); n += len(rs)
            if best is None or e / n < best[0]:
                best = (e / n, ds)
        seg_shift[name] = best[1]
        for key in ("main_w", "main_h"):
            rs = [(r["t"], r[key]) for r in ref if a <= r["t"] <= b and r[key] is not None]
            if not rs:
                continue
            sel = ~np.isnan(ours_s[key])
            ov = np.interp([t for t, _ in rs], ot[sel] + best[1], ours_s[key][sel])
            diff = ov - np.array([v for _, v in rs])
            lines.append(f"{name:10s} {key}: RMS {math.sqrt((diff ** 2).mean()) * 37.33:5.2f}pt  最大 {abs(diff).max() * 37.33:5.2f}pt"
                         f"  （時刻のずれ {best[1] * 1000:+.0f}ms）")
    report = "\n".join(lines)
    open(os.path.join(outdir, "report.txt"), "w").write(report + "\n")
    print(report)

    # グラフ
    W_, H_ = 1800, 820
    pad = 60
    im = Image.new("RGB", (W_, H_), "white")
    d = ImageDraw.Draw(im)
    t0, t1 = 0.5, 8.2
    vmax = 10.5

    def X(t): return pad + (t - t0) / (t1 - t0) * (W_ - 2 * pad)
    def Y(v): return H_ - pad - v / vmax * (H_ - 2 * pad)
    for s in np.arange(1, 9):
        d.line([(X(s), pad), (X(s), H_ - pad)], fill=(232, 232, 232))
        d.text((X(s) + 2, H_ - pad + 6), f"{s:.0f}s", fill="black")
    for v in range(0, 11):
        d.line([(pad, Y(v)), (W_ - pad, Y(v))], fill=(242, 242, 242))
        d.text((8, Y(v) - 6), f"{v}H", fill="gray")
    for _, a, b in SEGMENTS:
        d.line([(X(a), pad), (X(a), H_ - pad)], fill=(255, 210, 210))
    colors = {"main_w": ((200, 30, 30), (255, 150, 150)), "main_h": ((30, 70, 210), (140, 170, 255)),
              "bub_w": ((20, 140, 50), (140, 220, 150))}
    for key, (cr, co) in colors.items():
        for t, v in zip(ot, ours_s[key]):
            if t0 <= t <= t1 and not np.isnan(v):
                d.ellipse([X(t) - 2, Y(v) - 2, X(t) + 2, Y(v) + 2], outline=co)
        for r in ref:
            if r[key] is not None and t0 <= r["t"] <= t1:
                d.ellipse([X(r["t"]) - 1.6, Y(r[key]) - 1.6, X(r["t"]) + 1.6, Y(r[key]) + 1.6], fill=cr)
    d.text((W_ - 620, 12), "filled = Apple reference / open = Island on device   red: width  blue: height  green: detached width  (unit: H)", fill="black")
    im.save(os.path.join(outdir, "compare.png"))

    if ref_frames:
        sheet(rec_path, ours, ot, H, ref, ref_frames, outdir, w)


def sheet(rec_path, ours, ot, H, ref, ref_dir, outdir, w):
    """同じ時刻の見本と実機のコマを並べる（見本 1 コアおき）"""
    picks = []
    for _, a, b in SEGMENTS:
        picks += [r for r in ref if a <= r["t"] <= min(b, a + 0.8)][::2]
    # 実機の該当フレームを取り出す
    want = {}
    for r in picks:
        i = int(np.argmin(abs(ot - r["t"])))
        want[i] = r
    ours_imgs = {}
    for i, g in enumerate(frames(rec_path, w, CROP_H)):
        if i in want:
            ours_imgs[i] = g
    scale = H / 99.1
    cell_w, cell_h = 560, 170
    rows = []
    for i, r in sorted(want.items(), key=lambda kv: kv[1]["t"]):
        k = int(round(r["t"] * 30000 / 1001))
        rp = os.path.join(ref_dir, f"f{k + 1:04d}.png")
        if not os.path.exists(rp):
            continue
        ri = Image.open(rp).convert("L")
        ri = ri.resize((int(ri.width * scale), int(ri.height * scale)))
        cx = (r["main_cx"] or 7.7) * 99.1 * scale
        cy_ref = 401 * scale if r["t"] < 3.3 or r["t"] > 5.9 else 352 * scale + (r["main_h"] or 1) * 99.1 * scale / 2
        top_ref = 352 * scale - 20
        a = ri.crop((int(cx - cell_w / 2), int(top_ref), int(cx + cell_w / 2), int(top_ref + cell_h)))
        m = ours[i][1]
        ocx = w / 2
        g = Image.fromarray(ours_imgs[i].astype(np.uint8))
        otop = (m["main"][3] - m["main"][1] / 2 if "main" in m else 20) - 20
        b = g.crop((int(ocx - cell_w / 2), int(max(0, otop)), int(ocx + cell_w / 2), int(max(0, otop) + cell_h)))
        rows.append((r["t"], a, b))
    if not rows:
        return
    out = Image.new("L", (cell_w * 2 + 90, cell_h * len(rows)), 255)
    d = ImageDraw.Draw(out)
    for n, (t, a, b) in enumerate(rows):
        out.paste(a, (90, n * cell_h))
        out.paste(b, (90 + cell_w, n * cell_h))
        d.text((4, n * cell_h + 4), f"{t:.3f}s", fill=0)
    d.text((100, 2), "Apple", fill=0)
    d.text((100 + cell_w, 2), "Island", fill=0)
    out.save(os.path.join(outdir, "frames.png"))


if __name__ == "__main__":
    main()
