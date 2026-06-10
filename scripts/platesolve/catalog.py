"""Load the bundled HYG star catalog and constellation lines for plate solving."""
from __future__ import annotations
import gzip, csv, json, os
from dataclasses import dataclass
from typing import List, Optional
import numpy as np

HYG = "../../data/raw/hygdata_v42.csv.gz"
LINES = "../../data/raw/constellations.lines.json"

_GREEK = {
    "alp": "α", "bet": "β", "gam": "γ", "del": "δ", "eps": "ε", "zet": "ζ",
    "eta": "η", "the": "θ", "iot": "ι", "kap": "κ", "lam": "λ", "mu": "μ",
    "nu": "ν", "xi": "ξ", "omi": "ο", "pi": "π", "rho": "ρ", "sig": "σ",
    "tau": "τ", "ups": "υ", "phi": "φ", "chi": "χ", "psi": "ψ", "ome": "ω",
}


@dataclass
class Star:
    ra_deg: float
    dec_deg: float
    mag: float
    proper: str
    con: str
    bayer: str
    flam: str

    def label(self) -> str:
        if self.proper:
            return self.proper
        b = _GREEK.get(self.bayer.lower(), self.bayer) if self.bayer else ""
        if b and self.con:
            return f"{b} {self.con}"
        if self.flam and self.con:
            return f"{self.flam} {self.con}"
        return ""


class Catalog:
    def __init__(self, stars: List[Star]):
        self.stars = stars
        ra = np.radians(np.array([s.ra_deg for s in stars]))
        dec = np.radians(np.array([s.dec_deg for s in stars]))
        self.ra = ra
        self.dec = dec
        self.mag = np.array([s.mag for s in stars])
        self.vec = np.column_stack([
            np.cos(dec) * np.cos(ra),
            np.cos(dec) * np.sin(ra),
            np.sin(dec),
        ])  # (N,3) celestial unit vectors

    def __len__(self):
        return len(self.stars)


def load_catalog(mag_limit: float, base: Optional[str] = None) -> Catalog:
    base = base or os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(base, HYG)
    out: List[Star] = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        r = csv.DictReader(f)
        for row in r:
            mraw = row.get("mag", "")
            if mraw in ("", "null"):
                continue
            try:
                mag = float(mraw)
            except ValueError:
                continue
            if mag > mag_limit:
                continue
            if row.get("proper", "") == "Sol":  # the Sun
                continue
            try:
                ra_h = float(row["ra"])     # hours
                dec = float(row["dec"])     # degrees
            except (ValueError, KeyError):
                continue
            out.append(Star(ra_h * 15.0, dec, mag, row.get("proper", "") or "",
                            (row.get("con", "") or "").strip(),
                            (row.get("bayer", "") or "").strip(),
                            (row.get("flam", "") or "").strip()))
    return Catalog(out)


def load_constellation_lines(base: Optional[str] = None):
    """Return list of (con_id, [polyline,...]) where polyline is Nx2 array [ra_deg,dec_deg]."""
    base = base or os.path.dirname(os.path.abspath(__file__))
    data = json.load(open(os.path.join(base, LINES), encoding="utf-8"))
    out = []
    for feat in data["features"]:
        cid = feat.get("id", "")
        geom = feat.get("geometry", {})
        if geom.get("type") != "MultiLineString":
            continue
        polys = [np.array(seg, dtype=float) for seg in geom["coordinates"] if len(seg) >= 2]
        if polys:
            out.append((cid, polys))
    return out
