package com.example.onstepcontroller;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Off-device end-to-end check of the REAL app classes (StarDetector core + PlateSolver) on
 * still JPEGs, so the camera plate-solving pipeline can be reviewed without a phone.
 *
 * Usage: see jtest/README.md. Args: stars.tsv  image1.jpg [image2.jpg ...]
 * It mimics the app: downscale to the detector's analysis edge, run detectForSolveCore,
 * then solve with a focal prior from the camera's horizontal FOV.
 */
public class E2ETest {

    static final int ANALYSIS_LONG_EDGE = 1600;
    static final double FOV_H_DEG = 65.4; // P30 Pro main cam horizontal FOV (CaptureInfo.fovDegrees)

    public static void main(String[] args) throws Exception {
        List<SkyCatalog.Star> stars = loadStars(args[0]);
        PlateSolver solver = new PlateSolver(stars);
        System.out.println("catalog " + stars.size() + ", seed triangles " + solver.triangleCount());
        for (int i = 1; i < args.length; i++) {
            run(solver, args[i]);
        }
    }

    static void run(PlateSolver solver, String path) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        int srcW = img.getWidth(), srcH = img.getHeight();
        int longEdge = Math.max(srcW, srcH);
        double scale = longEdge > ANALYSIS_LONG_EDGE ? (double) ANALYSIS_LONG_EDGE / longEdge : 1.0;
        int w = (int) Math.round(srcW * scale), h = (int) Math.round(srcH * scale);
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        int[] argb = small.getRGB(0, 0, w, h, null, 0, w);

        long t0 = System.currentTimeMillis();
        StarDetector.StarField field = StarDetector.detectForSolveCore(argb, w, h, srcW, srcH);
        long tDet = System.currentTimeMillis() - t0;

        double[] xs = new double[field.stars.size()];
        double[] ys = new double[field.stars.size()];
        double[] pk = new double[field.stars.size()];
        for (int i = 0; i < xs.length; i++) {
            StarDetector.Detection d = field.stars.get(i);
            xs[i] = d.x; ys[i] = d.y; pk[i] = d.peak;
        }
        System.out.printf("%n=== %s  %dx%d ===%n", new File(path).getName(), srcW, srcH);
        System.out.printf("  detect %d ms: %d compact stars (raw %d, noise %.2f, sky %d%%)%n",
                tDet, field.stars.size(), field.rawCount, field.noise, skyPercent(field.skyMask));

        double fPrior = srcW / (2.0 * Math.tan(Math.toRadians(FOV_H_DEG) / 2.0));
        long t1 = System.currentTimeMillis();
        PlateSolver.Solution sol = solver.solve(xs, ys, pk, fPrior, srcW / 2.0, srcH / 2.0);
        long tSol = System.currentTimeMillis() - t1;
        if (sol == null) {
            System.out.println("  NO SOLUTION");
            return;
        }
        System.out.printf("  SOLVED %d ms: RA %.3f (%.2fh) Dec %+.3f | FOV %.1fx%.1f roll %.0f "
                        + "f=%.0f matched %d rms %.2f%n",
                tSol, sol.centerRaDeg, sol.centerRaDeg / 15.0, sol.centerDecDeg,
                sol.fovWDeg, sol.fovHDeg, sol.rollDeg, sol.fPix, sol.matchDet.length, sol.rmsPx);
        Integer[] ord = new Integer[sol.matchStar.length];
        for (int i = 0; i < ord.length; i++) ord[i] = i;
        java.util.Arrays.sort(ord, (a, b) -> Double.compare(
                solver.starMag(sol.matchStar[a]), solver.starMag(sol.matchStar[b])));
        int shown = 0;
        for (int i = 0; i < ord.length && shown < 8; i++) {
            int gi = sol.matchStar[ord[i]];
            if (solver.starName(gi) != null && !solver.starName(gi).isEmpty()) {
                System.out.printf("     mag %.2f  %s%n", solver.starMag(gi), solver.starName(gi));
                shown++;
            }
        }
    }

    static int skyPercent(boolean[] m) {
        int c = 0;
        for (boolean b : m) if (b) c++;
        return m.length == 0 ? 0 : 100 * c / m.length;
    }

    static List<SkyCatalog.Star> loadStars(String path) throws Exception {
        List<SkyCatalog.Star> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            r.readLine(); // header
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                out.add(new SkyCatalog.Star(p[0], Double.parseDouble(p[1]),
                        Double.parseDouble(p[2]), Double.parseDouble(p[3])));
            }
        }
        return out;
    }
}
