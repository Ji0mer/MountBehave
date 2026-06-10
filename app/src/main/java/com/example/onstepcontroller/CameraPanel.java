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
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
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

    private static final long DEFAULT_EXPOSURE_NANOS = 1_000_000_000L; // 1 s
    private static final int DEFAULT_ISO = 800;
    private static final int EXPOSURE_STEPS = 1000;
    // Allow long exposures up to the device limit; many phones cap well under this.
    private static final long EXPOSURE_CEILING_NANOS = 8_000_000_000L; // 8 s

    // Bound the decoded bitmap for imported photos, matching the camera capture path; star
    // detection downscales again internally, so this only caps memory.
    private static final int IMPORT_DECODE_LONG_EDGE = 2400;
    // 35mm-equivalent focal length -> horizontal FOV uses the full-frame 36mm sensor width.
    private static final double FULL_FRAME_WIDTH_MM = 36.0;
    // Manual-retry horizontal-FOV presets (deg) when an imported photo fails to solve. The
    // automatic import already scans ~24..100 deg, so these reach a bit beyond that range
    // (ultrawide / telephoto) where the grid did not look; manual entry covers anything else.
    private static final double[] FOV_PRESETS_DEG = {110, 65, 22};

    private final Activity activity;
    private final int cameraPermissionRequest;
    private final int pickImageRequest;
    private final ObserverProvider observerProvider;
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
    private Button polarResetButton;
    private TextView polarText;

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

    // The solve engine (catalog + solver) is heavy (≈119k stars, ≈164k seed triangles), so it
    // is built once on a background thread the first time the page is shown and reused.
    private boolean solverLoading;
    private boolean solveInFlight; // a solve task for the current frame is running
    // Bumped each time a solve is launched and on every reset. A solve callback only owns the
    // shared solve state if its captured token still matches, so a stale solve that finishes
    // after a hide/return cannot clear a newer solve's solveInFlight flag.
    private int solveGeneration;

    CameraPanel(Activity activity, int cameraPermissionRequest, int pickImageRequest,
                ObserverProvider observerProvider) {
        this.activity = activity;
        this.cameraPermissionRequest = cameraPermissionRequest;
        this.pickImageRequest = pickImageRequest;
        this.observerProvider = observerProvider;
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
                activity.runOnUiThread(() -> solverLoading = false);
            }
        });
    }

    /** Called when another page is selected. */
    void onHidden() {
        shown = false;
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

        LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        captureButton = new Button(activity);
        captureButton.setAllCaps(false);
        captureButton.setText(R.string.camera_capture);
        captureButton.setOnClickListener(v -> onCaptureClicked());
        buttonRow.addView(captureButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pickButton = new Button(activity);
        pickButton.setAllCaps(false);
        pickButton.setText(R.string.camera_pick);
        pickButton.setOnClickListener(v -> onPickImageClicked());
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pickParams.leftMargin = dp(8);
        buttonRow.addView(pickButton, pickParams);
        saveButton = new Button(activity);
        saveButton.setAllCaps(false);
        saveButton.setText(R.string.camera_save);
        saveButton.setOnClickListener(v -> onSaveClicked());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.leftMargin = dp(8);
        buttonRow.addView(saveButton, saveParams);
        container.addView(buttonRow, wrap());

        statusText = label();
        statusText.setText(R.string.camera_solve_hint);
        container.addView(statusText, wrap());

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

        ScrollView statsScroll = new ScrollView(activity);
        statsText = label();
        statsText.setTextColor(Color.rgb(148, 200, 255));
        statsScroll.addView(statsText);
        container.addView(statsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));

        // Polar alignment: rigidly mount the phone, enable, then shoot/import while rotating the
        // RA axis between shots. Each solved shot is accumulated; the mount's polar axis and its
        // error are derived from the relative rotations.
        LinearLayout polarRow = new LinearLayout(activity);
        polarRow.setOrientation(LinearLayout.HORIZONTAL);
        polarToggle = new CheckBox(activity);
        polarToggle.setText(R.string.camera_polar_toggle);
        polarToggle.setTextColor(Color.rgb(226, 232, 240));
        polarToggle.setOnCheckedChangeListener((b, checked) -> onPolarToggled(checked));
        polarRow.addView(polarToggle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        polarResetButton = new Button(activity);
        polarResetButton.setAllCaps(false);
        polarResetButton.setText(R.string.camera_polar_reset);
        polarResetButton.setOnClickListener(v -> resetPolar());
        polarResetButton.setVisibility(View.GONE);
        polarRow.addView(polarResetButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(polarRow, wrap());
        polarText = label();
        polarText.setTextColor(Color.rgb(255, 210, 140));
        polarText.setVisibility(View.GONE);
        container.addView(polarText, wrap());

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
        if (capturing) {
            return;
        }
        camera.capture(manualToggle.isChecked(), selectedExposureNanos, selectedIso,
                autoFocusToggle.isChecked(), selectedFocusDiopters);
    }

    private void onPickImageClicked() {
        if (capturing) {
            return; // busy detecting/solving a previous frame
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
        polarResetButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        polarText.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) {
            polarAlignment.clear();
            updatePolarReadout();
        }
    }

    private void resetPolar() {
        polarAlignment.clear();
        updatePolarReadout();
    }

    /** Feed a successful solve into the polar-alignment accumulator while that mode is on. */
    private void feedPolarIfActive(PlateSolver.Solution sol) {
        if (sol == null || polarToggle == null || !polarToggle.isChecked()) {
            return;
        }
        polarAlignment.addShot(sol.r);
        updatePolarReadout();
    }

    private void updatePolarReadout() {
        if (polarText == null) {
            return;
        }
        int n = polarAlignment.shotCount();
        if (n < 2) {
            polarText.setText(activity.getString(R.string.camera_polar_progress, n));
            return;
        }
        ObserverState obs = observerProvider != null ? observerProvider.current() : null;
        double lat = obs != null ? obs.latitudeDegrees : ObserverState.BOSTON_LATITUDE;
        double lon = obs != null ? obs.longitudeDegrees : ObserverState.BOSTON_LONGITUDE;
        PolarAlignment.Result r = polarAlignment.compute(lat, lon, System.currentTimeMillis());
        if (r == null) {
            polarText.setText(activity.getString(R.string.camera_polar_need_rotation, n));
            return;
        }
        polarText.setText(formatPolar(r));
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
        if (field == null || solveInFlight || !solveEngine.isReady() || field.stars.size() < 3) {
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
        logDetection(location);
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

    private void logDetection(String location) {
        StarFieldCamera.CaptureInfo info = lastInfo;
        StarDetector.StarField f = lastField;
        PlateSolver.Solution sol = lastSolution;
        StringBuilder sb = new StringBuilder("CAMERA-SOLVE phase2");
        sb.append(" file=").append(location);
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
                });
                return;
            }
            final StarDetector.StarField finalField = field;
            activity.runOnUiThread(() -> {
                if (isPanelStale(detGen)) {
                    return; // panel hidden/destroyed during detection; drop the result
                }
                lastField = finalField;
                lastSolution = null;
                detectionView.setDetections(finalField.stars);
                setCapturing(false);
                if (!maybeSolveCurrentField()) {
                    showStats(finalField, null, captureSourceLine(info)); // solver not ready / too few stars
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
        detectExecutor.execute(() -> {
            PlateSolver.Solution sol;
            try {
                sol = solveEngine.solve(field, input);
            } catch (Throwable t) {
                Logger.error("plate solve failed", t);
                sol = null;
            }
            final PlateSolver.Solution finalSol = sol;
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
                lastSolution = finalSol;
                statusText.setText("");
                if (finalSol != null) {
                    detectionView.setSolve(finalSol, solveEngine.catalog(), field.skyMask,
                            field.analysisWidth, field.analysisHeight);
                }
                feedPolarIfActive(finalSol);
                showStats(field, finalSol, sourceLine);
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
