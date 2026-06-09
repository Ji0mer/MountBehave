package com.example.onstepcontroller;

import android.graphics.Bitmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Detects star-like point sources in a still photo of the night sky.
 *
 * <p>Pure Java / CPU, with no camera, UI or network dependency, so it can be fed an
 * arbitrary {@link Bitmap} and unit-tested in isolation. This is phase 1 of the camera
 * plate-solving feature: its output (how many stars a real phone + tripod can extract,
 * their signal-to-noise, and how they spread between the centre and the distortion-prone
 * edges) is what decides whether blind solving is viable and how deep the future quad
 * index needs to go. Nothing here touches coordinates or the mount; that is later phases.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Downscale the source so the long edge is at most {@code maxAnalysisLongEdge}
 *       (full-resolution phone photos are 12 MP+; star detection does not need that and
 *       the smaller buffer keeps memory and time bounded).</li>
 *   <li>Convert to a 0..255 perceptual grey value.</li>
 *   <li>Estimate the sky background as the histogram median and the noise as a robust
 *       MAD (median absolute deviation); both are insensitive to the handful of bright
 *       star pixels, unlike mean / standard deviation.</li>
 *   <li>Threshold at {@code background + thresholdSigma * noise}.</li>
 *   <li>Group supra-threshold pixels into 8-connected blobs with an explicit-stack flood
 *       fill (no recursion, so a large connected region can never overflow the call
 *       stack).</li>
 *   <li>For each blob compute a background-subtracted, flux-weighted sub-pixel centroid,
 *       discarding blobs that are too small (hot-pixel noise) or too large (Moon, a lamp,
 *       a trailed/defocused blob).</li>
 *   <li>Map centroids back to original pixel coordinates and sort by brightness.</li>
 * </ol>
 *
 * <p>Note on counts: frame statistics (detection count, edge fraction, brightness, SNR)
 * are computed over ALL accepted candidates, before the list is truncated to
 * {@code maxStars}. The truncated {@link Result#stars} list exists only to bound how many
 * markers the UI draws; it must not be used to judge how many sources the frame really
 * had. That distinction matters in phase 1, where the whole point is to learn whether a
 * real frame yields a useful number of stars or a flood of false detections.
 */
final class StarDetector {

    /** A single detected point source, in ORIGINAL (pre-downscale) bitmap pixel coordinates. */
    static final class Detection {
        /** Sub-pixel centroid X in source-image pixels. */
        final float x;
        /** Sub-pixel centroid Y in source-image pixels. */
        final float y;
        /** Background-subtracted integrated flux (sum of pixel value above background). */
        final double brightness;
        /** Background-subtracted peak pixel value (0..255 scale). */
        final double peak;
        /** Per-source signal-to-noise estimate: peak / noise. */
        final double snr;
        /** Number of pixels in the blob, at analysis scale. */
        final int pixelCount;

        Detection(float x, float y, double brightness, double peak, double snr, int pixelCount) {
            this.x = x;
            this.y = y;
            this.brightness = brightness;
            this.peak = peak;
            this.snr = snr;
            this.pixelCount = pixelCount;
        }
    }

    /** Detection result plus the frame-level statistics phase 1 needs to characterise a device. */
    static final class Result {
        /**
         * Detected sources for rendering, brightness descending, capped at {@code maxStars}.
         * This is a DISPLAY list only; use {@link #detectionCount} for "how many were found".
         */
        final List<Detection> stars;
        /** Total accepted candidates BEFORE truncation; the honest detection count. */
        final int detectionCount;
        /** Accepted candidates within the outer edge band, over all candidates (not just rendered). */
        final int edgeStarCount;
        /** Mean integrated flux over all accepted candidates. */
        final double averageBrightness;
        /** Maximum integrated flux over all accepted candidates. */
        final double maxBrightness;
        /** Mean per-source SNR over all accepted candidates. */
        final double averageSnr;
        /** Sky background level (histogram median, 0..255). */
        final double backgroundLevel;
        /** Robust noise estimate (1.4826 * MAD, 0..255). */
        final double noise;
        /** Threshold actually used (background + thresholdSigma * noise), pre-rounding. */
        final double thresholdLevel;
        /** Image dimensions at the scale the analysis ran. */
        final int analysisWidth;
        final int analysisHeight;
        /** Original source dimensions (centroids are reported in these coordinates). */
        final int sourceWidth;
        final int sourceHeight;

        Result(List<Detection> stars, int detectionCount, int edgeStarCount,
               double averageBrightness, double maxBrightness, double averageSnr,
               double backgroundLevel, double noise, double thresholdLevel,
               int analysisWidth, int analysisHeight, int sourceWidth, int sourceHeight) {
            this.stars = stars;
            this.detectionCount = detectionCount;
            this.edgeStarCount = edgeStarCount;
            this.averageBrightness = averageBrightness;
            this.maxBrightness = maxBrightness;
            this.averageSnr = averageSnr;
            this.backgroundLevel = backgroundLevel;
            this.noise = noise;
            this.thresholdLevel = thresholdLevel;
            this.analysisWidth = analysisWidth;
            this.analysisHeight = analysisHeight;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }

        /** Honest count of detected sources (NOT limited by the render cap). */
        int starCount() {
            return detectionCount;
        }

        /** Number of markers actually drawn (render list size, capped at maxStars). */
        int renderedStarCount() {
            return stars.size();
        }

        /** Fraction of detected stars sitting in the distortion-prone outer band (0..1). */
        double edgeFraction() {
            return detectionCount == 0 ? 0.0 : (double) edgeStarCount / detectionCount;
        }
    }

    private final int maxAnalysisLongEdge;
    private final double thresholdSigma;
    private final int minBlobPixels;
    private final int maxBlobPixels;
    private final int maxStars;
    private final double edgeMarginFraction;

    /** Defaults tuned for a wide-angle phone frame; all are overridable for experimentation. */
    StarDetector() {
        this(1600, 6.0, 2, 600, 200, 0.12);
    }

    StarDetector(int maxAnalysisLongEdge, double thresholdSigma, int minBlobPixels,
                 int maxBlobPixels, int maxStars, double edgeMarginFraction) {
        this.maxAnalysisLongEdge = Math.max(64, maxAnalysisLongEdge);
        this.thresholdSigma = thresholdSigma;
        this.minBlobPixels = Math.max(1, minBlobPixels);
        this.maxBlobPixels = Math.max(this.minBlobPixels, maxBlobPixels);
        this.maxStars = Math.max(1, maxStars);
        this.edgeMarginFraction = Math.max(0.0, Math.min(0.49, edgeMarginFraction));
    }

    /**
     * Detect point sources in {@code source}. Never mutates or recycles {@code source}.
     * Returns an empty-but-valid result for a null or degenerate bitmap.
     */
    Result detect(Bitmap source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return new Result(new ArrayList<>(), 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0);
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        int longEdge = Math.max(sourceWidth, sourceHeight);
        double scale = longEdge > maxAnalysisLongEdge
                ? (double) maxAnalysisLongEdge / longEdge
                : 1.0;
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));

        Bitmap scaled;
        boolean scaledIsCopy = false;
        if (width != sourceWidth || height != sourceHeight) {
            scaled = Bitmap.createScaledBitmap(source, width, height, true);
            scaledIsCopy = scaled != source;
        } else {
            scaled = source;
        }

        try {
            int[] pixels = new int[width * height];
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);

            // Perceptual grey (0..255). Star light is faint, but luma keeps a single channel's
            // hot pixels from masquerading as sources better than max(R,G,B) would.
            int[] grey = new int[width * height];
            int[] histogram = new int[256];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int luma = (r * 77 + g * 150 + b * 29) >> 8; // ~0.299/0.587/0.114
                if (luma > 255) {
                    luma = 255;
                }
                grey[i] = luma;
                histogram[luma]++;
            }

            int total = grey.length;
            double background = medianFromHistogram(histogram, total);
            double noise = robustNoiseFromHistogram(histogram, total, background);
            double threshold = background + thresholdSigma * noise;
            if (threshold < background + 1.0) {
                threshold = background + 1.0;
            }
            if (threshold > 254.0) {
                threshold = 254.0;
            }
            // floor (not ceil) so a pixel just above background+k*noise still counts: with
            // ceil the discrete test was one grey level stricter than the configured sigma,
            // which clips low-SNR phone stars unnecessarily.
            int thresholdInt = (int) Math.floor(threshold);

            List<Detection> candidates = extractBlobs(
                    grey, width, height, thresholdInt, background, noise, scale,
                    sourceWidth, sourceHeight);

            // Frame statistics over ALL candidates, computed before the render cap so the
            // numbers reflect what the frame really contains (the phase-1 question).
            int detectionCount = candidates.size();
            int edgeStarCount = countEdgeStars(candidates, sourceWidth, sourceHeight);
            double sumBrightness = 0.0;
            double maxBrightness = 0.0;
            double sumSnr = 0.0;
            for (Detection d : candidates) {
                sumBrightness += d.brightness;
                sumSnr += d.snr;
                if (d.brightness > maxBrightness) {
                    maxBrightness = d.brightness;
                }
            }
            double averageBrightness = detectionCount == 0 ? 0.0 : sumBrightness / detectionCount;
            double averageSnr = detectionCount == 0 ? 0.0 : sumSnr / detectionCount;

            candidates.sort(Comparator.comparingDouble((Detection d) -> d.brightness).reversed());
            List<Detection> rendered = candidates.size() > maxStars
                    ? new ArrayList<>(candidates.subList(0, maxStars))
                    : candidates;

            return new Result(
                    Collections.unmodifiableList(rendered),
                    detectionCount, edgeStarCount,
                    averageBrightness, maxBrightness, averageSnr,
                    background, noise, threshold,
                    width, height, sourceWidth, sourceHeight);
        } finally {
            if (scaledIsCopy && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    /** Median grey value via cumulative histogram. */
    private static double medianFromHistogram(int[] histogram, int total) {
        if (total <= 0) {
            return 0.0;
        }
        int target = total / 2;
        int cumulative = 0;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative > target) {
                return value;
            }
        }
        return histogram.length - 1;
    }

    /**
     * Robust noise = 1.4826 * MAD, where MAD is the median of |grey - background|.
     * Built from a deviation histogram so it stays O(n) without sorting all pixels.
     */
    private static double robustNoiseFromHistogram(int[] histogram, int total, double background) {
        if (total <= 0) {
            return 0.0;
        }
        int bg = (int) Math.round(background);
        int[] devHistogram = new int[256];
        for (int value = 0; value < histogram.length; value++) {
            int count = histogram[value];
            if (count == 0) {
                continue;
            }
            int dev = Math.abs(value - bg);
            if (dev > 255) {
                dev = 255;
            }
            devHistogram[dev] += count;
        }
        double mad = medianFromHistogram(devHistogram, total);
        double noise = 1.4826 * mad;
        // Floor at ~1 grey level: a perfectly flat (clipped) background would give MAD 0
        // and then nothing could ever clear the threshold.
        return Math.max(1.0, noise);
    }

    /**
     * 8-connected flood fill over supra-threshold pixels. Each blob yields a
     * background-subtracted flux-weighted centroid. Uses an explicit index stack so a
     * large connected region cannot blow the call stack. Returns ALL accepted candidates
     * (no render cap applied here).
     */
    private List<Detection> extractBlobs(int[] grey, int width, int height, int thresholdInt,
                                         double background, double noise, double scale,
                                         int sourceWidth, int sourceHeight) {
        List<Detection> stars = new ArrayList<>();
        boolean[] visited = new boolean[grey.length];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        // Map analysis-scale centroid back to source pixels. Using the dimension ratio
        // (rather than 1/scale) avoids drift from the integer rounding of width/height.
        double sxScale = (double) sourceWidth / width;
        double syScale = (double) sourceHeight / height;

        for (int start = 0; start < grey.length; start++) {
            if (visited[start] || grey[start] <= thresholdInt) {
                continue;
            }
            stack.clear();
            stack.push(start);
            visited[start] = true;

            int pixelCount = 0;
            double weightSum = 0.0;
            double weightedX = 0.0;
            double weightedY = 0.0;
            double peak = 0.0;

            while (!stack.isEmpty()) {
                int idx = stack.pop();
                int px = idx % width;
                int py = idx / width;
                int value = grey[idx];
                double weight = value - background;
                if (weight < 0.0) {
                    weight = 0.0;
                }
                pixelCount++;
                weightSum += weight;
                weightedX += weight * px;
                weightedY += weight * py;
                if (value > peak) {
                    peak = value;
                }

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = px + dx;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        int nIdx = ny * width + nx;
                        if (!visited[nIdx] && grey[nIdx] > thresholdInt) {
                            visited[nIdx] = true;
                            stack.push(nIdx);
                        }
                    }
                }
            }

            if (pixelCount < minBlobPixels || pixelCount > maxBlobPixels) {
                continue; // hot-pixel noise, or Moon/lamp/trail: not a usable point source
            }
            if (weightSum <= 0.0) {
                continue;
            }

            float centroidX = (float) ((weightedX / weightSum) * sxScale);
            float centroidY = (float) ((weightedY / weightSum) * syScale);
            double peakAboveBackground = Math.max(0.0, peak - background);
            double snr = peakAboveBackground / noise;
            stars.add(new Detection(centroidX, centroidY, weightSum, peakAboveBackground, snr, pixelCount));
        }
        return stars;
    }

    /** Count candidates within the outer {@code edgeMarginFraction} band on any side. */
    private int countEdgeStars(List<Detection> stars, int sourceWidth, int sourceHeight) {
        double marginX = sourceWidth * edgeMarginFraction;
        double marginY = sourceHeight * edgeMarginFraction;
        int edge = 0;
        for (Detection d : stars) {
            if (d.x < marginX || d.x > sourceWidth - marginX
                    || d.y < marginY || d.y > sourceHeight - marginY) {
                edge++;
            }
        }
        return edge;
    }
}
