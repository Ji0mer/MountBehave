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
 * Displays a captured sky photo fit-centred, with a marker circle drawn over every detected
 * star. Detections are stored in source-image coordinates and mapped to the on-screen
 * letterboxed rectangle at draw time, so the same result survives view resizes.
 */
final class StarDetectionView extends View {

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap image;
    private List<StarDetector.Detection> detections = new ArrayList<>();
    private int sourceWidth;
    private int sourceHeight;

    StarDetectionView(Context context) {
        super(context);
        init();
    }

    StarDetectionView(Context context, AttributeSet attrs) {
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
    }

    /** Set the captured image (source coordinates are this bitmap's pixel grid). */
    void setImage(Bitmap image, int sourceWidth, int sourceHeight) {
        this.image = image;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        invalidate();
    }

    void setDetections(List<StarDetector.Detection> detections) {
        this.detections = detections == null ? new ArrayList<>() : detections;
        invalidate();
    }

    void clear() {
        image = null;
        detections = new ArrayList<>();
        invalidate();
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

        canvas.drawBitmap(
                image,
                new android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()),
                new android.graphics.RectF(offsetX, offsetY, offsetX + drawW, offsetY + drawH),
                imagePaint);

        float radius = dp(7);
        for (StarDetector.Detection d : detections) {
            float cx = offsetX + d.x * scale;
            float cy = offsetY + d.y * scale;
            canvas.drawCircle(cx, cy, radius, markerPaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
