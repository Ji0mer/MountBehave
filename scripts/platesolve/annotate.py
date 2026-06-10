"""Render a plate-solve onto the original frame: constellation lines, catalog stars,
matched detections, field centre/North, and a stats panel. Everything is projected
through the recovered camera model (pinhole), so the wide field is handled correctly."""
from __future__ import annotations
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from catalog import Catalog, load_constellation_lines
from solver import Solution, project

_CON_NAMES = {
    "UMa": "Ursa Major", "Leo": "Leo", "CVn": "Canes Venatici", "Boo": "Bootes",
    "Com": "Coma Berenices", "LMi": "Leo Minor", "Dra": "Draco", "Cnc": "Cancer",
    "Lyn": "Lynx", "Vir": "Virgo", "Hya": "Hydra", "Crv": "Corvus", "Crt": "Crater",
}


def _font(size):
    for path in (r"C:\Windows\Fonts\arialbd.ttf", r"C:\Windows\Fonts\arial.ttf"):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            pass
    return ImageFont.load_default()


def _radec_to_vec(ra_deg, dec_deg):
    ra = np.radians(ra_deg); dec = np.radians(dec_deg)
    return np.column_stack([np.cos(dec) * np.cos(ra), np.cos(dec) * np.sin(ra), np.sin(dec)])


def annotate(img: Image.Image, sol: Solution, cat: Catalog, det_xy, sky_mask=None,
             annot_mag=5.0, label_mag=3.0, out_path=None):
    W, H = img.size
    vis = img.convert("RGB").copy()
    if sky_mask is not None:
        arr = np.asarray(vis).copy()
        arr[~sky_mask] = (arr[~sky_mask] * 0.5).astype(np.uint8)  # dim masked foreground
        vis = Image.fromarray(arr)
    dr = ImageDraw.Draw(vis, "RGBA")
    f, cx, cy, R = sol.f_pix, sol.cx, sol.cy, sol.R
    margin = 0.10 * max(W, H)

    def visible(p, z):
        return z > 0 and -margin < p[0] < W + margin and -margin < p[1] < H + margin

    # --- constellation lines ---
    for cid, polys in load_constellation_lines():
        for seg in polys:
            v = _radec_to_vec(seg[:, 0], seg[:, 1])
            P, Z = project(v, R, cx, cy, f)
            for a in range(len(seg) - 1):
                if Z[a] > 0 and Z[a + 1] > 0 and (
                        visible(P[a], Z[a]) or visible(P[a + 1], Z[a + 1])):
                    dr.line([tuple(P[a]), tuple(P[a + 1])], fill=(80, 170, 255, 200), width=3)

    # --- catalog stars in frame ---
    sub = np.flatnonzero(cat.mag <= annot_mag)
    P, Z = project(cat.vec[sub], R, cx, cy, f)
    fnt = _font(34); fnt_small = _font(26)
    for li, gi in enumerate(sub):
        if not visible(P[li], Z[li]):
            continue
        mag = cat.mag[gi]
        r = max(3, 13 - 2.0 * mag)
        x, y = P[li]
        dr.ellipse([x - r, y - r, x + r, y + r], outline=(255, 255, 255, 230), width=2)
        if mag <= label_mag:
            lbl = cat.stars[gi].label()
            if lbl:
                dr.text((x + r + 3, y - r - 2), lbl, fill=(255, 240, 150, 255), font=fnt_small)

    # --- matched detections (confirmed) ---
    for di, gi in sol.matches:
        x, y = det_xy[di]
        dr.ellipse([x - 22, y - 22, x + 22, y + 22], outline=(60, 255, 90, 255), width=4)

    # --- field centre + North arrow ---
    bore = R.T @ np.array([0, 0, 1.0])
    north_pt = bore + 0.12 * (np.array([0, 0, 1.0]) - bore[2] * bore)
    north_pt /= np.linalg.norm(north_pt)
    Pc, Zc = project(np.array([bore, north_pt]), R, cx, cy, f)
    dr.line([(cx - 30, cy), (cx + 30, cy)], fill=(255, 80, 80, 255), width=3)
    dr.line([(cx, cy - 30), (cx, cy + 30)], fill=(255, 80, 80, 255), width=3)
    if Zc[1] > 0:
        dr.line([tuple(Pc[0]), tuple(Pc[1])], fill=(255, 80, 80, 255), width=4)
        dr.text(tuple(Pc[1]), "N", fill=(255, 80, 80, 255), font=fnt)

    # --- stats panel ---
    cons = sorted({cat.stars[gi].con for _, gi in sol.matches if cat.stars[gi].con})
    cons_full = ", ".join(_CON_NAMES.get(c, c) for c in cons)
    ra_h = sol.center_ra / 15.0
    lines = [
        f"Center  RA {int(ra_h):02d}h{int((ra_h%1)*60):02d}m  "
        f"Dec {sol.center_dec:+.1f}°",
        f"FOV  {sol.fov_w:.1f} x {sol.fov_h:.1f}°   roll {sol.roll_deg:.0f}°",
        f"focal {sol.f_pix:.0f}px   matched {len(sol.matches)} stars   rms {sol.rms_px:.1f}px",
        f"Region: {cons_full}",
    ]
    pad = 16
    th = 40 * len(lines) + pad
    dr.rectangle([0, 0, 1080, th], fill=(0, 0, 0, 150))
    for i, t in enumerate(lines):
        dr.text((pad, pad // 2 + i * 40), t, fill=(255, 255, 255, 255), font=fnt)

    if out_path:
        vis.save(out_path, quality=90)
    return vis
