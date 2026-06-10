package com.example.onstepcontroller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin Camera2 wrapper for phase 1 of the camera plate-solving feature: open the back
 * camera, run a preview for framing, and take a single still with optional manual exposure
 * time / ISO / focus-at-infinity. It also reports lens metadata (focal length, sensor size,
 * nominal FOV) that later phases and the distortion assessment need.
 *
 * <p>All public methods are called from the UI thread; all camera callbacks run on an
 * internal background {@link HandlerThread} and are posted back to the UI thread before
 * reaching {@link Listener}. The class owns no UI; the host supplies a preview
 * {@link SurfaceTexture}.
 */
final class StarFieldCamera {

    interface Listener {
        void onReady(Capabilities capabilities);
        void onError(String message);
        void onCaptureStarted();
        void onCaptureComplete(Bitmap image, CaptureInfo info);
    }

    /** Static camera capabilities, read once from {@link CameraCharacteristics}. */
    static final class Capabilities {
        final boolean manualSensor;
        final boolean manualFocus;
        final long minExposureNanos;
        final long maxExposureNanos;
        final int minIso;
        final int maxIso;
        final float focalLengthMm;        // 0 if unknown
        final float sensorWidthMm;        // 0 if unknown
        final float sensorHeightMm;       // 0 if unknown
        final double horizontalFovDegrees; // 0 if unknown
        // Closest focus, in diopters (1/m). 0 means fixed-focus. The manual focus slider
        // runs 0 (infinity) .. minFocusDiopters (nearest).
        final float minFocusDiopters;

        Capabilities(boolean manualSensor, boolean manualFocus, long minExposureNanos,
                     long maxExposureNanos, int minIso, int maxIso, float focalLengthMm,
                     float sensorWidthMm, float sensorHeightMm, double horizontalFovDegrees,
                     float minFocusDiopters) {
            this.manualSensor = manualSensor;
            this.manualFocus = manualFocus;
            this.minExposureNanos = minExposureNanos;
            this.maxExposureNanos = maxExposureNanos;
            this.minIso = minIso;
            this.maxIso = maxIso;
            this.focalLengthMm = focalLengthMm;
            this.sensorWidthMm = sensorWidthMm;
            this.sensorHeightMm = sensorHeightMm;
            this.horizontalFovDegrees = horizontalFovDegrees;
            this.minFocusDiopters = minFocusDiopters;
        }
    }

    /** Per-capture metadata reported alongside the resulting bitmap. */
    static final class CaptureInfo {
        final long exposureNanos;
        final int iso;
        final float focalLengthMm;
        final double fovDegrees; // nominal horizontal FOV, 0 if unknown
        final int imageWidth;
        final int imageHeight;
        final boolean manualUsed;
        /**
         * True when exposureNanos/iso came from the camera's TotalCaptureResult (the values
         * actually used by the sensor). False means they are the REQUESTED values, reported
         * as a fallback because the HAL did not return per-frame sensor metadata (e.g. on a
         * device without manual-sensor support, or in pure auto mode).
         */
        final boolean actualValuesUsed;

        CaptureInfo(long exposureNanos, int iso, float focalLengthMm, double fovDegrees,
                    int imageWidth, int imageHeight, boolean manualUsed, boolean actualValuesUsed) {
            this.exposureNanos = exposureNanos;
            this.iso = iso;
            this.focalLengthMm = focalLengthMm;
            this.fovDegrees = fovDegrees;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.manualUsed = manualUsed;
            this.actualValuesUsed = actualValuesUsed;
        }
    }

    // Cap the decoded bitmap so a 12 MP+ JPEG does not blow up memory; still high enough
    // to keep small stars resolved for the detector (which downscales again internally).
    private static final int MAX_DECODE_LONG_EDGE = 2400;

    private final Context context;
    private final Listener listener;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private CameraManager cameraManager;
    private String cameraId;
    private Capabilities capabilities;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size captureSize;
    private boolean opening;
    // Set true in close(); a HandlerThread join can time out while a long JPEG decode or a
    // queued Camera2 callback is still finishing, so every background callback checks this
    // and bails out instead of repopulating pending state or posting to a paused Activity.
    private volatile boolean closed;
    // Bumped by both open() and close(). Each capture/image callback captures the generation
    // it was created under and bails if it no longer matches: a single `closed` flag cannot
    // tell an old session's late callback apart from a new session after a fast close+reopen,
    // so the token prevents a stale capture from polluting the new session's pairing.
    private final AtomicInteger generation = new AtomicInteger();

    // Parameters of the capture currently in flight (echoed back in CaptureInfo).
    private long pendingExposureNanos;
    private int pendingIso;
    private boolean pendingManual;

    // Capture pairing state (backgroundHandler thread only, so no locking is needed).
    // Camera2 does NOT guarantee the ImageReader callback fires after the capture-result
    // callback, so we gather both halves and emit the CaptureInfo only once both arrive;
    // otherwise a JPEG that lands first would force the actual exposure/ISO to fall back to
    // the requested values.
    private Bitmap pendingBitmap;
    private boolean pendingBitmapReady;
    private boolean pendingResultReady;
    private boolean pendingResultHasValues;
    private long pendingResultExposureNanos;
    private int pendingResultIso;

    StarFieldCamera(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    Capabilities capabilities() {
        return capabilities;
    }

    /**
     * Open the back camera and start a preview onto {@code previewTexture}. Caller must have
     * already obtained CAMERA permission. Safe to call again after {@link #close()}.
     */
    void open(SurfaceTexture previewTexture, int previewWidth, int previewHeight) {
        if (opening || cameraDevice != null) {
            return;
        }
        opening = true;
        closed = false; // re-arm: this instance can be reopened after a previous close()
        final int gen = generation.incrementAndGet();
        startBackgroundThread();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = chooseBackCamera(cameraManager);
            if (cameraId == null) {
                opening = false;
                fail(gen, context.getString(R.string.camera_unavailable));
                return;
            }
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            capabilities = readCapabilities(characteristics);
            captureSize = chooseCaptureSize(characteristics);

            Size previewSize = choosePreviewSize(characteristics, previewWidth, previewHeight);
            previewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(previewTexture);

            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(
                    reader -> onImageAvailable(reader, gen), backgroundHandler);

            //noinspection MissingPermission  -- caller guarantees CAMERA permission
            cameraManager.openCamera(cameraId, createDeviceStateCallback(gen), backgroundHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException ex) {
            opening = false;
            fail(gen, context.getString(R.string.camera_open_failed, safe(ex)));
        }
    }

    /**
     * Take one still. If {@code manual} and the device supports it, applies exposure/ISO.
     * If {@code autoFocus} is false and the device supports manual focus, focuses at
     * {@code focusDiopters} (0 = infinity); otherwise uses continuous autofocus.
     *
     * @return false when the camera is not ready and NO callback will fire (so callers
     *         waiting on a result, like the auto polar sequence, can fail immediately);
     *         true when the capture was submitted (success or failure arrives via the
     *         listener callbacks).
     */
    boolean capture(boolean manual, long exposureNanos, int iso, boolean autoFocus, float focusDiopters) {
        CameraDevice device = cameraDevice;
        CameraCaptureSession captureSession = session;
        if (device == null || captureSession == null || imageReader == null) {
            return false;
        }
        pendingManual = manual && capabilities != null && capabilities.manualSensor;
        pendingExposureNanos = exposureNanos;
        pendingIso = iso;
        // Discard any half-paired leftovers from a previous capture (recycling a stranded
        // decoded bitmap) so a stale JPEG can never pair with this capture's metadata.
        clearPendingCapture();
        // Capture the session generation up front so every callback and UI post is gated to it.
        final int gen = generation.get();
        postCaptureStarted(gen);
        // Reads the actual sensor exposure/ISO the HAL used, gated to this session's generation.
        CameraCaptureSession.CaptureCallback metaCallback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                           TotalCaptureResult result) {
                if (isStale(gen)) {
                    return;
                }
                Long exposure = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME);
                Integer sensitivity = result.get(TotalCaptureResult.SENSOR_SENSITIVITY);
                if (exposure != null) {
                    pendingResultExposureNanos = exposure;
                }
                if (sensitivity != null) {
                    pendingResultIso = sensitivity;
                }
                pendingResultHasValues = exposure != null && sensitivity != null;
                pendingResultReady = true;
                tryFinishCapture(gen);
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                        CaptureFailure failure) {
                if (isStale(gen)) {
                    return;
                }
                clearPendingCapture();
                fail(gen, context.getString(R.string.camera_open_failed, "capture failed"));
            }
        };
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0);

            if (pendingManual) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposure(exposureNanos));
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, clampIso(iso));
                // Frame duration must be at least the exposure time for long exposures.
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, clampExposure(exposureNanos));
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }

            if (!autoFocus && capabilities != null && capabilities.manualFocus) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, clampFocus(focusDiopters));
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }

            captureSession.capture(builder.build(), metaCallback, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException ex) {
            fail(gen, context.getString(R.string.camera_open_failed, safe(ex)));
        }
        return true;
    }

    /** True if this generation has been superseded by a close()/open(), or the camera is closed. */
    private boolean isStale(int gen) {
        return closed || gen != generation.get();
    }

    /** Release the camera and background thread. Safe to call multiple times. */
    void close() {
        opening = false;
        closed = true; // gate every in-flight/queued callback before tearing resources down
        generation.incrementAndGet(); // invalidate callbacks still bound to this session
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (RuntimeException ignored) {
            // session may already be closed
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        stopBackgroundThread();
        // Background thread has joined, so camera callbacks can no longer touch the pairing
        // fields; releasing any stranded bitmap from this (UI) thread is now race-free.
        clearPendingCapture();
    }

    // --- Camera2 callbacks (background thread) ---

    private CameraDevice.StateCallback createDeviceStateCallback(int gen) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                if (isStale(gen)) {
                    // A newer open()/close() superseded this request; do not adopt the device.
                    device.close();
                    return;
                }
                cameraDevice = device;
                opening = false;
                createSession(gen);
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                device.close();
                if (isStale(gen)) {
                    return; // a newer cycle owns cameraDevice now; don't touch shared state
                }
                if (cameraDevice == device) {
                    cameraDevice = null;
                }
            }

            @Override
            public void onError(CameraDevice device, int error) {
                device.close();
                // Check generation BEFORE mutating shared open state, so a late error from an
                // old open cannot clear a newer open's opening flag or surface its error.
                if (isStale(gen)) {
                    return;
                }
                if (cameraDevice == device) {
                    cameraDevice = null;
                }
                opening = false;
                fail(gen, context.getString(R.string.camera_open_failed, "code " + error));
            }
        };
    }

    private void createSession(int gen) {
        CameraDevice device = cameraDevice;
        if (device == null || previewSurface == null || imageReader == null) {
            return;
        }
        try {
            List<Surface> outputs = Arrays.asList(previewSurface, imageReader.getSurface());
            device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configuredSession) {
                    if (isStale(gen) || cameraDevice == null) {
                        configuredSession.close();
                        return;
                    }
                    session = configuredSession;
                    startPreview();
                    postReady(gen);
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession configuredSession) {
                    if (isStale(gen)) {
                        return;
                    }
                    fail(gen, context.getString(R.string.camera_open_failed, "session"));
                }
            }, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException ex) {
            fail(gen, context.getString(R.string.camera_open_failed, safe(ex)));
        }
    }

    private void startPreview() {
        CameraDevice device = cameraDevice;
        CameraCaptureSession captureSession = session;
        if (device == null || captureSession == null || previewSurface == null) {
            return;
        }
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException ex) {
            // Preview failing is non-fatal for phase 1 (capture is what matters); log only.
            Logger.warn("camera preview failed: " + safe(ex));
        }
    }

    private void onImageAvailable(ImageReader reader, int gen) {
        if (isStale(gen)) {
            // Late callback from a closed/superseded session. The old ImageReader has already
            // been closed (and its buffers released), so do not touch it; just bail out.
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bitmap = decodeBounded(bytes);
            if (bitmap == null) {
                clearPendingCapture();
                fail(gen, context.getString(R.string.camera_open_failed, "decode"));
                return;
            }
            if (isStale(gen)) {
                // close()/reopen raced us during decode; drop this frame without touching
                // pending state (owned/cleared by close()'s clearPendingCapture).
                bitmap.recycle();
                return;
            }
            pendingBitmap = bitmap;
            pendingBitmapReady = true;
            tryFinishCapture(gen);
        } catch (RuntimeException ex) {
            clearPendingCapture();
            fail(gen, context.getString(R.string.camera_open_failed, safe(ex)));
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    /**
     * Emit the finished capture once BOTH the decoded bitmap and the capture result have
     * arrived (order is not guaranteed). The actual sensor exposure/ISO are used when the
     * HAL reported them; otherwise we fall back to the requested values and flag it.
     */
    private void tryFinishCapture(int gen) {
        if (!pendingBitmapReady || !pendingResultReady) {
            return;
        }
        Bitmap bitmap = pendingBitmap;
        pendingBitmap = null;
        pendingBitmapReady = false;
        pendingResultReady = false;
        if (bitmap == null) {
            return;
        }
        double fov = capabilities == null ? 0.0 : capabilities.horizontalFovDegrees;
        float focal = capabilities == null ? 0.0f : capabilities.focalLengthMm;
        long exposure = pendingResultHasValues ? pendingResultExposureNanos : pendingExposureNanos;
        int iso = pendingResultHasValues ? pendingResultIso : pendingIso;
        CaptureInfo info = new CaptureInfo(
                exposure, iso, focal, fov,
                bitmap.getWidth(), bitmap.getHeight(), pendingManual, pendingResultHasValues);
        postCaptureComplete(gen, bitmap, info);
    }

    /**
     * Drop any half-paired capture state. Recycles a decoded bitmap that was waiting for its
     * metadata (it was never handed to a listener, so recycling is safe); a bitmap already
     * emitted via {@link #tryFinishCapture()} has its field cleared there and is owned by the
     * listener, so it is never touched here.
     */
    private void clearPendingCapture() {
        if (pendingBitmap != null && !pendingBitmap.isRecycled()) {
            pendingBitmap.recycle();
        }
        pendingBitmap = null;
        pendingBitmapReady = false;
        pendingResultReady = false;
        pendingResultHasValues = false;
    }

    // --- Helpers ---

    private static String chooseBackCamera(CameraManager manager) throws CameraAccessException {
        String firstAny = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (firstAny == null) {
                firstAny = id;
            }
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return firstAny; // fall back to whatever exists
    }

    private static Capabilities readCapabilities(CameraCharacteristics c) {
        boolean manualSensor = false;
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) {
            for (int cap : caps) {
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                    manualSensor = true;
                    break;
                }
            }
        }

        long minExp = 1_000_000L;       // 1 ms fallback
        long maxExp = 1_000_000_000L;   // 1 s fallback
        Range<Long> expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (expRange != null) {
            minExp = expRange.getLower();
            maxExp = expRange.getUpper();
        }

        int minIso = 50;
        int maxIso = 1600;
        Range<Integer> isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange != null) {
            minIso = isoRange.getLower();
            maxIso = isoRange.getUpper();
        }

        float focalLengthMm = 0.0f;
        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focals != null && focals.length > 0) {
            focalLengthMm = focals[0];
        }

        float sensorWidthMm = 0.0f;
        float sensorHeightMm = 0.0f;
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (physical != null) {
            sensorWidthMm = physical.getWidth();
            sensorHeightMm = physical.getHeight();
        }

        double fov = 0.0;
        if (focalLengthMm > 0.0f && sensorWidthMm > 0.0f) {
            fov = Math.toDegrees(2.0 * Math.atan(sensorWidthMm / (2.0 * focalLengthMm)));
        }

        Float minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        boolean manualFocus = minFocusDistance != null && minFocusDistance > 0.0f;
        float minFocusDiopters = manualFocus ? minFocusDistance : 0.0f;

        return new Capabilities(manualSensor, manualFocus, minExp, maxExp, minIso, maxIso,
                focalLengthMm, sensorWidthMm, sensorHeightMm, fov, minFocusDiopters);
    }

    private static Size chooseCaptureSize(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(1920, 1080);
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return new Size(1920, 1080);
        }
        // Largest available: stars are tiny, so capture at full resolution; the decode step
        // bounds the bitmap, and the detector downsamples for analysis.
        Size best = sizes[0];
        long bestArea = (long) best.getWidth() * best.getHeight();
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area > bestArea) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    private static Size choosePreviewSize(CameraCharacteristics c, int targetW, int targetH) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(1280, 720);
        }
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        long target = (long) Math.max(1, targetW) * Math.max(1, targetH);
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            // Keep preview modest so it stays smooth; cap the long edge near 1920.
            if (Math.max(s.getWidth(), s.getHeight()) > 1920) {
                continue;
            }
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - target);
            if (diff < bestDiff) {
                best = s;
                bestDiff = diff;
            }
        }
        return best;
    }

    private static Bitmap decodeBounded(byte[] jpeg) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longEdge / sample > MAX_DECODE_LONG_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
    }

    private long clampExposure(long nanos) {
        if (capabilities == null) {
            return nanos;
        }
        return Math.max(capabilities.minExposureNanos, Math.min(capabilities.maxExposureNanos, nanos));
    }

    private int clampIso(int iso) {
        if (capabilities == null) {
            return iso;
        }
        return Math.max(capabilities.minIso, Math.min(capabilities.maxIso, iso));
    }

    private float clampFocus(float diopters) {
        if (capabilities == null) {
            return Math.max(0.0f, diopters);
        }
        return Math.max(0.0f, Math.min(capabilities.minFocusDiopters, diopters));
    }

    private void startBackgroundThread() {
        if (backgroundThread != null) {
            return;
        }
        backgroundThread = new HandlerThread("star-camera");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread == null) {
            return;
        }
        backgroundThread.quitSafely();
        try {
            backgroundThread.join(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        backgroundThread = null;
        backgroundHandler = null;
    }

    // All four take the originating generation and re-check isStale right before delivering,
    // because close()+reopen can happen between a callback passing its own isStale check and
    // the posted runnable actually running on the UI thread.

    private void postReady(int gen) {
        Capabilities caps = capabilities;
        uiHandler.post(() -> {
            if (isStale(gen)) {
                return;
            }
            listener.onReady(caps);
        });
    }

    private void postCaptureStarted(int gen) {
        uiHandler.post(() -> {
            if (isStale(gen)) {
                return;
            }
            listener.onCaptureStarted();
        });
    }

    private void postCaptureComplete(int gen, Bitmap bitmap, CaptureInfo info) {
        uiHandler.post(() -> {
            if (isStale(gen)) {
                // Superseded before delivery: release the bitmap we still own.
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                return;
            }
            listener.onCaptureComplete(bitmap, info);
        });
    }

    private void fail(int gen, String message) {
        if (isStale(gen)) {
            return;
        }
        uiHandler.post(() -> {
            if (isStale(gen)) {
                return;
            }
            listener.onError(message);
        });
    }

    private static String safe(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }
}
