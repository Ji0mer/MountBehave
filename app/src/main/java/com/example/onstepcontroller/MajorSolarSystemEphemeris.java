package com.example.onstepcontroller;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MajorSolarSystemEphemeris {
    private static final String ASSET_PATH = "ephemeris/solar_major_vectors.bin";
    private static final byte[] MAGIC = new byte[]{'M', 'B', 'E', 'P', 'H', '0', '1', '\n'};
    private static final double EARTH_EQUATORIAL_RADIUS_AU = 6_378.137 / 149_597_870.7;
    private static final double WGS84_FLATTENING = 1.0 / 298.257_223_563;
    private static final ExecutorService LOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "major-ephemeris-loader");
        thread.setDaemon(true);
        return thread;
    });

    private static final String[] IDS = {
            "sun", "moon", "mercury", "venus", "mars", "jupiter", "saturn", "uranus", "neptune"
    };
    private static final String[] LABELS = {
            "\u592a\u9633", "\u6708\u7403", "\u6c34\u661f", "\u91d1\u661f", "\u706b\u661f",
            "\u6728\u661f", "\u571f\u661f", "\u5929\u738b\u661f", "\u6d77\u738b\u661f"
    };
    private static final String[] ENGLISH = {
            "Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"
    };
    private static final double[] DEFAULT_MAGNITUDES = {
            -26.7, -12.0, -0.2, -4.2, -1.0, -2.4, 0.7, 5.7, 7.8
    };

    private static volatile Table table;
    private static volatile boolean attemptedLoad;
    private static volatile boolean loadFinished;
    private static volatile boolean fallbackWarningLogged;

    private MajorSolarSystemEphemeris() {
    }

    static void init(Context context) {
        if (attemptedLoad || context == null) {
            return;
        }
        synchronized (MajorSolarSystemEphemeris.class) {
            if (attemptedLoad) {
                return;
            }
            attemptedLoad = true;
            Context appContext = context.getApplicationContext();
            LOAD_EXECUTOR.execute(() -> {
                try {
                    Table loaded = Table.load(appContext);
                    table = loaded;
                    Logger.info("major solar-system ephemeris loaded samples=" + loaded.sampleCount
                            + " bodies=" + loaded.bodyCount
                            + " startJd=" + loaded.startJulianDay
                            + " stepDays=" + loaded.stepDays);
                } catch (IOException | RuntimeException ex) {
                    Logger.warn("major solar-system ephemeris unavailable; falling back to analytic model: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
                } finally {
                    loadFinished = true;
                }
            });
        }
    }

    static List<SolarSystemEphemeris.Body> bodies(Instant instant, ObserverState observer) {
        Table current = table;
        if (current == null || instant == null) {
            if (current == null && attemptedLoad && loadFinished) {
                warnFallbackOnce("major solar-system ephemeris is not loaded");
            }
            return null;
        }
        double jd = julianDay(instant);
        if (!current.contains(jd)) {
            warnFallbackOnce("major solar-system ephemeris out of range jd=" + jd
                    + " supported=[" + current.startJulianDay + ", " + current.endJulianDay() + "]");
            return null;
        }

        Vector3[] vectors = current.interpolate(jd);
        Vector3 sun = vectors[0];
        Vector3 moon = vectors[1];
        double moonPhase = illuminatedFraction(sun, moon);
        boolean english = Locale.getDefault().getLanguage().equals(Locale.ENGLISH.getLanguage());

        Vector3 observerVector = observer == null ? null : observerVectorAu(jd, observer);
        List<SolarSystemEphemeris.Body> result = new ArrayList<>(IDS.length);
        for (int i = 0; i < IDS.length; i++) {
            Vector3 geocentricVector = vectors[i];
            Vector3 vector = geocentricVector;
            if (observerVector != null) {
                vector = vector.minus(observerVector);
            }
            EquatorialPoint equatorial = vector.toEquatorial();
            result.add(new SolarSystemEphemeris.Body(
                    IDS[i],
                    english ? ENGLISH[i] : LABELS[i],
                    ENGLISH[i],
                    equatorial.raHours,
                    equatorial.decDegrees,
                    apparentMagnitude(i, geocentricVector, sun),
                    "moon".equals(IDS[i]) ? moonPhase : 1.0
            ));
        }
        return Collections.unmodifiableList(result);
    }

    private static void warnFallbackOnce(String reason) {
        if (fallbackWarningLogged) {
            return;
        }
        synchronized (MajorSolarSystemEphemeris.class) {
            if (!fallbackWarningLogged) {
                fallbackWarningLogged = true;
                Logger.warn(reason + "; falling back to analytic solar-system model");
            }
        }
    }

    private static double illuminatedFraction(Vector3 sunVector, Vector3 moonVector) {
        double cosElongation = sunVector.dot(moonVector) / Math.max(1.0e-12, sunVector.length() * moonVector.length());
        return clamp((1.0 - cosElongation) * 0.5, 0.0, 1.0);
    }

    private static double apparentMagnitude(int bodyIndex, Vector3 geocentricVector, Vector3 sunVector) {
        String id = IDS[bodyIndex];
        if ("sun".equals(id) || "moon".equals(id)) {
            return DEFAULT_MAGNITUDES[bodyIndex];
        }
        Vector3 heliocentricVector = geocentricVector.minus(sunVector);
        double delta = Math.max(1.0e-6, geocentricVector.length());
        double r = Math.max(1.0e-6, heliocentricVector.length());
        double phaseAngle = phaseAngleDegrees(heliocentricVector, geocentricVector);
        double distanceTerm = 5.0 * Math.log10(r * delta);
        switch (id) {
            case "mercury":
                return -0.42 + distanceTerm + 0.038 * phaseAngle - 0.000273 * phaseAngle * phaseAngle
                        + 0.000002 * phaseAngle * phaseAngle * phaseAngle;
            case "venus":
                return -4.40 + distanceTerm + 0.0009 * phaseAngle + 0.000239 * phaseAngle * phaseAngle
                        - 0.00000065 * phaseAngle * phaseAngle * phaseAngle;
            case "mars":
                return -1.52 + distanceTerm + 0.016 * phaseAngle;
            case "jupiter":
                return -9.40 + distanceTerm;
            case "saturn":
                return -8.88 + distanceTerm;
            case "uranus":
                return -7.19 + distanceTerm;
            case "neptune":
                return -6.87 + distanceTerm;
            default:
                return DEFAULT_MAGNITUDES[bodyIndex];
        }
    }

    private static double phaseAngleDegrees(Vector3 heliocentricVector, Vector3 geocentricVector) {
        double denom = Math.max(1.0e-12, heliocentricVector.length() * geocentricVector.length());
        double cosPhase = heliocentricVector.dot(geocentricVector) / denom;
        return Math.toDegrees(Math.acos(clamp(cosPhase, -1.0, 1.0)));
    }

    private static Vector3 observerVectorAu(double jd, ObserverState observer) {
        double latitude = Math.toRadians(observer.latitudeDegrees);
        double longitudeDegrees = observer.longitudeDegrees;
        double lst = Math.toRadians(localSiderealDegrees(jd, longitudeDegrees));
        double flatteningFactor = 1.0 - WGS84_FLATTENING;
        double u = Math.atan(flatteningFactor * Math.tan(latitude));
        double rhoCosPhi = Math.cos(u);
        double rhoSinPhi = flatteningFactor * Math.sin(u);
        return new Vector3(
                EARTH_EQUATORIAL_RADIUS_AU * rhoCosPhi * Math.cos(lst),
                EARTH_EQUATORIAL_RADIUS_AU * rhoCosPhi * Math.sin(lst),
                EARTH_EQUATORIAL_RADIUS_AU * rhoSinPhi
        );
    }

    private static double localSiderealDegrees(double jd, double longitudeDegrees) {
        double d = jd - 2_451_545.0;
        double t = d / 36_525.0;
        double gmst = 280.460_618_37 + 360.985_647_366_29 * d + 0.000_387_933 * t * t
                - t * t * t / 38_710_000.0;
        return normalizeDegrees(gmst + longitudeDegrees);
    }

    private static double julianDay(Instant instant) {
        return instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5;
    }

    private static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0.0 ? result + 360.0 : result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Table {
        final double startJulianDay;
        final double stepDays;
        final int sampleCount;
        final int bodyCount;
        final float[] values;

        Table(double startJulianDay, double stepDays, int sampleCount, int bodyCount, float[] values) {
            this.startJulianDay = startJulianDay;
            this.stepDays = stepDays;
            this.sampleCount = sampleCount;
            this.bodyCount = bodyCount;
            this.values = values;
        }

        static Table load(Context context) throws IOException {
            byte[] raw;
            try (InputStream input = context.getAssets().open(ASSET_PATH);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16_384];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                raw = output.toByteArray();
            }
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (byte expected : MAGIC) {
                if (!buffer.hasRemaining() || buffer.get() != expected) {
                    throw new IOException("bad ephemeris magic");
                }
            }
            double startJulianDay = buffer.getDouble();
            double stepDays = buffer.getDouble();
            int sampleCount = buffer.getInt();
            int bodyCount = buffer.getInt();
            if (bodyCount != IDS.length || sampleCount < 2 || stepDays <= 0.0) {
                throw new IOException("bad ephemeris header");
            }
            int expectedFloats = sampleCount * bodyCount * 3;
            if (buffer.remaining() != expectedFloats * Float.BYTES) {
                throw new IOException("bad ephemeris payload size");
            }
            float[] values = new float[expectedFloats];
            for (int i = 0; i < values.length; i++) {
                values[i] = buffer.getFloat();
            }
            double firstSunDistance = Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
            if (!Double.isFinite(firstSunDistance) || firstSunDistance < 0.9 || firstSunDistance > 1.1) {
                throw new IOException("ephemeris vectors look corrupted");
            }
            return new Table(startJulianDay, stepDays, sampleCount, bodyCount, values);
        }

        boolean contains(double jd) {
            double u = (jd - startJulianDay) / stepDays;
            return u >= 0.0 && u <= sampleCount - 1.0;
        }

        double endJulianDay() {
            return startJulianDay + stepDays * (sampleCount - 1.0);
        }

        Vector3[] interpolate(double jd) {
            double u = (jd - startJulianDay) / stepDays;
            int index = (int) Math.floor(u);
            if (index < 0) {
                index = 0;
                u = 0.0;
            } else if (index >= sampleCount - 1) {
                index = sampleCount - 2;
                u = sampleCount - 1.0;
            }
            double fraction = u - index;
            Vector3[] out = new Vector3[bodyCount];
            for (int body = 0; body < bodyCount; body++) {
                int base0 = ((index * bodyCount + body) * 3);
                int base1 = (((index + 1) * bodyCount + body) * 3);
                out[body] = new Vector3(
                        lerp(values[base0], values[base1], fraction),
                        lerp(values[base0 + 1], values[base1 + 1], fraction),
                        lerp(values[base0 + 2], values[base1 + 2], fraction)
                );
            }
            return out;
        }

        private static double lerp(double a, double b, double fraction) {
            return a + (b - a) * fraction;
        }
    }

    private static final class Vector3 {
        final double x;
        final double y;
        final double z;

        Vector3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Vector3 minus(Vector3 other) {
            return new Vector3(x - other.x, y - other.y, z - other.z);
        }

        double dot(Vector3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        EquatorialPoint toEquatorial() {
            double radius = length();
            if (radius < 1.0e-15) {
                return new EquatorialPoint(0.0, 0.0);
            }
            double raHours = normalizeDegrees(Math.toDegrees(Math.atan2(y, x))) / 15.0;
            double decDegrees = Math.toDegrees(Math.asin(clamp(z / radius, -1.0, 1.0)));
            return new EquatorialPoint(raHours, decDegrees);
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
