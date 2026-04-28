#!/usr/bin/env python3
"""Sanity-check the VSOP87D-based ephemeris by computing planet RA/Dec at a
known date and printing values for comparison against Stellarium / JPL Horizons.

This loads the same VSOP87D files cached under scripts/data/vsop87/ that the
Java generator uses, applies the same truncation thresholds and the same
post-processing chain (light-time → aberration → nutation → ecliptic→equatorial).
If the printed values match an external reference within a few arcseconds, we
have high confidence the Java implementation is mathematically correct.
"""

import math
from datetime import datetime, timezone
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = PROJECT_ROOT / "scripts" / "data" / "vsop87"

PLANETS = {
    "mercury": "VSOP87D.mer",
    "venus":   "VSOP87D.ven",
    "earth":   "VSOP87D.ear",
    "mars":    "VSOP87D.mar",
    "jupiter": "VSOP87D.jup",
    "saturn":  "VSOP87D.sat",
    "uranus":  "VSOP87D.ura",
    "neptune": "VSOP87D.nep",
}

THRESHOLD_LB = 1e-6
THRESHOLD_R = 1e-7
LIGHT_AU_PER_DAY = 173.144632674240
ABERRATION_DEG = 20.49552 / 3600.0
DAYS_PER_MILLENNIUM = 365250.0


def parse_file(path: Path):
    series = {"L": {}, "B": {}, "R": {}}
    var = None
    power = None
    current = None
    with open(path, "r", encoding="ascii") as f:
        for line in f:
            if " VSOP87" in line and "VARIABLE" in line:
                idx = int(line.split("VARIABLE")[1].strip()[0])
                var = "LBR"[idx - 1]
                t_pos = line.index("*T**")
                power = int(line[t_pos + 4: t_pos + 5])
                series[var][power] = []
                current = series[var][power]
            else:
                if current is None or len(line) < 131:
                    continue
                try:
                    A = float(line[79:97])
                    B = float(line[97:111])
                    C = float(line[111:131])
                except ValueError:
                    continue
                threshold = THRESHOLD_R if var == "R" else THRESHOLD_LB
                if abs(A) >= threshold:
                    current.append((A, B, C))
    return series


def sum_series(series_per_power, tau):
    total = 0.0
    tau_power = 1.0
    for power in range(6):
        terms = series_per_power.get(power, [])
        partial = 0.0
        for A, B, C in terms:
            partial += A * math.cos(B + C * tau)
        total += partial * tau_power
        tau_power *= tau
    return total


def coordinates(planet_series, tau):
    L = sum_series(planet_series["L"], tau)
    B = sum_series(planet_series["B"], tau)
    R = sum_series(planet_series["R"], tau)
    return L, B, R  # L,B in radians; R in AU


def normalize_deg(x):
    x = x % 360.0
    return x + 360.0 if x < 0 else x


def nutation(centuries):
    T = centuries
    Omega = math.radians(normalize_deg(125.04452 - 1934.136261 * T))
    Lsun = math.radians(normalize_deg(280.4665 + 36000.7698 * T))
    Lmoon = math.radians(normalize_deg(218.3165 + 481267.8813 * T))
    delta_psi_arcsec = (
        -17.20 * math.sin(Omega)
        - 1.32 * math.sin(2.0 * Lsun)
        - 0.23 * math.sin(2.0 * Lmoon)
        + 0.21 * math.sin(2.0 * Omega)
    )
    delta_eps_arcsec = (
        9.20 * math.cos(Omega)
        + 0.57 * math.cos(2.0 * Lsun)
        + 0.10 * math.cos(2.0 * Lmoon)
        - 0.09 * math.cos(2.0 * Omega)
    )
    return delta_psi_arcsec / 3600.0, delta_eps_arcsec / 3600.0


def mean_obliquity_deg(centuries):
    T = centuries
    seconds = 21.448 - 46.8150 * T - 0.00059 * T * T + 0.001813 * T * T * T
    return 23.0 + (26.0 + seconds / 60.0) / 60.0


def spherical_to_cartesian(L_rad, B_rad, R):
    cos_b = math.cos(B_rad)
    return (R * math.cos(L_rad) * cos_b, R * math.sin(L_rad) * cos_b, R * math.sin(B_rad))


def apply_aberration(longitude_deg, latitude_deg, sun_long_deg):
    cos_b = math.cos(math.radians(latitude_deg))
    if abs(cos_b) < 1e-9:
        return longitude_deg
    return longitude_deg - ABERRATION_DEG * math.cos(math.radians(sun_long_deg - longitude_deg)) / cos_b


def ecliptic_to_equatorial(lon_deg, lat_deg, obl_deg):
    lam = math.radians(lon_deg)
    bet = math.radians(lat_deg)
    eps = math.radians(obl_deg)
    sb, cb = math.sin(bet), math.cos(bet)
    y = math.sin(lam) * math.cos(eps) - math.tan(bet) * math.sin(eps)
    x = math.cos(lam)
    ra_deg = normalize_deg(math.degrees(math.atan2(y, x)))
    dec_deg = math.degrees(math.asin(max(-1.0, min(1.0, sb * math.cos(eps) + cb * math.sin(eps) * math.sin(lam)))))
    return ra_deg / 15.0, dec_deg


def julian_day(dt: datetime) -> float:
    return dt.timestamp() / 86400.0 + 2440587.5


def fmt_ra(hours):
    h = int(hours)
    m = (hours - h) * 60.0
    mi = int(m)
    s = (m - mi) * 60.0
    return f"{h:02d}h{mi:02d}m{s:05.2f}s"


def fmt_dec(deg):
    sign = "+" if deg >= 0 else "-"
    deg = abs(deg)
    d = int(deg)
    m = (deg - d) * 60.0
    mi = int(m)
    s = (m - mi) * 60.0
    return f"{sign}{d:02d}°{mi:02d}'{s:04.1f}\""


def compute(planet_name, planet_series, earth_series, tau, centuries):
    delta_psi_deg, delta_eps_deg = nutation(centuries)
    obl_deg = mean_obliquity_deg(centuries) + delta_eps_deg

    L_e, B_e, R_e = coordinates(earth_series, tau)
    earth_xyz = spherical_to_cartesian(L_e, B_e, R_e)

    sun_long_deg = normalize_deg(math.degrees(L_e) + 180.0)

    if planet_name == "sun":
        sun_app_lon = normalize_deg(sun_long_deg - ABERRATION_DEG + delta_psi_deg)
        sun_lat = -math.degrees(B_e)
        ra, dec = ecliptic_to_equatorial(sun_app_lon, sun_lat, obl_deg)
        return ra, dec

    L_p, B_p, R_p = coordinates(planet_series, tau)
    planet_xyz = spherical_to_cartesian(L_p, B_p, R_p)
    geo = (planet_xyz[0] - earth_xyz[0], planet_xyz[1] - earth_xyz[1], planet_xyz[2] - earth_xyz[2])
    distance = math.sqrt(geo[0] ** 2 + geo[1] ** 2 + geo[2] ** 2)

    tau_emission = tau - distance / LIGHT_AU_PER_DAY / DAYS_PER_MILLENNIUM
    L_p2, B_p2, R_p2 = coordinates(planet_series, tau_emission)
    planet_xyz2 = spherical_to_cartesian(L_p2, B_p2, R_p2)
    geo2 = (planet_xyz2[0] - earth_xyz[0], planet_xyz2[1] - earth_xyz[1], planet_xyz2[2] - earth_xyz[2])
    delta = math.sqrt(geo2[0] ** 2 + geo2[1] ** 2 + geo2[2] ** 2)
    lon = math.degrees(math.atan2(geo2[1], geo2[0]))
    lat = math.degrees(math.asin(geo2[2] / delta))
    lon = normalize_deg(lon)

    app_lon = apply_aberration(lon, lat, sun_long_deg)
    app_lon = normalize_deg(app_lon + delta_psi_deg)
    ra, dec = ecliptic_to_equatorial(app_lon, lat, obl_deg)
    return ra, dec


def main():
    dt = datetime(2026, 4, 28, 0, 0, 0, tzinfo=timezone.utc)
    jd = julian_day(dt)
    centuries = (jd - 2451545.0) / 36525.0
    tau = (jd - 2451545.0) / DAYS_PER_MILLENNIUM
    print(f"Date (UTC): {dt.isoformat()}")
    print(f"JD = {jd:.6f}, centuries = {centuries:.6f}, tau = {tau:.9f}")
    print()

    earth_series = parse_file(DATA_DIR / PLANETS["earth"])

    for name, fname in PLANETS.items():
        if name == "earth":
            planet_series = earth_series
        else:
            planet_series = parse_file(DATA_DIR / fname)
        if name == "earth":
            ra, dec = compute("sun", None, earth_series, tau, centuries)
            print(f"  Sun:     RA = {fmt_ra(ra)}  Dec = {fmt_dec(dec)}")
            continue
        ra, dec = compute(name, planet_series, earth_series, tau, centuries)
        print(f"  {name.capitalize():9s}RA = {fmt_ra(ra)}  Dec = {fmt_dec(dec)}")


if __name__ == "__main__":
    main()
