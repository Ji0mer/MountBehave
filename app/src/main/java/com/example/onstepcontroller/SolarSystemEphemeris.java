package com.example.onstepcontroller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class SolarSystemEphemeris {
    private static final double J2000_JULIAN_DAY = 2_451_545.0;
    private static final double SCHLYTER_EPOCH_JULIAN_DAY = 2_451_543.5;
    // Speed of light in AU/day (1/c·86400, with c = 299792.458 km/s, 1 AU = 149597870.7 km)
    private static final double LIGHT_AU_PER_DAY = 173.144632674240;
    // Annual aberration constant κ in degrees (20.49552″).
    private static final double ABERRATION_DEG = 20.49552 / 3600.0;
    private static final double DAYS_PER_MILLENNIUM = 365250.0;

    private SolarSystemEphemeris() {
    }

    static List<Body> bodies(Instant instant) {
        double jd = julianDay(instant);
        double centuries = (jd - J2000_JULIAN_DAY) / 36525.0;
        double millennia = (jd - J2000_JULIAN_DAY) / DAYS_PER_MILLENNIUM;
        double days = jd - SCHLYTER_EPOCH_JULIAN_DAY;

        Nutation nutation = nutation(centuries);
        double meanObliquity = meanObliquityDegrees(centuries);
        double trueObliquity = meanObliquity + nutation.deltaEpsilonDeg;

        double[] earth = Vsop87Tables.earthCoordinates(millennia);
        double earthLDeg = normalizeDegrees(degrees(earth[0]));
        double earthBDeg = degrees(earth[1]);
        double earthR = earth[2];
        EclipticVector earthHelio = EclipticVector.fromSpherical(earthLDeg, earthBDeg, earthR);

        double sunGeometricLongitude = normalizeDegrees(earthLDeg + 180.0);
        double sunGeometricLatitude = -earthBDeg;
        double sunApparentLongitude = normalizeDegrees(
                sunGeometricLongitude - ABERRATION_DEG + nutation.deltaPsiDeg);
        EquatorialPoint sunEquatorial = eclipticToEquatorial(sunApparentLongitude, sunGeometricLatitude, trueObliquity);

        double sunMeanAnomalyDeg = normalizeDegrees(
                357.52911 + 35999.05029 * centuries - 0.0001537 * centuries * centuries);

        MoonPosition moon = moonPosition(days, sunGeometricLongitude, sunMeanAnomalyDeg, nutation);
        EquatorialPoint moonEquatorial = eclipticToEquatorial(moon.apparentLongitudeDeg, moon.latitudeDeg, trueObliquity);

        List<Body> result = new ArrayList<>();
        result.add(new Body("sun", "太阳", "Sun", sunEquatorial.raHours, sunEquatorial.decDegrees, -26.7, 1.0));
        result.add(new Body("moon", "月亮", "Moon", moonEquatorial.raHours, moonEquatorial.decDegrees, -12.0,
                phaseFraction(sunGeometricLongitude, moon.longitudeDeg)));

        double j2000Days = jd - J2000_JULIAN_DAY;
        addPlanet(result, "mercury", "水星", "Mercury", Planet.MERCURY, millennia, j2000Days, earthHelio, sunGeometricLongitude, nutation, trueObliquity);
        addPlanet(result, "venus", "金星", "Venus", Planet.VENUS, millennia, j2000Days, earthHelio, sunGeometricLongitude, nutation, trueObliquity);
        addPlanet(result, "mars", "火星", "Mars", Planet.MARS, millennia, j2000Days, earthHelio, sunGeometricLongitude, nutation, trueObliquity);
        addPlanet(result, "jupiter", "木星", "Jupiter", Planet.JUPITER, millennia, j2000Days, earthHelio, sunGeometricLongitude, nutation, trueObliquity);
        addPlanet(result, "saturn", "土星", "Saturn", Planet.SATURN, millennia, j2000Days, earthHelio, sunGeometricLongitude, nutation, trueObliquity);
        addPlanet(result, "uranus", "天王星", "Uranus", Planet.URANUS, millennia, j2000Days, earthHelio, sunGeometricLongitude, nutation, trueObliquity);
        addPlanet(result, "neptune", "海王星", "Neptune", Planet.NEPTUNE, millennia, j2000Days, earthHelio, sunGeometricLongitude, nutation, trueObliquity);
        return Collections.unmodifiableList(result);
    }

    static Body findBody(Instant instant, String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        String normalized = normalizeName(query);
        String trimmed = query.trim();
        for (Body body : bodies(instant)) {
            if (body.label.equals(trimmed)
                    || body.englishName.equalsIgnoreCase(trimmed)
                    || body.id.equalsIgnoreCase(trimmed)
                    || normalizeName(body.label).equals(normalized)
                    || normalizeName(body.englishName).equals(normalized)
                    || normalizeName(body.id).equals(normalized)) {
                return body;
            }
        }
        return null;
    }

    private static MoonPosition moonPosition(double days, double sunGeometricLongitude, double sunMeanAnomaly, Nutation nutation) {
        double ascendingNode = normalizeDegrees(125.1228 - 0.0529538083 * days);
        double inclination = 5.1454;
        double perihelion = normalizeDegrees(318.0634 + 0.1643573223 * days);
        double eccentricity = 0.054900;
        double meanAnomaly = normalizeDegrees(115.3654 + 13.0649929509 * days);
        EclipticVector base = orbitalToEcliptic(ascendingNode, inclination, perihelion, 60.2666, eccentricity, meanAnomaly);

        SphericalEcliptic spherical = base.toSpherical();
        double moonMeanLongitude = normalizeDegrees(ascendingNode + perihelion + meanAnomaly);
        double elongation = normalizeDegrees(moonMeanLongitude - sunGeometricLongitude);
        double argumentOfLatitude = normalizeDegrees(moonMeanLongitude - ascendingNode);

        double Mp = meanAnomaly;
        double Ms = sunMeanAnomaly;
        double D = elongation;
        double F = argumentOfLatitude;

        double dl =
                -1.274 * sinDegrees(Mp - 2.0 * D)
                        + 0.658 * sinDegrees(2.0 * D)
                        - 0.186 * sinDegrees(Ms)
                        - 0.059 * sinDegrees(2.0 * Mp - 2.0 * D)
                        - 0.057 * sinDegrees(Mp - 2.0 * D + Ms)
                        + 0.053 * sinDegrees(Mp + 2.0 * D)
                        + 0.046 * sinDegrees(2.0 * D - Ms)
                        + 0.041 * sinDegrees(Mp - Ms)
                        - 0.035 * sinDegrees(D)
                        - 0.031 * sinDegrees(Mp + Ms)
                        - 0.015 * sinDegrees(2.0 * F - 2.0 * D)
                        + 0.011 * sinDegrees(Mp - 4.0 * D)
                        + 0.0214 * sinDegrees(2.0 * Mp)
                        - 0.0066 * sinDegrees(2.0 * Mp - 2.0 * Ms)
                        - 0.0058 * sinDegrees(2.0 * F)
                        + 0.0057 * sinDegrees(Mp + 2.0 * D - 2.0 * Ms)
                        + 0.0053 * sinDegrees(Mp - 2.0 * D - Ms);

        double db =
                -0.173 * sinDegrees(F - 2.0 * D)
                        - 0.055 * sinDegrees(Mp - F - 2.0 * D)
                        - 0.046 * sinDegrees(Mp + F - 2.0 * D)
                        + 0.033 * sinDegrees(F + 2.0 * D)
                        + 0.017 * sinDegrees(2.0 * Mp + F)
                        - 0.0090 * sinDegrees(F - 2.0 * D - Ms)
                        - 0.0080 * sinDegrees(F - 2.0 * D + Ms)
                        + 0.0070 * sinDegrees(F + 2.0 * D - Ms)
                        + 0.0050 * sinDegrees(F - Mp + 2.0 * D);

        double dr =
                -0.58 * cosDegrees(Mp - 2.0 * D)
                        - 0.46 * cosDegrees(2.0 * D)
                        - 0.058 * cosDegrees(2.0 * Mp);

        double longitudeDeg = normalizeDegrees(spherical.longitudeDegrees + dl);
        double latitudeDeg = spherical.latitudeDegrees + db;
        double distance = spherical.radius + dr;
        double apparentLongitudeDeg = normalizeDegrees(longitudeDeg + nutation.deltaPsiDeg);
        return new MoonPosition(longitudeDeg, apparentLongitudeDeg, latitudeDeg, distance);
    }

    private static void addPlanet(
            List<Body> result,
            String id,
            String label,
            String englishName,
            Planet planet,
            double millennia,
            double j2000Days,
            EclipticVector earthHelio,
            double sunLongitudeDeg,
            Nutation nutation,
            double trueObliquityDeg
    ) {
        EclipticVector geocentric = geocentricFromHelio(planet, millennia, earthHelio);
        double distance = Math.sqrt(geocentric.x * geocentric.x + geocentric.y * geocentric.y + geocentric.z * geocentric.z);
        double tauDays = distance / LIGHT_AU_PER_DAY;
        double millenniaAtEmission = millennia - tauDays / DAYS_PER_MILLENNIUM;
        EclipticVector geocentricCorrected = geocentricFromHelio(planet, millenniaAtEmission, earthHelio);

        SphericalEcliptic spherical = geocentricCorrected.toSpherical();
        double apparentLongitude = applyAberration(spherical.longitudeDegrees, spherical.latitudeDegrees, sunLongitudeDeg);
        apparentLongitude = normalizeDegrees(apparentLongitude + nutation.deltaPsiDeg);

        EquatorialPoint equatorial = eclipticToEquatorial(apparentLongitude, spherical.latitudeDegrees, trueObliquityDeg);
        result.add(new Body(id, label, englishName, equatorial.raHours, equatorial.decDegrees, approximateMagnitude(planet, j2000Days), 1.0));
    }

    private static EclipticVector geocentricFromHelio(Planet planet, double millennia, EclipticVector earthHelio) {
        double[] lbr = Vsop87Tables.coordinates(planet, millennia);
        double longitudeDeg = normalizeDegrees(degrees(lbr[0]));
        double latitudeDeg = degrees(lbr[1]);
        EclipticVector helio = EclipticVector.fromSpherical(longitudeDeg, latitudeDeg, lbr[2]);
        return new EclipticVector(
                helio.x - earthHelio.x,
                helio.y - earthHelio.y,
                helio.z - earthHelio.z
        );
    }

    private static double applyAberration(double longitudeDeg, double latitudeDeg, double sunLongitudeDeg) {
        double cosBeta = cosDegrees(latitudeDeg);
        if (Math.abs(cosBeta) < 1.0e-9) {
            return longitudeDeg;
        }
        double deltaLongitude = -ABERRATION_DEG * cosDegrees(sunLongitudeDeg - longitudeDeg) / cosBeta;
        return longitudeDeg + deltaLongitude;
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
        double deltaEpsilonArcsec =
                9.20 * cosDegrees(Omega)
                        + 0.57 * cosDegrees(2.0 * Lsun)
                        + 0.10 * cosDegrees(2.0 * Lmoon)
                        - 0.09 * cosDegrees(2.0 * Omega);
        return new Nutation(deltaPsiArcsec / 3600.0, deltaEpsilonArcsec / 3600.0);
    }

    private static double meanObliquityDegrees(double centuries) {
        double T = centuries;
        double seconds = 21.448 - 46.8150 * T - 0.00059 * T * T + 0.001813 * T * T * T;
        return 23.0 + (26.0 + seconds / 60.0) / 60.0;
    }

    private static EclipticVector orbitalToEcliptic(
            double ascendingNodeDegrees,
            double inclinationDegrees,
            double perihelionDegrees,
            double semiMajorAxis,
            double eccentricity,
            double meanAnomalyDegrees
    ) {
        double eccentricAnomaly = eccentricAnomaly(meanAnomalyDegrees, eccentricity);
        double xv = semiMajorAxis * (Math.cos(eccentricAnomaly) - eccentricity);
        double yv = semiMajorAxis * Math.sqrt(1.0 - eccentricity * eccentricity) * Math.sin(eccentricAnomaly);
        double trueAnomaly = Math.atan2(yv, xv);
        double radius = Math.sqrt(xv * xv + yv * yv);

        double node = radians(ascendingNodeDegrees);
        double inclination = radians(inclinationDegrees);
        double argument = trueAnomaly + radians(perihelionDegrees);
        double cosNode = Math.cos(node);
        double sinNode = Math.sin(node);
        double cosArgument = Math.cos(argument);
        double sinArgument = Math.sin(argument);
        double cosInclination = Math.cos(inclination);
        double sinInclination = Math.sin(inclination);

        return new EclipticVector(
                radius * (cosNode * cosArgument - sinNode * sinArgument * cosInclination),
                radius * (sinNode * cosArgument + cosNode * sinArgument * cosInclination),
                radius * (sinArgument * sinInclination)
        );
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

    private static double phaseFraction(double sunLongitudeDeg, double moonLongitudeDeg) {
        double elongation = normalizeDegrees(moonLongitudeDeg - sunLongitudeDeg);
        double radians = radians(elongation);
        return clamp((1.0 - Math.cos(radians)) * 0.5, 0.0, 1.0);
    }

    private static double approximateMagnitude(Planet planet, double j2000Days) {
        switch (planet) {
            case MERCURY:
                return -0.2;
            case VENUS:
                return -4.2;
            case MARS:
                return -1.0 + 0.00018 * Math.abs(j2000Days);
            case JUPITER:
                return -2.4;
            case SATURN:
                return 0.7;
            case URANUS:
                return 5.7;
            case NEPTUNE:
                return 7.8;
            default:
                return 0.0;
        }
    }

    private static double eccentricAnomaly(double meanAnomalyDegrees, double eccentricity) {
        double meanAnomaly = radians(normalizeDegrees(meanAnomalyDegrees));
        double eccentricAnomaly = meanAnomaly + eccentricity * Math.sin(meanAnomaly) * (1.0 + eccentricity * Math.cos(meanAnomaly));
        for (int i = 0; i < 6; i++) {
            double delta = (eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly) - meanAnomaly)
                    / (1.0 - eccentricity * Math.cos(eccentricAnomaly));
            eccentricAnomaly -= delta;
            if (Math.abs(delta) < 1.0e-10) {
                break;
            }
        }
        return eccentricAnomaly;
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

    private static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    enum Planet {
        MERCURY,
        VENUS,
        MARS,
        JUPITER,
        SATURN,
        URANUS,
        NEPTUNE
    }

    static final class Body {
        final String id;
        final String label;
        final String englishName;
        final double raHours;
        final double decDegrees;
        final double magnitude;
        final double phaseFraction;

        Body(String id, String label, String englishName, double raHours, double decDegrees, double magnitude, double phaseFraction) {
            this.id = id;
            this.label = label;
            this.englishName = englishName;
            this.raHours = raHours;
            this.decDegrees = decDegrees;
            this.magnitude = magnitude;
            this.phaseFraction = phaseFraction;
        }
    }

    private static final class EclipticVector {
        final double x;
        final double y;
        final double z;

        EclipticVector(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static EclipticVector fromSpherical(double longitudeDegrees, double latitudeDegrees, double radius) {
            double longitude = radians(longitudeDegrees);
            double latitude = radians(latitudeDegrees);
            double cosLatitude = Math.cos(latitude);
            return new EclipticVector(
                    radius * Math.cos(longitude) * cosLatitude,
                    radius * Math.sin(longitude) * cosLatitude,
                    radius * Math.sin(latitude)
            );
        }

        SphericalEcliptic toSpherical() {
            double radius = Math.sqrt(x * x + y * y + z * z);
            if (radius < 1.0e-12) {
                return new SphericalEcliptic(0.0, 0.0, 0.0);
            }
            return new SphericalEcliptic(
                    normalizeDegrees(degrees(Math.atan2(y, x))),
                    degrees(Math.asin(clamp(z / radius, -1.0, 1.0))),
                    radius
            );
        }
    }

    private static final class SphericalEcliptic {
        final double longitudeDegrees;
        final double latitudeDegrees;
        final double radius;

        SphericalEcliptic(double longitudeDegrees, double latitudeDegrees, double radius) {
            this.longitudeDegrees = longitudeDegrees;
            this.latitudeDegrees = latitudeDegrees;
            this.radius = radius;
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

    private static final class Nutation {
        final double deltaPsiDeg;
        final double deltaEpsilonDeg;

        Nutation(double deltaPsiDeg, double deltaEpsilonDeg) {
            this.deltaPsiDeg = deltaPsiDeg;
            this.deltaEpsilonDeg = deltaEpsilonDeg;
        }
    }

    private static final class MoonPosition {
        final double longitudeDeg;
        final double apparentLongitudeDeg;
        final double latitudeDeg;
        final double distanceEarthRadii;

        MoonPosition(double longitudeDeg, double apparentLongitudeDeg, double latitudeDeg, double distanceEarthRadii) {
            this.longitudeDeg = longitudeDeg;
            this.apparentLongitudeDeg = apparentLongitudeDeg;
            this.latitudeDeg = latitudeDeg;
            this.distanceEarthRadii = distanceEarthRadii;
        }
    }
}
