package com.example.onstepcontroller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays a captured sky photo fit-centred. Before a solve it draws a marker over every
 * detected star; after a plate solve it overlays the recovered sky: constellation lines,
 * catalog stars (bright ones labelled), the confirmed matched stars, the field centre and
 * celestial north, and dims the foreground that the detector masked out. All sky geometry
 * is projected through the {@link PlateSolver.Solution} camera model, so the wide field is
 * handled correctly. Everything is stored in source-image coordinates and mapped to the
 * on-screen letterboxed rectangle at draw time, so it survives view resizes.
 */
final class SolveOverlayView extends View {

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint matchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint polarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint polarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap image;
    private List<StarDetector.Detection> detections = new ArrayList<>();
    private int sourceWidth;
    private int sourceHeight;

    private PlateSolver.Solution solution;
    private SkyCatalog catalog;
    private Bitmap foregroundDim; // semi-transparent overlay marking masked (non-sky) pixels

    // Polar-alignment correction overlay: the mount axis (target circle), the visible
    // celestial pole (cross), an arrow from pole to axis and the error-angle label. The
    // user closes the gap by turning the alt/az bolts. NaN axis RA = no correction shown.
    private double polarAxisRaDeg = Double.NaN;
    private double polarAxisDecDeg;
    private double polarPoleDecDeg;
    private String polarLabel;

    SolveOverlayView(Context context) {
        super(context);
        init();
    }

    SolveOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(dp(1.5f));
        markerPaint.setColor(Color.rgb(96, 230, 120));
        placeholderPaint.setColor(Color.rgb(120, 132, 150));
        placeholderPaint.setTextSize(dp(14));
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1.2f));
        linePaint.setColor(Color.argb(200, 80, 170, 255));
        starPaint.setStyle(Paint.Style.STROKE);
        starPaint.setStrokeWidth(dp(1.0f));
        starPaint.setColor(Color.argb(230, 255, 255, 255));
        matchPaint.setStyle(Paint.Style.STROKE);
        matchPaint.setStrokeWidth(dp(2.0f));
        matchPaint.setColor(Color.rgb(96, 230, 120));
        labelPaint.setColor(Color.rgb(255, 240, 150));
        labelPaint.setTextSize(dp(11));
        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setStrokeWidth(dp(1.8f));
        centerPaint.setColor(Color.rgb(255, 90, 90));
        polarPaint.setStyle(Paint.Style.STROKE);
        polarPaint.setStrokeWidth(dp(2.2f));
        polarPaint.setColor(Color.rgb(255, 190, 80));
        polarTextPaint.setColor(Color.rgb(255, 210, 140));
        polarTextPaint.setTextSize(dp(13));
        polarTextPaint.setFakeBoldText(true);
    }

    /** Set the captured image (source coordinates are this bitmap's pixel grid). */
    void setImage(Bitmap image, int sourceWidth, int sourceHeight) {
        this.image = image;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        // Everything derived from the previous frame's solve is invalid on a new frame;
        // clear it so a failed solve cannot leave the old sky lines / polar arrow overlaid
        // on the new image. The caller re-sets them when this frame's solve succeeds.
        solution = null;
        catalog = null;
        polarAxisRaDeg = Double.NaN;
        polarLabel = null;
        recycleDim();
        invalidate();
    }

    void setDetections(List<StarDetector.Detection> detections) {
        this.detections = detections == null ? new ArrayList<>() : detections;
        invalidate();
    }

    /**
     * Show a plate-solve overlay. {@code skyMask} is the detector's analysis-scale sky mask
     * (true = sky); the masked foreground is dimmed. Pass null solution to clear the overlay.
     */
    void setSolve(PlateSolver.Solution solution, SkyCatalog catalog,
                  boolean[] skyMask, int maskW, int maskH) {
        this.solution = solution;
        this.catalog = catalog;
        recycleDim();
        if (skyMask != null && maskW > 0 && maskH > 0 && skyMask.length == maskW * maskH) {
            int[] px = new int[skyMask.length];
            for (int i = 0; i < px.length; i++) {
                px[i] = skyMask[i] ? 0 : 0x96000000; // dim non-sky (~59% black)
            }
            foregroundDim = Bitmap.createBitmap(px, maskW, maskH, Bitmap.Config.ARGB_8888);
        }
        invalidate();
    }

    /**
     * Show the polar-alignment correction on top of the solve overlay: the mount axis at
     * {@code axisRaDeg}/{@code axisDecDeg} (target circle), the visible pole at
     * {@code poleDecDeg} (cross), an arrow between them and the {@code label} error angle.
     */
    void setPolarCorrection(double axisRaDeg, double axisDecDeg, double poleDecDeg, String label) {
        this.polarAxisRaDeg = axisRaDeg;
        this.polarAxisDecDeg = axisDecDeg;
        this.polarPoleDecDeg = poleDecDeg;
        this.polarLabel = label;
        invalidate();
    }

    void clearPolarCorrection() {
        polarAxisRaDeg = Double.NaN;
        polarLabel = null;
        invalidate();
    }

    void clear() {
        image = null;
        detections = new ArrayList<>();
        solution = null;
        catalog = null;
        polarAxisRaDeg = Double.NaN;
        polarLabel = null;
        recycleDim();
        invalidate();
    }

    private void recycleDim() {
        if (foregroundDim != null && !foregroundDim.isRecycled()) {
            foregroundDim.recycle();
        }
        foregroundDim = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int viewW = getWidth();
        int viewH = getHeight();
        if (image == null || image.isRecycled() || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        // Fit-centre the source into the view, preserving aspect ratio (letterbox).
        float scale = Math.min((float) viewW / sourceWidth, (float) viewH / sourceHeight);
        float drawW = sourceWidth * scale;
        float drawH = sourceHeight * scale;
        float offsetX = (viewW - drawW) * 0.5f;
        float offsetY = (viewH - drawH) * 0.5f;

        android.graphics.RectF dest =
                new android.graphics.RectF(offsetX, offsetY, offsetX + drawW, offsetY + drawH);
        canvas.drawBitmap(
                image,
                new android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()),
                dest, imagePaint);

        if (solution != null) {
            drawSolveOverlay(canvas, offsetX, offsetY, scale, dest);
            return;
        }

        float radius = dp(7);
        for (StarDetector.Detection d : detections) {
            float cx = offsetX + d.x * scale;
            float cy = offsetY + d.y * scale;
            canvas.drawCircle(cx, cy, radius, markerPaint);
        }
    }

    private void drawSolveOverlay(Canvas canvas, float offsetX, float offsetY, float scale,
                                  android.graphics.RectF dest) {
        if (foregroundDim != null && !foregroundDim.isRecycled()) {
            canvas.drawBitmap(foregroundDim,
                    new android.graphics.Rect(0, 0, foregroundDim.getWidth(), foregroundDim.getHeight()),
                    dest, imagePaint);
        }
        // constellation lines
        if (catalog != null) {
            for (SkyCatalog.ConstellationLine line : catalog.constellationLines) {
                double[] a = solution.project(line.startRaHours * 15.0, line.startDecDegrees);
                double[] b = solution.project(line.endRaHours * 15.0, line.endDecDegrees);
                if (a[2] > 0 && b[2] > 0) {
                    canvas.drawLine(offsetX + (float) a[0] * scale, offsetY + (float) a[1] * scale,
                            offsetX + (float) b[0] * scale, offsetY + (float) b[1] * scale, linePaint);
                }
            }
            // catalog stars (in frame), brightest labelled
            float margin = 0.05f * Math.max(sourceWidth, sourceHeight);
            for (SkyCatalog.Star s : catalog.stars) {
                if (s.magnitude > 5.0) {
                    continue;
                }
                double[] p = solution.project(s.raHours * 15.0, s.decDegrees);
                if (p[2] <= 0) {
                    continue;
                }
                float sx = (float) p[0], sy = (float) p[1];
                if (sx < -margin || sx > sourceWidth + margin
                        || sy < -margin || sy > sourceHeight + margin) {
                    continue;
                }
                float cx = offsetX + sx * scale, cy = offsetY + sy * scale;
                float r = dp((float) Math.max(1.5, 4.5 - 0.7 * s.magnitude));
                canvas.drawCircle(cx, cy, r, starPaint);
                if (s.magnitude <= 3.0 && s.name != null && !s.name.isEmpty()) {
                    canvas.drawText(s.name, cx + r + dp(2), cy - r, labelPaint);
                }
            }
            // confirmed matched stars
            for (int gi : solution.matchStar) {
                if (gi < 0 || gi >= catalog.stars.size()) {
                    continue;
                }
                SkyCatalog.Star s = catalog.stars.get(gi);
                double[] p = solution.project(s.raHours * 15.0, s.decDegrees);
                if (p[2] <= 0) {
                    continue;
                }
                canvas.drawCircle(offsetX + (float) p[0] * scale, offsetY + (float) p[1] * scale,
                        dp(8), matchPaint);
            }
        }
        // field centre crosshair
        float ccx = offsetX + (float) solution.cx * scale;
        float ccy = offsetY + (float) solution.cy * scale;
        canvas.drawLine(ccx - dp(10), ccy, ccx + dp(10), ccy, centerPaint);
        canvas.drawLine(ccx, ccy - dp(10), ccx, ccy + dp(10), centerPaint);

        if (!Double.isNaN(polarAxisRaDeg)) {
            drawPolarCorrection(canvas, offsetX, offsetY, scale, dest);
        }
    }

    /**
     * NINA/SharpCap-style correction: the mount axis as a fixed target circle, the celestial
     * pole as a cross that moves with the star field, an arrow showing how the pole marker
     * must travel into the target, and the error angle at the arrow's midpoint. Clipped to
     * the photo rectangle so off-frame geometry degrades gracefully.
     */
    private void drawPolarCorrection(Canvas canvas, float offsetX, float offsetY, float scale,
                                     android.graphics.RectF dest) {
        double[] axis = solution.project(polarAxisRaDeg, polarAxisDecDeg);
        double[] pole = solution.project(0.0, polarPoleDecDeg);
        if (axis[2] <= 0 || pole[2] <= 0) {
            return; // behind the camera; the text readout still carries the numbers
        }
        float ax = offsetX + (float) axis[0] * scale;
        float ay = offsetY + (float) axis[1] * scale;
        float px = offsetX + (float) pole[0] * scale;
        float py = offsetY + (float) pole[1] * scale;
        canvas.save();
        canvas.clipRect(dest);

        // Mount axis: double target circle.
        canvas.drawCircle(ax, ay, dp(12), polarPaint);
        canvas.drawCircle(ax, ay, dp(3), polarPaint);
        canvas.drawText(getContext().getString(R.string.camera_polar_axis_label),
                ax + dp(14), ay - dp(6), polarTextPaint);

        // Celestial pole: cross.
        canvas.drawLine(px - dp(9), py, px + dp(9), py, polarPaint);
        canvas.drawLine(px, py - dp(9), px, py + dp(9), polarPaint);
        canvas.drawText(getContext().getString(R.string.camera_polar_pole_label),
                px + dp(11), py + dp(14), polarTextPaint);

        // Arrow pole -> axis (the direction the pole marker must move). Trims scale down
        // with the gap so the arrow stays visible even for small errors; once the markers
        // essentially coincide the arrow is dropped (aligned).
        float dx = ax - px, dy = ay - py;
        float len = (float) Math.hypot(dx, dy);
        float labelX;
        float labelY;
        if (len > dp(6)) {
            float ux = dx / len, uy = dy / len;
            float trimStart = Math.min(dp(12), len * 0.30f);
            float trimEnd = Math.min(dp(15), len * 0.35f);
            float sx = px + ux * trimStart, sy = py + uy * trimStart;
            float ex = ax - ux * trimEnd, ey = ay - uy * trimEnd;
            canvas.drawLine(sx, sy, ex, ey, polarPaint);
            float head = Math.min(dp(7), len * 0.25f);
            double ang = Math.atan2(ey - sy, ex - sx);
            canvas.drawLine(ex, ey,
                    ex - head * (float) Math.cos(ang - 0.5), ey - head * (float) Math.sin(ang - 0.5),
                    polarPaint);
            canvas.drawLine(ex, ey,
                    ex - head * (float) Math.cos(ang + 0.5), ey - head * (float) Math.sin(ang + 0.5),
                    polarPaint);
            // Error label beside the arrow midpoint, offset perpendicular to it so it does
            // not collide with the axis/pole labels along the line.
            labelX = (ax + px) * 0.5f - uy * dp(16);
            labelY = (ay + py) * 0.5f + ux * dp(16);
        } else {
            labelX = ax + dp(16);
            labelY = ay + dp(24);
        }
        if (polarLabel != null && !polarLabel.isEmpty()) {
            canvas.drawText(polarLabel, labelX, labelY, polarTextPaint);
        }
        canvas.restore();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
