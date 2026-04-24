package com.example.onstepcontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SkyChartView extends View {
    private static final double MIN_FIELD_OF_VIEW_DEGREES = 28.0;
    private static final double MAX_FIELD_OF_VIEW_DEGREES = 135.0;
    private static final double DEFAULT_FIELD_OF_VIEW_DEGREES = 96.0;
    private static final double DEFAULT_VIEW_AZIMUTH_DEGREES = 90.0;
    private static final double DEFAULT_VIEW_ALTITUDE_DEGREES = 32.0;
    private static final double DEFAULT_MOUNT_AZIMUTH_DEGREES = 90.0;
    private static final double DEFAULT_MOUNT_ALTITUDE_DEGREES = 0.0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SkyCatalog catalog;
    private String loadError;

    private ObserverState observer = ObserverState.boston();
    private Instant currentInstant = Instant.now();
    private double viewAzimuthDegrees = DEFAULT_VIEW_AZIMUTH_DEGREES;
    private double viewAltitudeDegrees = DEFAULT_VIEW_ALTITUDE_DEGREES;
    private double fieldOfViewDegrees = DEFAULT_FIELD_OF_VIEW_DEGREES;
    private boolean hasMountEquatorial;
    private double mountRaHours;
    private double mountDecDegrees;
    private Target selectedTarget;
    private TargetSelectionListener targetSelectionListener;
    private Runnable viewStateListener;
    private float lastTouchX;
    private float lastTouchY;
    private float downX;
    private float downY;
    private float lastPinchDistance;
    private boolean touchMoved;
    private boolean pinching;
    private boolean suppressTapAfterPinch;

    public SkyChartView(Context context) {
        super(context);
        init(context);
    }

    public SkyChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    void setObserver(ObserverState observer, Instant instant) {
        this.observer = observer;
        this.currentInstant = instant;
        invalidate();
    }

    void setCurrentInstant(Instant instant) {
        this.currentInstant = instant;
        invalidate();
    }

    void setTargetSelectionListener(TargetSelectionListener listener) {
        targetSelectionListener = listener;
    }

    void setViewStateListener(Runnable listener) {
        viewStateListener = listener;
    }

    void setSelectedTarget(Target target, boolean centerView) {
        selectedTarget = target;
        if (centerView) {
            centerOnTarget(target);
        }
        invalidate();
        notifyViewStateChanged();
    }

    void setMountEquatorial(double raHours, double decDegrees) {
        hasMountEquatorial = true;
        mountRaHours = normalizeHours(raHours);
        mountDecDegrees = clamp(decDegrees, -90.0, 90.0);
        invalidate();
    }

    void clearMountEquatorial() {
        hasMountEquatorial = false;
        invalidate();
    }

    void resetView() {
        viewAzimuthDegrees = DEFAULT_VIEW_AZIMUTH_DEGREES;
        viewAltitudeDegrees = DEFAULT_VIEW_ALTITUDE_DEGREES;
        fieldOfViewDegrees = DEFAULT_FIELD_OF_VIEW_DEGREES;
        invalidate();
        notifyViewStateChanged();
    }

    Target findTarget(String query) {
        if (catalog == null) {
            return null;
        }
        String normalized = normalizeTargetName(query);
        if (normalized.isEmpty()) {
            return null;
        }
        for (SkyCatalog.DeepSkyObject object : catalog.deepSkyObjects) {
            if (normalizeTargetName(object.name).equals(normalized)
                    || normalizeTargetName(object.label()).equals(normalized)
                    || normalizeTargetName(object.commonName).equals(normalized)
                    || aliasMatches(object.aliases, normalized, false)) {
                return Target.deepSkyObject(object.label(), object.raHours, object.decDegrees);
            }
        }
        for (SkyCatalog.Star star : catalog.stars) {
            if (normalizeTargetName(star.name).equals(normalized)) {
                return Target.star(star.name, star.raHours, star.decDegrees);
            }
        }
        for (SkyCatalog.DeepSkyObject object : catalog.deepSkyObjects) {
            if (normalizeTargetName(object.label()).contains(normalized)
                    || normalizeTargetName(object.commonName).contains(normalized)
                    || aliasMatches(object.aliases, normalized, true)) {
                return Target.deepSkyObject(object.label(), object.raHours, object.decDegrees);
            }
        }
        for (SkyCatalog.Star star : catalog.stars) {
            if (normalizeTargetName(star.name).contains(normalized)) {
                return Target.star(star.name, star.raHours, star.decDegrees);
            }
        }
        return null;
    }

    List<Target> suggestedAlignmentTargets(int maxTargets) {
        List<Target> result = new ArrayList<>();
        if (catalog == null || maxTargets <= 0) {
            return result;
        }
        addSuggestedAlignmentTargets(result, maxTargets, 20.0, 3.0);
        if (result.isEmpty()) {
            addSuggestedAlignmentTargets(result, maxTargets, 10.0, 4.0);
        }
        return result;
    }

    String summary() {
        if (catalog == null) {
            return loadError == null ? "星图数据加载中" : loadError;
        }
        return String.format(
                Locale.US,
                "恒星 %d 颗，深空天体 %d 个，星座线 %d 段。%s 本地天空，视场 %.0f°，星等上限 %.1f。",
                catalog.stars.size(),
                catalog.deepSkyObjects.size(),
                catalog.constellationLines.size(),
                observer.locationName,
                fieldOfViewDegrees,
                visibleStarMagnitudeLimit()
        );
    }

    private void init(Context context) {
        setMinimumHeight(dp(460));
        try {
            catalog = SkyCatalog.load(context);
        } catch (IOException ex) {
            loadError = "星图数据加载失败：" + ex.getMessage();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);
        if (catalog == null) {
            drawCenteredMessage(canvas, summary());
            return;
        }

        CameraBasis basis = cameraBasis();
        drawGround(canvas, basis);
        drawSkyGrid(canvas, basis);
        drawConstellationLines(canvas, basis);
        drawStars(canvas, basis);
        drawDeepSkyObjects(canvas, basis);
        drawSelectedTarget(canvas, basis);
        drawMountPointing(canvas, basis);
        drawViewReadout(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(event.getActionMasked() != MotionEvent.ACTION_UP
                    && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
        }

        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            pinching = false;
            suppressTapAfterPinch = true;
            lastTouchX = event.getX(0);
            lastTouchY = event.getY(0);
            return true;
        }

        if (event.getPointerCount() >= 2) {
            handlePinch(event);
            return true;
        }

        pinching = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                downX = lastTouchX;
                downY = lastTouchY;
                touchMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                if (distance(event.getX(), event.getY(), downX, downY) > dp(8)) {
                    touchMoved = true;
                }
                panView(dx, dy);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                invalidate();
                notifyViewStateChanged();
                return true;
            case MotionEvent.ACTION_UP:
                if (!touchMoved && !suppressTapAfterPinch) {
                    selectNearestTarget(event.getX(), event.getY());
                }
                suppressTapAfterPinch = false;
                return true;
            default:
                return true;
        }
    }

    private void panView(float dx, float dy) {
        double horizontalScale = fieldOfViewDegrees / Math.max(1, getWidth());
        double verticalScale = fieldOfViewDegrees / Math.max(1, getHeight());
        viewAzimuthDegrees = normalizeDegrees(viewAzimuthDegrees - dx * horizontalScale * 1.18);
        viewAltitudeDegrees = clamp(viewAltitudeDegrees + dy * verticalScale * 1.18, -12.0, 88.0);
    }

    private void handlePinch(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (!pinching || lastPinchDistance <= 0f) {
            pinching = true;
            suppressTapAfterPinch = true;
            lastPinchDistance = distance;
            return;
        }
        if (distance > 8f) {
            fieldOfViewDegrees = clamp(
                    fieldOfViewDegrees * lastPinchDistance / distance,
                    MIN_FIELD_OF_VIEW_DEGREES,
                    MAX_FIELD_OF_VIEW_DEGREES
            );
            lastPinchDistance = distance;
            invalidate();
            notifyViewStateChanged();
        }
    }

    private void notifyViewStateChanged() {
        if (viewStateListener != null) {
            viewStateListener.run();
        }
    }

    private void drawBackground(Canvas canvas) {
        canvas.drawColor(Color.rgb(5, 10, 21));
    }

    private void drawGround(Canvas canvas, CameraBasis basis) {
        if (Math.abs(basis.up.z) < 1.0e-6) {
            return;
        }
        double scale = getHeight() * 0.5 / Math.tan(Math.toRadians(fieldOfViewDegrees) * 0.5);
        float horizonY = (float) (getHeight() * 0.5 + basis.forward.z * scale / basis.up.z);
        if (horizonY >= getHeight()) {
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(20, 42, 32));
        canvas.drawRect(0, Math.max(0f, horizonY), getWidth(), getHeight(), paint);

        paint.setColor(Color.argb(95, 63, 98, 65));
        float bandTop = (float) clamp(horizonY, 0.0, getHeight());
        canvas.drawRect(0, bandTop, getWidth(), Math.min(getHeight(), bandTop + dp(18)), paint);
    }

    private void drawSkyGrid(Canvas canvas, CameraBasis basis) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(88, 148, 163, 184));
        for (int altitude = 15; altitude <= 75; altitude += 15) {
            drawAltitudeLine(canvas, basis, altitude);
        }

        paint.setColor(Color.argb(70, 148, 163, 184));
        for (int azimuth = 0; azimuth < 360; azimuth += 30) {
            drawAzimuthLine(canvas, basis, azimuth);
        }

        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.argb(190, 100, 116, 139));
        drawAltitudeLine(canvas, basis, 0);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(13));
        paint.setColor(Color.rgb(226, 232, 240));
        paint.setFakeBoldText(true);
        drawCardinal(canvas, basis, "N", 0);
        drawCardinal(canvas, basis, "E", 90);
        drawCardinal(canvas, basis, "S", 180);
        drawCardinal(canvas, basis, "W", 270);
        paint.setFakeBoldText(false);

        paint.setTextSize(dp(11));
        paint.setColor(Color.argb(190, 203, 213, 225));
        drawAltitudeLabel(canvas, basis, 30);
        drawAltitudeLabel(canvas, basis, 60);
        ScreenPoint zenith = projectVector(vectorFromHorizontal(0, 90), basis);
        if (zenith != null && isVisible(zenith.x, zenith.y, dp(40))) {
            canvas.drawText("天顶", zenith.x + dp(7), zenith.y - dp(7), paint);
        }
    }

    private void drawConstellationLines(Canvas canvas, CameraBasis basis) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(118, 96, 165, 250));
        double localSiderealDegrees = localSiderealDegrees();
        for (SkyCatalog.ConstellationLine line : catalog.constellationLines) {
            HorizontalPosition start = toHorizontal(line.startRaHours, line.startDecDegrees, localSiderealDegrees);
            HorizontalPosition end = toHorizontal(line.endRaHours, line.endDecDegrees, localSiderealDegrees);
            if (start.altitudeDegrees < -4.0 && end.altitudeDegrees < -4.0) {
                continue;
            }
            ScreenPoint startPoint = project(start, basis);
            ScreenPoint endPoint = project(end, basis);
            if (startPoint == null || endPoint == null) {
                continue;
            }
            if (!isVisible(startPoint.x, startPoint.y, dp(40)) && !isVisible(endPoint.x, endPoint.y, dp(40))) {
                continue;
            }
            if (distance(startPoint, endPoint) > Math.max(getWidth(), getHeight()) * 0.58f) {
                continue;
            }
            canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, paint);
        }
    }

    private void drawAltitudeLine(Canvas canvas, CameraBasis basis, double altitudeDegrees) {
        Path path = new Path();
        ScreenPoint previous = null;
        boolean active = false;
        for (int azimuth = 0; azimuth <= 360; azimuth += 2) {
            ScreenPoint point = projectVector(vectorFromHorizontal(azimuth, altitudeDegrees), basis);
            if (!usableLinePoint(point)) {
                active = false;
                previous = null;
                continue;
            }
            if (!active || previous == null || distance(previous, point) > Math.max(getWidth(), getHeight()) * 0.42f) {
                path.moveTo(point.x, point.y);
                active = true;
            } else {
                path.lineTo(point.x, point.y);
            }
            previous = point;
        }
        canvas.drawPath(path, paint);
    }

    private void drawAzimuthLine(Canvas canvas, CameraBasis basis, double azimuthDegrees) {
        Path path = new Path();
        ScreenPoint previous = null;
        boolean active = false;
        for (int altitude = 0; altitude <= 90; altitude += 2) {
            ScreenPoint point = projectVector(vectorFromHorizontal(azimuthDegrees, altitude), basis);
            if (!usableLinePoint(point)) {
                active = false;
                previous = null;
                continue;
            }
            if (!active || previous == null || distance(previous, point) > Math.max(getWidth(), getHeight()) * 0.42f) {
                path.moveTo(point.x, point.y);
                active = true;
            } else {
                path.lineTo(point.x, point.y);
            }
            previous = point;
        }
        canvas.drawPath(path, paint);
    }

    private void drawCardinal(Canvas canvas, CameraBasis basis, String label, double azimuthDegrees) {
        ScreenPoint point = projectVector(vectorFromHorizontal(azimuthDegrees, 0), basis);
        if (point != null && isVisible(point.x, point.y, dp(36))) {
            canvas.drawText(label, point.x - dp(4), point.y - dp(8), paint);
        }
    }

    private void drawAltitudeLabel(Canvas canvas, CameraBasis basis, double altitudeDegrees) {
        ScreenPoint point = projectVector(vectorFromHorizontal(viewAzimuthDegrees, altitudeDegrees), basis);
        if (point != null && isVisible(point.x, point.y, dp(32))) {
            canvas.drawText(String.format(Locale.US, "%.0f°", altitudeDegrees), point.x + dp(7), point.y, paint);
        }
    }

    private void drawStars(Canvas canvas, CameraBasis basis) {
        paint.setStyle(Paint.Style.FILL);
        double localSiderealDegrees = localSiderealDegrees();
        double magnitudeLimit = visibleStarMagnitudeLimit();
        for (SkyCatalog.Star star : catalog.stars) {
            if (star.magnitude > magnitudeLimit) {
                continue;
            }
            HorizontalPosition position = toHorizontal(star.raHours, star.decDegrees, localSiderealDegrees);
            if (position.altitudeDegrees < 0.0) {
                continue;
            }
            ScreenPoint point = project(position, basis);
            if (point == null || !isVisible(point.x, point.y, 8)) {
                continue;
            }

            float radius = (float) clamp(4.8 - star.magnitude * 0.52, 0.8, 4.6);
            int shade = (int) clamp(255 - star.magnitude * 12, 152, 255);
            paint.setColor(Color.rgb(shade, shade, 255));
            canvas.drawCircle(point.x, point.y, radius, paint);

            if (shouldLabelStar(star.magnitude, magnitudeLimit)) {
                paint.setTextSize(dp(10));
                paint.setColor(Color.argb(215, 226, 232, 240));
                canvas.drawText(star.name, point.x + dp(5), point.y - dp(5), paint);
            }
        }
    }

    private void drawDeepSkyObjects(Canvas canvas, CameraBasis basis) {
        double localSiderealDegrees = localSiderealDegrees();
        for (SkyCatalog.DeepSkyObject object : catalog.deepSkyObjects) {
            if (!shouldDrawDeepSkyObject(object)) {
                continue;
            }
            HorizontalPosition position = toHorizontal(object.raHours, object.decDegrees, localSiderealDegrees);
            if (position.altitudeDegrees < 0.0) {
                continue;
            }
            ScreenPoint point = project(position, basis);
            if (point == null || !isVisible(point.x, point.y, 12)) {
                continue;
            }

            float size = object.magnitude == object.magnitude ? (float) clamp(10.5 - object.magnitude * 0.35, 4.5, 9.0) : dp(6);
            drawDeepSkyIcon(canvas, object.type, point.x, point.y, size);

            if (shouldLabelDeepSkyObject(object, position.altitudeDegrees, point)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(dp(10));
                paint.setColor(Color.argb(220, 125, 211, 252));
                canvas.drawText(object.label(), point.x + dp(6), point.y + dp(12), paint);
            }
        }
    }

    private void drawDeepSkyIcon(Canvas canvas, String type, float x, float y, float size) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        if (isCluster(type)) {
            paint.setColor(Color.rgb(251, 191, 36));
            canvas.drawCircle(x, y, size, paint);
            canvas.drawLine(x - size * 0.55f, y, x + size * 0.55f, y, paint);
            canvas.drawLine(x, y - size * 0.55f, x, y + size * 0.55f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, Math.max(1.4f, size * 0.18f), paint);
        } else if (isNebula(type)) {
            paint.setColor(Color.rgb(52, 211, 153));
            Path path = new Path();
            path.moveTo(x, y - size);
            path.lineTo(x + size, y);
            path.lineTo(x, y + size);
            path.lineTo(x - size, y);
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawCircle(x, y, size * 0.42f, paint);
        } else {
            paint.setColor(Color.rgb(56, 189, 248));
            canvas.save();
            canvas.rotate(-24f, x, y);
            canvas.drawOval(x - size * 1.25f, y - size * 0.55f, x + size * 1.25f, y + size * 0.55f, paint);
            canvas.drawLine(x - size * 0.95f, y, x + size * 0.95f, y, paint);
            canvas.restore();
        }
    }

    private void drawSelectedTarget(Canvas canvas, CameraBasis basis) {
        if (selectedTarget == null) {
            return;
        }
        ScreenPoint point = projectTarget(selectedTarget, basis);
        if (point == null || !isVisible(point.x, point.y, dp(36))) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.rgb(244, 114, 182));
        float radius = dp(14);
        canvas.drawCircle(point.x, point.y, radius, paint);
        canvas.drawLine(point.x - radius - dp(3), point.y, point.x - radius + dp(5), point.y, paint);
        canvas.drawLine(point.x + radius - dp(5), point.y, point.x + radius + dp(3), point.y, paint);
        canvas.drawLine(point.x, point.y - radius - dp(3), point.x, point.y - radius + dp(5), paint);
        canvas.drawLine(point.x, point.y + radius - dp(5), point.x, point.y + radius + dp(3), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(10));
        paint.setColor(Color.rgb(251, 207, 232));
        canvas.drawText(selectedTarget.label, point.x + dp(16), point.y - dp(10), paint);
    }

    private void drawMountPointing(Canvas canvas, CameraBasis basis) {
        HorizontalPosition position = currentMountHorizontalPosition();
        Vector3 vector = vectorFromHorizontal(position.azimuthDegrees, position.altitudeDegrees);
        ScreenPoint point = projectVector(vector, basis);
        boolean inView = point != null && isVisible(point.x, point.y, dp(20));
        if (!inView) {
            point = projectVectorToEdge(vector, basis);
        }
        if (point == null) {
            return;
        }
        point = clampToChart(point, dp(20));

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(inView ? Color.rgb(248, 113, 113) : Color.argb(210, 248, 113, 113));
        float r = dp(12);
        canvas.drawCircle(point.x, point.y, r, paint);
        canvas.drawLine(point.x - r - dp(4), point.y, point.x + r + dp(4), point.y, paint);
        canvas.drawLine(point.x, point.y - r - dp(4), point.x, point.y + r + dp(4), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(10));
        paint.setColor(Color.rgb(254, 202, 202));
        canvas.drawText(hasMountEquatorial ? "赤道仪" : "赤道仪 E", point.x + dp(14), point.y - dp(10), paint);
    }

    private HorizontalPosition currentMountHorizontalPosition() {
        if (!hasMountEquatorial) {
            return new HorizontalPosition(DEFAULT_MOUNT_ALTITUDE_DEGREES, DEFAULT_MOUNT_AZIMUTH_DEGREES);
        }
        return toHorizontal(mountRaHours, mountDecDegrees, localSiderealDegrees());
    }

    private ScreenPoint projectTarget(Target target, CameraBasis basis) {
        HorizontalPosition position = toHorizontal(target.raHours, target.decDegrees, localSiderealDegrees());
        if (position.altitudeDegrees < 0.0) {
            return null;
        }
        return project(position, basis);
    }

    private void selectNearestTarget(float x, float y) {
        if (catalog == null) {
            return;
        }
        CameraBasis basis = cameraBasis();
        double localSiderealDegrees = localSiderealDegrees();
        Target nearest = null;
        double nearestDistance = dp(34);

        for (SkyCatalog.DeepSkyObject object : catalog.deepSkyObjects) {
            if (!shouldDrawDeepSkyObject(object)) {
                continue;
            }
            HorizontalPosition position = toHorizontal(object.raHours, object.decDegrees, localSiderealDegrees);
            if (position.altitudeDegrees < 0.0) {
                continue;
            }
            ScreenPoint point = project(position, basis);
            if (point == null || !isVisible(point.x, point.y, dp(28))) {
                continue;
            }
            double targetDistance = distance(point.x, point.y, x, y);
            if (targetDistance < nearestDistance) {
                nearestDistance = targetDistance;
                nearest = Target.deepSkyObject(object.label(), object.raHours, object.decDegrees);
            }
        }

        double starDistanceLimit = nearest == null ? dp(26) : Math.min(nearestDistance, dp(18));
        for (SkyCatalog.Star star : catalog.stars) {
            if (star.magnitude > Math.min(visibleStarMagnitudeLimit(), 4.8)) {
                continue;
            }
            HorizontalPosition position = toHorizontal(star.raHours, star.decDegrees, localSiderealDegrees);
            if (position.altitudeDegrees < 0.0) {
                continue;
            }
            ScreenPoint point = project(position, basis);
            if (point == null || !isVisible(point.x, point.y, dp(20))) {
                continue;
            }
            double targetDistance = distance(point.x, point.y, x, y);
            if (targetDistance < starDistanceLimit) {
                starDistanceLimit = targetDistance;
                nearest = Target.star(star.name, star.raHours, star.decDegrees);
            }
        }

        if (nearest == null) {
            return;
        }
        selectedTarget = nearest;
        if (targetSelectionListener != null) {
            targetSelectionListener.onTargetSelected(nearest);
        }
        invalidate();
    }

    private void centerOnTarget(Target target) {
        HorizontalPosition position = toHorizontal(target.raHours, target.decDegrees, localSiderealDegrees());
        viewAzimuthDegrees = position.azimuthDegrees;
        viewAltitudeDegrees = clamp(position.altitudeDegrees, -12.0, 88.0);
    }

    private void addSuggestedAlignmentTargets(List<Target> result, int maxTargets, double minimumAltitude, double maximumMagnitude) {
        double siderealDegrees = localSiderealDegrees();
        for (SkyCatalog.Star star : catalog.stars) {
            if (result.size() >= maxTargets) {
                return;
            }
            if (star.name.startsWith("HYG ") || star.magnitude > maximumMagnitude || Math.abs(star.decDegrees) > 82.0) {
                continue;
            }
            HorizontalPosition position = toHorizontal(star.raHours, star.decDegrees, siderealDegrees);
            if (position.altitudeDegrees >= minimumAltitude) {
                result.add(Target.star(star.name, star.raHours, star.decDegrees));
            }
        }
    }

    private void drawViewReadout(Canvas canvas) {
        String readout = String.format(
                Locale.US,
                "%s  %.0f°",
                cardinalName(viewAzimuthDegrees),
                viewAltitudeDegrees
        );
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(180, 15, 23, 42));
        canvas.drawRoundRect(dp(8), dp(8), dp(92), dp(34), dp(4), dp(4), paint);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(226, 232, 240));
        canvas.drawText(readout, dp(15), dp(26), paint);
    }

    private double visibleStarMagnitudeLimit() {
        if (fieldOfViewDegrees >= 116.0) {
            return 2.9;
        }
        if (fieldOfViewDegrees >= 94.0) {
            return 3.6;
        }
        if (fieldOfViewDegrees >= 70.0) {
            return 4.8;
        }
        if (fieldOfViewDegrees >= 46.0) {
            return 6.0;
        }
        return 7.05;
    }

    private boolean shouldLabelStar(double magnitude, double magnitudeLimit) {
        if (fieldOfViewDegrees >= 90.0) {
            return magnitude <= 0.4;
        }
        if (fieldOfViewDegrees >= 58.0) {
            return magnitude <= 1.5;
        }
        return magnitude <= Math.min(2.8, magnitudeLimit - 1.7);
    }

    private boolean shouldDrawDeepSkyObject(SkyCatalog.DeepSkyObject object) {
        if (fieldOfViewDegrees >= 112.0) {
            return isShowpiece(object);
        }
        if (fieldOfViewDegrees >= 86.0) {
            return isShowpiece(object)
                    || object.name.startsWith("M") && object.magnitude == object.magnitude && object.magnitude <= 6.8;
        }
        if (fieldOfViewDegrees >= 58.0) {
            return isShowpiece(object)
                    || object.name.startsWith("M") && object.magnitude == object.magnitude && object.magnitude <= 9.5;
        }
        return true;
    }

    private boolean shouldLabelDeepSkyObject(SkyCatalog.DeepSkyObject object, double altitudeDegrees, ScreenPoint point) {
        if (altitudeDegrees < 8.0) {
            return false;
        }
        if (!isNearViewCenter(point, 0.34f)) {
            return false;
        }
        if (fieldOfViewDegrees <= 52.0) {
            return true;
        }
        return isShowpiece(object);
    }

    private boolean isShowpiece(SkyCatalog.DeepSkyObject object) {
        return object.name.equals("M31")
                || object.name.equals("M33")
                || object.name.equals("M42")
                || object.name.equals("M45")
                || object.name.equals("M8")
                || object.name.equals("M13")
                || object.name.equals("M27")
                || object.name.equals("M57");
    }

    private HorizontalPosition toHorizontal(double raHours, double decDegrees, double localSiderealDegrees) {
        double hourAngle = Math.toRadians(wrapDegrees(localSiderealDegrees - raHours * 15.0));
        double dec = Math.toRadians(decDegrees);
        double lat = Math.toRadians(observer.latitudeDegrees);

        double sinAlt = Math.sin(dec) * Math.sin(lat) + Math.cos(dec) * Math.cos(lat) * Math.cos(hourAngle);
        double altitude = Math.asin(clamp(sinAlt, -1.0, 1.0));
        double cosAlt = Math.max(1.0e-8, Math.cos(altitude));
        double cosLat = Math.cos(lat);
        double sinAz = -Math.cos(dec) * Math.sin(hourAngle) / cosAlt;
        double cosAz = Math.abs(cosLat) < 1.0e-8
                ? 1.0
                : (Math.sin(dec) - Math.sin(altitude) * Math.sin(lat)) / (cosAlt * cosLat);
        double azimuth = Math.toDegrees(Math.atan2(sinAz, cosAz));
        return new HorizontalPosition(Math.toDegrees(altitude), normalizeDegrees(azimuth));
    }

    private double localSiderealDegrees() {
        double jd = currentInstant.toEpochMilli() / 86_400_000.0 + 2_440_587.5;
        double d = jd - 2_451_545.0;
        double t = d / 36_525.0;
        double gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38_710_000.0;
        return normalizeDegrees(gmst + observer.longitudeDegrees);
    }

    private ScreenPoint project(HorizontalPosition position, CameraBasis basis) {
        return projectVector(vectorFromHorizontal(position.azimuthDegrees, position.altitudeDegrees), basis);
    }

    private ScreenPoint projectVector(Vector3 vector, CameraBasis basis) {
        double x = vector.dot(basis.right);
        double y = vector.dot(basis.up);
        double z = vector.dot(basis.forward);
        if (z <= 0.025) {
            return null;
        }

        double scale = getHeight() * 0.5 / Math.tan(Math.toRadians(fieldOfViewDegrees) * 0.5);
        float screenX = (float) (getWidth() * 0.5 + x * scale / z);
        float screenY = (float) (getHeight() * 0.5 - y * scale / z);
        return new ScreenPoint(screenX, screenY);
    }

    private ScreenPoint projectVectorToEdge(Vector3 vector, CameraBasis basis) {
        double x = vector.dot(basis.right);
        double y = vector.dot(basis.up);
        double z = Math.max(0.025, vector.dot(basis.forward));
        double scale = getHeight() * 0.5 / Math.tan(Math.toRadians(fieldOfViewDegrees) * 0.5);
        float projectedX = (float) (getWidth() * 0.5 + x * scale / z);
        float projectedY = (float) (getHeight() * 0.5 - y * scale / z);
        float margin = dp(16);
        float centerX = getWidth() * 0.5f;
        float centerY = getHeight() * 0.5f;
        float dx = projectedX - centerX;
        float dy = projectedY - centerY;
        if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) {
            return new ScreenPoint(centerX, centerY);
        }
        float scaleX = dx == 0f ? Float.MAX_VALUE : ((dx > 0 ? getWidth() - margin : margin) - centerX) / dx;
        float scaleY = dy == 0f ? Float.MAX_VALUE : ((dy > 0 ? getHeight() - margin : margin) - centerY) / dy;
        float edgeScale = Math.max(0f, Math.min(Math.abs(scaleX), Math.abs(scaleY)));
        return new ScreenPoint(centerX + dx * edgeScale, centerY + dy * edgeScale);
    }

    private CameraBasis cameraBasis() {
        Vector3 forward = vectorFromHorizontal(viewAzimuthDegrees, viewAltitudeDegrees).normalized();
        Vector3 right = vectorFromHorizontal(viewAzimuthDegrees + 90.0, 0.0).normalized();
        Vector3 up = right.cross(forward).normalized();
        return new CameraBasis(forward, right, up);
    }

    private Vector3 vectorFromHorizontal(double azimuthDegrees, double altitudeDegrees) {
        double azimuth = Math.toRadians(normalizeDegrees(azimuthDegrees));
        double altitude = Math.toRadians(altitudeDegrees);
        double cosAltitude = Math.cos(altitude);
        return new Vector3(
                cosAltitude * Math.sin(azimuth),
                cosAltitude * Math.cos(azimuth),
                Math.sin(altitude)
        );
    }

    private String cardinalName(double azimuthDegrees) {
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.floor((normalizeDegrees(azimuthDegrees) + 22.5) / 45.0) % names.length;
        return names[index];
    }

    private void drawCenteredMessage(Canvas canvas, String message) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(14));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(message, getWidth() / 2f, getHeight() / 2f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private int dsoColor(String type) {
        if (isCluster(type)) {
            return Color.rgb(251, 191, 36);
        }
        if (isNebula(type)) {
            return Color.rgb(52, 211, 153);
        }
        return Color.rgb(56, 189, 248);
    }

    private boolean isCluster(String type) {
        return type.contains("Cl") || type.contains("*Ass");
    }

    private boolean isNebula(String type) {
        return type.contains("Neb") || type.contains("HII") || type.contains("PN") || type.contains("SNR") || type.contains("DrkN");
    }

    private boolean usableLinePoint(ScreenPoint point) {
        if (point == null) {
            return false;
        }
        float margin = Math.max(getWidth(), getHeight()) * 1.4f;
        return point.x >= -margin && point.x <= getWidth() + margin && point.y >= -margin && point.y <= getHeight() + margin;
    }

    private boolean isVisible(float x, float y, float margin) {
        return x >= -margin && x <= getWidth() + margin && y >= -margin && y <= getHeight() + margin;
    }

    private boolean isNearViewCenter(ScreenPoint point, float fraction) {
        float dx = point.x - getWidth() * 0.5f;
        float dy = point.y - getHeight() * 0.5f;
        float radius = Math.min(getWidth(), getHeight()) * fraction;
        return dx * dx + dy * dy <= radius * radius;
    }

    private ScreenPoint clampToChart(ScreenPoint point, float margin) {
        return new ScreenPoint(
                (float) clamp(point.x, margin, getWidth() - margin),
                (float) clamp(point.y, margin, getHeight() - margin)
        );
    }

    private static float distance(ScreenPoint a, ScreenPoint b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float distance(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static double wrapDegrees(double degrees) {
        double result = normalizeDegrees(degrees);
        return result > 180.0 ? result - 360.0 : result;
    }

    private static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0 ? result + 360.0 : result;
    }

    private static double normalizeHours(double hours) {
        double result = hours % 24.0;
        return result < 0 ? result + 24.0 : result;
    }

    private static String normalizeTargetName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (normalized.matches("M0+[0-9]+")) {
            return "M" + Integer.parseInt(normalized.substring(1));
        }
        return normalized;
    }

    private static boolean aliasMatches(String aliases, String normalizedQuery, boolean allowContains) {
        if (aliases == null || aliases.isEmpty()) {
            return false;
        }
        for (String alias : aliases.split(",")) {
            String normalizedAlias = normalizeTargetName(alias);
            if (allowContains ? normalizedAlias.contains(normalizedQuery) : normalizedAlias.equals(normalizedQuery)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    interface TargetSelectionListener {
        void onTargetSelected(Target target);
    }

    static final class Target {
        final String label;
        final double raHours;
        final double decDegrees;
        final boolean deepSkyObject;

        private Target(String label, double raHours, double decDegrees, boolean deepSkyObject) {
            this.label = label;
            this.raHours = raHours;
            this.decDegrees = decDegrees;
            this.deepSkyObject = deepSkyObject;
        }

        static Target deepSkyObject(String label, double raHours, double decDegrees) {
            return new Target(label, raHours, decDegrees, true);
        }

        static Target star(String label, double raHours, double decDegrees) {
            return new Target(label, raHours, decDegrees, false);
        }

        static Target custom(String label, double raHours, double decDegrees) {
            return new Target(label, raHours, decDegrees, false);
        }
    }

    private static final class HorizontalPosition {
        final double altitudeDegrees;
        final double azimuthDegrees;

        HorizontalPosition(double altitudeDegrees, double azimuthDegrees) {
            this.altitudeDegrees = altitudeDegrees;
            this.azimuthDegrees = azimuthDegrees;
        }
    }

    private static final class CameraBasis {
        final Vector3 forward;
        final Vector3 right;
        final Vector3 up;

        CameraBasis(Vector3 forward, Vector3 right, Vector3 up) {
            this.forward = forward;
            this.right = right;
            this.up = up;
        }
    }

    private static final class Vector3 {
        final double x;
        final double y;
        final double z;

        Vector3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        double dot(Vector3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        Vector3 cross(Vector3 other) {
            return new Vector3(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x
            );
        }

        Vector3 normalized() {
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0e-10) {
                return new Vector3(0.0, 0.0, 1.0);
            }
            return new Vector3(x / length, y / length, z / length);
        }
    }

    private static final class ScreenPoint {
        final float x;
        final float y;

        ScreenPoint(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
