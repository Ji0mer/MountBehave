package com.example.onstepcontroller;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase-1 camera plate-solving test bench, hosted as a page inside MainActivity (not a
 * separate Activity) so the shell-level side menu and floating Stop button stay available
 * while the camera page is shown. Owns its own Camera2 wrapper, star detector and detection
 * overlay; MainActivity drives it via {@link #onShown()}/{@link #onHidden()} on page changes
 * and {@link #onResume()}/{@link #onPause()} on the activity lifecycle.
 *
 * <p>No coordinate maths and no mount commands here; this only measures whether a real
 * phone + tripod yields a usable star field, which drives the future index design.
 */
final class CameraPanel implements StarFieldCamera.Listener {

    private static final long DEFAULT_EXPOSURE_NANOS = 1_000_000_000L; // 1 s
    private static final int DEFAULT_ISO = 800;
    private static final int EXPOSURE_STEPS = 1000;
    // Allow long exposures up to the device limit; many phones cap well under this.
    private static final long EXPOSURE_CEILING_NANOS = 8_000_000_000L; // 8 s

    private final Activity activity;
    private final int cameraPermissionRequest;
    private final StarFieldCamera camera;
    private final StarDetector detector = new StarDetector();
    private final ExecutorService detectExecutor = Executors.newSingleThreadExecutor();
    private final View root;

    private TextureView previewView;
    private StarDetectionView detectionView;
    private CheckBox manualToggle;
    private CheckBox autoFocusToggle;
    private SeekBar exposureSeek;
    private SeekBar isoSeek;
    private SeekBar focusSeek;
    private TextView exposureLabel;
    private TextView isoLabel;
    private TextView focusLabel;
    private Button captureButton;
    private Button saveButton;
    private TextView statusText;
    private TextView statsText;

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
    private StarDetector.Result lastResult;

    CameraPanel(Activity activity, int cameraPermissionRequest) {
        this.activity = activity;
        this.cameraPermissionRequest = cameraPermissionRequest;
        this.camera = new StarFieldCamera(activity, this);
        this.root = buildView();
    }

    View view() {
        return root;
    }

    /** Called when the camera page becomes the selected page. */
    void onShown() {
        shown = true;
        maybeOpenCamera();
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
        lastBitmap = null;
        lastInfo = null;
        lastResult = null;
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
        detectionView = new StarDetectionView(activity);
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

        ScrollView statsScroll = new ScrollView(activity);
        statsText = label();
        statsText.setTextColor(Color.rgb(148, 200, 255));
        statsScroll.addView(statsText);
        container.addView(statsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));

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

    private void onSaveClicked() {
        if (lastBitmap == null || lastBitmap.isRecycled() || lastResult == null) {
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
        StarDetector.Result r = lastResult;
        StringBuilder sb = new StringBuilder("CAMERA-SOLVE phase1");
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
        if (r != null) {
            sb.append(" detections=").append(r.starCount())
                    .append(" rendered=").append(r.renderedStarCount())
                    .append(" background=").append(String.format(Locale.US, "%.2f", r.backgroundLevel))
                    .append(" noise=").append(String.format(Locale.US, "%.3f", r.noise))
                    .append(" threshold=").append(String.format(Locale.US, "%.2f", r.thresholdLevel))
                    .append(" avgBrightness=").append(String.format(Locale.US, "%.1f", r.averageBrightness))
                    .append(" maxBrightness=").append(String.format(Locale.US, "%.1f", r.maxBrightness))
                    .append(" avgSnr=").append(String.format(Locale.US, "%.2f", r.averageSnr))
                    .append(" edgeFraction=").append(String.format(Locale.US, "%.3f", r.edgeFraction()));
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
        lastResult = null;
        previewView.setVisibility(View.GONE);
        detectionView.setVisibility(View.VISIBLE);
        detectionView.setImage(image, info.imageWidth, info.imageHeight);
        detectionView.setDetections(null);
        statusText.setText(R.string.camera_analyzing);

        if (previous != null && previous != image && !previous.isRecycled()) {
            previous.recycle();
        }

        final int detGen = panelGeneration;
        detectExecutor.execute(() -> {
            StarDetector.Result result;
            try {
                result = detector.detect(image);
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
            final StarDetector.Result finalResult = result;
            activity.runOnUiThread(() -> {
                if (isPanelStale(detGen)) {
                    return; // panel hidden/destroyed during detection; drop the result
                }
                lastResult = finalResult;
                detectionView.setDetections(finalResult.stars);
                showStats(finalResult, info);
                setCapturing(false);
            });
        });
    }

    private void showStats(StarDetector.Result r, StarFieldCamera.CaptureInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(activity.getString(R.string.camera_stats_title)).append('\n');
        sb.append(activity.getString(R.string.camera_stat_detections, r.starCount(), r.renderedStarCount())).append('\n');
        sb.append(activity.getString(R.string.camera_stat_brightness, r.averageBrightness, r.maxBrightness)).append('\n');
        sb.append(activity.getString(R.string.camera_stat_noise, r.backgroundLevel, r.noise, r.thresholdLevel)).append('\n');
        sb.append(activity.getString(R.string.camera_stat_snr, r.averageSnr)).append('\n');
        sb.append(activity.getString(R.string.camera_stat_edge, r.edgeFraction() * 100.0)).append('\n');
        sb.append(activity.getString(R.string.camera_stat_capture, formatExposure(info.exposureNanos), info.iso)).append('\n');
        if (info.fovDegrees > 0.0) {
            sb.append(activity.getString(R.string.camera_stat_fov, info.fovDegrees, info.focalLengthMm));
        } else {
            sb.append(activity.getString(R.string.camera_stat_fov_unknown));
        }
        statsText.setText(sb.toString());
    }

    private void setCapturing(boolean value) {
        capturing = value;
        captureButton.setEnabled(!value);
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
