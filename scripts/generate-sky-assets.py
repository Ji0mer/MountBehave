import csv
import gzip
import json
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = PROJECT_ROOT / "data" / "raw"
ASSET_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "catalog"

STAR_MAG_LIMIT = 14.0
DSO_MAG_LIMIT = 11.5


def parse_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def hms_to_hours(value):
    parts = value.split(":")
    if len(parts) != 3:
        return None
    try:
        h, m, s = float(parts[0]), float(parts[1]), float(parts[2])
    except ValueError:
        return None
    return h + m / 60.0 + s / 3600.0


def dms_to_degrees(value):
    if not value:
        return None
    sign = -1 if value[0] == "-" else 1
    clean = value[1:] if value[0] in "+-" else value
    parts = clean.split(":")
    if len(parts) != 3:
        return None
    try:
        d, m, s = float(parts[0]), float(parts[1]), float(parts[2])
    except ValueError:
        return None
    return sign * (d + m / 60.0 + s / 3600.0)


def clean_text(value):
    return " ".join((value or "").replace("\t", " ").split())


def star_name(row):
    for key in ("proper", "bf"):
        value = clean_text(row.get(key))
        if value:
            return value
    if row.get("hr"):
        return f"HR {row['hr']}"
    if row.get("hip"):
        return f"HIP {row['hip']}"
    return f"HYG {row['id']}"


def generate_stars():
    rows = []
    with gzip.open(RAW_DIR / "hygdata_v42.csv.gz", "rt", encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            if row.get("proper") == "Sol":
                continue
            ra = parse_float(row.get("ra"))
            dec = parse_float(row.get("dec"))
            mag = parse_float(row.get("mag"))
            if ra is None or dec is None or mag is None or mag > STAR_MAG_LIMIT:
                continue
            rows.append((mag, star_name(row), ra, dec))

    rows.sort(key=lambda item: item[0])
    with (ASSET_DIR / "stars.tsv").open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, delimiter="\t", lineterminator="\n")
        writer.writerow(["name", "ra_hours", "dec_deg", "mag"])
        for mag, name, ra, dec in rows:
            writer.writerow([name, f"{ra:.6f}", f"{dec:.5f}", f"{mag:.2f}"])
    return len(rows)


def dso_primary_name(row):
    if row.get("M"):
        return f"M{int(row['M'])}"
    if row.get("Name"):
        return row["Name"]
    if row.get("NGC"):
        return f"NGC {row['NGC']}"
    if row.get("IC"):
        return f"IC {row['IC']}"
    return "DSO"


def dso_aliases(row, primary_name):
    aliases = []
    if row.get("M"):
        aliases.append(f"M{int(row['M'])}")
    if row.get("NGC"):
        aliases.append(f"NGC {row['NGC']}")
    if row.get("IC"):
        aliases.append(f"IC {row['IC']}")
    if row.get("Name"):
        aliases.append(row["Name"])

    cleaned = []
    seen = {clean_text(primary_name).upper()}
    for alias in aliases:
        value = clean_text(alias)
        key = value.upper()
        if value and key not in seen:
            seen.add(key)
            cleaned.append(value)
    return ",".join(cleaned)


def dso_mag(row):
    visual = parse_float(row.get("V-Mag"))
    if visual is not None:
        return visual
    blue = parse_float(row.get("B-Mag"))
    if blue is not None:
        return blue - 0.8
    return None


def include_dso(row, is_addendum):
    if is_addendum:
        return True
    if row.get("M"):
        return True
    mag = dso_mag(row)
    return mag is not None and mag <= DSO_MAG_LIMIT


def generate_dsos():
    rows = []
    seen = set()
    for filename, is_addendum in (("NGC.csv", False), ("addendum.csv", True)):
        with (RAW_DIR / filename).open("r", encoding="utf-8", newline="") as source:
            for row in csv.DictReader(source, delimiter=";"):
                ra = hms_to_hours(row.get("RA", ""))
                dec = dms_to_degrees(row.get("Dec", ""))
                if ra is None or dec is None or not include_dso(row, is_addendum):
                    continue
                name = clean_text(dso_primary_name(row))
                if name in seen:
                    continue
                seen.add(name)
                mag = dso_mag(row)
                common = clean_text(row.get("Common names", "").split(",")[0])
                aliases = dso_aliases(row, name)
                rows.append((
                    99.0 if mag is None else mag,
                    name,
                    clean_text(row.get("Type")),
                    ra,
                    dec,
                    "" if mag is None else f"{mag:.1f}",
                    common,
                    aliases,
                ))

    rows.sort(key=lambda item: (item[0], item[1]))
    with (ASSET_DIR / "dsos.tsv").open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, delimiter="\t", lineterminator="\n")
        writer.writerow(["name", "type", "ra_hours", "dec_deg", "mag", "common_name", "aliases"])
        for _, name, obj_type, ra, dec, mag, common, aliases in rows:
            writer.writerow([name, obj_type, f"{ra:.6f}", f"{dec:.5f}", mag, common, aliases])
    return len(rows)


def constellation_ra_hours(ra_degrees):
    return (ra_degrees % 360.0) / 15.0


def generate_constellation_lines():
    source_path = RAW_DIR / "constellations.lines.json"
    if not source_path.exists():
        print(f"Skipping constellation lines; missing {source_path}")
        return 0

    with source_path.open("r", encoding="utf-8") as source:
        data = json.load(source)

    rows = []
    for feature in data.get("features", []):
        constellation = clean_text(feature.get("id", ""))
        geometry = feature.get("geometry", {})
        if geometry.get("type") != "MultiLineString":
            continue
        for polyline in geometry.get("coordinates", []):
            for start, end in zip(polyline, polyline[1:]):
                if len(start) < 2 or len(end) < 2:
                    continue
                rows.append((
                    constellation,
                    constellation_ra_hours(float(start[0])),
                    float(start[1]),
                    constellation_ra_hours(float(end[0])),
                    float(end[1]),
                ))

    with (ASSET_DIR / "constellation_lines.tsv").open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, delimiter="\t", lineterminator="\n")
        writer.writerow(["constellation", "ra1_hours", "dec1_deg", "ra2_hours", "dec2_deg"])
        for constellation, ra1, dec1, ra2, dec2 in rows:
            writer.writerow([constellation, f"{ra1:.6f}", f"{dec1:.5f}", f"{ra2:.6f}", f"{dec2:.5f}"])
    return len(rows)


def main():
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    star_count = generate_stars()
    dso_count = generate_dsos()
    constellation_line_count = generate_constellation_lines()
    print(
        f"Generated {star_count} stars, {dso_count} deep-sky objects, "
        f"and {constellation_line_count} constellation line segments in {ASSET_DIR}"
    )


if __name__ == "__main__":
    main()
