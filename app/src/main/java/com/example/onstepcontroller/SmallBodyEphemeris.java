package com.example.onstepcontroller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keplerian propagator for asteroids and comets. Reuses VSOP87D Earth from
 * {@link Vsop87Tables} for the geocentric reduction. Mirrors the
 * {@link SolarSystemEphemeris} chain (light-time → aberration → nutation →
 * ecliptic→equatorial of date) so that small bodies share the planet frame.
 *
 * Only elliptic orbits (e &lt; 1) are supported; parabolic/hyperbolic comets
 * (rare) are skipped. Apparent visual magnitude is filtered by caller.
 */
final class SmallBodyEphemeris {
    private static final double J2000_JULIAN_DAY = 2_451_545.0;
    private static final double DAYS_PER_MILLENNIUM = 365250.0;
    private static final double LIGHT_AU_PER_DAY = 173.144632674240;
    private static final double ABERRATION_DEG = 20.49552 / 3600.0;
    // Gauss gravitational constant: 0.01720209895 rad/day = 0.9856076686 deg/day.
    private static final double GAUSS_DEG_PER_DAY = 0.9856076686;

    private SmallBodyEphemeris() {
    }

    static List<SolarSystemEphemeris.Body> bodies(Instant when, List<SmallBody> sources, double magnitudeLimit) {
        if (sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }
        double jd = julianDay(when);
        double centuries = (jd - J2000_JULIAN_DAY) / 36525.0;
        double tau = (jd - J2000_JULIAN_DAY) / DAYS_PER_MILLENNIUM;

        Nutation nutation = nutation(centuries);
        double trueObliquity = meanObliquityDegrees(centuries) + nutation.deltaEpsilonDeg;

        double[] earth = Vsop87Tables.earthCoordinates(tau);
        double earthLDeg = normalizeDegrees(degrees(earth[0]));
        double earthBDeg = degrees(earth[1]);
        double earthR = earth[2];
        double[] earthXyz = sphericalToCartesian(radians(earthLDeg), radians(earthBDeg), earthR);
        double sunGeometricLongitude = normalizeDegrees(earthLDeg + 180.0);
        double precessionDeg = generalPrecessionLongitudeDeg(centuries);

        List<SolarSystemEphemeris.Body> result = new ArrayList<>(sources.size());
        for (SmallBody body : sources) {
            if (body.eccentricity >= 1.0 || body.perihelionDistanceAu <= 0.0) {
                continue;
            }
            ApparentPosition pos = apparent(body, jd, earthXyz, sunGeometricLongitude, nutation, trueObliquity, precessionDeg);
            if (pos == null) {
                continue;
            }
            double mag = apparentMagnitude(body, pos.heliocentricDistance, pos.geocentricDistance, pos.phaseAngleDeg);
            if (Double.isFinite(magnitudeLimit) && mag > magnitudeLimit) {
                continue;
            }
            result.add(new SolarSystemEphemeris.Body(
                    body.bodyId(),
                    body.displayLabel(),
                    body.nameEn,
                    pos.raHours,
                    pos.decDegrees,
                    mag,
                    1.0
            ));
        }
        return result;
    }

    static SolarSystemEphemeris.Body computeOne(SmallBody body, Instant when) {
        List<SmallBody> single = new ArrayList<>(1);
        single.add(body);
        List<SolarSystemEphemeris.Body> result = bodies(when, single, Double.POSITIVE_INFINITY);
        return result.isEmpty() ? null : result.get(0);
    }

    private static ApparentPosition apparent(SmallBody body, double jd, double[] earthXyz,
                                             double sunGeometricLongitude, Nutation nutation,
                                             double trueObliquityDeg, double precessionDeg) {
        double a = body.perihelionDistanceAu / (1.0 - body.eccentricity);
        if (!Double.isFinite(a) || a <= 0.0) {
            return null;
        }
        double n = GAUSS_DEG_PER_DAY / Math.pow(a, 1.5);

        double meanAnomalyDeg = n * (jd - body.tpJd);
        double[] geocentric = geocentric(body, a, meanAnomalyDeg, earthXyz, precessionDeg);
        double distance0 = Math.sqrt(geocentric[0] * geocentric[0] + geocentric[1] * geocentric[1] + geocentric[2] * geocentric[2]);
        double tauDays = distance0 / LIGHT_AU_PER_DAY;

        // Iterate once with light-time correction.
        double meanAnomalyEmissionDeg = n * (jd - tauDays - body.tpJd);
        double[] heliocentricJ2000 = heliocentric(body, a, meanAnomalyEmissionDeg);
        double[] heliocentric = rotateAroundZ(heliocentricJ2000, precessionDeg);
        double[] geo = {
                heliocentric[0] - earthXyz[0],
                heliocentric[1] - earthXyz[1],
                heliocentric[2] - earthXyz[2]
        };
        double geoDistance = Math.sqrt(geo[0] * geo[0] + geo[1] * geo[1] + geo[2] * geo[2]);
        double helioDistance = Math.sqrt(heliocentric[0] * heliocentric[0] + heliocentric[1] * heliocentric[1] + heliocentric[2] * heliocentric[2]);

        double longitudeDeg = normalizeDegrees(degrees(Math.atan2(geo[1], geo[0])));
        double latitudeDeg = degrees(Math.asin(clamp(geo[2] / geoDistance, -1.0, 1.0)));

        double appLongitude = applyAberration(longitudeDeg, latitudeDeg, sunGeometricLongitude);
        appLongitude = normalizeDegrees(appLongitude + nutation.deltaPsiDeg);

        EquatorialPoint equatorial = eclipticToEquatorial(appLongitude, latitudeDeg, trueObliquityDeg);

        double earthDistance = Math.sqrt(earthXyz[0] * earthXyz[0] + earthXyz[1] * earthXyz[1] + earthXyz[2] * earthXyz[2]);
        double phaseAngleDeg = phaseAngleDegrees(helioDistance, geoDistance, earthDistance);

        ApparentPosition out = new ApparentPosition();
        out.raHours = equatorial.raHours;
        out.decDegrees = equatorial.decDegrees;
        out.heliocentricDistance = helioDistance;
        out.geocentricDistance = geoDistance;
        out.phaseAngleDeg = phaseAngleDeg;
        return out;
    }

    private static double[] geocentric(SmallBody body, double a, double meanAnomalyDeg, double[] earthXyz, double precessionDeg) {
        double[] helioJ2000 = heliocentric(body, a, meanAnomalyDeg);
        double[] helio = rotateAroundZ(helioJ2000, precessionDeg);
        return new double[]{helio[0] - earthXyz[0], helio[1] - earthXyz[1], helio[2] - earthXyz[2]};
    }

    /**
     * Rotate ecliptic Cartesian J2000 coordinates around the ecliptic pole by
     * the IAU general precession in longitude p_A, yielding mean ecliptic of
     * date. Accurate to a few arcseconds for centuries from J2000 since the
     * ecliptic pole itself moves only slowly (planetary precession).
     */
    private static double[] rotateAroundZ(double[] xyz, double angleDeg) {
        if (angleDeg == 0.0) {
            return xyz;
        }
        double rad = radians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{
                xyz[0] * cos - xyz[1] * sin,
                xyz[0] * sin + xyz[1] * cos,
                xyz[2]
        };
    }

    private static double generalPrecessionLongitudeDeg(double centuries) {
        // p_A in arcseconds (Lieske 1977 / Meeus 21).
        double T = centuries;
        double arcsec = 5028.7962 * T + 1.05039 * T * T - 0.00077 * T * T * T;
        return arcsec / 3600.0;
    }

    private static double[] heliocentric(SmallBody body, double a, double meanAnomalyDeg) {
        double E = eccentricAnomaly(meanAnomalyDeg, body.eccentricity);
        double xv = a * (Math.cos(E) - body.eccentricity);
        double yv = a * Math.sqrt(1.0 - body.eccentricity * body.eccentricity) * Math.sin(E);
        double trueAnomaly = Math.atan2(yv, xv);
        double r = Math.sqrt(xv * xv + yv * yv);

        double node = radians(body.ascendingNodeDeg);
        double inclination = radians(body.inclinationDeg);
        double argument = trueAnomaly + radians(body.argumentPerihelionDeg);
        double cosNode = Math.cos(node);
        double sinNode = Math.sin(node);
        double cosArg = Math.cos(argument);
        double sinArg = Math.sin(argument);
        double cosInc = Math.cos(inclination);
        double sinInc = Math.sin(inclination);

        return new double[]{
                r * (cosNode * cosArg - sinNode * sinArg * cosInc),
                r * (sinNode * cosArg + cosNode * sinArg * cosInc),
                r * (sinArg * sinInc)
        };
    }

    private static double phaseAngleDegrees(double r, double delta, double earthDistance) {
        double cosAlpha = (r * r + delta * delta - earthDistance * earthDistance) / (2.0 * r * delta);
        cosAlpha = clamp(cosAlpha, -1.0, 1.0);
        return degrees(Math.acos(cosAlpha));
    }

    private static double apparentMagnitude(SmallBody body, double r, double delta, double phaseDeg) {
        if (r <= 0.0 || delta <= 0.0) {
            return Double.NaN;
        }
        if (body.isComet) {
            // Comet visual magnitude formula: m = M1 + 5*log10(Δ) + 2.5*K1*log10(r).
            return body.absoluteMagnitude + 5.0 * Math.log10(delta) + 2.5 * body.slope * Math.log10(r);
        }
        // Asteroid Bowell (H, G) model: m = H + 5*log10(r·Δ) - 2.5*log10((1-G)Φ1 + GΦ2).
        double tanHalfAlpha = Math.tan(radians(phaseDeg) * 0.5);
        if (!Double.isFinite(tanHalfAlpha) || tanHalfAlpha < 0.0) {
            tanHalfAlpha = 0.0;
        }
        double phi1 = Math.exp(-3.33 * Math.pow(tanHalfAlpha, 0.63));
        double phi2 = Math.exp(-1.87 * Math.pow(tanHalfAlpha, 1.22));
        double G = body.slope;
        double phaseTerm = -2.5 * Math.log10((1.0 - G) * phi1 + G * phi2);
        return body.absoluteMagnitude + 5.0 * Math.log10(r * delta) + phaseTerm;
    }

    private static double[] sphericalToCartesian(double longitudeRad, double latitudeRad, double radius) {
        double cosLat = Math.cos(latitudeRad);
        return new double[]{
                radius * Math.cos(longitudeRad) * cosLat,
                radius * Math.sin(longitudeRad) * cosLat,
                radius * Math.sin(latitudeRad)
        };
    }

    private static Nutation nutation(double centuries) {
        double T = centuries;
        double Omega = normalizeDegrees(125.04452 - 1934.136261 * T);
        double Lsun = normalizeDegrees(280.4665 + 36000.7698 * T);
        double Lmoon = normalizeDegrees(218.3165 + 481267.8813 * T);

        double deltaPsiArcsec =
                -17.20 * sinDegrees(Omega)
                        - 1.32 * sinDegrees(2.0 * Lsun)
                        - 0.23 * sinDegrees(2.0 * Lmoon)
                        + 0.21 * sinDegrees(2.0 * Omega);
        double deltaEpsArcsec =
                9.20 * cosDegrees(Omega)
                        + 0.57 * cosDegrees(2.0 * Lsun)
                        + 0.10 * cosDegrees(2.0 * Lmoon)
                        - 0.09 * cosDegrees(2.0 * Omega);
        return new Nutation(deltaPsiArcsec / 3600.0, deltaEpsArcsec / 3600.0);
    }

    private static double meanObliquityDegrees(double centuries) {
        double T = centuries;
        double seconds = 21.448 - 46.8150 * T - 0.00059 * T * T + 0.001813 * T * T * T;
        return 23.0 + (26.0 + seconds / 60.0) / 60.0;
    }

    private static double applyAberration(double longitudeDeg, double latitudeDeg, double sunLongitudeDeg) {
        double cosBeta = cosDegrees(latitudeDeg);
        if (Math.abs(cosBeta) < 1.0e-9) {
            return longitudeDeg;
        }
        return longitudeDeg - ABERRATION_DEG * cosDegrees(sunLongitudeDeg - longitudeDeg) / cosBeta;
    }

    private static EquatorialPoint eclipticToEquatorial(double longitudeDeg, double latitudeDeg, double obliquityDeg) {
        double lambda = radians(longitudeDeg);
        double beta = radians(latitudeDeg);
        double obliquity = radians(obliquityDeg);
        double sinBeta = Math.sin(beta);
        double cosBeta = Math.cos(beta);
        double y = Math.sin(lambda) * Math.cos(obliquity) - Math.tan(beta) * Math.sin(obliquity);
        double x = Math.cos(lambda);
        double ra = normalizeDegrees(degrees(Math.atan2(y, x))) / 15.0;
        double dec = degrees(Math.asin(clamp(sinBeta * Math.cos(obliquity) + cosBeta * Math.sin(obliquity) * Math.sin(lambda), -1.0, 1.0)));
        return new EquatorialPoint(ra, dec);
    }

    private static double eccentricAnomaly(double meanAnomalyDegrees, double eccentricity) {
        double meanAnomaly = radians(normalizeDegrees(meanAnomalyDegrees));
        double E = meanAnomaly + eccentricity * Math.sin(meanAnomaly) * (1.0 + eccentricity * Math.cos(meanAnomaly));
        for (int i = 0; i < 8; i++) {
            double delta = (E - eccentricity * Math.sin(E) - meanAnomaly) / (1.0 - eccentricity * Math.cos(E));
            E -= delta;
            if (Math.abs(delta) < 1.0e-10) {
                break;
            }
        }
        return E;
    }

    private static double julianDay(Instant instant) {
        return instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5;
    }

    private static double sinDegrees(double degrees) {
        return Math.sin(radians(degrees));
    }

    private static double cosDegrees(double degrees) {
        return Math.cos(radians(degrees));
    }

    private static double radians(double degrees) {
        return Math.toRadians(degrees);
    }

    private static double degrees(double radians) {
        return Math.toDegrees(radians);
    }

    private static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0.0 ? result + 360.0 : result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Nutation {
        final double deltaPsiDeg;
        final double deltaEpsilonDeg;

        Nutation(double deltaPsiDeg, double deltaEpsilonDeg) {
            this.deltaPsiDeg = deltaPsiDeg;
            this.deltaEpsilonDeg = deltaEpsilonDeg;
        }
    }

    private static final class EquatorialPoint {
        final double raHours;
        final double decDegrees;

        EquatorialPoint(double raHours, double decDegrees) {
            this.raHours = raHours;
            this.decDegrees = decDegrees;
        }
    }

    private static final class ApparentPosition {
        double raHours;
        double decDegrees;
        double heliocentricDistance;
        double geocentricDistance;
        double phaseAngleDeg;
    }
}
