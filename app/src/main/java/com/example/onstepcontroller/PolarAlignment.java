package com.example.onstepcontroller;

import java.util.ArrayList;
import java.util.List;

/**
 * Plate-solve polar alignment (manual version, phase 3 of the camera feature). The phone is
 * rigidly fixed to the mount and the user rotates the RA axis between shots; each shot is
 * blind-solved to a full celestial->camera attitude. Because the camera is rigid, the relative
 * rotation between two attitudes is exactly the mount's RA rotation, whose axis (in celestial
 * coordinates) IS the mount's polar axis. Comparing that axis to the celestial pole gives the
 * polar misalignment, decomposed into altitude and azimuth so the user knows which adjuster to
 * turn.
 *
 * <p>Pure Java / CPU, no Android dependency, so the geometry is unit-testable off-device.
 * Needs only the solved rotations plus the observer's latitude/longitude/time (for the
 * alt-az split; the error magnitude itself is purely celestial and time-independent).
 *
 * <p>Accuracy note: a phone wide field is ~64 arcsec/pixel, so this recovers the axis to a few
 * arcminutes -- a rough/assist-grade alignment, far coarser than an imaging camera. It does NOT
 * depend on the camera's optical axis matching the RA axis; only on the camera being rigid.
 */
final class PolarAlignment {

    /** A recovered polar-alignment estimate. */
    static final class Result {
        final double axisRaDeg;       // RA of the mount polar axis (celestial)
        final double axisDecDeg;      // Dec of the mount polar axis
        final double polarErrorDeg;   // angle between the axis and the celestial pole
        final double altErrorDeg;     // on-sky altitude offset (+ = axis too high)
        final double azErrorDeg;      // on-sky azimuth offset (+ = axis too far east)
        final int shotsUsed;

        Result(double axisRaDeg, double axisDecDeg, double polarErrorDeg,
               double altErrorDeg, double azErrorDeg, int shotsUsed) {
            this.axisRaDeg = axisRaDeg;
            this.axisDecDeg = axisDecDeg;
            this.polarErrorDeg = polarErrorDeg;
            this.altErrorDeg = altErrorDeg;
            this.azErrorDeg = azErrorDeg;
            this.shotsUsed = shotsUsed;
        }
    }

    // Celestial->camera rotations from each solved shot, in capture order.
    private final List<double[][]> shots = new ArrayList<>();

    void addShot(double[][] celestialToCamera) {
        shots.add(celestialToCamera);
    }

    int shotCount() {
        return shots.size();
    }

    void clear() {
        shots.clear();
    }

    /**
     * Compute the polar axis and error from the collected shots (needs at least 2). Returns null
     * if there are too few shots or the rotations between them are too small to define an axis.
     */
    Result compute(double latitudeDeg, double longitudeDeg, long epochMillis) {
        if (shots.size() < 2) {
            return null;
        }
        // For every pair, the relative rotation M = Rb^T * Ra is the mount's RA rotation in the
        // celestial frame; its axis (the rotation "vee" vector, magnitude 2*sin(angle)) is the
        // polar axis. Summing the vee vectors over all pairs averages them and weights each by
        // its rotation size, so near-duplicate shots (tiny rotation) contribute little.
        double poleSign = latitudeDeg >= 0 ? 1.0 : -1.0; // axis points to the visible pole
        double[] axisSum = {0, 0, 0};
        for (int a = 0; a < shots.size(); a++) {
            for (int b = a + 1; b < shots.size(); b++) {
                double[][] m = mul(transpose(shots.get(b)), shots.get(a));
                double[] vee = {m[2][1] - m[1][2], m[0][2] - m[2][0], m[1][0] - m[0][1]};
                // Orient toward the visible pole so opposite slew senses still add coherently.
                if (vee[2] * poleSign < 0) {
                    vee[0] = -vee[0];
                    vee[1] = -vee[1];
                    vee[2] = -vee[2];
                }
                axisSum[0] += vee[0];
                axisSum[1] += vee[1];
                axisSum[2] += vee[2];
            }
        }
        double norm = Math.sqrt(axisSum[0] * axisSum[0] + axisSum[1] * axisSum[1] + axisSum[2] * axisSum[2]);
        if (norm < 1e-6) {
            return null; // shots essentially identical: no usable rotation
        }
        double[] axis = {axisSum[0] / norm, axisSum[1] / norm, axisSum[2] / norm};

        double axisDec = Math.toDegrees(Math.asin(clamp(axis[2], -1, 1)));
        double axisRa = Math.toDegrees(Math.atan2(axis[1], axis[0]));
        if (axisRa < 0) {
            axisRa += 360.0;
        }
        // Polar error magnitude: angle from the axis to the visible celestial pole (time-free).
        double polarError = Math.toDegrees(Math.acos(clamp(axis[2] * poleSign, -1, 1)));

        // Decompose into altitude/azimuth in the local horizontal frame (needs time + location).
        double lst = localSiderealDegrees(epochMillis, longitudeDeg);
        double[] altAz = toAltAz(axisRa, axisDec, latitudeDeg, lst);
        double poleAlt = Math.abs(latitudeDeg);
        double poleAz = latitudeDeg >= 0 ? 0.0 : 180.0;
        double altError = altAz[0] - poleAlt; // altitude is 1:1 with on-sky angle
        // Azimuth lines converge near the pole, so scale the azimuth difference by cos(altitude)
        // to an on-sky angle; then altError and azError sum in quadrature to the polar error.
        // Multiply by poleSign so a positive azError means the axis is absolute-EAST of the pole
        // in BOTH hemispheres (south's pole azimuth is 180, where the az difference runs the
        // opposite compass way), keeping the east/west adjustment hint correct.
        double azError = wrapSigned(altAz[1] - poleAz) * Math.cos(Math.toRadians(altAz[0])) * poleSign;

        return new Result(axisRa, axisDec, polarError, altError, azError, shots.size());
    }

    // --- coordinate helpers (mirroring SkyChartView's transforms) ---

    /** RA/Dec (deg) -> {altitude, azimuth} (deg) for the observer. */
    static double[] toAltAz(double raDeg, double decDeg, double latDeg, double lstDeg) {
        double hourAngle = Math.toRadians(wrapSigned(lstDeg - raDeg));
        double dec = Math.toRadians(decDeg);
        double lat = Math.toRadians(latDeg);
        double sinAlt = Math.sin(dec) * Math.sin(lat) + Math.cos(dec) * Math.cos(lat) * Math.cos(hourAngle);
        double altitude = Math.asin(clamp(sinAlt, -1, 1));
        double cosAlt = Math.max(1e-8, Math.cos(altitude));
        double cosLat = Math.cos(lat);
        double sinAz = -Math.cos(dec) * Math.sin(hourAngle) / cosAlt;
        double cosAz = Math.abs(cosLat) < 1e-8
                ? 1.0
                : (Math.sin(dec) - Math.sin(altitude) * Math.sin(lat)) / (cosAlt * cosLat);
        double azimuth = Math.toDegrees(Math.atan2(sinAz, cosAz));
        if (azimuth < 0) {
            azimuth += 360.0;
        }
        return new double[]{Math.toDegrees(altitude), azimuth};
    }

    static double localSiderealDegrees(long epochMillis, double longitudeDeg) {
        double jd = epochMillis / 86_400_000.0 + 2_440_587.5;
        double d = jd - 2_451_545.0;
        double t = d / 36_525.0;
        double gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38_710_000.0;
        double lst = (gmst + longitudeDeg) % 360.0;
        return lst < 0 ? lst + 360.0 : lst;
    }

    // --- small matrix helpers ---

    private static double[][] transpose(double[][] m) {
        return new double[][]{
                {m[0][0], m[1][0], m[2][0]},
                {m[0][1], m[1][1], m[2][1]},
                {m[0][2], m[1][2], m[2][2]}};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Wrap to (-180, 180]. */
    private static double wrapSigned(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }
}
