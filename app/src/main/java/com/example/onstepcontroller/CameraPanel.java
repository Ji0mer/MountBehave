package com.example.onstepcontroller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera plate-solving page, hosted inside MainActivity (not a separate Activity) so the
 * shell-level side menu and floating Stop button stay available while it is shown. Owns the
 * Camera2 wrapper and the detection overlay, and drives an {@link ImageSolveEngine} that does
 * the detection + blind solving; MainActivity drives it via {@link #onShown()}/{@link
 * #onHidden()} on page changes and {@link #onResume()}/{@link #onPause()} on the lifecycle.
 *
 * <p>This class is UI orchestration only -- capture, threading/lifecycle guards and result
 * display. The detection/solve algorithms live in {@link ImageSolveEngine} so they can be
 * shared with other image sources (e.g. imported photos). No mount commands here.
 */
final class CameraPanel implements StarFieldCamera.Listener {

    /** Supplies the current observer location, for the polar-alignment alt/az decomposition. */
    interface ObserverProvider {
        ObserverState current();
    }

    /**
     * Mount-side RA-axis rotation for the automatic polar-alignment sequence (phase 4).
     * Implemented by MainActivity, which owns the OnStep connection and its safety gates.
     * The implementation must rotate ONLY the RA motor (timed move, never a GOTO -- a GOTO
     * could flip the pier, and any Dec motion between shots breaks the axis recovery).
     */
    interface RaRotator {
        /** Null when a rotation may start now, else a user-facing reason it is blocked. */
        String blockedReason();

        /**
         * The measurement sequence is starting: reserve the mount (refuse GOTO/manual
         * motion/tracking changes) and pause anything that could move the Dec axis between
         * shots (e.g. dual-axis model tracking). Reports via {@code callback}: onDone means
         * the mount is quiet and the first shot may start; onError aborts the sequence.
         */
        void onSequenceStarted(Callback callback);

        /** Rotate the RA axis by roughly {@code degrees} (best effort), then report once. */
        void rotateRa(double degrees, Callback callback);

        /** The sequence ended (done/failed/cancelled): restore what onSequenceStarted paused. */
        void onSequenceFinished();

        /** Stop an in-flight rotation; a late callback after this is ignored by the panel. */
        void cancelRotation();

        interface Callback {
            void onDone(double actualDegrees);

            void onError(String message);
        }
    }

    private static final long DEFAULT_EXPOSURE_NANOS = 1_000_000_000L; // 1 s
    private static final int DEFAULT_ISO = 800;
    private static final int EXPOSURE_STEPS = 1000;
    // Allow long exposures up to the device limit; many phones cap well under this.
    private static final long EXPOSURE_CEILING_NANOS = 30_000_000_000L; // 30 s

    // Bound the decoded bitmap for imported photos, matching the camera capture path; star
    // detection downscales again internally, so this only caps memory.
    private static final int IMPORT_DECODE_LONG_EDGE = 2400;
    // 35mm-equivalent focal length -> horizontal FOV uses the full-frame 36mm sensor width.
    private static final double FULL_FRAME_WIDTH_MM = 36.0;
    // Manual-retry horizontal-FOV presets (deg) when an imported photo fails to solve. The
    // automatic import already scans ~24..100 deg, so these reach a bit beyond that range
    // (ultrawide / telephoto) where the grid did not look; manual entry covers anything else.
    private static final double[] FOV_PRESETS_DEG = {110, 65, 22};

    // Automatic polar alignment: shots to take and RA rotation between consecutive shots.
    // ~15 deg/step keeps the axis-recovery noise a few times the solve noise (error scales
    // with 1/sin(rotation)) while staying well clear of mount limits.
    private static final int AUTO_POLAR_SHOTS = 3;
    private static final double AUTO_POLAR_STEP_DEG = 15.0;

    private final Activity activity;
    private final int cameraPermissionRequest;
    private final int pickImageRequest;
    private final ObserverProvider observerProvider;
    private final RaRotator raRotator;
    private final StarFieldCamera camera;
    private final ImageSolveEngine solveEngine; // app-scoped shared instance (built once)
    private final PolarAlignment polarAlignment = new PolarAlignment();
    private final ExecutorService detectExecutor = Executors.newSingleThreadExecutor();
    private final View root;

    private TextureView previewView;
    private SolveOverlayView detectionView;
    private CheckBox manualToggle;
    private CheckBox autoFocusToggle;
    private SeekBar exposureSeek;
    private SeekBar isoSeek;
    private SeekBar focusSeek;
    private TextView exposureLabel;
    private TextView isoLabel;
    private TextView focusLabel;
    private Button captureButton;
    private Button pickButton;
    private Button saveButton;
    private TextView statusText;
    private TextView statsText;
    private LinearLayout fovRetryRow; // shown when an imported photo fails to solve
    private CheckBox polarToggle;     // enter polar-alignment mode (accumulate solves)
    private Button polarAutoButton;   // run/cancel the automatic shoot-rotate-solve sequence
    private Button polarResetButton;
    private TextView polarText;

    // Automatic polar-alignment sequence state (UI thread only). awaitingSolve marks the
    // window between triggering a capture and its solve finishing, so solves from manual
    // taps or imports during a rotation cannot advance the sequence.
    private boolean autoPolarActive;
    private boolean autoPolarAwaitingSolve;
    // Adjustment phase (after an auto measurement): the measured mount axis frozen in the
    // CAMERA frame. Bolt turns, tracking and RA motion all rotate the rigid body about or
    // around this very axis, so it stays constant in camera coordinates -- each later shot
    // only needs a solve to refresh the correction arrow, no re-measurement. Null while
    // measuring; non-null switches feedPolarIfActive from accumulating to refreshing.
    private double[] polarAdjustAxisCam;

    private long exposureMinNanos = 1_000_000L;
    private long exposureMaxNanos = EXPOSURE_CEILING_NANOS;
    private int isoMin = 50;
    private int isoMax = 1600;
    private float focusMaxDiopters = 0.0f;
    private long selectedExposureNanos = DEFAULT_EXPOSURE_NANOS;
    private int selectedIso = DEFAULT_ISO;
    private float selectedFocusDiopters = 0.0f; // 0 = infinity (best default for stars)

    private boolean shown;
    private boolean capturing;
    // Bumped on hide/pause/destroy. A detection task captures it at submit time and drops its
    // result if the panel moved on (hidden, rebuilt, or destroyed) before detect() finished;
    // shutdownNow() alone cannot interrupt the pure-CPU detect() loop. All accessed on the UI
    // thread (lifecycle callbacks + runOnUiThread), so no synchronization is needed.
    private int panelGeneration;
    private Bitmap lastBitmap;
    private StarFieldCamera.CaptureInfo lastInfo;
    private ImageSource lastSource; // provenance of the current frame (camera vs imported)
    private StarDetector.StarField lastField;
    private PlateSolver.Solution lastSolution;
    // Last successful LIVE-capture solution, used as a warm-start hint for the next live
    // solve (same camera; a stale hint falls back to the blind solve, so never cleared).
    private PlateSolver.Solution liveSolveHint;
    // Durations of the latest detect/solve, for the CAMERA-SOLVE log line (UI thread).
    private long lastDetectMs;
    private long lastSolveMs;

    // The solve engine (catalog + solver) is heavy (≈119k stars, ≈164k seed triangles), so it
    // is built once on a background thread the first time the page is shown and reused.
    private boolean solverLoading;
    private boolean solveInFlight; // a solve task for the current frame is running
    // Bumped each time a solve is launched and on every reset. A solve callback only owns the
    // shared solve state if its captured token still matches, so a stale solve that finishes
    // after a hide/return cannot clear a newer solve's solveInFlight flag.
    private int solveGeneration;

    CameraPanel(Activity activity, int cameraPermissionRequest, int pickImageRequest,
                ObserverProvider observerProvider, RaRotator raRotator) {
        this.activity = activity;
        this.cameraPermissionRequest = cameraPermissionRequest;
        this.pickImageRequest = pickImageRequest;
        this.observerProvider = observerProvider;
        this.raRotator = raRotator;
        this.camera = new StarFieldCamera(activity, this);
        this.solveEngine = ImageSolveEngine.shared(activity); // shared app-scoped instance
        this.root = buildView();
    }

    View view() {
        return root;
    }

    /** Called when the camera page becomes the selected page. */
    void onShown() {
        shown = true;
        maybeOpenCamera();
        ensureSolverAsync();
    }

    /** Build the solve engine once, off the UI thread, so the first solve is ready. */
    private void ensureSolverAsync() {
        if (solveEngine.isReady() || solverLoading) {
            return;
        }
        solverLoading = true;
        detectExecutor.execute(() -> {
            try {
                solveEngine.load();
                activity.runOnUiThread(() -> {
                    solverLoading = false;
                    // A frame captured before the solver was ready was only detected; solve it now.
                    maybeSolveCurrentField();
                });
            } catch (Throwable t) {
                Logger.error("plate solver init failed", t);
                activity.runOnUiThread(() -> {
                    solverLoading = false;
                    // An auto-polar shot waiting on this engine can never be solved now.
                    autoPolarOnSolveFinished(null);
                });
            }
        });
    }

    /** Called when another page is selected. */
    void onHidden() {
        shown = false;
        cancelAutoPolarAlignment(null); // leaving the page must stop any auto rotation
        panelGeneration++;       // invalidate any in-flight detection result
        setCapturing(false);     // never leave the capture button stuck disabled after a switch
        resetPreviewState();     // restore live preview so we don't return to a stale frame
        camera.close();
    }

    /**
     * Return to the live-preview state: show the preview, drop the captured image / detection
     * result / stats. Does NOT recycle lastBitmap: an in-flight detect() may still hold it,
     * and recycling from here would crash that task; GC reclaims it once detection finishes.
     * Clearing lastBitmap also means the next capture's onCaptureComplete won't try to recycle
     * a bitmap a stale detection could still be reading.
     */
    private void resetPreviewState() {
        if (previewView != null) {
            previewView.setVisibility(View.VISIBLE);
        }
        if (detectionView != null) {
            detectionView.setVisibility(View.GONE);
            detectionView.clear();
        }
        showFovRetry(false);
        lastBitmap = null;
        lastInfo = null;
        lastSource = null;
        lastField = null;
        lastSolution = null;
        solveInFlight = false;
        solveGeneration++; // orphan any in-flight solve so its callback won't touch new state
        if (statsText != null) {
            statsText.setText("");
        }
        if (statusText != null) {
            statusText.setText(R.string.camera_solve_hint);
        }
    }

    void onResume() {
        if (shown) {
            maybeOpenCamera();
        }
    }

    void onPause() {
        cancelAutoPolarAlignment(null); // backgrounded: stop any auto rotation
        panelGeneration++;
        setCapturing(false);
        resetPreviewState(); // same as onHidden: don't return from background to a stale frame
        camera.close();
    }

    void onDestroy() {
        panelGeneration++;
        detectExecutor.shutdownNow();
        camera.close();
    }

    private boolean isPanelStale(int gen) {
        return gen != panelGeneration;
    }

    void onCameraPermissionResult(boolean granted) {
        if (granted) {
            statusText.setText("");
            maybeOpenCamera();
        } else {
            statusText.setText(R.string.camera_permission_denied);
        }
    }

    private View buildView() {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        FrameLayout frame = new FrameLayout(activity);
        previewView = new TextureView(activity);
        previewView.setSurfaceTextureListener(surfaceListener);
        frame.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detectionView = new SolveOverlayView(activity);
        detectionView.setVisibility(View.GONE);
        frame.addView(detectionView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Fixed-height preview so the controls below always fit; leaves room for the
        // shell side menu / Stop button overlapping the top-left.
        container.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        manualToggle = new CheckBox(activity);
        manualToggle.setText(R.string.camera_manual_toggle);
        manualToggle.setTextColor(Color.rgb(226, 232, 240));
        manualToggle.setChecked(true);
        manualToggle.setOnCheckedChangeListener((b, checked) -> updateManualControlsEnabled());
        container.addView(manualToggle, wrap());

        exposureLabel = label();
        container.addView(exposureLabel, wrap());
        exposureSeek = new SeekBar(activity);
        exposureSeek.setMax(EXPOSURE_STEPS);
        exposureSeek.setOnSeekBarChangeListener(new SimpleSeekListener(() -> {
            selectedExposureNanos = exposureFromProgress(exposureSeek.getProgress());
            updateExposureLabel();
        }));
        container.addView(exposureSeek, wrap());

        isoLabel = label();
        container.addView(isoLabel, wrap());
        isoSeek = new SeekBar(activity);
        isoSeek.setMax(1000);
        isoSeek.setOnSeekBarChangeListener(new SimpleSeekListener(() -> {
            selectedIso = isoMin + (int) Math.round((isoMax - isoMin) * (isoSeek.getProgress() / 1000.0));
            updateIsoLabel();
        }));
        container.addView(isoSeek, wrap());

        autoFocusToggle = new CheckBox(activity);
        autoFocusToggle.setText(R.string.camera_autofocus);
        autoFocusToggle.setTextColor(Color.rgb(226, 232, 240));
        autoFocusToggle.setChecked(true);
        autoFocusToggle.setOnCheckedChangeListener((b, checked) -> updateFocusControlsEnabled());
        container.addView(autoFocusToggle, wrap());

        focusLabel = label();
        container.addView(focusLabel, wrap());
        focusSeek = new SeekBar(activity);
        focusSeek.setMax(1000);
        focusSeek.setOnSeekBarChangeListener(new SimpleSeekListener(() -> {
            selectedFocusDiopters = (float) (focusMaxDiopters * (focusSeek.getProgress() / 1000.0));
            updateFocusLabel();
        }));
        container.addView(focusSeek, wrap());

        // Polar alignment: rigidly mount the phone, enable, then shoot/import while rotating the
        // RA axis between shots. Each solved shot is accumulated; the mount's polar axis and its
        // error are derived from the relative rotations.
        LinearLayout polarRow = new LinearLayout(activity);
        polarRow.setOrientation(LinearLayout.HORIZONTAL);
        polarRow.setGravity(Gravity.CENTER_VERTICAL);
        polarToggle = new CheckBox(activity);
        polarToggle.setText(R.string.camera_polar_toggle);
        polarToggle.setTextColor(Color.rgb(226, 232, 240));
        polarToggle.setOnCheckedChangeListener((b, checked) -> onPolarToggled(checked));
        // Three equal columns so the buttons line up with the capture row right below.
        polarRow.addView(polarToggle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        polarAutoButton = actionButton(R.string.camera_polar_auto_start);
        polarAutoButton.setOnClickListener(v -> toggleAutoPolar());
        polarAutoButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        autoParams.leftMargin = dp(8);
        polarRow.addView(polarAutoButton, autoParams);
        polarResetButton = actionButton(R.string.camera_polar_reset);
        polarResetButton.setOnClickListener(v -> resetPolar());
        polarResetButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        resetParams.leftMargin = dp(8);
        polarRow.addView(polarResetButton, resetParams);
        LinearLayout.LayoutParams polarRowParams = wrap();
        polarRowParams.topMargin = dp(4);
        container.addView(polarRow, polarRowParams);

        LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        captureButton = actionButton(R.string.camera_capture);
        captureButton.setOnClickListener(v -> onCaptureClicked());
        buttonRow.addView(captureButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pickButton = actionButton(R.string.camera_pick);
        pickButton.setOnClickListener(v -> onPickImageClicked());
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pickParams.leftMargin = dp(8);
        buttonRow.addView(pickButton, pickParams);
        saveButton = actionButton(R.string.camera_save);
        saveButton.setOnClickListener(v -> onSaveClicked());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.leftMargin = dp(8);
        buttonRow.addView(saveButton, saveParams);
        LinearLayout.LayoutParams buttonRowParams = wrap();
        buttonRowParams.topMargin = dp(6);
        container.addView(buttonRow, buttonRowParams);

        // One combined, scrollable info box under the capture row: transient status, then the
        // polar-alignment readout, then detection/solve stats. Empty sections collapse so the
        // box reads as a single block of text.
        statusText = label();
        statusText.setText(R.string.camera_solve_hint);
        collapseWhenEmpty(statusText);
        polarText = label();
        polarText.setTextColor(Color.rgb(255, 210, 140));
        polarText.setVisibility(View.GONE);
        statsText = label();
        statsText.setTextColor(Color.rgb(148, 200, 255));
        statsText.setVisibility(View.GONE);
        collapseWhenEmpty(statsText);
        LinearLayout infoColumn = new LinearLayout(activity);
        infoColumn.setOrientation(LinearLayout.VERTICAL);
        infoColumn.addView(statusText, wrap());
        infoColumn.addView(polarText, wrap());
        infoColumn.addView(statsText, wrap());
        ScrollView infoScroll = new ScrollView(activity);
        infoScroll.addView(infoColumn); // ScrollView default params: match width, wrap height
        container.addView(infoScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132)));

        // Shown only when an imported photo fails to solve: let the user pick a field of view
        // (the photo's EXIF prior may be missing or wrong) and re-solve the same frame.
        fovRetryRow = new LinearLayout(activity);
        fovRetryRow.setOrientation(LinearLayout.HORIZONTAL);
        fovRetryRow.setVisibility(View.GONE);
        TextView fovLabel = label();
        fovLabel.setText(R.string.camera_fov_retry_label);
        fovRetryRow.addView(fovLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (double presetFov : FOV_PRESETS_DEG) {
            final double fov = presetFov;
            Button b = new Button(activity);
            b.setAllCaps(false);
            b.setText(activity.getString(R.string.camera_fov_preset, (int) presetFov));
            b.setOnClickListener(v -> retrySolveWithFov(fov));
            fovRetryRow.addView(b, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        Button manualFov = new Button(activity);
        manualFov.setAllCaps(false);
        manualFov.setText(R.string.camera_fov_manual);
        manualFov.setOnClickListener(v -> promptManualFov());
        fovRetryRow.addView(manualFov, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(fovRetryRow, wrap());

        updateExposureLabel();
        updateIsoLabel();
        updateFocusLabel();
        updateFocusControlsEnabled();
        return container;
    }

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    maybeOpenCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    private void maybeOpenCamera() {
        if (!shown) {
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.setText(R.string.camera_permission_rationale);
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, cameraPermissionRequest);
            return;
        }
        if (previewView == null || !previewView.isAvailable()) {
            return;
        }
        SurfaceTexture texture = previewView.getSurfaceTexture();
        if (texture == null) {
            return;
        }
        camera.open(texture, previewView.getWidth(), previewView.getHeight());
    }

    private void onCaptureClicked() {
        if (autoPolarActive) {
            return; // the auto polar sequence owns the camera; a manual shot mid-sequence
                    // (rotating, or while a solve is still running) would feed a junk attitude
        }
        startCapture();
    }

    /** Submit a capture; false when the camera cannot take one now (no callback will fire). */
    private boolean startCapture() {
        if (capturing) {
            return false;
        }
        return camera.capture(manualToggle.isChecked(), selectedExposureNanos, selectedIso,
                autoFocusToggle.isChecked(), selectedFocusDiopters);
    }

    private void onPickImageClicked() {
        if (capturing) {
            return; // busy detecting/solving a previous frame
        }
        if (autoPolarActive) {
            return; // don't mix imported shots into a running auto sequence
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(
                    Intent.createChooser(intent, activity.getString(R.string.camera_pick)),
                    pickImageRequest);
        } catch (RuntimeException ex) {
            statusText.setText(activity.getString(R.string.camera_pick_failed, safeMessage(ex)));
        }
    }

    /**
     * Handle a photo chosen from the gallery: decode it (with EXIF orientation), read a focal
     * prior from EXIF if present, then detect + solve via the shared engine and show the result.
     * Forwarded by MainActivity from onActivityResult. The solve runs on the worker; an unknown
     * FOV (no lens EXIF) falls back to the engine's FOV grid search.
     */
    void onImagePicked(Uri uri) {
        if (uri == null) {
            statusText.setText(R.string.camera_pick_cancelled);
            return;
        }
        previewView.setVisibility(View.GONE);
        detectionView.setVisibility(View.VISIBLE);
        detectionView.clear();
        showFovRetry(false);
        statusText.setText(R.string.camera_analyzing);
        setCapturing(true);
        final int gen = panelGeneration;
        final int solveGen = ++solveGeneration; // invalidate any in-flight camera solve
        solveInFlight = true;
        detectExecutor.execute(() -> {
            Bitmap bitmap = null; // declared out here so the catch can recycle it on failure
            StarDetector.StarField field;
            PlateSolver.Solution sol;
            try {
                ImportedImage imported = decodeImported(uri);
                bitmap = imported.bitmap;
                solveEngine.load(); // ensure ready (idempotent; waits if still building)
                ImageSolveResult result = solveEngine.run(bitmap,
                        ImageSolveEngine.SolveInput.forImport(imported.fovHorizontalDeg));
                field = result.field;
                sol = result.solution;
            } catch (Throwable t) {
                Logger.error("imported image solve failed", t);
                // The bitmap was decoded but never handed to the UI; free it (failures here
                // are often memory pressure, so leaking a large bitmap worsens retries).
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                activity.runOnUiThread(() -> {
                    if (solveGen == solveGeneration) {
                        solveInFlight = false;
                    }
                    if (isPanelStale(gen)) {
                        return;
                    }
                    statusText.setText(activity.getString(R.string.camera_pick_failed, safeMessage(t)));
                    setCapturing(false);
                });
                return;
            }
            final Bitmap finalBitmap = bitmap;
            final StarDetector.StarField finalField = field;
            final PlateSolver.Solution finalSol = sol;
            activity.runOnUiThread(() -> {
                if (solveGen != solveGeneration || isPanelStale(gen)) {
                    if (!finalBitmap.isRecycled()) {
                        finalBitmap.recycle();
                    }
                    return;
                }
                solveInFlight = false;
                Bitmap previous = lastBitmap;
                lastBitmap = finalBitmap;
                lastInfo = null; // imported photo has no live capture metadata
                lastDetectMs = 0; // timings are only tracked for the live-capture path
                lastSolveMs = 0;
                lastSource = ImageSource.IMPORT;
                lastField = finalField;
                lastSolution = finalSol;
                detectionView.setImage(finalBitmap, finalField.sourceWidth, finalField.sourceHeight);
                detectionView.setDetections(finalField.stars);
                if (finalSol != null) {
                    detectionView.setSolve(finalSol, solveEngine.catalog(), finalField.skyMask,
                            finalField.analysisWidth, finalField.analysisHeight);
                }
                statusText.setText("");
                // Imported photo with no solution: offer a manual FOV retry (the EXIF prior may
                // be missing or wrong, and a few-star frame won't be helped by FOV either way).
                showFovRetry(lastSource == ImageSource.IMPORT
                        && finalSol == null && finalField.stars.size() >= 3);
                feedPolarIfActive(finalSol);
                showStats(finalField, finalSol, activity.getString(R.string.camera_source_import));
                logDetection();
                setCapturing(false);
                if (previous != null && previous != finalBitmap && !previous.isRecycled()) {
                    previous.recycle();
                }
            });
        });
    }

    /** Decoded imported photo plus the horizontal FOV from EXIF (0 if unknown). */
    private static final class ImportedImage {
        final Bitmap bitmap;
        final double fovHorizontalDeg;

        ImportedImage(Bitmap bitmap, double fovHorizontalDeg) {
            this.bitmap = bitmap;
            this.fovHorizontalDeg = fovHorizontalDeg;
        }
    }

    /** Decode {@code uri} bounded to {@link #IMPORT_DECODE_LONG_EDGE}, applying EXIF orientation,
     *  and read a horizontal FOV from the 35mm-equivalent focal length if present. */
    private ImportedImage decodeImported(Uri uri) throws IOException {
        ContentResolver resolver = activity.getContentResolver();

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        double fov = 0.0;
        try (InputStream exifStream = resolver.openInputStream(uri)) {
            if (exifStream != null) {
                ExifInterface exif = new ExifInterface(exifStream);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                int focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0);
                if (focal35 > 0) {
                    fov = Math.toDegrees(2.0 * Math.atan(FULL_FRAME_WIDTH_MM / (2.0 * focal35)));
                }
            }
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream boundsStream = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(boundsStream, null, bounds);
        }
        int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longEdge / sample > IMPORT_DECODE_LONG_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream decodeStream = resolver.openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(decodeStream, null, opts);
        }
        if (decoded == null) {
            throw new IOException("could not decode image");
        }
        return new ImportedImage(applyExifOrientation(decoded, orientation), fov);
    }

    /** Rotate/flip {@code src} to upright per the EXIF orientation tag. Recycles {@code src}
     *  when a transformed copy is produced. */
    private static Bitmap applyExifOrientation(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(270); m.postScale(-1, 1); break;
            default: return src; // ORIENTATION_NORMAL / undefined: no transform
        }
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (rotated != src && !src.isRecycled()) {
            src.recycle();
        }
        return rotated;
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    // --- polar alignment ---

    private void onPolarToggled(boolean enabled) {
        if (!enabled) {
            cancelAutoPolarAlignment(null);
        }
        polarAutoButton.setVisibility(enabled && raRotator != null ? View.VISIBLE : View.GONE);
        polarResetButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        polarText.setVisibility(enabled ? View.VISIBLE : View.GONE);
        polarAdjustAxisCam = null;
        detectionView.clearPolarCorrection();
        if (enabled) {
            polarAlignment.clear();
            updatePolarReadout(null);
        }
    }

    private void resetPolar() {
        cancelAutoPolarAlignment(null); // reset means start over; don't keep rotating
        polarAlignment.clear();
        polarAdjustAxisCam = null;
        detectionView.clearPolarCorrection();
        updatePolarReadout(null);
    }

    // --- automatic polar alignment (shoot -> solve -> rotate RA -> repeat) ---

    private void toggleAutoPolar() {
        if (autoPolarActive) {
            cancelAutoPolarAlignment(activity.getString(R.string.camera_polar_auto_cancelled));
            return;
        }
        if (raRotator == null) {
            return;
        }
        String blocked = raRotator.blockedReason();
        if (blocked != null) {
            statusText.setText(blocked);
            return;
        }
        if (capturing) {
            return; // a capture/solve is already in flight; let it finish first
        }
        Logger.info("AUTO-POLAR start shots=" + AUTO_POLAR_SHOTS
                + " stepDeg=" + AUTO_POLAR_STEP_DEG);
        ensureSolverAsync(); // retry the engine build if an earlier attempt failed
        polarAlignment.clear();
        polarAdjustAxisCam = null; // back to measurement: feeds must accumulate again
        detectionView.clearPolarCorrection();
        updatePolarReadout(null);
        autoPolarActive = true;
        polarAutoButton.setText(R.string.camera_polar_auto_cancel);
        // First shot waits until the mount side confirms it is quiet (dual-axis tracking
        // paused), so the Dec axis cannot be moving during any exposure of the sequence.
        raRotator.onSequenceStarted(new RaRotator.Callback() {
            @Override
            public void onDone(double ignored) {
                if (!autoPolarActive) {
                    return; // cancelled while preparing
                }
                autoPolarShoot();
            }

            @Override
            public void onError(String message) {
                if (!autoPolarActive) {
                    return;
                }
                failAutoPolar(message);
            }
        });
    }

    private void autoPolarShoot() {
        if (!autoPolarActive) {
            return;
        }
        autoPolarAwaitingSolve = true;
        statusText.setText(activity.getString(R.string.camera_polar_auto_shooting,
                polarAlignment.shotCount() + 1, AUTO_POLAR_SHOTS));
        if (!startCapture()) {
            // Camera not ready (still opening, permission missing, open failed): no capture
            // callback will ever fire, so fail now instead of waiting forever.
            autoPolarAwaitingSolve = false;
            failAutoPolar(activity.getString(R.string.camera_polar_auto_camera_not_ready));
        }
    }

    /**
     * Advance the sequence after a live-capture attempt finished (solve result, or null when
     * the frame could not be solved). Solves the panel did not trigger itself (manual taps,
     * imports) are ignored via the awaiting flag.
     */
    private void autoPolarOnSolveFinished(PlateSolver.Solution sol) {
        if (!autoPolarActive || !autoPolarAwaitingSolve) {
            return;
        }
        autoPolarAwaitingSolve = false;
        if (sol == null) {
            failAutoPolar(activity.getString(R.string.camera_polar_auto_solve_failed));
            return;
        }
        int shots = polarAlignment.shotCount();
        if (shots >= AUTO_POLAR_SHOTS) {
            endAutoPolarSequence();
            double[] latLon = observerLatLon();
            PolarAlignment.Result r = polarAlignment.compute(
                    latLon[0], latLon[1], System.currentTimeMillis());
            if (r != null) {
                // Enter the adjustment phase: freeze the measured axis in the camera frame
                // (invariant under bolt turns / tracking / RA motion) so each further shot
                // just refreshes the correction arrow.
                polarAdjustAxisCam = PolarAlignment.apply(sol.r,
                        PolarAlignment.unitVector(r.axisRaDeg, r.axisDecDeg));
                statusText.setText(R.string.camera_polar_adjust_hint);
            } else {
                statusText.setText(R.string.camera_polar_auto_done);
            }
            Logger.info("AUTO-POLAR done shots=" + shots);
            return;
        }
        statusText.setText(activity.getString(R.string.camera_polar_auto_rotating,
                shots, AUTO_POLAR_SHOTS));
        Logger.info("AUTO-POLAR rotate request deg=" + AUTO_POLAR_STEP_DEG + " afterShot=" + shots);
        raRotator.rotateRa(AUTO_POLAR_STEP_DEG, new RaRotator.Callback() {
            @Override
            public void onDone(double actualDegrees) {
                Logger.info("AUTO-POLAR rotated deg="
                        + String.format(Locale.US, "%.2f", actualDegrees));
                if (!autoPolarActive) {
                    return; // cancelled while rotating
                }
                autoPolarShoot();
            }

            @Override
            public void onError(String message) {
                if (!autoPolarActive) {
                    return;
                }
                failAutoPolar(message);
            }
        });
    }

    /** Stop the sequence on an unrecoverable step failure; accumulated shots are kept. */
    private void failAutoPolar(String message) {
        endAutoPolarSequence();
        statusText.setText(message);
        Logger.warn("AUTO-POLAR stopped: " + message);
    }

    /**
     * Cancel the automatic sequence and any rotation in flight. Safe to call when inactive.
     * Called on user cancel, polar-mode off, reset, page hide/pause, and by MainActivity on
     * emergency stop / connection loss (the safety convention: stop always wins).
     */
    void cancelAutoPolarAlignment(String statusMessage) {
        if (!autoPolarActive) {
            return;
        }
        if (raRotator != null) {
            raRotator.cancelRotation();
        }
        endAutoPolarSequence();
        if (statusMessage != null) {
            statusText.setText(statusMessage);
        }
        Logger.info("AUTO-POLAR cancelled");
    }

    /** Single exit funnel: clears sequence state and lets the mount side restore tracking. */
    private void endAutoPolarSequence() {
        autoPolarActive = false;
        autoPolarAwaitingSolve = false;
        polarAutoButton.setText(R.string.camera_polar_auto_start);
        if (raRotator != null) {
            raRotator.onSequenceFinished();
        }
    }

    /** Feed a successful solve into the polar-alignment accumulator while that mode is on. */
    private void feedPolarIfActive(PlateSolver.Solution sol) {
        if (sol == null || polarToggle == null || !polarToggle.isChecked()) {
            return;
        }
        if (polarAdjustAxisCam != null) {
            // Adjustment phase: refresh the arrow, don't measure. Only live captures may
            // refresh -- the frozen axis lives in the rigidly-mounted phone's camera frame,
            // which an imported photo's attitude has nothing to do with.
            if (lastSource == ImageSource.CAMERA) {
                updatePolarAdjustment(sol);
            }
            return;
        }
        polarAlignment.addShot(sol.r);
        updatePolarReadout(sol);
    }

    /** Observer {latitude, longitude} with the panel's usual Boston fallback. */
    private double[] observerLatLon() {
        ObserverState obs = observerProvider != null ? observerProvider.current() : null;
        double lat = obs != null ? obs.latitudeDegrees : ObserverState.BOSTON_LATITUDE;
        double lon = obs != null ? obs.longitudeDegrees : ObserverState.BOSTON_LONGITUDE;
        return new double[]{lat, lon};
    }

    /** @param sol the solve shown on screen, for drawing the correction; null = text only. */
    private void updatePolarReadout(PlateSolver.Solution sol) {
        if (polarText == null) {
            return;
        }
        int n = polarAlignment.shotCount();
        if (n < 2) {
            polarText.setText(activity.getString(R.string.camera_polar_progress, n));
            return;
        }
        double[] latLon = observerLatLon();
        PolarAlignment.Result r = polarAlignment.compute(latLon[0], latLon[1], System.currentTimeMillis());
        if (r == null) {
            polarText.setText(activity.getString(R.string.camera_polar_need_rotation, n));
            return;
        }
        polarText.setText(formatPolar(r));
        if (sol != null) {
            showPolarCorrection(r, latLon[0]);
        }
    }

    /**
     * Adjustment phase: re-express the frozen camera-frame axis in celestial coordinates
     * through this shot's attitude, then refresh the readout and the on-image arrow. The
     * error shrinks live as the user turns the alt/az bolts and re-shoots.
     */
    private void updatePolarAdjustment(PlateSolver.Solution sol) {
        double[] latLon = observerLatLon();
        double[] axisCel = PolarAlignment.applyTranspose(sol.r, polarAdjustAxisCam);
        PolarAlignment.Result r = PolarAlignment.resultFromAxis(
                axisCel, latLon[0], latLon[1], System.currentTimeMillis(),
                polarAlignment.shotCount());
        polarText.setText(formatPolar(r));
        showPolarCorrection(r, latLon[0]);
        statusText.setText(R.string.camera_polar_adjust_hint);
        Logger.info("AUTO-POLAR adjust refresh errorDeg="
                + String.format(Locale.US, "%.3f", r.polarErrorDeg));
    }

    /** Draw the axis/pole markers, arrow and error label on the current solve overlay. */
    private void showPolarCorrection(PolarAlignment.Result r, double latitudeDeg) {
        if (detectionView == null) {
            return;
        }
        double poleDec = latitudeDeg >= 0 ? 90.0 : -90.0;
        detectionView.setPolarCorrection(r.axisRaDeg, r.axisDecDeg, poleDec,
                formatAngle(r.polarErrorDeg));
    }

    private String formatPolar(PolarAlignment.Result r) {
        String altDir = activity.getString(r.altErrorDeg >= 0
                ? R.string.camera_polar_alt_high : R.string.camera_polar_alt_low);
        String azDir = activity.getString(r.azErrorDeg >= 0
                ? R.string.camera_polar_az_east : R.string.camera_polar_az_west);
        return activity.getString(R.string.camera_polar_result,
                r.shotsUsed,
                formatAngle(r.polarErrorDeg),
                altDir, formatAngle(Math.abs(r.altErrorDeg)),
                azDir, formatAngle(Math.abs(r.azErrorDeg)),
                r.axisRaDeg / 15.0, r.axisDecDeg);
    }

    /** Angle as arcminutes below 1 degree, else degrees. */
    private static String formatAngle(double deg) {
        if (deg < 1.0) {
            return String.format(Locale.US, "%.1f'", deg * 60.0);
        }
        return String.format(Locale.US, "%.2f°", deg);
    }

    /** Show/hide the manual-FOV retry row (only meaningful for an imported, unsolved frame). */
    private void showFovRetry(boolean show) {
        if (fovRetryRow != null) {
            fovRetryRow.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** Ask for a horizontal FOV in degrees, then re-solve the current frame at that scale. */
    private void promptManualFov() {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(R.string.camera_fov_manual_hint);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.camera_fov_manual)
                .setView(input)
                .setPositiveButton(R.string.camera_fov_manual_ok, (d, w) -> {
                    double fov;
                    try {
                        fov = Double.parseDouble(input.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        return;
                    }
                    if (fov > 0.0 && fov < 180.0) {
                        retrySolveWithFov(fov);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Re-solve the already-detected current frame at a user-chosen horizontal FOV. Reuses the
     *  same generation guards as the automatic solve so a stale result cannot clobber state. */
    private void retrySolveWithFov(double fovDeg) {
        final StarDetector.StarField field = lastField;
        if (field == null || solveInFlight || !solveEngine.isReady() || field.stars.size() < 3
                || autoPolarActive) { // don't feed a stale import's re-solve into a running sequence
            return;
        }
        final int gen = panelGeneration;
        final int solveGen = ++solveGeneration;
        final String sourceLine = activity.getString(R.string.camera_source_manual_fov, fovDeg);
        solveInFlight = true;
        setCapturing(true);
        showFovRetry(false);
        statusText.setText(R.string.camera_analyzing);
        showStats(field, null, sourceLine);
        detectExecutor.execute(() -> {
            PlateSolver.Solution sol;
            try {
                // A deliberate user FOV: solve once at that scale (no grid override).
                sol = solveEngine.solve(field, ImageSolveEngine.SolveInput.fromHorizontalFov(fovDeg));
            } catch (Throwable t) {
                Logger.error("manual-fov solve failed", t);
                sol = null;
            }
            final PlateSolver.Solution finalSol = sol;
            activity.runOnUiThread(() -> {
                if (solveGen != solveGeneration) {
                    return;
                }
                solveInFlight = false;
                setCapturing(false);
                if (isPanelStale(gen) || lastField != field) {
                    return;
                }
                lastSolution = finalSol;
                statusText.setText("");
                if (finalSol != null) {
                    detectionView.setSolve(finalSol, solveEngine.catalog(), field.skyMask,
                            field.analysisWidth, field.analysisHeight);
                } else {
                    showFovRetry(true); // still no solution; let the user try another FOV
                }
                feedPolarIfActive(finalSol);
                showStats(field, finalSol, sourceLine);
                logDetection();
            });
        });
    }

    private void onSaveClicked() {
        if (lastBitmap == null || lastBitmap.isRecycled() || lastField == null) {
            statusText.setText(R.string.camera_save_nothing);
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String location;
        try {
            location = savePhoto(lastBitmap, "clearsky-sky-" + stamp + ".jpg");
        } catch (Exception ex) {
            statusText.setText(activity.getString(R.string.camera_save_failed,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            return;
        }
        Logger.info("CAMERA-SAVE file=" + location);
        String msg = activity.getString(R.string.camera_saved, location);
        statusText.setText(msg);
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
    }

    private String savePhoto(Bitmap bitmap, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/ClearskyGoto");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            ContentResolver resolver = activity.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert returned null");
            }
            boolean published = false;
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("openOutputStream returned null");
                    }
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                        throw new IOException("JPEG compression failed");
                    }
                }
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(uri, publish, null, null) <= 0) {
                    throw new IOException("MediaStore publish failed");
                }
                published = true;
            } finally {
                if (!published) {
                    resolver.delete(uri, null, null);
                }
            }
            return Environment.DIRECTORY_PICTURES + "/ClearskyGoto/" + fileName;
        }
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                throw new IOException("JPEG compression failed");
            }
        }
        return file.getAbsolutePath();
    }

    /** Logs the current frame's capture/detection/solve summary; called after every solve. */
    private void logDetection() {
        StarFieldCamera.CaptureInfo info = lastInfo;
        StarDetector.StarField f = lastField;
        PlateSolver.Solution sol = lastSolution;
        StringBuilder sb = new StringBuilder("CAMERA-SOLVE phase2");
        if (info != null) {
            sb.append(" exposureNanos=").append(info.exposureNanos)
                    .append(" iso=").append(info.iso)
                    .append(" manual=").append(info.manualUsed)
                    .append(" actualValues=").append(info.actualValuesUsed)
                    .append(" focalMm=").append(info.focalLengthMm)
                    .append(" fovDeg=").append(String.format(Locale.US, "%.2f", info.fovDegrees))
                    .append(" image=").append(info.imageWidth).append("x").append(info.imageHeight);
        }
        if (f != null) {
            sb.append(" stars=").append(f.stars.size())
                    .append(" raw=").append(f.rawCount)
                    .append(" background=").append(String.format(Locale.US, "%.2f", f.background))
                    .append(" noise=").append(String.format(Locale.US, "%.3f", f.noise))
                    .append(" threshold=").append(String.format(Locale.US, "%.2f", f.threshold));
        }
        if (lastDetectMs > 0 || lastSolveMs > 0) {
            sb.append(" detectMs=").append(lastDetectMs).append(" solveMs=").append(lastSolveMs);
        }
        if (sol != null) {
            sb.append(" SOLVED raDeg=").append(String.format(Locale.US, "%.3f", sol.centerRaDeg))
                    .append(" decDeg=").append(String.format(Locale.US, "%.3f", sol.centerDecDeg))
                    .append(" fovW=").append(String.format(Locale.US, "%.2f", sol.fovWDeg))
                    .append(" roll=").append(String.format(Locale.US, "%.1f", sol.rollDeg))
                    .append(" fPix=").append(String.format(Locale.US, "%.0f", sol.fPix))
                    .append(" matched=").append(sol.matchDet.length)
                    .append(" rmsPx=").append(String.format(Locale.US, "%.2f", sol.rmsPx));
        } else {
            sb.append(" SOLVED=none");
        }
        Logger.info(sb.toString());
    }

    // --- StarFieldCamera.Listener (already on UI thread) ---

    @Override
    public void onReady(StarFieldCamera.Capabilities caps) {
        if (caps == null) {
            return;
        }
        exposureMinNanos = Math.max(1, caps.minExposureNanos);
        exposureMaxNanos = Math.max(exposureMinNanos + 1, Math.min(caps.maxExposureNanos, EXPOSURE_CEILING_NANOS));
        isoMin = caps.minIso;
        isoMax = Math.max(caps.minIso + 1, caps.maxIso);
        focusMaxDiopters = caps.minFocusDiopters;

        selectedExposureNanos = Math.max(exposureMinNanos, Math.min(exposureMaxNanos, DEFAULT_EXPOSURE_NANOS));
        selectedIso = Math.max(isoMin, Math.min(isoMax, DEFAULT_ISO));
        selectedFocusDiopters = 0.0f;
        exposureSeek.setProgress(progressFromExposure(selectedExposureNanos));
        isoSeek.setProgress((int) Math.round((selectedIso - isoMin) * 1000.0 / Math.max(1, isoMax - isoMin)));
        focusSeek.setProgress(0);
        updateExposureLabel();
        updateIsoLabel();
        updateFocusLabel();

        if (!caps.manualSensor) {
            manualToggle.setChecked(false);
            manualToggle.setEnabled(false);
            statusText.setText(R.string.camera_manual_unsupported);
        }
        if (!caps.manualFocus) {
            autoFocusToggle.setChecked(true);
            autoFocusToggle.setEnabled(false);
        }
        updateManualControlsEnabled();
        updateFocusControlsEnabled();
    }

    @Override
    public void onError(String message) {
        statusText.setText(message);
        setCapturing(false);
        if (autoPolarActive) {
            failAutoPolar(message); // camera failure mid-sequence: stop instead of hanging
        }
    }

    @Override
    public void onCaptureStarted() {
        setCapturing(true);
        statusText.setText(R.string.camera_capturing);
    }

    @Override
    public void onCaptureComplete(Bitmap image, StarFieldCamera.CaptureInfo info) {
        Bitmap previous = lastBitmap;
        lastBitmap = image;
        lastInfo = info;
        lastSource = ImageSource.CAMERA;
        lastField = null;
        lastSolution = null;
        previewView.setVisibility(View.GONE);
        detectionView.setVisibility(View.VISIBLE);
        detectionView.setImage(image, info.imageWidth, info.imageHeight);
        detectionView.setDetections(null);
        showFovRetry(false);
        statusText.setText(R.string.camera_analyzing);

        if (previous != null && previous != image && !previous.isRecycled()) {
            previous.recycle();
        }

        // Detect on the worker, then publish the frame and let maybeSolveCurrentField() decide
        // whether to solve. Detection and the (separately loaded) solve engine each call that
        // method on completion, so the solve fires whenever BOTH are ready regardless of which
        // finishes first -- and a frame captured before the engine loaded is still solved.
        final int detGen = panelGeneration;
        detectExecutor.execute(() -> {
            StarDetector.StarField field;
            final long detectStart = System.currentTimeMillis();
            try {
                field = solveEngine.detect(image);
            } catch (Throwable t) {
                Logger.error("star detection failed", t);
                activity.runOnUiThread(() -> {
                    if (isPanelStale(detGen)) {
                        return; // panel hidden/destroyed during detection
                    }
                    statusText.setText(activity.getString(R.string.camera_detect_failed,
                            t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                    setCapturing(false);
                    autoPolarOnSolveFinished(null);
                });
                return;
            }
            final StarDetector.StarField finalField = field;
            final long detectMs = System.currentTimeMillis() - detectStart;
            activity.runOnUiThread(() -> {
                if (isPanelStale(detGen)) {
                    return; // panel hidden/destroyed during detection; drop the result
                }
                lastDetectMs = detectMs;
                lastSolveMs = 0; // new frame; the solve has not run yet
                lastField = finalField;
                lastSolution = null;
                detectionView.setDetections(finalField.stars);
                setCapturing(false);
                if (!maybeSolveCurrentField()) {
                    showStats(finalField, null, captureSourceLine(info)); // solver not ready / too few stars
                    // Too few stars can never solve: fail the auto sequence now. A solver
                    // that is still loading will solve this frame when ready, so keep waiting.
                    if (finalField.stars.size() < 3) {
                        autoPolarOnSolveFinished(null);
                    }
                }
            });
        });
    }

    /**
     * Solve the current detected frame, once, via {@link ImageSolveEngine}. Called from BOTH
     * detection-complete and solver-ready (UI thread), so the solve runs as soon as a detected
     * frame and a built solver coexist -- whichever arrives last triggers it. Idempotent: a
     * {@code solveInFlight} guard and the {@code lastSolution == null} check prevent a double
     * solve, and the result is dropped if a newer capture replaced the frame meanwhile. Returns
     * true if it launched.
     */
    private boolean maybeSolveCurrentField() {
        final StarDetector.StarField field = lastField;
        final StarFieldCamera.CaptureInfo info = lastInfo;
        if (!solveEngine.isReady() || field == null || info == null || lastSolution != null
                || solveInFlight || field.stars.size() < 3) {
            return false;
        }
        final ImageSolveEngine.SolveInput input =
                ImageSolveEngine.SolveInput.fromHorizontalFov(info.fovDegrees);
        final int gen = panelGeneration;
        final int solveGen = ++solveGeneration;
        final String sourceLine = captureSourceLine(info);
        solveInFlight = true;
        statusText.setText(R.string.camera_analyzing);
        showStats(field, null, sourceLine); // shows the "solving" line via the solveInFlight branch
        final PlateSolver.Solution hint = liveSolveHint;
        detectExecutor.execute(() -> {
            PlateSolver.Solution sol;
            final long solveStart = System.currentTimeMillis();
            try {
                sol = solveEngine.solve(field, input, hint);
            } catch (Throwable t) {
                Logger.error("plate solve failed", t);
                sol = null;
            }
            final PlateSolver.Solution finalSol = sol;
            final long solveMs = System.currentTimeMillis() - solveStart;
            activity.runOnUiThread(() -> {
                if (solveGen != solveGeneration) {
                    return; // superseded by a newer solve or a reset; owns no shared state
                }
                solveInFlight = false;
                if (isPanelStale(gen)) {
                    return; // panel hidden/destroyed
                }
                if (lastField != field) {
                    maybeSolveCurrentField(); // a newer capture arrived mid-solve; solve it now
                    return;
                }
                lastSolveMs = solveMs;
                lastSolution = finalSol;
                if (finalSol != null) {
                    liveSolveHint = finalSol; // warm start for the next live solve
                }
                statusText.setText("");
                if (finalSol != null) {
                    detectionView.setSolve(finalSol, solveEngine.catalog(), field.skyMask,
                            field.analysisWidth, field.analysisHeight);
                }
                feedPolarIfActive(finalSol);
                showStats(field, finalSol, sourceLine);
                logDetection();
                autoPolarOnSolveFinished(finalSol);
            });
        });
        return true;
    }

    /** The per-frame source line for the stats panel: exposure + ISO for a live capture. */
    private String captureSourceLine(StarFieldCamera.CaptureInfo info) {
        return activity.getString(R.string.camera_stat_capture,
                formatExposure(info.exposureNanos), info.iso);
    }

    private void showStats(StarDetector.StarField field, PlateSolver.Solution sol,
                           String sourceLine) {
        StringBuilder sb = new StringBuilder();
        sb.append(activity.getString(R.string.camera_stats_title)).append('\n');
        sb.append(activity.getString(R.string.camera_stat_detections,
                field.stars.size(), field.rawCount)).append('\n');
        sb.append(activity.getString(R.string.camera_stat_noise,
                field.background, field.noise, field.threshold)).append('\n');
        sb.append(sourceLine).append('\n');
        if (sol != null) {
            double raHours = sol.centerRaDeg / 15.0;
            sb.append(activity.getString(R.string.camera_solve_center,
                    (int) raHours, (int) Math.round((raHours - (int) raHours) * 60),
                    sol.centerDecDeg)).append('\n');
            sb.append(activity.getString(R.string.camera_solve_field,
                    sol.fovWDeg, sol.fovHDeg, sol.rollDeg)).append('\n');
            sb.append(activity.getString(R.string.camera_solve_match,
                    sol.matchDet.length, sol.rmsPx, sol.fPix)).append('\n');
            sb.append(activity.getString(R.string.camera_solve_stars, brightMatchNames(sol)));
        } else if (solveInFlight) {
            sb.append(activity.getString(R.string.camera_solve_running));
        } else if (solverLoading || !solveEngine.isReady()) {
            sb.append(activity.getString(R.string.camera_solve_loading));
        } else {
            sb.append(activity.getString(R.string.camera_solve_failed));
        }
        statsText.setText(sb.toString());
    }

    /** Names of the brightest matched stars, to identify the field at a glance. */
    private String brightMatchNames(PlateSolver.Solution sol) {
        SkyCatalog cat = solveEngine.catalog();
        if (cat == null) {
            return "";
        }
        Integer[] order = new Integer[sol.matchStar.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = sol.matchStar[i];
        }
        java.util.Arrays.sort(order, (a, b) ->
                Double.compare(cat.stars.get(a).magnitude, cat.stars.get(b).magnitude));
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (int gi : order) {
            String nm = cat.stars.get(gi).name;
            if (nm == null || nm.isEmpty()) {
                continue;
            }
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(nm);
            if (++shown >= 5) {
                break;
            }
        }
        return names.toString();
    }

    private void setCapturing(boolean value) {
        capturing = value;
        captureButton.setEnabled(!value);
        pickButton.setEnabled(!value); // also gate gallery import while busy
    }

    private void updateManualControlsEnabled() {
        boolean manual = manualToggle.isChecked() && manualToggle.isEnabled();
        exposureSeek.setEnabled(manual);
        isoSeek.setEnabled(manual);
        float alpha = manual ? 1.0f : 0.5f;
        exposureSeek.setAlpha(alpha);
        isoSeek.setAlpha(alpha);
        exposureLabel.setAlpha(alpha);
        isoLabel.setAlpha(alpha);
    }

    private void updateFocusControlsEnabled() {
        boolean manualFocus = !autoFocusToggle.isChecked() && autoFocusToggle.isEnabled();
        focusSeek.setEnabled(manualFocus);
        float alpha = manualFocus ? 1.0f : 0.5f;
        focusSeek.setAlpha(alpha);
        focusLabel.setAlpha(alpha);
    }

    private void updateExposureLabel() {
        exposureLabel.setText(activity.getString(R.string.camera_exposure_label, formatExposure(selectedExposureNanos)));
    }

    private void updateIsoLabel() {
        isoLabel.setText(activity.getString(R.string.camera_iso_label, selectedIso));
    }

    private void updateFocusLabel() {
        String distance;
        if (selectedFocusDiopters <= 0.0001f) {
            distance = activity.getString(R.string.camera_focus_infinity_short);
        } else {
            distance = String.format(Locale.US, "%.2fm", 1.0f / selectedFocusDiopters);
        }
        focusLabel.setText(activity.getString(R.string.camera_focus_label, distance));
    }

    private long exposureFromProgress(int progress) {
        double frac = progress / (double) EXPOSURE_STEPS;
        double ratio = (double) exposureMaxNanos / exposureMinNanos;
        return (long) (exposureMinNanos * Math.pow(ratio, frac));
    }

    private int progressFromExposure(long nanos) {
        double ratio = (double) exposureMaxNanos / exposureMinNanos;
        if (ratio <= 1.0) {
            return 0;
        }
        double frac = Math.log((double) nanos / exposureMinNanos) / Math.log(ratio);
        frac = Math.max(0.0, Math.min(1.0, frac));
        return (int) Math.round(frac * EXPOSURE_STEPS);
    }

    private static String formatExposure(long nanos) {
        double seconds = nanos / 1.0e9;
        if (seconds >= 1.0) {
            return String.format(Locale.US, "%.1fs", seconds);
        }
        double ms = nanos / 1.0e6;
        if (ms >= 1.0) {
            return String.format(Locale.US, "%.0fms", ms);
        }
        return String.format(Locale.US, "%.0fus", nanos / 1.0e3);
    }

    private TextView label() {
        TextView text = new TextView(activity);
        text.setTextColor(Color.rgb(203, 213, 225));
        text.setTextSize(13);
        text.setPadding(0, dp(4), 0, dp(2));
        return text;
    }

    /** Hide the view while its text is empty, so the merged info box has no blank gaps. */
    private static void collapseWhenEmpty(TextView text) {
        text.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                text.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
    }

    // Same look as MainActivity#compactButton/createActionButtonBackground (day theme),
    // so camera-page buttons match the rest of the app.
    private Button actionButton(int textRes) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(textRes);
        button.setTextSize(15);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextColor(Color.rgb(226, 232, 240));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.rgb(30, 41, 59));
        bg.setStroke(dp(1), Color.rgb(51, 65, 85));
        bg.setCornerRadius(dp(12));
        button.setBackground(bg);
        return button;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        private final Runnable onChange;

        SimpleSeekListener(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            onChange.run();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
