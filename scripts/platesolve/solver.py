"""Blind plate solver for wide-field phone frames, given an approximate focal length.

Detections -> camera-frame rays (pinhole) -> geometric voting against catalog star
pairs (angular distance is rotation/translation invariant) -> RANSAC seed of the
celestial->camera rotation from high-vote correspondences -> verify by projecting the
whole catalog and counting inliers -> refine rotation (Wahba/SVD) and focal length.

The approximate scale (Huawei P30 Pro main cam, ~27mm-equiv) makes this tractable and
robust: foreground false detections simply never accumulate consistent votes.
"""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Tuple, Optional
import numpy as np
from catalog import Catalog


@dataclass
class Solution:
    R: np.ndarray              # celestial -> camera rotation (3x3), cam = R @ c
    f_pix: float               # focal length, full-res pixels
    cx: float
    cy: float
    center_ra: float           # boresight RA/Dec (deg)
    center_dec: float
    roll_deg: float            # camera "up" position angle (deg E of N)
    fov_w: float               # horizontal FOV (deg)
    fov_h: float
    matches: List[Tuple[int, int]] = field(default_factory=list)  # (det_idx, cat_idx)
    rms_px: float = 0.0
    det_xy: np.ndarray = None  # (N,2) detected pixel coords used


def pix_to_ray(xy, cx, cy, f):
    """(N,2) pixels -> (N,3) unit rays in camera frame (image x right, y down, +z fwd)."""
    x = (xy[:, 0] - cx) / f
    y = (xy[:, 1] - cy) / f
    z = np.ones_like(x)
    v = np.column_stack([x, y, z])
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def project(cat_vec, R, cx, cy, f):
    """Celestial unit vectors (N,3) -> pixel coords; z<=0 (behind) -> nan."""
    cam = cat_vec @ R.T
    z = cam[:, 2]
    px = np.where(z > 1e-6, cx + f * cam[:, 0] / z, np.nan)
    py = np.where(z > 1e-6, cy + f * cam[:, 1] / z, np.nan)
    return np.column_stack([px, py]), z


def wahba(d, c, w=None):
    """Rotation R (cel->cam) minimizing sum w|d - R c|, d&c are (N,3) unit vectors."""
    if w is None:
        w = np.ones(len(d))
    B = (d * w[:, None]).T @ c
    U, _, Vt = np.linalg.svd(B)
    M = np.diag([1.0, 1.0, np.sign(np.linalg.det(U) * np.linalg.det(Vt))])
    return U @ M @ Vt


class TriangleIndex:
    """Scale-invariant shape index of bright catalog-star triangles, for seeding."""
    def __init__(self, cat: Catalog, seed_mag=3.2, max_side_deg=75.0, min_side_deg=3.0):
        self.bright = np.flatnonzero(cat.mag <= seed_mag)
        bvec = cat.vec[self.bright]
        n = len(self.bright)
        # pairwise angles among bright stars
        dots = np.clip(bvec @ bvec.T, -1, 1)
        ang = np.arccos(dots)
        shapes, sides, verts = [], [], []
        smax, smin = np.radians(max_side_deg), np.radians(min_side_deg)
        for i in range(n):
            for j in range(i + 1, n):
                aij = ang[i, j]
                if aij > smax or aij < smin:
                    continue
                for k in range(j + 1, n):
                    s = (ang[j, k], ang[i, k], aij)  # opposite vertex i,j,k
                    hi, lo = max(s), min(s)
                    if hi > smax or lo < smin:
                        continue
                    so = np.argsort(s)            # ascending side order
                    ss = np.array(s)[so]
                    shapes.append((ss[0] / ss[2], ss[1] / ss[2]))
                    sides.append(ss)
                    tri = (self.bright[i], self.bright[j], self.bright[k])
                    verts.append([tri[so[0]], tri[so[1]], tri[so[2]]])
        self.shapes = np.array(shapes)
        self.sides = np.array(sides)         # ascending angular sides
        self.verts = np.array(verts)         # global star idx, side-ascending order

    def __len__(self):
        return len(self.shapes)


def _tri_sides_px(pts):
    return np.array([np.linalg.norm(pts[1] - pts[2]),
                     np.linalg.norm(pts[0] - pts[2]),
                     np.linalg.norm(pts[0] - pts[1])])


def solve(det_xy, det_weight, cat: Catalog, f_pix, cx, cy, tri: 'TriangleIndex' = None,
          n_det=14, tol_shape=0.015, f_window=0.2, seed_tol_px=None,
          min_matches=8, max_rms=6.0, verbose=False):
    """Blind solve via triangle-shape matching (f-independent) + a scale gate vs the
    focal prior, then deep-catalog refine. Returns accepted Solution or None.

    `tri` is a prebuilt TriangleIndex (build once, reuse across frames). `n_det` bright
    compact detections form the seed triangles -- keep small, they should be real stars.
    """
    if tri is None:
        tri = TriangleIndex(cat)
    diag = float(np.hypot(cx * 2, cy * 2))
    if seed_tol_px is None:
        seed_tol_px = 0.012 * diag

    order = np.argsort(det_weight)[::-1][:n_det]
    dxy = det_xy[order]
    nd = len(dxy)
    cshapes = tri.shapes

    best = None
    for combo in _triples(nd):
        pts = dxy[list(combo)]
        ps = _tri_sides_px(pts)
        so = np.argsort(ps)
        ss = ps[so]
        dsh = (ss[0] / ss[2], ss[1] / ss[2])
        dd = np.hypot(cshapes[:, 0] - dsh[0], cshapes[:, 1] - dsh[1])
        cand = np.flatnonzero(dd < tol_shape)
        if len(cand) == 0:
            continue
        dverts = [combo[so[0]], combo[so[1]], combo[so[2]]]   # side-ascending
        for ci in cand:
            cside = tri.sides[ci]
            f_est = float(np.median(ss / cside))               # px/rad ~ focal length
            if not ((1 - f_window) * f_pix < f_est < (1 + f_window) * f_pix):
                continue
            cverts = tri.verts[ci]
            R = wahba(pix_to_ray(dxy[dverts], cx, cy, f_est), cat.vec[list(cverts)])
            sol = _verify(R, dxy, cat, f_est, cx, cy, seed_tol_px, subset=tri.bright)
            if sol and len(sol.matches) >= 5 and \
                    (best is None or len(sol.matches) > len(best.matches)):
                best = sol
    if best is None:
        return None
    sol = _refine(best, dxy, cat, cx, cy, seed_tol_px)
    if verbose:
        print(f"    seed {len(best.matches)} bright -> refined "
              f"{len(sol.matches) if sol else 0} (rms {sol.rms_px if sol else 0:.1f})")
    if sol is None or len(sol.matches) < min_matches or sol.rms_px > max_rms:
        return None
    return sol


def _triples(n):
    import itertools
    return itertools.combinations(range(n), 3)


def _verify(R, dxy, cat: Catalog, f, cx, cy, tol_px, subset=None):
    """Project catalog (optionally a subset), match each detection to nearest projected
    star within tol. Returns a Solution carrying the (det_idx, cat_idx) matches."""
    vec = cat.vec if subset is None else cat.vec[subset]
    proj, z = project(vec, R, cx, cy, f)
    inframe = (z > 0) & (proj[:, 0] > -0.1 * cx) & (proj[:, 0] < cx * 2 + 0.1 * cx) & \
              (proj[:, 1] > -0.1 * cy) & (proj[:, 1] < cy * 2 + 0.1 * cy)
    local = np.flatnonzero(inframe)
    if len(local) == 0:
        return None
    gidx = local if subset is None else np.asarray(subset)[local]
    P = proj[local]
    matches = []
    used = set()
    for di_ in range(len(dxy)):
        dd = np.hypot(P[:, 0] - dxy[di_, 0], P[:, 1] - dxy[di_, 1])
        m = int(np.argmin(dd))
        g = int(gidx[m])
        if dd[m] <= tol_px and g not in used:
            matches.append((di_, g))
            used.add(g)
    if len(matches) < 2:
        return None
    return _make_solution(R, f, cx, cy, matches, dxy)


def _make_solution(R, f, cx, cy, matches, dxy):
    # boresight = camera +z axis in celestial frame = R^T @ [0,0,1]
    bore = R.T @ np.array([0, 0, 1.0])
    dec = np.degrees(np.arcsin(np.clip(bore[2], -1, 1)))
    ra = np.degrees(np.arctan2(bore[1], bore[0])) % 360.0
    # roll: position angle of camera "up" (-y) -- direction of celestial north in image
    north = np.array([0, 0, 1.0])
    cam_n = R @ north
    # project north onto image plane near center: its image-space direction
    roll = np.degrees(np.arctan2(cam_n[0], -cam_n[1]))
    fov_w = 2 * np.degrees(np.arctan(cx / f))
    fov_h = 2 * np.degrees(np.arctan(cy / f))
    return Solution(R=R, f_pix=f, cx=cx, cy=cy, center_ra=ra, center_dec=dec,
                    roll_deg=roll, fov_w=fov_w, fov_h=fov_h, matches=matches, det_xy=dxy)


def _refine(sol: Solution, dxy, cat: Catalog, cx, cy, tol_px):
    """Iterate: refit R (Wahba) and f from current matches, then re-match against the
    FULL catalog with a tightening tolerance. The seed gave bright-star matches; once
    R and f are pinned the deeper catalog can be admitted without chance matches."""
    R, f = sol.R, sol.f_pix
    matches = sol.matches
    for it in range(7):
        if len(matches) >= 3:
            d = pix_to_ray(dxy[[m[0] for m in matches]], cx, cy, f)
            c = cat.vec[[m[1] for m in matches]]
            R = wahba(d, c)
            # refine f by minimizing radial residual: f s.t. projected radii match
            cam = c @ R.T
            good = cam[:, 2] > 1e-6
            if good.sum() >= 3:
                rt = np.hypot(cam[good, 0] / cam[good, 2], cam[good, 1] / cam[good, 2])
                rp = np.hypot(dxy[[m[0] for m in matches]][good, 0] - cx,
                              dxy[[m[0] for m in matches]][good, 1] - cy)
                denom = np.sum(rt * rt)
                if denom > 0:
                    f = float(np.sum(rt * rp) / denom)
        tol = max(tol_px * (0.55 ** it), 4.0)
        new = _verify(R, dxy, cat, f, cx, cy, tol)
        if new is None:
            break
        R, f, matches = new.R, new.f_pix, new.matches
    final = _make_solution(R, f, cx, cy, matches, dxy)
    if matches:
        d = pix_to_ray(dxy[[m[0] for m in matches]], cx, cy, f)
        proj, _ = project(cat.vec[[m[1] for m in matches]], R, cx, cy, f)
        mxy = dxy[[m[0] for m in matches]]
        final.rms_px = float(np.sqrt(np.mean((proj - mxy) ** 2)))
    return final
