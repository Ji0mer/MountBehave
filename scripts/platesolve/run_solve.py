"""Detect + blind-solve both phone frames; print the recovered sky region."""
import sys
import numpy as np
from PIL import Image
from star_detector import detect_local
from catalog import load_catalog
from solver import solve, project, pix_to_ray, TriangleIndex
from annotate import annotate

# Huawei P30 Pro main camera (~27mm equiv) on the 3648x2736 (4:3) frame.
F_PIX_NOMINAL = 2845.0

FRAMES = {"img_05s": "work/img_05s.jpg", "img_1s": "work/img_1s.jpg"}


def star_candidates(dets, min_px=3, max_px=60, min_snr=5.0, min_sep=8.0):
    """Keep compact, high-peak sources (real stars) and drop diffuse blobs (foreground
    residue / sky-glow patches). Rank by PEAK, not integrated flux."""
    good = [d for d in dets if min_px <= d.pixel_count <= max_px and d.snr >= min_snr]
    good.sort(key=lambda d: d.peak, reverse=True)
    out = []
    for d in good:
        if all((d.x - o.x) ** 2 + (d.y - o.y) ** 2 > min_sep ** 2 for o in out):
            out.append(d)
    return out


def to_hms(ra):
    h = ra / 15.0
    hh = int(h); mm = int((h - hh) * 60); ss = (h - hh - mm / 60) * 3600
    return f"{hh:02d}h{mm:02d}m{ss:04.1f}s"


def to_dms(dec):
    s = "+" if dec >= 0 else "-"
    dec = abs(dec); dd = int(dec); mm = int((dec - dd) * 60); ss = (dec - dd - mm / 60) * 3600
    return f"{s}{dd:02d}d{mm:02d}m{ss:04.1f}s"


def main():
    mag_limit = float(sys.argv[1]) if len(sys.argv) > 1 else 5.5
    cat = load_catalog(mag_limit)
    tri = TriangleIndex(cat)
    print(f"catalog: {len(cat)} stars to mag {mag_limit}; {len(tri)} seed triangles")
    for name, path in FRAMES.items():
        img = Image.open(path)
        W, H = img.size
        cx, cy = W / 2.0, H / 2.0
        res, mask = detect_local(img, threshold_sigma=4.5, fg_dilate=2)
        dets = star_candidates(res.stars)
        det_xy = np.array([[d.x, d.y] for d in dets])
        det_w = np.array([d.peak for d in dets])
        print(f"\n=== {name} === {W}x{H}, {len(dets)} compact star candidates "
              f"(of {res.detection_count} raw, noise {res.noise:.2f})")
        sol = solve(det_xy, det_w, cat, F_PIX_NOMINAL, cx, cy, tri=tri,
                    n_det=14, min_matches=8, verbose=True)
        if sol is None:
            print("  NO SOLUTION")
            continue
        print(f"  SOLVED: center RA {to_hms(sol.center_ra)} ({sol.center_ra:.3f} deg)  "
              f"Dec {to_dms(sol.center_dec)} ({sol.center_dec:.3f} deg)")
        print(f"  FOV {sol.fov_w:.1f} x {sol.fov_h:.1f} deg, roll {sol.roll_deg:.1f} deg, "
              f"f={sol.f_pix:.0f}px ({F_PIX_NOMINAL:.0f} nominal)")
        print(f"  matched {len(sol.matches)} stars, rms {sol.rms_px:.1f}px")
        # name the matched bright stars
        named = sorted(sol.matches, key=lambda m: cat.stars[m[1]].mag)[:10]
        for di, ci in named:
            s = cat.stars[ci]
            print(f"    mag {s.mag:5.2f}  {s.con:<3} {s.label():<16} "
                  f"@det({det_xy[di,0]:.0f},{det_xy[di,1]:.0f})")
        out = f"work/{name}_solved.jpg"
        annotate(img, sol, cat, det_xy, sky_mask=mask, out_path=out)
        print(f"  -> annotated {out}")


if __name__ == "__main__":
    main()
