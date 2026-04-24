package com.example.onstepcontroller;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SkyCatalog {
    final List<Star> stars;
    final List<DeepSkyObject> deepSkyObjects;
    final List<ConstellationLine> constellationLines;

    private SkyCatalog(List<Star> stars, List<DeepSkyObject> deepSkyObjects, List<ConstellationLine> constellationLines) {
        this.stars = stars;
        this.deepSkyObjects = deepSkyObjects;
        this.constellationLines = constellationLines;
    }

    static SkyCatalog load(Context context) throws IOException {
        return new SkyCatalog(loadStars(context), loadDeepSkyObjects(context), loadConstellationLines(context));
    }

    private static List<Star> loadStars(Context context) throws IOException {
        List<Star> result = new ArrayList<>();
        try (BufferedReader reader = assetReader(context, "catalog/stars.tsv")) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 4) {
                    continue;
                }
                result.add(new Star(
                        parts[0],
                        parseDouble(parts[1]),
                        parseDouble(parts[2]),
                        parseDouble(parts[3])
                ));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<DeepSkyObject> loadDeepSkyObjects(Context context) throws IOException {
        List<DeepSkyObject> result = new ArrayList<>();
        try (BufferedReader reader = assetReader(context, "catalog/dsos.tsv")) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 6) {
                    continue;
                }
                result.add(new DeepSkyObject(
                        parts[0],
                        parts[1],
                        parseDouble(parts[2]),
                        parseDouble(parts[3]),
                        parts[4].isEmpty() ? Double.NaN : parseDouble(parts[4]),
                        parts[5],
                        parts.length >= 7 ? parts[6] : ""
                ));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<ConstellationLine> loadConstellationLines(Context context) throws IOException {
        List<ConstellationLine> result = new ArrayList<>();
        try (BufferedReader reader = assetReader(context, "catalog/constellation_lines.tsv")) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 5) {
                    continue;
                }
                result.add(new ConstellationLine(
                        parts[0],
                        parseDouble(parts[1]),
                        parseDouble(parts[2]),
                        parseDouble(parts[3]),
                        parseDouble(parts[4])
                ));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static BufferedReader assetReader(Context context, String path) throws IOException {
        return new BufferedReader(new InputStreamReader(
                context.getAssets().open(path),
                StandardCharsets.UTF_8
        ));
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value);
    }

    static final class Star {
        final String name;
        final double raHours;
        final double decDegrees;
        final double magnitude;

        Star(String name, double raHours, double decDegrees, double magnitude) {
            this.name = name;
            this.raHours = raHours;
            this.decDegrees = decDegrees;
            this.magnitude = magnitude;
        }
    }

    static final class DeepSkyObject {
        final String name;
        final String type;
        final double raHours;
        final double decDegrees;
        final double magnitude;
        final String commonName;
        final String aliases;

        DeepSkyObject(
                String name,
                String type,
                double raHours,
                double decDegrees,
                double magnitude,
                String commonName,
                String aliases
        ) {
            this.name = name;
            this.type = type;
            this.raHours = raHours;
            this.decDegrees = decDegrees;
            this.magnitude = magnitude;
            this.commonName = commonName;
            this.aliases = aliases;
        }

        String label() {
            return commonName.isEmpty() ? name : name + " " + commonName;
        }
    }

    static final class ConstellationLine {
        final String constellation;
        final double startRaHours;
        final double startDecDegrees;
        final double endRaHours;
        final double endDecDegrees;

        ConstellationLine(
                String constellation,
                double startRaHours,
                double startDecDegrees,
                double endRaHours,
                double endDecDegrees
        ) {
            this.constellation = constellation;
            this.startRaHours = startRaHours;
            this.startDecDegrees = startDecDegrees;
            this.endRaHours = endRaHours;
            this.endDecDegrees = endDecDegrees;
        }
    }
}
