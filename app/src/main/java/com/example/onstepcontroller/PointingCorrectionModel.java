package com.example.onstepcontroller;

import java.util.ArrayList;
import java.util.List;

final class PointingCorrectionModel {
    static final int MAX_CORRECTION_POINTS = 30;
    static final double POST_SYNC_RESIDUAL_THRESHOLD_DEGREES = 0.25;
    static final double POLAR_SKIP_DEC_DEGREES = 80.0;

    private static final double SIGMA_DEGREES = 15.0;
    private static final long SESSION_HALF_LIFE_MS = 2L * 60L * 60L * 1000L;
    private static final double MIN_WEIGHT_THRESHOLD = 0.01;

    private final List<CorrectionPoint> points = new ArrayList<>();

    synchronized AddResult addPoint(
            String label,
            double catalogRaHours,
            double catalogDecDegrees,
            double postSyncMountRaHours,
            double postSyncMountDecDegrees,
            long timestampMs,
            boolean modelReady
    ) {
        double residualDistanceDegrees = angularDistanceDegrees(
                catalogRaHours,
                catalogDecDegrees,
                postSyncMountRaHours,
                postSyncMountDecDegrees
        );
        if (!modelReady) {
            return AddResult.notRecordedNoModel(residualDistanceDegrees, points.size());
        }
        if (Math.abs(catalogDecDegrees) >= POLAR_SKIP_DEC_DEGREES) {
            return AddResult.skippedPolar(residualDistanceDegrees, points.size());
        }
        if (residualDistanceDegrees <= POST_SYNC_RESIDUAL_THRESHOLD_DEGREES) {
            return AddResult.skippedSmallResidual(residualDistanceDegrees, points.size());
        }

        double residualRaDegrees = wrapDegrees((postSyncMountRaHours - catalogRaHours) * 15.0);
        double residualDecDegrees = clamp(postSyncMountDecDegrees - catalogDecDegrees, -45.0, 45.0);
        points.add(new CorrectionPoint(
                label == null ? "" : label,
                normalizeHours(catalogRaHours),
                clamp(catalogDecDegrees, -90.0, 90.0),
                residualRaDegrees,
                residualDecDegrees,
                timestampMs
        ));
        while (points.size() > MAX_CORRECTION_POINTS) {
            points.remove(0);
        }
        return AddResult.added(residualDistanceDegrees, points.size(), residualRaDegrees, residualDecDegrees);
    }

    synchronized Correction correctTarget(String label, double raHours, double decDegrees, long timestampMs) {
        if (points.isEmpty()) {
            return Correction.none(raHours, decDegrees);
        }

        double weightedRaDegrees = 0.0;
        double weightedDecDegrees = 0.0;
        double totalWeight = 0.0;
        for (CorrectionPoint point : points) {
            double distanceDegrees = angularDistanceDegrees(
                    raHours,
                    decDegrees,
                    point.catalogRaHours,
                    point.catalogDecDegrees
            );
            double ageUnits = Math.max(0.0, (timestampMs - point.timestampMs) / (double) SESSION_HALF_LIFE_MS);
            double distanceWeight = Math.exp(-(distanceDegrees * distanceDegrees) / (SIGMA_DEGREES * SIGMA_DEGREES));
            double ageWeight = Math.exp(-ageUnits);
            double weight = distanceWeight * ageWeight;
            weightedRaDegrees += point.residualRaDegrees * weight;
            weightedDecDegrees += point.residualDecDegrees * weight;
            totalWeight += weight;
        }

        if (totalWeight <= MIN_WEIGHT_THRESHOLD) {
            return Correction.none(raHours, decDegrees);
        }

        double correctionRaDegrees = weightedRaDegrees / totalWeight;
        double correctionDecDegrees = weightedDecDegrees / totalWeight;
        return new Correction(
                true,
                label == null ? "" : label,
                normalizeHours(raHours - correctionRaDegrees / 15.0),
                clamp(decDegrees - correctionDecDegrees, -90.0, 90.0),
                correctionRaDegrees,
                correctionDecDegrees,
                totalWeight,
                points.size()
        );
    }

    synchronized void clear() {
        points.clear();
    }

    synchronized int size() {
        return points.size();
    }

    private static double angularDistanceDegrees(
            double raHoursA,
            double decDegreesA,
            double raHoursB,
            double decDegreesB
    ) {
        double raA = Math.toRadians(raHoursA * 15.0);
        double decA = Math.toRadians(decDegreesA);
        double raB = Math.toRadians(raHoursB * 15.0);
        double decB = Math.toRadians(decDegreesB);
        double sinDDec = Math.sin((decB - decA) / 2.0);
        double sinDRa = Math.sin((raB - raA) / 2.0);
        double hav = sinDDec * sinDDec
                + Math.cos(decA) * Math.cos(decB) * sinDRa * sinDRa;
        double clamped = Math.min(1.0, Math.max(0.0, hav));
        return Math.toDegrees(2.0 * Math.asin(Math.sqrt(clamped)));
    }

    private static double normalizeHours(double hours) {
        double value = hours % 24.0;
        if (value < 0.0) {
            value += 24.0;
        }
        return value;
    }

    private static double wrapDegrees(double degrees) {
        double value = degrees % 360.0;
        if (value > 180.0) {
            value -= 360.0;
        } else if (value < -180.0) {
            value += 360.0;
        }
        return value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    enum AddStatus {
        ADDED,
        NOT_RECORDED_NO_MODEL,
        SKIPPED_SMALL_RESIDUAL,
        SKIPPED_POLAR
    }

    static final class AddResult {
        final AddStatus status;
        final double residualDistanceDegrees;
        final int pointCount;
        final double residualRaDegrees;
        final double residualDecDegrees;

        private AddResult(
                AddStatus status,
                double residualDistanceDegrees,
                int pointCount,
                double residualRaDegrees,
                double residualDecDegrees
        ) {
            this.status = status;
            this.residualDistanceDegrees = residualDistanceDegrees;
            this.pointCount = pointCount;
            this.residualRaDegrees = residualRaDegrees;
            this.residualDecDegrees = residualDecDegrees;
        }

        private static AddResult added(
                double residualDistanceDegrees,
                int pointCount,
                double residualRaDegrees,
                double residualDecDegrees
        ) {
            return new AddResult(
                    AddStatus.ADDED,
                    residualDistanceDegrees,
                    pointCount,
                    residualRaDegrees,
                    residualDecDegrees
            );
        }

        private static AddResult notRecordedNoModel(double residualDistanceDegrees, int pointCount) {
            return new AddResult(
                    AddStatus.NOT_RECORDED_NO_MODEL,
                    residualDistanceDegrees,
                    pointCount,
                    0.0,
                    0.0
            );
        }

        private static AddResult skippedSmallResidual(double residualDistanceDegrees, int pointCount) {
            return new AddResult(
                    AddStatus.SKIPPED_SMALL_RESIDUAL,
                    residualDistanceDegrees,
                    pointCount,
                    0.0,
                    0.0
            );
        }

        private static AddResult skippedPolar(double residualDistanceDegrees, int pointCount) {
            return new AddResult(
                    AddStatus.SKIPPED_POLAR,
                    residualDistanceDegrees,
                    pointCount,
                    0.0,
                    0.0
            );
        }
    }

    static final class Correction {
        final boolean applied;
        final String label;
        final double commandRaHours;
        final double commandDecDegrees;
        final double correctionRaDegrees;
        final double correctionDecDegrees;
        final double totalWeight;
        final int pointCount;

        private Correction(
                boolean applied,
                String label,
                double commandRaHours,
                double commandDecDegrees,
                double correctionRaDegrees,
                double correctionDecDegrees,
                double totalWeight,
                int pointCount
        ) {
            this.applied = applied;
            this.label = label;
            this.commandRaHours = commandRaHours;
            this.commandDecDegrees = commandDecDegrees;
            this.correctionRaDegrees = correctionRaDegrees;
            this.correctionDecDegrees = correctionDecDegrees;
            this.totalWeight = totalWeight;
            this.pointCount = pointCount;
        }

        private static Correction none(double raHours, double decDegrees) {
            return new Correction(
                    false,
                    "",
                    normalizeHours(raHours),
                    clamp(decDegrees, -90.0, 90.0),
                    0.0,
                    0.0,
                    0.0,
                    0
            );
        }
    }

    private static final class CorrectionPoint {
        final String label;
        final double catalogRaHours;
        final double catalogDecDegrees;
        final double residualRaDegrees;
        final double residualDecDegrees;
        final long timestampMs;

        CorrectionPoint(
                String label,
                double catalogRaHours,
                double catalogDecDegrees,
                double residualRaDegrees,
                double residualDecDegrees,
                long timestampMs
        ) {
            this.label = label;
            this.catalogRaHours = catalogRaHours;
            this.catalogDecDegrees = catalogDecDegrees;
            this.residualRaDegrees = residualRaDegrees;
            this.residualDecDegrees = residualDecDegrees;
            this.timestampMs = timestampMs;
        }
    }
}
