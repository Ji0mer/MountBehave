package com.example.onstepcontroller;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.IOException;

/**
 * Camera-independent entry point for "given an image of the sky, detect stars and blind-solve
 * the field". It owns the heavy, build-once state (the bundled {@link SkyCatalog} and the
 * {@link PlateSolver} with its triangle index) and exposes the detect / solve steps so any
 * image source -- live capture or an imported photo -- can share one detection + solving path.
 *
 * <p>No camera, threading or UI here: {@link #detect}/{@link #solve} are pure compute meant to
 * run on a worker thread, and the caller keeps the lifecycle/cancellation orchestration. The
 * focal-length prior comes from {@link SolveInput}, which abstracts over where it was obtained
 * (camera lens metadata vs. an imported photo's EXIF or a user-chosen field of view).
 *
 * <p>Obtain it via {@link #shared(Context)}: a single application-scoped instance so the heavy
 * catalog + triangle index is built once and reused across every image source (live capture,
 * imported photos) and across Activity/page rebuilds, rather than rebuilt per entry point.
 */
final class ImageSolveEngine {

    private static volatile ImageSolveEngine shared;

    /** The single application-scoped engine, created on first use. */
    static ImageSolveEngine shared(Context context) {
        ImageSolveEngine instance = shared;
        if (instance == null) {
            synchronized (ImageSolveEngine.class) {
                instance = shared;
                if (instance == null) {
                    instance = new ImageSolveEngine(context.getApplicationContext());
                    shared = instance;
                }
            }
        }
        return instance;
    }

    /** Solve inputs that vary per image source; currently the focal-length prior. */
    static final class SolveInput {
        /** Horizontal field of view in degrees, or <=0 if unknown (a fallback is used). */
        final double fovHorizontalDeg;

        private SolveInput(double fovHorizontalDeg) {
            this.fovHorizontalDeg = fovHorizontalDeg;
        }

        static SolveInput fromHorizontalFov(double fovHorizontalDeg) {
            return new SolveInput(fovHorizontalDeg);
        }
    }

    /** Fallback horizontal FOV when none is known (a typical phone main-camera field). */
    private static final double FALLBACK_FOV_DEG = 65.0;

    private final Context appContext;
    private final StarDetector detector = new StarDetector();
    private volatile SkyCatalog catalog;
    private volatile PlateSolver solver;

    private ImageSolveEngine(Context appContext) {
        this.appContext = appContext;
    }

    /**
     * Build the catalog and solver. Heavy; call off the UI thread. {@code synchronized} so a
     * camera capture and an imported photo cannot both kick a build at once -- the second
     * caller waits, then returns immediately because the first already populated it.
     */
    synchronized void load() throws IOException {
        if (solver != null) {
            return;
        }
        SkyCatalog cat = SkyCatalog.load(appContext);
        PlateSolver ps = new PlateSolver(cat.stars);
        catalog = cat;
        solver = ps;
    }

    boolean isReady() {
        return solver != null;
    }

    /** The loaded catalog (for overlay rendering), or null if not yet loaded. */
    SkyCatalog catalog() {
        return catalog;
    }

    /** Detect compact star sources in a frame. Never mutates {@code image}. */
    StarDetector.StarField detect(Bitmap image) {
        return detector.detectForSolve(image);
    }

    /**
     * Blind-solve a detected frame. Returns null if the solver is not ready, the frame has too
     * few stars, or no confident solution is found. Pure compute; safe off the UI thread.
     */
    PlateSolver.Solution solve(StarDetector.StarField field, SolveInput input) {
        PlateSolver ps = solver;
        if (ps == null || field == null || field.stars.size() < 3) {
            return null;
        }
        int n = field.stars.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        double[] pk = new double[n];
        for (int i = 0; i < n; i++) {
            StarDetector.Detection d = field.stars.get(i);
            xs[i] = d.x;
            ys[i] = d.y;
            pk[i] = d.peak;
        }
        double cx = field.sourceWidth / 2.0;
        double cy = field.sourceHeight / 2.0;
        return ps.solve(xs, ys, pk, focalPriorPx(field.sourceWidth, input), cx, cy);
    }

    /** Focal length in source pixels from a horizontal FOV; falls back when unknown. */
    private static double focalPriorPx(int width, SolveInput input) {
        double fov = input != null && input.fovHorizontalDeg > 0.0
                ? input.fovHorizontalDeg : FALLBACK_FOV_DEG;
        return width / (2.0 * Math.tan(Math.toRadians(fov) / 2.0));
    }
}
