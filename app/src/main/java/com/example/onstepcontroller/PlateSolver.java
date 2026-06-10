package com.example.onstepcontroller;

import java.util.ArrayList;
import java.util.List;

/**
 * Blind plate solver for wide-field phone frames (phase 2 of the camera plate-solving
 * feature). Given detected star pixel positions and an approximate focal length, it
 * recovers the camera pointing (boresight RA/Dec), roll and exact focal length by
 * matching the detected pattern to the bundled star catalog.
 *
 * <p>Pure Java / CPU, no Android or network dependency, so it can be unit-tested in
 * isolation. Method:
 * <ol>
 *   <li>Build a SCALE-INVARIANT shape index of bright catalog-star triangles (the ratio
 *       of a triangle's angular sides does not depend on the unknown focal length, so a
 *       triangle seeds the solve without first knowing the scale; absolute pairwise-angle
 *       matching fails here because a few-percent focal error scales every wide-field
 *       angle past any fixed tolerance).</li>
 *   <li>For each triangle of the brightest detections, find catalog triangles of matching
 *       shape AND a scale (px-side / angular-side) consistent with the focal prior.</li>
 *   <li>From a 3-star correspondence solve the celestial->camera rotation (Wahba's problem,
 *       Davenport q-method), then VERIFY against the sparse bright catalog: a real solve
 *       lands several more bright stars on detections, a chance shape match lands none.</li>
 *   <li>Refine rotation and focal length against the full catalog with a tightening
 *       tolerance.</li>
 * </ol>
 *
 * <p>Foreground (trees/roofs/wires) is handled upstream by {@link StarDetector}'s sky mask
 * and compact-source selection; any residual false detections simply never accumulate a
 * consistent celestial match here and are dropped as outliers.
 */
final class PlateSolver {

    /** A recovered pointing solution; also projects catalog coordinates back to pixels. */
    static final class Solution {
        final double[][] r;          // celestial -> camera rotation, cam = R * c
        final double fPix;
        final double cx, cy;
        final double centerRaDeg;    // boresight
        final double centerDecDeg;
        final double rollDeg;        // position angle of celestial north in the image
        final double fovWDeg, fovHDeg;
        final double rmsPx;
        final int[] matchDet;        // matched detection indices
        final int[] matchStar;       // matched global catalog-star indices

        Solution(double[][] r, double fPix, double cx, double cy, double centerRaDeg,
                 double centerDecDeg, double rollDeg, double fovWDeg, double fovHDeg,
                 double rmsPx, int[] matchDet, int[] matchStar) {
            this.r = r;
            this.fPix = fPix;
            this.cx = cx;
            this.cy = cy;
            this.centerRaDeg = centerRaDeg;
            this.centerDecDeg = centerDecDeg;
            this.rollDeg = rollDeg;
            this.fovWDeg = fovWDeg;
            this.fovHDeg = fovHDeg;
            this.rmsPx = rmsPx;
            this.matchDet = matchDet;
            this.matchStar = matchStar;
        }

        /** Project a sky coordinate to pixels. Returns {px, py, z}; z<=0 means behind camera. */
        double[] project(double raDeg, double decDeg) {
            double[] c = raDecToVec(raDeg, decDeg);
            double camX = r[0][0] * c[0] + r[0][1] * c[1] + r[0][2] * c[2];
            double camY = r[1][0] * c[0] + r[1][1] * c[1] + r[1][2] * c[2];
            double camZ = r[2][0] * c[0] + r[2][1] * c[1] + r[2][2] * c[2];
            if (camZ <= 1e-9) {
                return new double[]{Double.NaN, Double.NaN, camZ};
            }
            return new double[]{cx + fPix * camX / camZ, cy + fPix * camY / camZ, camZ};
        }
    }

    // --- catalog ---
    private final double[][] vec;      // [N][3] celestial unit vectors
    private final double[] mag;
    private final double[] raDeg;
    private final double[] decDeg;
    private final String[] name;

    // --- bright triangle index (built once) ---
    private final int[] brightIdx;     // global indices of bright stars (mag <= seedMag)
    private final int[] verifyIdx;     // global indices for refine/verify (mag <= VERIFY_MAG)
    private static final double VERIFY_MAG = 6.0;
    private double[] triR1, triR2;     // shape ratios (sorted angular sides: s0<=s1<=s2)
    private float[] triS0, triS1, triS2;
    private int[] triV0, triV1, triV2; // global star indices, ordered by ascending opposite side

    private final double seedMag;
    private final double minSideDeg;
    private final double maxSideDeg;

    PlateSolver(List<SkyCatalog.Star> stars, double seedMag, double minSideDeg, double maxSideDeg) {
        int n = stars.size();
        vec = new double[n][];
        mag = new double[n];
        raDeg = new double[n];
        decDeg = new double[n];
        name = new String[n];
        List<Integer> bright = new ArrayList<>();
        List<Integer> verify = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SkyCatalog.Star s = stars.get(i);
            raDeg[i] = s.raHours * 15.0;
            decDeg[i] = s.decDegrees;
            mag[i] = s.magnitude;
            name[i] = s.name;
            vec[i] = raDecToVec(raDeg[i], decDeg[i]);
            if (s.magnitude <= seedMag) {
                bright.add(i);
            }
            if (s.magnitude <= VERIFY_MAG) {
                verify.add(i);
            }
        }
        brightIdx = new int[bright.size()];
        for (int i = 0; i < brightIdx.length; i++) {
            brightIdx[i] = bright.get(i);
        }
        verifyIdx = new int[verify.size()];
        for (int i = 0; i < verifyIdx.length; i++) {
            verifyIdx[i] = verify.get(i);
        }
        this.seedMag = seedMag;
        this.minSideDeg = minSideDeg;
        this.maxSideDeg = maxSideDeg;
        buildTriangleIndex();
    }

    /** Convenience: defaults tuned for a ~65 deg phone field. */
    PlateSolver(List<SkyCatalog.Star> stars) {
        this(stars, 3.2, 3.0, 75.0);
    }

    // Stop the blind triangle scan once a pose matches this many bright stars at seed
    // tolerance (cannot happen by chance; further triples only re-find the same pose).
    private static final int EARLY_STOP_MATCHES = 12;

    /**
     * Warm start: re-verify a previous solution against a new frame and refine it, skipping
     * the blind triangle search entirely. Consecutive camera frames (tracking, the polar
     * alignment's bolt-turn refresh shots) move the attitude only a little, so the previous
     * pose usually still matches. The seed tolerance is doubled to absorb up to ~2 deg of
     * motion (refine re-tightens it); returns null when the hint no longer fits, in which
     * case the caller falls back to a blind {@link #solve}.
     */
    Solution refineFromHint(Solution hint, double[] xs, double[] ys, double[] peak,
                            double cx, double cy) {
        if (hint == null) {
            return null;
        }
        int n = xs.length;
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) {
            ord[i] = i;
        }
        java.util.Arrays.sort(ord, (a, b) -> Double.compare(peak[b], peak[a]));
        int nv = Math.min(40, n);
        double[] dx = new double[nv];
        double[] dy = new double[nv];
        for (int i = 0; i < nv; i++) {
            dx[i] = xs[ord[i]];
            dy[i] = ys[ord[i]];
        }
        double seedTolPx = 2 * 0.012 * Math.hypot(cx * 2, cy * 2);
        return refine(hint.r, hint.fPix, dx, dy, cx, cy, seedTolPx, 8, 6.0);
    }

    private void buildTriangleIndex() {
        int nb = brightIdx.length;
        double[][] bvec = new double[nb][];
        for (int i = 0; i < nb; i++) {
            bvec[i] = vec[brightIdx[i]];
        }
        double[][] ang = new double[nb][nb];
        for (int i = 0; i < nb; i++) {
            for (int j = i + 1; j < nb; j++) {
                double a = angleBetween(bvec[i], bvec[j]);
                ang[i][j] = a;
                ang[j][i] = a;
            }
        }
        double sMin = Math.toRadians(minSideDeg);
        double sMax = Math.toRadians(maxSideDeg);
        // first pass: count
        int count = 0;
        for (int i = 0; i < nb; i++) {
            for (int j = i + 1; j < nb; j++) {
                if (ang[i][j] < sMin || ang[i][j] > sMax) {
                    continue;
                }
                for (int k = j + 1; k < nb; k++) {
                    double lo = Math.min(ang[j][k], Math.min(ang[i][k], ang[i][j]));
                    double hi = Math.max(ang[j][k], Math.max(ang[i][k], ang[i][j]));
                    if (lo >= sMin && hi <= sMax) {
                        count++;
                    }
                }
            }
        }
        triR1 = new double[count];
        triR2 = new double[count];
        triS0 = new float[count];
        triS1 = new float[count];
        triS2 = new float[count];
        triV0 = new int[count];
        triV1 = new int[count];
        triV2 = new int[count];
        int t = 0;
        for (int i = 0; i < nb; i++) {
            for (int j = i + 1; j < nb; j++) {
                if (ang[i][j] < sMin || ang[i][j] > sMax) {
                    continue;
                }
                for (int k = j + 1; k < nb; k++) {
                    // sides opposite vertex i,j,k
                    double si = ang[j][k];
                    double sj = ang[i][k];
                    double sk = ang[i][j];
                    double lo = Math.min(si, Math.min(sj, sk));
                    double hi = Math.max(si, Math.max(sj, sk));
                    if (lo < sMin || hi > sMax) {
                        continue;
                    }
                    int[] vtx = {brightIdx[i], brightIdx[j], brightIdx[k]};
                    double[] sides = {si, sj, sk};
                    int[] order = sortOrder3(sides);   // ascending by opposite side
                    double s0 = sides[order[0]], s1 = sides[order[1]], s2 = sides[order[2]];
                    triR1[t] = s0 / s2;
                    triR2[t] = s1 / s2;
                    triS0[t] = (float) s0;
                    triS1[t] = (float) s1;
                    triS2[t] = (float) s2;
                    triV0[t] = vtx[order[0]];
                    triV1[t] = vtx[order[1]];
                    triV2[t] = vtx[order[2]];
                    t++;
                }
            }
        }
        sortByR1();
    }

    /** Sort all triangle parallel-arrays by the smaller shape ratio r1, so the matcher can
     *  binary-search the r1 band for a query triangle instead of scanning all triangles. */
    private void sortByR1() {
        int n = triR1.length;
        Integer[] perm = new Integer[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        java.util.Arrays.sort(perm, (a, b) -> Double.compare(triR1[a], triR1[b]));
        double[] r1 = new double[n], r2 = new double[n];
        float[] s0 = new float[n], s1 = new float[n], s2 = new float[n];
        int[] v0 = new int[n], v1 = new int[n], v2 = new int[n];
        for (int i = 0; i < n; i++) {
            int p = perm[i];
            r1[i] = triR1[p]; r2[i] = triR2[p];
            s0[i] = triS0[p]; s1[i] = triS1[p]; s2[i] = triS2[p];
            v0[i] = triV0[p]; v1[i] = triV1[p]; v2[i] = triV2[p];
        }
        triR1 = r1; triR2 = r2; triS0 = s0; triS1 = s1; triS2 = s2;
        triV0 = v0; triV1 = v1; triV2 = v2;
    }

    /** First index with triR1[i] >= key. */
    private int lowerBoundR1(double key) {
        int lo = 0, hi = triR1.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (triR1[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    int triangleCount() {
        return triR1 == null ? 0 : triR1.length;
    }

    /**
     * Solve. {@code xs,ys} are detection pixel coordinates and {@code peak} their brightness
     * (for ranking); {@code fPrior,cx,cy} the camera intrinsics prior. Returns null if no
     * confident solution is found.
     */
    Solution solve(double[] xs, double[] ys, double[] peak, double fPrior, double cx, double cy) {
        return solve(xs, ys, peak, fPrior, cx, cy,
                14, 40, 0.015, 0.2, 0.012, 8, 6.0);
    }

    Solution solve(double[] xs, double[] ys, double[] peak, double fPrior, double cx, double cy,
                   int nSeed, int nVerify, double tolShape, double fWindow,
                   double seedTolFrac, int minMatches, double maxRms) {
        int n = xs.length;
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) {
            ord[i] = i;
        }
        java.util.Arrays.sort(ord, (a, b) -> Double.compare(peak[b], peak[a]));
        int nv = Math.min(nVerify, n);
        double[] dx = new double[nv];
        double[] dy = new double[nv];
        for (int i = 0; i < nv; i++) {
            dx[i] = xs[ord[i]];
            dy[i] = ys[ord[i]];
        }
        double diag = Math.hypot(cx * 2, cy * 2);
        double seedTolPx = seedTolFrac * diag;
        int ns = Math.min(nSeed, nv);

        // detection rays at the prior focal length (only used to order/seed); the actual
        // ray set for Wahba is rebuilt per hypothesis at the estimated focal length.
        int[] bestDet = null, bestStar = null;
        double[][] bestR = null;
        double bestF = 0;
        int bestCount = 0;

        search:
        for (int a = 0; a < ns; a++) {
            for (int b = a + 1; b < ns; b++) {
                for (int c = b + 1; c < ns; c++) {
                    double[] pts = {dx[a], dy[a], dx[b], dy[b], dx[c], dy[c]};
                    double sa = dist(dx[b], dy[b], dx[c], dy[c]); // opposite a
                    double sb = dist(dx[a], dy[a], dx[c], dy[c]); // opposite b
                    double sc = dist(dx[a], dy[a], dx[b], dy[b]); // opposite c
                    double[] sides = {sa, sb, sc};
                    int[] vidx = {a, b, c};
                    int[] o = sortOrder3(sides);
                    double p0 = sides[o[0]], p1 = sides[o[1]], p2 = sides[o[2]];
                    if (p2 <= 1e-6) {
                        continue;
                    }
                    double dr1 = p0 / p2, dr2 = p1 / p2;
                    int[] dv = {vidx[o[0]], vidx[o[1]], vidx[o[2]]};
                    // catalog triangles are sorted by r1: only the [dr1-tol, dr1+tol] band
                    // can match, so binary-search it instead of scanning all ~164k triangles.
                    int from = lowerBoundR1(dr1 - tolShape);
                    int to = lowerBoundR1(dr1 + tolShape + 1e-12);
                    for (int ti = from; ti < to; ti++) {
                        if (Math.abs(triR2[ti] - dr2) > tolShape) {
                            continue; // r1 already within band; only r2 left to filter
                        }
                        // scale gate: px-side / angular-side must match the focal prior
                        double fEst = median3(p0 / triS0[ti], p1 / triS1[ti], p2 / triS2[ti]);
                        if (fEst < (1 - fWindow) * fPrior || fEst > (1 + fWindow) * fPrior) {
                            continue;
                        }
                        double[][] dRays = {
                                ray(dx[dv[0]], dy[dv[0]], cx, cy, fEst),
                                ray(dx[dv[1]], dy[dv[1]], cx, cy, fEst),
                                ray(dx[dv[2]], dy[dv[2]], cx, cy, fEst)};
                        double[][] cVecs = {vec[triV0[ti]], vec[triV1[ti]], vec[triV2[ti]]};
                        double[][] R = wahba(dRays, cVecs, null);
                        int[][] m = verify(R, fEst, dx, dy, cx, cy, brightIdx, seedTolPx);
                        if (m[0].length >= 5 && m[0].length > bestCount) {
                            bestCount = m[0].length;
                            bestDet = m[0];
                            bestStar = m[1];
                            bestR = R;
                            bestF = fEst;
                        }
                    }
                    // A pose that 12+ bright stars agree with at seed tolerance cannot be a
                    // chance alignment; the remaining triples would only re-find it, so stop
                    // scanning and go straight to refine. Big speedup on solvable frames.
                    if (bestCount >= EARLY_STOP_MATCHES) {
                        break search;
                    }
                }
            }
        }
        if (bestR == null) {
            return null;
        }
        return refine(bestR, bestF, dx, dy, cx, cy, seedTolPx, minMatches, maxRms);
    }

    // --- verification & refinement ---

    /** Project the given catalog subset; match each detection to the nearest projected star
     *  within tol (unique). Returns {detIndices, globalStarIndices}. */
    private int[][] verify(double[][] R, double f, double[] dx, double[] dy,
                           double cx, double cy, int[] subset, double tolPx) {
        double margin = 0.1 * Math.max(cx, cy);
        int ns = subset.length;
        double[] px = new double[ns];
        double[] py = new double[ns];
        boolean[] inFrame = new boolean[ns];
        for (int i = 0; i < ns; i++) {
            double[] c = vec[subset[i]];
            double camX = R[0][0] * c[0] + R[0][1] * c[1] + R[0][2] * c[2];
            double camY = R[1][0] * c[0] + R[1][1] * c[1] + R[1][2] * c[2];
            double camZ = R[2][0] * c[0] + R[2][1] * c[1] + R[2][2] * c[2];
            if (camZ <= 1e-9) {
                continue;
            }
            double x = cx + f * camX / camZ;
            double y = cy + f * camY / camZ;
            px[i] = x;
            py[i] = y;
            inFrame[i] = x > -margin && x < 2 * cx + margin && y > -margin && y < 2 * cy + margin;
        }
        List<int[]> matches = new ArrayList<>();
        boolean[] used = new boolean[ns];
        for (int d = 0; d < dx.length; d++) {
            int best = -1;
            double bestDd = tolPx * tolPx;
            for (int i = 0; i < ns; i++) {
                if (!inFrame[i] || used[i]) {
                    continue;
                }
                double dd = (px[i] - dx[d]) * (px[i] - dx[d]) + (py[i] - dy[d]) * (py[i] - dy[d]);
                if (dd <= bestDd) {
                    bestDd = dd;
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                matches.add(new int[]{d, subset[best]});
            }
        }
        int[] md = new int[matches.size()];
        int[] ms = new int[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            md[i] = matches.get(i)[0];
            ms[i] = matches.get(i)[1];
        }
        return new int[][]{md, ms};
    }

    private Solution refine(double[][] R, double f, double[] dx, double[] dy,
                            double cx, double cy, double seedTolPx, int minMatches, double maxRms) {
        int[] md, ms;
        // seed matches against bright catalog first, then deepen to the mag<=VERIFY_MAG set
        int[][] m = verify(R, f, dx, dy, cx, cy, brightIdx, seedTolPx);
        md = m[0];
        ms = m[1];
        for (int it = 0; it < 7; it++) {
            if (md.length >= 3) {
                double[][] dRays = new double[md.length][];
                double[][] cVecs = new double[md.length][];
                for (int i = 0; i < md.length; i++) {
                    dRays[i] = ray(dx[md[i]], dy[md[i]], cx, cy, f);
                    cVecs[i] = vec[ms[i]];
                }
                R = wahba(dRays, cVecs, null);
                // refine focal length by radial least squares
                double num = 0, den = 0;
                for (int i = 0; i < md.length; i++) {
                    double[] c = cVecs[i];
                    double camX = R[0][0] * c[0] + R[0][1] * c[1] + R[0][2] * c[2];
                    double camY = R[1][0] * c[0] + R[1][1] * c[1] + R[1][2] * c[2];
                    double camZ = R[2][0] * c[0] + R[2][1] * c[1] + R[2][2] * c[2];
                    if (camZ <= 1e-9) {
                        continue;
                    }
                    double rt = Math.hypot(camX / camZ, camY / camZ);
                    double rp = Math.hypot(dx[md[i]] - cx, dy[md[i]] - cy);
                    num += rt * rp;
                    den += rt * rt;
                }
                if (den > 0) {
                    f = num / den;
                }
            }
            double tol = Math.max(seedTolPx * Math.pow(0.55, it), 4.0);
            int[][] nm = verify(R, f, dx, dy, cx, cy, verifyIdx, tol);
            if (nm[0].length < 2) {
                break;
            }
            md = nm[0];
            ms = nm[1];
        }
        if (md.length < minMatches) {
            return null;
        }
        // rms over final matches
        double sum = 0;
        for (int i = 0; i < md.length; i++) {
            double[] p = projectVec(vec[ms[i]], R, f, cx, cy);
            sum += (p[0] - dx[md[i]]) * (p[0] - dx[md[i]]) + (p[1] - dy[md[i]]) * (p[1] - dy[md[i]]);
        }
        double rms = Math.sqrt(sum / md.length);
        if (rms > maxRms) {
            return null;
        }
        return makeSolution(R, f, cx, cy, rms, md, ms);
    }

    private Solution makeSolution(double[][] R, double f, double cx, double cy,
                                  double rms, int[] md, int[] ms) {
        // boresight = camera +z axis in celestial frame = R^T * [0,0,1]
        double bx = R[2][0], by = R[2][1], bz = R[2][2];
        double dec = Math.toDegrees(Math.asin(clamp(bz, -1, 1)));
        double ra = Math.toDegrees(Math.atan2(by, bx));
        if (ra < 0) {
            ra += 360.0;
        }
        // roll: direction of celestial north ([0,0,1]) in the camera/image frame
        double cnX = R[0][2], cnY = R[1][2];
        double roll = Math.toDegrees(Math.atan2(cnX, -cnY));
        double fovW = 2 * Math.toDegrees(Math.atan(cx / f));
        double fovH = 2 * Math.toDegrees(Math.atan(cy / f));
        return new Solution(R, f, cx, cy, ra, dec, roll, fovW, fovH, rms, md, ms);
    }

    String starName(int globalIdx) {
        return name[globalIdx];
    }

    double starMag(int globalIdx) {
        return mag[globalIdx];
    }

    double starRaDeg(int globalIdx) {
        return raDeg[globalIdx];
    }

    double starDecDeg(int globalIdx) {
        return decDeg[globalIdx];
    }

    // --- math helpers ---

    private static double[] projectVec(double[] c, double[][] R, double f, double cx, double cy) {
        double camX = R[0][0] * c[0] + R[0][1] * c[1] + R[0][2] * c[2];
        double camY = R[1][0] * c[0] + R[1][1] * c[1] + R[1][2] * c[2];
        double camZ = R[2][0] * c[0] + R[2][1] * c[1] + R[2][2] * c[2];
        return new double[]{cx + f * camX / camZ, cy + f * camY / camZ, camZ};
    }

    private static double[] ray(double x, double y, double cx, double cy, double f) {
        double vx = (x - cx) / f, vy = (y - cy) / f, vz = 1.0;
        double n = Math.sqrt(vx * vx + vy * vy + vz * vz);
        return new double[]{vx / n, vy / n, vz / n};
    }

    static double[] raDecToVec(double raDeg, double decDeg) {
        double ra = Math.toRadians(raDeg), dec = Math.toRadians(decDeg);
        return new double[]{Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
    }

    private static double angleBetween(double[] a, double[] b) {
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return Math.acos(clamp(d, -1, 1));
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }

    private static double median3(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    private static int[] sortOrder3(double[] s) {
        int i0 = 0, i1 = 1, i2 = 2;
        // simple ascending sort of three indices by s[]
        if (s[i0] > s[i1]) {
            int t = i0; i0 = i1; i1 = t;
        }
        if (s[i1] > s[i2]) {
            int t = i1; i1 = i2; i2 = t;
        }
        if (s[i0] > s[i1]) {
            int t = i0; i0 = i1; i1 = t;
        }
        return new int[]{i0, i1, i2};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Wahba's problem via Davenport's q-method: find rotation R minimising
     * sum |d_i - R c_i|^2, returned so that cam = R * c. Uses a symmetric 4x4
     * eigen-decomposition (Jacobi), avoiding any external SVD dependency.
     */
    static double[][] wahba(double[][] d, double[][] c, double[] w) {
        double[][] B = new double[3][3];
        for (int i = 0; i < d.length; i++) {
            double wi = w == null ? 1.0 : w[i];
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    B[a][b] += wi * d[i][a] * c[i][b];
                }
            }
        }
        double sigma = B[0][0] + B[1][1] + B[2][2];
        double[] z = {B[1][2] - B[2][1], B[2][0] - B[0][2], B[0][1] - B[1][0]};
        double[][] S = new double[3][3];
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                S[a][b] = B[a][b] + B[b][a];
            }
        }
        // K = [[sigma, z^T], [z, S - sigma I]]
        double[][] K = new double[4][4];
        K[0][0] = sigma;
        for (int i = 0; i < 3; i++) {
            K[0][i + 1] = z[i];
            K[i + 1][0] = z[i];
            for (int j = 0; j < 3; j++) {
                K[i + 1][j + 1] = S[i][j] - (i == j ? sigma : 0.0);
            }
        }
        double[][] evec = new double[4][4];
        double[] eval = new double[4];
        jacobiEigen(K, eval, evec);
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (eval[i] > eval[best]) {
                best = i;
            }
        }
        // optimal quaternion: q = [q0(scalar); q1; q2; q3] = column `best`
        double q0 = evec[0][best], q1 = evec[1][best], q2 = evec[2][best], q3 = evec[3][best];
        double nrm = Math.sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        q0 /= nrm; q1 /= nrm; q2 /= nrm; q3 /= nrm;
        // Davenport's optimal quaternion (w=q0, x=q1, y=q2, z=q3) gives the attitude matrix
        // A(q) mapping reference->body (c->d). A(q) is the TRANSPOSE of the usual
        // vector-rotation DCM, so build A(q) directly (note the w-term signs).
        double[][] R = new double[3][3];
        R[0][0] = 1 - 2 * (q2 * q2 + q3 * q3);
        R[0][1] = 2 * (q1 * q2 + q0 * q3);
        R[0][2] = 2 * (q1 * q3 - q0 * q2);
        R[1][0] = 2 * (q1 * q2 - q0 * q3);
        R[1][1] = 1 - 2 * (q1 * q1 + q3 * q3);
        R[1][2] = 2 * (q2 * q3 + q0 * q1);
        R[2][0] = 2 * (q1 * q3 + q0 * q2);
        R[2][1] = 2 * (q2 * q3 - q0 * q1);
        R[2][2] = 1 - 2 * (q1 * q1 + q2 * q2);
        return R;
    }

    /** Cyclic Jacobi eigen-decomposition for a small symmetric matrix. evec columns are
     *  eigenvectors, eval the eigenvalues. */
    private static void jacobiEigen(double[][] aIn, double[] eval, double[][] evec) {
        int n = aIn.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(aIn[i], 0, a[i], 0, n);
            for (int j = 0; j < n; j++) {
                evec[i][j] = (i == j) ? 1.0 : 0.0;
            }
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += a[p][q] * a[p][q];
                }
            }
            if (off < 1e-20) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-18) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double cs = 1 / Math.sqrt(t * t + 1);
                    double sn = t * cs;
                    for (int i = 0; i < n; i++) {
                        double aip = a[i][p], aiq = a[i][q];
                        a[i][p] = cs * aip - sn * aiq;
                        a[i][q] = sn * aip + cs * aiq;
                    }
                    for (int i = 0; i < n; i++) {
                        double api = a[p][i], aqi = a[q][i];
                        a[p][i] = cs * api - sn * aqi;
                        a[q][i] = sn * api + cs * aqi;
                    }
                    for (int i = 0; i < n; i++) {
                        double vip = evec[i][p], viq = evec[i][q];
                        evec[i][p] = cs * vip - sn * viq;
                        evec[i][q] = sn * vip + cs * viq;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            eval[i] = a[i][i];
        }
    }
}
