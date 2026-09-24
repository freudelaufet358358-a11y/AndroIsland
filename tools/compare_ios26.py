"""実機の録画から島の寸法を 1 コマずつ測り、iOS 26 の見本（test-data/reference-ios26-short.csv）と遷移ごとに比べる。

    python3 tools/compare_ios26.py <録画.mp4> <test-data/reference-ios26-short.csv> <出力フォルダ>

- 測り方は compare_reference.py と同じ（背景と黒の中間の明るさで縁を線形補間）
- 見本の iPhone と Pixel では画面に対する島の比率が違う（Beta 5 は幅 11.1H、この端末は 10.0H）ので、
  遷移ごとに「動き出す前の値 → 止まった値」を 0 → 1 とした進み具合で比べる。誤差は見本の動いた量を掛けて pt に直す
- 実機の遷移は refseq26 の順（待機→展開、展開→待機、待機→コンパクト、コンパクト→展開、展開→待機）。
  動き出しは前の遷移から 0.8 秒以上あとで、幅か高さが 2% 以上変わった最初のコマ。区間ごとに ±0.1 秒で時刻を合わせる
- 出力: measured.csv、compare.png（進み具合を重ねたグラフ）、report.txt
"""
import csv
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
from compare_reference import CROP_H, frames, probe  # noqa: E402


def measure_box(g):
    """島の外形（中身の文字やボタンがあっても切れないように、暗い画素の外接矩形）。幅・高さ（px）"""
    bg = np.percentile(g, 75)
    dark = g < 60
    if not dark.any():
        return None
    lo = np.percentile(g[dark], 10)
    mask = g < (bg + lo) / 2
    # screenrecord を縮めると画面の端に 1px の暗い線が出るので、端の 2px は見ない
    mask[:, :2] = False
    mask[:, -2:] = False
    # 点のようなゴミを除く（行・列に 3 画素以上）
    rows = np.where(mask.sum(1) >= 3)[0]
    cols = np.where(mask.sum(0) >= 3)[0]
    if len(rows) == 0 or len(cols) == 0:
        return None
    return cols[-1] - cols[0] + 1, rows[-1] - rows[0] + 1

ORDER = ["待機→展開", "展開→待機", "待機→コンパクト", "コンパクト→展開", "展開→待機(b4)"]
# 実機の何番目の遷移と比べるか（展開→待機 は 2 回ある: 電池残量低下のあとと、コンパクトを展開したあと）
DEVICE_INDEX = {"待機→展開": 0, "展開→待機": 1, "待機→コンパクト": 2, "コンパクト→展開": 3, "展開→待機(b4)": 4}
WINDOW = 0.9  # 見る長さ（秒）
# 見本の幅が比べられない区間: コンパクト→展開は長押しで膨らんだ幅から動き出す（初速があり、動く量も 0.7H と小さい）。
# Beta 4 の展開→待機は、終わりに録画中の赤い点が出て幅が広がる
SKIP = {("コンパクト→展開", "幅"), ("展開→待機(b4)", "幅")}


def load(path):
    segs = {}
    for r in csv.DictReader(line for line in open(path) if not line.startswith("#")):
        segs.setdefault(r["segment"], []).append((float(r["t"]), float(r["w"]), float(r["h"])))
    return segs


def hold(t, ts, vs):
    """screenrecord は画面が変わったときだけコマを出すので、コマの間は直前の値のまま（線形補間しない）"""
    i = np.clip(np.searchsorted(ts, t, side="right") - 1, 0, len(ts) - 1)
    return vs[i]


def at(t, ts, vs):
    """時刻 t に見えていた値（t 以前で測れた最後のコマ）"""
    ok = (ts <= t) & ~np.isnan(vs)
    return vs[ok][-1] if ok.any() else np.nan


def progress(v, a, b):
    return (v - a) / (b - a) if abs(b - a) > 1e-6 else np.zeros_like(v)


def main():
    rec, ref_path, outdir = sys.argv[1:4]
    os.makedirs(outdir, exist_ok=True)
    w, _, times = probe(rec)
    t_all, W, Hh = [], [], []
    for t, g in zip(times, frames(rec, w, CROP_H)):
        m = measure_box(g)
        t_all.append(t)
        W.append(m[0] if m else np.nan)
        Hh.append(m[1] if m else np.nan)
    t_all = np.array(t_all); W = np.array(W, float); Hh = np.array(Hh, float)
    H = float(np.nanmedian(Hh[t_all - t_all[0] < 0.3]))
    W /= H; Hh /= H
    with open(os.path.join(outdir, "measured.csv"), "w") as f:
        f.write("t,w,h\n")
        for t, a, b in zip(t_all, W, Hh):
            f.write(f"{t:.4f},{a:.4f},{b:.4f}\n")

    # 実機の遷移の動き出し
    onsets = []
    last = -1e9
    i = 3
    while i < len(t_all) and len(onsets) < 5:
        if t_all[i] - last > 0.8:
            j = max(0, i - 3)
            if (abs(W[i] - W[j]) > 0.02 * W[j]) or (abs(Hh[i] - Hh[j]) > 0.02 * Hh[j]):
                onsets.append(t_all[j + 1])
                last = t_all[i]
        i += 1

    ref = load(ref_path)
    lines = [f"H: 実機 {H:.1f}px。動き出し: " + ", ".join(f"{o - t_all[0]:.2f}s" for o in onsets), ""]
    img = Image.new("RGB", (1500, 280 * len(ORDER)), "white")
    d = ImageDraw.Draw(img)
    for n, name in enumerate(ORDER):
        rs = ref.get(name)
        k = DEVICE_INDEX[name]
        if not rs or k >= len(onsets):
            lines.append(f"{name}: 実機の遷移が見つからない")
            continue
        rt = np.array([r[0] for r in rs]); rw = np.array([r[1] for r in rs]); rh = np.array([r[2] for r in rs])
        # 見本の動き出し（最初のコマから 2% 変わったところ）
        r_on = next((t for t, a, b in rs if abs(a - rw[0]) > 0.02 * rw[0] or abs(b - rh[0]) > 0.02 * rh[0]), rt[0])
        on = onsets[k]
        sel = (t_all >= on - 0.2) & (t_all <= on + WINDOW + 0.3)
        ot = t_all[sel] - on + r_on
        ow = W[sel]; oh = Hh[sel]
        # 実機の始まりと終わりの値
        ow0, oh0 = at(on - 0.01, t_all, W), at(on - 0.01, t_all, Hh)
        ow1, oh1 = at(on + WINDOW, t_all, W), at(on + WINDOW, t_all, Hh)
        rw0, rh0, rw1, rh1 = rw[0], rh[0], np.median(rw[-4:]), np.median(rh[-4:])
        res = []
        best = None
        for ds in np.arange(-0.1, 0.1001, 0.004):
            e = 0.0
            for (label, rv, a, b, ov, oa, ob) in (("幅", rw, rw0, rw1, ow, ow0, ow1), ("高さ", rh, rh0, rh1, oh, oh0, oh1)):
                if abs(b - a) < 0.1 or abs(ob - oa) < 0.1 or (name, label) in SKIP:
                    continue
                pv = hold(rt, ot + ds, progress(ov, oa, ob))
                e += ((pv - progress(rv, a, b)) ** 2).sum()
            if best is None or e < best[0]:
                best = (e, ds)
        ds = best[1]
        for label, rv, a, b, ov, oa, ob in (("幅", rw, rw0, rw1, ow, ow0, ow1), ("高さ", rh, rh0, rh1, oh, oh0, oh1)):
            if abs(b - a) < 0.1 or abs(ob - oa) < 0.1 or (name, label) in SKIP:
                continue
            pr = progress(rv, a, b)
            po = hold(rt, ot + ds, progress(ov, oa, ob))
            diff = po - pr
            rms = math.sqrt((diff ** 2).mean())
            over_r = (pr.max() - 1) * 100
            over_o = (np.nanmax(progress(ov, oa, ob)) - 1) * 100
            res.append(f"{label} RMS {rms * 100:4.1f}%（{rms * abs(b - a) * 37.33:4.1f}pt） 行き過ぎ 見本 {over_r:+.1f}% / 実機 {over_o:+.1f}%")
            # グラフ
            y0 = n * 280 + 20
            col = (200, 30, 30) if label == "幅" else (30, 70, 210)
            X = lambda t: 60 + t / (WINDOW + 0.1) * 1400
            Y = lambda p: y0 + 220 - p * 180
            for t, p in zip(rt, pr):
                d.ellipse([X(t) - 3, Y(p) - 3, X(t) + 3, Y(p) + 3], fill=col)
            for t, p in zip(ot + ds, progress(ov, oa, ob)):
                if 0 <= t <= WINDOW + 0.1 and not np.isnan(p):
                    d.ellipse([X(t) - 3, Y(p) - 3, X(t) + 3, Y(p) + 3], outline=col)
            d.line([(60, Y(1)), (1460, Y(1))], fill=(220, 220, 220))
            d.line([(60, Y(0)), (1460, Y(0))], fill=(220, 220, 220))
        d.text((64, n * 280 + 4), f"{name}  (filled = iOS 26, open = Island, red = width, blue = height)", fill="black")
        lines.append(f"{name:14s} 時刻のずれ {ds * 1000:+.0f}ms  " + " / ".join(res))
    report = "\n".join(lines)
    open(os.path.join(outdir, "report.txt"), "w").write(report + "\n")
    img.save(os.path.join(outdir, "compare.png"))
    print(report)


if __name__ == "__main__":
    main()
