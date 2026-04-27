package com.example.onstepcontroller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class SolarSystemEphemeris {
    private static final double J2000_JULIAN_DAY = 2_451_545.0;
    private static final double SCHLYTER_EPOCH_JULIAN_DAY = 2_451_543.5;

    private SolarSystemEphemeris() {
    }

    static List<Body> bodies(Instant instant) {
        double jd = julianDay(instant);
        double days = jd - SCHLYTER_EPOCH_JULIAN_DAY;
        double j2000Days = jd - J2000_JULIAN_DAY;

        EclipticVector sun = sunVector(days);
        EquatorialPoint sunEquatorial = toEquatorial(sun, days);
        EclipticVector moon = moonVector(days, sun);
        EquatorialPoint moonEquatorial = toEquatorial(moon, days);

        List<Body> result = new ArrayList<>();
        result.add(new Body("sun", "\u592a\u9633", "Sun", sunEquatorial.raHours, sunEquatorial.decDegrees, -26.7, 1.0));
        result.add(new Body("moon", "\u6708\u4eae", "Moon", moonEquatorial.raHours, moonEquatorial.decDegrees, -12.0, phaseFraction(sun, moon)));

        addPlanet(result, "mercury", "\u6c34\u661f", "Mercury", Planet.MERCURY, days, j2000Days, sun);
        addPlanet(result, "venus", "\u91d1\u661f", "Venus", Planet.VENUS, days, j2000Days, sun);
        addPlanet(result, "mars", "\u706b\u661f", "Mars", Planet.MARS, days, j2000Days, sun);
        addPlanet(result, "jupiter", "\u6728\u661f", "Jupiter", Planet.JUPITER, days, j2000Days, sun);
        addPlanet(result, "saturn", "\u571f\u661f", "Saturn", Planet.SATURN, days, j2000Days, sun);
        addPlanet(result, "uranus", "\u5929\u738b\u661f", "Uranus", Planet.URANUS, days, j2000Days, sun);
        addPlanet(result, "neptune", "\u6d77\u738b\u661f", "Neptune", Planet.NEPTUNE, days, j2000Days, sun);
        return Collections.unmodifiableList(result);
    }

    static Body findBody(Instant instant, String query) {
        String normalized = normalizeName(query);
        if (normalized.isEmpty() && (query == null || query.trim().isEmpty())) {
            return null;
        }
        String trimmed = query == null ? "" : query.trim();
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

    private static void addPlanet(
            List<Body> result,
            String id,
            String label,
            String englishName,
            Planet planet,
            double days,
            double j2000Days,
            EclipticVector sun
    ) {
        EclipticVector heliocentric = planetHeliocentric(planet, days);
        if (planet == Planet.JUPITER || planet == Planet.SATURN || planet == Planet.URANUS) {
            heliocentric = applyOuterPlanetPerturbations(planet, heliocentric, days);
        }
        EclipticVector geocentric = new EclipticVector(
                heliocentric.x + sun.x,
                heliocentric.y + sun.y,
                heliocentric.z
        );
        EquatorialPoint equatorial = toEquatorial(geocentric, days);
        result.add(new Body(id, label, englishName, equatorial.raHours, equatorial.decDegrees, approximateMagnitude(planet, j2000Days), 1.0));
    }

    private static EclipticVector sunVector(double days) {
        double meanAnomaly = normalizeDegrees(356.0470 + 0.9856002585 * days);
        double perihelion = normalizeDegrees(282.9404 + 0.0000470935 * days);
        double eccentricity = 0.016709 - 0.000000001151 * days;
        double eccentricAnomaly = eccentricAnomaly(meanAnomaly, eccentricity);
        double xv = Math.cos(eccentricAnomaly) - eccentricity;
        double yv = Math.sqrt(1.0 - eccentricity * eccentricity) * Math.sin(eccentricAnomaly);
        double trueAnomaly = Math.atan2(yv, xv);
        double radius = Math.sqrt(xv * xv + yv * yv);
        double longitude = trueAnomaly + radians(perihelion);
        return EclipticVector.fromSpherical(degrees(longitude), 0.0, radius);
    }

    private static EclipticVector moonVector(double days, EclipticVector sun) {
        double ascendingNode = normalizeDegrees(125.1228 - 0.0529538083 * days);
        double inclination = 5.1454;
        double perihelion = normalizeDegrees(318.0634 + 0.1643573223 * days);
        double eccentricity = 0.054900;
        double meanAnomaly = normalizeDegrees(115.3654 + 13.0649929509 * days);
        EclipticVector base = orbitalToEcliptic(ascendingNode, inclination, perihelion, 60.2666, eccentricity, meanAnomaly);

        SphericalEcliptic spherical = base.toSpherical();
        double sunLongitude = sun.toSpherical().longitudeDegrees;
        double sunMeanAnomaly = normalizeDegrees(356.0470 + 0.9856002585 * days);
        double moonMeanLongitude = normalizeDegrees(ascendingNode + perihelion + meanAnomaly);
        double elongation = normalizeDegrees(moonMeanLongitude - sunLongitude);
        double argumentOfLatitude = normalizeDegrees(moonMeanLongitude - ascendingNode);

        double longitudeCorrection =
                -1.274 * sinDegrees(meanAnomaly - 2.0 * elongation)
                        + 0.658 * sinDegrees(2.0 * elongation)
                        - 0.186 * sinDegrees(sunMeanAnomaly)
                        - 0.059 * sinDegrees(2.0 * meanAnomaly - 2.0 * elongation)
                        - 0.057 * sinDegrees(meanAnomaly - 2.0 * elongation + sunMeanAnomaly)
                        + 0.053 * sinDegrees(meanAnomaly + 2.0 * elongation)
                        + 0.046 * sinDegrees(2.0 * elongation - sunMeanAnomaly)
                        + 0.041 * sinDegrees(meanAnomaly - sunMeanAnomaly)
                        - 0.035 * sinDegrees(elongation)
                        - 0.031 * sinDegrees(meanAnomaly + sunMeanAnomaly)
                        - 0.015 * sinDegrees(2.0 * argumentOfLatitude - 2.0 * elongation)
                        + 0.011 * sinDegrees(meanAnomaly - 4.0 * elongation);
        double latitudeCorrection =
                -0.173 * sinDegrees(argumentOfLatitude - 2.0 * elongation)
                        - 0.055 * sinDegrees(meanAnomaly - argumentOfLatitude - 2.0 * elongation)
                        - 0.046 * sinDegrees(meanAnomaly + argumentOfLatitude - 2.0 * elongation)
                        + 0.033 * sinDegrees(argumentOfLatitude + 2.0 * elongation)
                        + 0.017 * sinDegrees(2.0 * meanAnomaly + argumentOfLatitude);
        double distanceCorrection =
                -0.58 * cosDegrees(meanAnomaly - 2.0 * elongation)
                        - 0.46 * cosDegrees(2.0 * elongation);

        return EclipticVector.fromSpherical(
                spherical.longitudeDegrees + longitudeCorrection,
                spherical.latitudeDegrees + latitudeCorrection,
                spherical.radius + distanceCorrection
        );
    }

    private static EclipticVector planetHeliocentric(Planet planet, double days) {
        Elements elements = elements(planet, days);
        return orbitalToEcliptic(
                elements.ascendingNodeDegrees,
                elements.inclinationDegrees,
                elements.perihelionDegrees,
                elements.semiMajorAxisAu,
                elements.eccentricity,
                elements.meanAnomalyDegrees
        );
    }

    private static EclipticVector applyOuterPlanetPerturbations(Planet planet, EclipticVector geocentric, double days) {
        double jupiterMeanAnomaly = normalizeDegrees(19.8950 + 0.0830853001 * days);
        double saturnMeanAnomaly = normalizeDegrees(316.9670 + 0.0334442282 * days);
        double uranusMeanAnomaly = normalizeDegrees(142.5905 + 0.011725806 * days);
        SphericalEcliptic spherical = geocentric.toSpherical();
        double longitude = spherical.longitudeDegrees;
        double latitude = spherical.latitudeDegrees;
        if (planet == Planet.JUPITER) {
            longitude += -0.332 * sinDegrees(2.0 * jupiterMeanAnomaly - 5.0 * saturnMeanAnomaly - 67.6)
                    - 0.056 * sinDegrees(2.0 * jupiterMeanAnomaly - 2.0 * saturnMeanAnomaly + 21.0)
                    + 0.042 * sinDegrees(3.0 * jupiterMeanAnomaly - 5.0 * saturnMeanAnomaly + 21.0)
                    - 0.036 * sinDegrees(jupiterMeanAnomaly - 2.0 * saturnMeanAnomaly)
                    + 0.022 * cosDegrees(jupiterMeanAnomaly - saturnMeanAnomaly)
                    + 0.023 * sinDegrees(2.0 * jupiterMeanAnomaly - 3.0 * saturnMeanAnomaly + 52.0)
                    - 0.016 * sinDegrees(jupiterMeanAnomaly - 5.0 * saturnMeanAnomaly - 69.0);
        } else if (planet == Planet.SATURN) {
            longitude += 0.812 * sinDegrees(2.0 * jupiterMeanAnomaly - 5.0 * saturnMeanAnomaly - 67.6)
                    - 0.229 * cosDegrees(2.0 * jupiterMeanAnomaly - 4.0 * saturnMeanAnomaly - 2.0)
                    + 0.119 * sinDegrees(jupiterMeanAnomaly - 2.0 * saturnMeanAnomaly - 3.0)
                    + 0.046 * sinDegrees(2.0 * jupiterMeanAnomaly - 6.0 * saturnMeanAnomaly - 69.0)
                    + 0.014 * sinDegrees(jupiterMeanAnomaly - 3.0 * saturnMeanAnomaly + 32.0);
            latitude += -0.020 * cosDegrees(2.0 * jupiterMeanAnomaly - 4.0 * saturnMeanAnomaly - 2.0)
                    + 0.018 * sinDegrees(2.0 * jupiterMeanAnomaly - 6.0 * saturnMeanAnomaly - 49.0);
        } else if (planet == Planet.URANUS) {
            longitude += 0.040 * sinDegrees(saturnMeanAnomaly - 2.0 * uranusMeanAnomaly + 6.0)
                    + 0.035 * sinDegrees(saturnMeanAnomaly - 3.0 * uranusMeanAnomaly + 33.0)
                    - 0.015 * sinDegrees(jupiterMeanAnomaly - uranusMeanAnomaly + 20.0);
        }
        return EclipticVector.fromSpherical(longitude, latitude, spherical.radius);
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

    private static EquatorialPoint toEquatorial(EclipticVector vector, double days) {
        double obliquity = radians(23.4393 - 0.0000003563 * days);
        double x = vector.x;
        double y = vector.y * Math.cos(obliquity) - vector.z * Math.sin(obliquity);
        double z = vector.y * Math.sin(obliquity) + vector.z * Math.cos(obliquity);
        double ra = normalizeDegrees(degrees(Math.atan2(y, x))) / 15.0;
        double dec = degrees(Math.atan2(z, Math.sqrt(x * x + y * y)));
        return new EquatorialPoint(ra, dec);
    }

    private static double phaseFraction(EclipticVector sun, EclipticVector moon) {
        double dot = sun.normalized().dot(moon.normalized());
        return clamp((1.0 - dot) * 0.5, 0.0, 1.0);
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

    private static Elements elements(Planet planet, double days) {
        switch (planet) {
            case MERCURY:
                return new Elements(
                        48.3313 + 0.0000324587 * days,
                        7.0047 + 0.00000005 * days,
                        29.1241 + 0.0000101444 * days,
                        0.387098,
                        0.205635 + 0.000000000559 * days,
                        168.6562 + 4.0923344368 * days
                );
            case VENUS:
                return new Elements(
                        76.6799 + 0.0000246590 * days,
                        3.3946 + 0.0000000275 * days,
                        54.8910 + 0.0000138374 * days,
                        0.723330,
                        0.006773 - 0.000000001302 * days,
                        48.0052 + 1.6021302244 * days
                );
            case MARS:
                return new Elements(
                        49.5574 + 0.0000211081 * days,
                        1.8497 - 0.0000000178 * days,
                        286.5016 + 0.0000292961 * days,
                        1.523688,
                        0.093405 + 0.000000002516 * days,
                        18.6021 + 0.5240207766 * days
                );
            case JUPITER:
                return new Elements(
                        100.4542 + 0.0000276854 * days,
                        1.3030 - 0.0000001557 * days,
                        273.8777 + 0.0000164505 * days,
                        5.20256,
                        0.048498 + 0.000000004469 * days,
                        19.8950 + 0.0830853001 * days
                );
            case SATURN:
                return new Elements(
                        113.6634 + 0.0000238980 * days,
                        2.4886 - 0.0000001081 * days,
                        339.3939 + 0.0000297661 * days,
                        9.55475,
                        0.055546 - 0.000000009499 * days,
                        316.9670 + 0.0334442282 * days
                );
            case URANUS:
                return new Elements(
                        74.0005 + 0.000013978 * days,
                        0.7733 + 0.000000019 * days,
                        96.6612 + 0.000030565 * days,
                        19.18171 - 0.0000000155 * days,
                        0.047318 + 0.00000000745 * days,
                        142.5905 + 0.011725806 * days
                );
            case NEPTUNE:
                return new Elements(
                        131.7806 + 0.000030173 * days,
                        1.7700 - 0.000000255 * days,
                        272.8461 - 0.000006027 * days,
                        30.05826 + 0.00000003313 * days,
                        0.008606 + 0.00000000215 * days,
                        260.2471 + 0.005995147 * days
                );
            default:
                throw new IllegalArgumentException("Unsupported planet " + planet);
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

    private static final class Elements {
        final double ascendingNodeDegrees;
        final double inclinationDegrees;
        final double perihelionDegrees;
        final double semiMajorAxisAu;
        final double eccentricity;
        final double meanAnomalyDegrees;

        Elements(
                double ascendingNodeDegrees,
                double inclinationDegrees,
                double perihelionDegrees,
                double semiMajorAxisAu,
                double eccentricity,
                double meanAnomalyDegrees
        ) {
            this.ascendingNodeDegrees = ascendingNodeDegrees;
            this.inclinationDegrees = inclinationDegrees;
            this.perihelionDegrees = perihelionDegrees;
            this.semiMajorAxisAu = semiMajorAxisAu;
            this.eccentricity = eccentricity;
            this.meanAnomalyDegrees = meanAnomalyDegrees;
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

        EclipticVector normalized() {
            double radius = Math.sqrt(x * x + y * y + z * z);
            if (radius < 1.0e-12) {
                return new EclipticVector(1.0, 0.0, 0.0);
            }
            return new EclipticVector(x / radius, y / radius, z / radius);
        }

        double dot(EclipticVector other) {
            return x * other.x + y * other.y + z * other.z;
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
}
