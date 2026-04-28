#!/usr/bin/env python3
"""Cross-check the SmallBodyEphemeris math by computing one bundled body
position with the same Keplerian + light-time + aberration + nutation chain
the Java code uses, then printing it for comparison against JPL Horizons.
"""

import math
import sys
from datetime import datetime, timezone
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from importlib import import_module

verify_vsop = import_module("verify-vsop87")

DAYS_PER_MILLENNIUM = 365250.0
LIGHT_AU_PER_DAY = 173.144632674240
ABERRATION_DEG = 20.49552 / 3600.0
GAUSS_DEG_PER_DAY = 0.9856076686


def normalize_deg(x):
    x = x % 360.0
    return x + 360.0 if x < 0 else x


def kepler(M_deg, e):
    M = math.radians(normalize_deg(M_deg))
    E = M + e * math.sin(M) * (1.0 + e * math.cos(M))
    for _ in range(8):
        delta = (E - e * math.sin(E) - M) / (1.0 - e * math.cos(E))
        E -= delta
        if abs(delta) < 1e-12:
            break
    return E


def helio_xyz(q, e, i_deg, om_deg, w_deg, M_deg):
    a = q / (1.0 - e)
    E = kepler(M_deg, e)
    xv = a * (math.cos(E) - e)
    yv = a * math.sqrt(1 - e * e) * math.sin(E)
    nu = math.atan2(yv, xv)
    r = math.sqrt(xv * xv + yv * yv)
    node = math.radians(om_deg)
    inc = math.radians(i_deg)
    arg = nu + math.radians(w_deg)
    cosNode, sinNode = math.cos(node), math.sin(node)
    cosArg, sinArg = math.cos(arg), math.sin(arg)
    cosInc, sinInc = math.cos(inc), math.sin(inc)
    return (r * (cosNode * cosArg - sinNode * sinArg * cosInc),
            r * (sinNode * cosArg + cosNode * sinArg * cosInc),
            r * (sinArg * sinInc))


def precession_longitude_deg(centuries):
    T = centuries
    arcsec = 5028.7962 * T + 1.05039 * T * T - 0.00077 * T * T * T
    return arcsec / 3600.0


def rotate_z(xyz, angle_deg):
    rad = math.radians(angle_deg)
    c, s = math.cos(rad), math.sin(rad)
    return (xyz[0] * c - xyz[1] * s, xyz[0] * s + xyz[1] * c, xyz[2])


def compute(name, q, e, i_deg, om_deg, w_deg, tp_jd, jd, earth_xyz, earth_series, centuries):
    a = q / (1.0 - e)
    n = GAUSS_DEG_PER_DAY / a ** 1.5
    pA_deg = precession_longitude_deg(centuries)
    M0 = n * (jd - tp_jd)
    helio0 = rotate_z(helio_xyz(q, e, i_deg, om_deg, w_deg, M0), pA_deg)
    geo0 = (helio0[0] - earth_xyz[0], helio0[1] - earth_xyz[1], helio0[2] - earth_xyz[2])
    dist0 = math.sqrt(sum(x * x for x in geo0))

    # light-time iteration
    M1 = n * (jd - dist0 / LIGHT_AU_PER_DAY - tp_jd)
    helio1 = rotate_z(helio_xyz(q, e, i_deg, om_deg, w_deg, M1), pA_deg)
    geo = (helio1[0] - earth_xyz[0], helio1[1] - earth_xyz[1], helio1[2] - earth_xyz[2])
    dist = math.sqrt(sum(x * x for x in geo))

    longitude = normalize_deg(math.degrees(math.atan2(geo[1], geo[0])))
    latitude = math.degrees(math.asin(geo[2] / dist))

    # apparent corrections
    L_e, _B_e, _R_e = verify_vsop.coordinates(earth_series, (jd - 2451545.0) / DAYS_PER_MILLENNIUM)
    sun_long = normalize_deg(math.degrees(L_e) + 180.0)
    delta_psi_deg, delta_eps_deg = verify_vsop.nutation(centuries)
    obl_deg = verify_vsop.mean_obliquity_deg(centuries) + delta_eps_deg
    longitude = verify_vsop.apply_aberration(longitude, latitude, sun_long)
    longitude = normalize_deg(longitude + delta_psi_deg)

    ra, dec = verify_vsop.ecliptic_to_equatorial(longitude, latitude, obl_deg)
    return ra, dec


def main():
    dt = datetime(2026, 4, 28, 0, 0, 0, tzinfo=timezone.utc)
    jd = verify_vsop.julian_day(dt)
    centuries = (jd - 2451545.0) / 36525.0
    tau = (jd - 2451545.0) / DAYS_PER_MILLENNIUM
    earth_series = verify_vsop.parse_file(verify_vsop.DATA_DIR / verify_vsop.PLANETS["earth"])
    L_e, B_e, R_e = verify_vsop.coordinates(earth_series, tau)
    earth_xyz = verify_vsop.spherical_to_cartesian(L_e, B_e, R_e)

    bodies = [
        # (name, q, e, i, om, w, tp_jd) — full-precision values from BundledSmallBodies.java
        ("Ceres",   2.545538135581839,  0.07957631994408416, 10.58788658206854,  80.24963090816965, 73.29975464616518, 2461599.9493352976),
        ("Pallas",  2.131061881814897,  0.230642978777006,   34.92832687101305, 172.8885963374254, 310.9333840115845, 2461694.9444217016),
        ("Vesta",   2.148606664177354,  0.09016764504738634,  7.144060599543863, 103.7022980342142, 151.5371488873794, 2460901.785547203),
        ("Eros",    1.133378156466068,  0.2226781846022014,  10.82810257921027, 304.3008812527148, 178.8893773069378, 2461088.831461533),
    ]

    print(f"Date: {dt.isoformat()}, JD = {jd:.4f}")
    for name, q, e, i, om, w, tp in bodies:
        ra, dec = compute(name, q, e, i, om, w, tp, jd, earth_xyz, earth_series, centuries)
        print(f"  {name:8s} RA = {verify_vsop.fmt_ra(ra)}  Dec = {verify_vsop.fmt_dec(dec)}")


if __name__ == "__main__":
    main()
