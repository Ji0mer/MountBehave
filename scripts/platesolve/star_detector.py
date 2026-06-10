"""Faithful Python port of the app's StarDetector.java (phase-1 detection algorithm).

Same pipeline as the Android code so what we validate here is the program's own
detector, not a different one:
  downscale -> perceptual luma -> histogram median background + 1.4826*MAD noise
  -> threshold = bg + sigma*noise -> 8-connected flood fill -> flux-weighted
  sub-pixel centroid -> blob-size filter -> map back to source pixels.

Centroids are returned in ORIGINAL (pre-downscale) image pixel coordinates, exactly
like StarDetector.Detection.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import List
import numpy as np
from PIL import Image


@dataclass
class Detection:
    x: float          # sub-pixel centroid X, source pixels
    y: float          # sub-pixel centroid Y, source pixels
    brightness: float # background-subtracted integrated flux
    peak: float       # background-subtracted peak (0..255)
    snr: float        # peak / noise
    pixel_count: int  # blob pixels at analysis scale


@dataclass
class DetectResult:
    stars: List[Detection]      # brightness-descending, capped at max_stars
    detection_count: int        # honest count, before render cap
    background: float
    noise: float
    threshold: float
    analysis_w: int
    analysis_h: int
    source_w: int
    source_h: int


class StarDetector:
    def __init__(self, max_analysis_long_edge=1600, threshold_sigma=6.0,
                 min_blob_pixels=2, max_blob_pixels=600, max_stars=200,
                 edge_margin_fraction=0.12):
        self.max_long = max(64, max_analysis_long_edge)
        self.sigma = threshold_sigma
        self.min_blob = max(1, min_blob_pixels)
        self.max_blob = max(self.min_blob, max_blob_pixels)
        self.max_stars = max(1, max_stars)
        self.edge_margin = min(0.49, max(0.0, edge_margin_fraction))

    def detect(self, img: Image.Image) -> DetectResult:
        src_w, src_h = img.size
        long_edge = max(src_w, src_h)
        scale = self.max_long / long_edge if long_edge > self.max_long else 1.0
        w = max(1, round(src_w * scale))
        h = max(1, round(src_h * scale))
        if (w, h) != (src_w, src_h):
            # bilinear, matching createScaledBitmap(..., filter=true)
            small = img.resize((w, h), Image.BILINEAR)
        else:
            small = img
        rgb = np.asarray(small.convert("RGB"), dtype=np.int32)
        r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
        # luma = (r*77 + g*150 + b*29) >> 8, clamped to 255  (Java integer math)
        grey = ((r * 77 + g * 150 + b * 29) >> 8).astype(np.int32)
        np.clip(grey, 0, 255, out=grey)

        total = grey.size
        hist = np.bincount(grey.ravel(), minlength=256)
        background = _median_from_hist(hist, total)
        noise = _robust_noise(hist, total, background)
        threshold = background + self.sigma * noise
        threshold = max(background + 1.0, min(254.0, threshold))
        thr_int = int(np.floor(threshold))

        dets = _extract_blobs(grey, w, h, thr_int, background, noise,
                              src_w, src_h, self.min_blob, self.max_blob)
        dets.sort(key=lambda d: d.brightness, reverse=True)
        rendered = dets[:self.max_stars]
        return DetectResult(rendered, len(dets), background, noise, threshold,
                            w, h, src_w, src_h)


def detect_local(img: Image.Image, threshold_sigma=5.0, max_analysis_long_edge=1600,
                 block=32, min_blob_pixels=2, max_blob_pixels=400, max_stars=300,
                 bg_percentile=35.0, fg_dark_frac=0.55, fg_bright_frac=1.7,
                 fg_texture_sigma=4.0, fg_dilate=1):
    """Enhanced detection for light-polluted frames WITH FOREGROUND (trees/roofs/wires).

    The phase-1 StarDetector uses a single global background+noise, which on these
    real phone frames is dominated by the sky gradient and the dark foreground
    silhouettes -> the threshold lands near saturation and almost nothing passes.

    This path instead:
      * estimates a LOCAL background from per-block low percentiles (stars, being
        bright outliers, do not inflate it), upsampled smoothly, and subtracts it;
      * estimates noise from the MAD of that residual (true pixel noise, small);
      * builds a FOREGROUND MASK from per-block statistics -- a block is foreground
        if it is much darker than sky (tree/roof/chimney silhouette), much brighter
        than sky (lit roof), or high-texture (leaf edges, wires) -- and detects only
        inside the sky region.

    Returns (DetectResult, sky_mask_full_res_bool).
    """
    src_w, src_h = img.size
    long_edge = max(src_w, src_h)
    scale = max_analysis_long_edge / long_edge if long_edge > max_analysis_long_edge else 1.0
    w = max(1, round(src_w * scale))
    h = max(1, round(src_h * scale))
    small = img.resize((w, h), Image.BILINEAR) if (w, h) != (src_w, src_h) else img
    rgb = np.asarray(small.convert("RGB"), dtype=np.float64)
    grey = (rgb[..., 0] * 77 + rgb[..., 1] * 150 + rgb[..., 2] * 29) / 256.0
    np.clip(grey, 0, 255, out=grey)

    # --- per-block statistics ---
    nby = (h + block - 1) // block
    nbx = (w + block - 1) // block
    padded = np.full((nby * block, nbx * block), np.nan)
    padded[:h, :w] = grey
    blocks = padded.reshape(nby, block, nbx, block)
    bg_blk = np.nanpercentile(blocks, bg_percentile, axis=(1, 3))      # robust local sky
    med_blk = np.nanmedian(blocks, axis=(1, 3))
    std_blk = _nanmad(blocks)                                          # texture proxy

    sky_level = float(np.nanmedian(grey))
    pix_noise_global = max(1.0, 1.4826 * float(np.nanmedian(np.abs(grey - sky_level))))
    fg = (med_blk < fg_dark_frac * sky_level) | \
         (med_blk > fg_bright_frac * sky_level) | \
         (std_blk > fg_texture_sigma * pix_noise_global)
    fg = _dilate_bool(fg, fg_dilate)

    # smooth local background, upsampled to pixel grid
    bg_img = Image.fromarray(bg_blk.astype(np.float32)).resize((w, h), Image.BILINEAR)
    bg_up = np.asarray(bg_img, dtype=np.float64)
    residual = grey - bg_up

    sky_mask = ~np.kron(fg, np.ones((block, block), dtype=bool))[:h, :w]
    sky_vals = residual[sky_mask]
    noise = max(1.0, 1.4826 * float(np.median(np.abs(sky_vals - np.median(sky_vals)))))
    threshold = threshold_sigma * noise

    cand = (residual > threshold) & sky_mask
    dets = _blobs_from_mask(cand, residual, w, h, src_w, src_h,
                            min_blob_pixels, max_blob_pixels, noise)
    dets.sort(key=lambda d: d.brightness, reverse=True)
    res = DetectResult(dets[:max_stars], len(dets), sky_level, noise, threshold,
                       w, h, src_w, src_h)
    full_mask = np.asarray(Image.fromarray(sky_mask).resize((src_w, src_h), Image.NEAREST))
    return res, full_mask


def _nanmad(blocks):
    med = np.nanmedian(blocks, axis=(1, 3), keepdims=True)
    return 1.4826 * np.nanmedian(np.abs(blocks - med), axis=(1, 3))


def _dilate_bool(m, it):
    for _ in range(max(0, it)):
        d = m.copy()
        d[:-1] |= m[1:]; d[1:] |= m[:-1]
        d[:, :-1] |= m[:, 1:]; d[:, 1:] |= m[:, :-1]
        m = d
    return m


def _blobs_from_mask(cand, residual, w, h, src_w, src_h, min_blob, max_blob, noise):
    flat_mask = cand.ravel()
    flat_res = residual.ravel()
    visited = np.zeros(flat_mask.shape, dtype=bool)
    seeds = np.flatnonzero(flat_mask)
    sx = src_w / w
    sy = src_h / h
    neigh = (-w - 1, -w, -w + 1, -1, 1, w - 1, w, w + 1)
    n = flat_mask.shape[0]
    out: List[Detection] = []
    for s in seeds:
        if visited[s]:
            continue
        stack = [int(s)]
        visited[s] = True
        pix = 0; wsum = 0.0; wx = 0.0; wy = 0.0; peak = 0.0
        while stack:
            idx = stack.pop()
            px = idx % w; py = idx // w
            val = float(flat_res[idx])
            wt = val if val > 0 else 0.0
            pix += 1; wsum += wt; wx += wt * px; wy += wt * py
            if val > peak:
                peak = val
            for off in neigh:
                nidx = idx + off
                if nidx < 0 or nidx >= n:
                    continue
                if abs((nidx % w) - px) > 1:
                    continue
                if not visited[nidx] and flat_mask[nidx]:
                    visited[nidx] = True
                    stack.append(nidx)
        if pix < min_blob or pix > max_blob or wsum <= 0.0:
            continue
        cx = (wx / wsum) * sx
        cy = (wy / wsum) * sy
        out.append(Detection(cx, cy, wsum, peak, peak / noise, pix))
    return out


def _median_from_hist(hist, total):
    if total <= 0:
        return 0.0
    target = total // 2
    c = 0
    for v in range(len(hist)):
        c += int(hist[v])
        if c > target:
            return float(v)
    return float(len(hist) - 1)


def _robust_noise(hist, total, background):
    if total <= 0:
        return 0.0
    bg = int(round(background))
    dev_hist = np.zeros(256, dtype=np.int64)
    vals = np.arange(256)
    dev = np.abs(vals - bg)
    np.clip(dev, 0, 255, out=dev)
    for v in range(256):
        if hist[v]:
            dev_hist[dev[v]] += int(hist[v])
    mad = _median_from_hist(dev_hist, total)
    return max(1.0, 1.4826 * mad)


def _extract_blobs(grey, w, h, thr_int, background, noise,
                   src_w, src_h, min_blob, max_blob):
    """8-connected flood fill over supra-threshold pixels only (sparse, fast)."""
    flat = grey.ravel()
    mask = flat > thr_int
    visited = np.zeros(flat.shape, dtype=bool)
    seeds = np.flatnonzero(mask)
    sx = src_w / w
    sy = src_h / h
    out: List[Detection] = []
    neigh = (-w - 1, -w, -w + 1, -1, 1, w - 1, w, w + 1)
    n = flat.shape[0]
    for s in seeds:
        if visited[s]:
            continue
        stack = [int(s)]
        visited[s] = True
        pix = 0
        wsum = 0.0
        wx = 0.0
        wy = 0.0
        peak = 0.0
        while stack:
            idx = stack.pop()
            px = idx % w
            py = idx // w
            val = float(flat[idx])
            weight = val - background
            if weight < 0.0:
                weight = 0.0
            pix += 1
            wsum += weight
            wx += weight * px
            wy += weight * py
            if val > peak:
                peak = val
            for off in neigh:
                nidx = idx + off
                if nidx < 0 or nidx >= n:
                    continue
                nx = nidx % w
                # reject wrap-around across row edges
                if abs(nx - px) > 1:
                    continue
                if not visited[nidx] and flat[nidx] > thr_int:
                    visited[nidx] = True
                    stack.append(nidx)
        if pix < min_blob or pix > max_blob or wsum <= 0.0:
            continue
        cx = (wx / wsum) * sx
        cy = (wy / wsum) * sy
        peak_above = max(0.0, peak - background)
        out.append(Detection(cx, cy, wsum, peak_above, peak_above / noise, pix))
    return out
