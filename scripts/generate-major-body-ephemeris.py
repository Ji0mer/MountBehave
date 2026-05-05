#!/usr/bin/env python3
"""Generate a compact JPL-DE440s derived ephemeris for major solar-system bodies.

The generated binary asset stores apparent geocentric equatorial-of-date
vectors in AU for Sun, Moon, and the major planets. The Android app applies
observer parallax at runtime, so the asset remains independent of user GPS.
"""

import math
import struct
from pathlib import Path

import numpy as np
from skyfield.api import Loader


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = PROJECT_ROOT / "scripts" / "data" / "jpl"
OUT_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "ephemeris"
OUT_FILE = OUT_DIR / "solar_major_vectors.bin"

START_YEAR = 2025
END_YEAR_EXCLUSIVE = 2050
STEP_HOURS = 3

BODIES = [
    ("sun", "sun"),
    ("moon", "moon"),
    ("mercury", "mercury barycenter"),
    ("venus", "venus barycenter"),
    ("mars", "mars barycenter"),
    ("jupiter", "jupiter barycenter"),
    ("saturn", "saturn barycenter"),
    ("uranus", "uranus barycenter"),
    ("neptune", "neptune barycenter"),
]

MAGIC = b"MBEPH01\n"


def julian_day_from_unix_ms(ms: int) -> float:
    return ms / 86_400_000.0 + 2_440_587.5


def main() -> None:
    load = Loader(str(DATA_DIR))
    ts = load.timescale()
    eph = load("de440s.bsp")

    start = ts.utc(START_YEAR, 1, 1, 0, 0, 0)
    end = ts.utc(END_YEAR_EXCLUSIVE, 1, 1, 0, 0, 0)
    total_hours = int(round((end.utc_datetime() - start.utc_datetime()).total_seconds() / 3600.0))
    sample_count = total_hours // STEP_HOURS + 1

    offsets_hours = np.arange(sample_count, dtype=np.float64) * STEP_HOURS
    times = ts.utc(START_YEAR, 1, 1, offsets_hours)

    start_jd = julian_day_from_unix_ms(int(start.utc_datetime().timestamp() * 1000))
    step_days = STEP_HOURS / 24.0

    vectors = np.zeros((sample_count, len(BODIES), 3), dtype=np.float32)
    earth = eph["earth"]
    for body_index, (body_id, skyfield_name) in enumerate(BODIES):
        apparent = earth.at(times).observe(eph[skyfield_name]).apparent()
        ra, dec, distance = apparent.radec(epoch=times)
        ra_rad = ra.radians
        dec_rad = dec.radians
        dist_au = distance.au
        cos_dec = np.cos(dec_rad)
        vectors[:, body_index, 0] = (dist_au * cos_dec * np.cos(ra_rad)).astype(np.float32)
        vectors[:, body_index, 1] = (dist_au * cos_dec * np.sin(ra_rad)).astype(np.float32)
        vectors[:, body_index, 2] = (dist_au * np.sin(dec_rad)).astype(np.float32)
        print(f"{body_id:8s} {sample_count} samples")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with OUT_FILE.open("wb") as target:
        target.write(MAGIC)
        target.write(struct.pack("<ddii", start_jd, step_days, sample_count, len(BODIES)))
        target.write(vectors.astype("<f4", copy=False).tobytes(order="C"))

    size_mb = OUT_FILE.stat().st_size / (1024 * 1024)
    print(
        f"Wrote {OUT_FILE} ({size_mb:.2f} MiB), "
        f"{START_YEAR}-01-01 through {END_YEAR_EXCLUSIVE}-01-01, step {STEP_HOURS}h"
    )


if __name__ == "__main__":
    main()
