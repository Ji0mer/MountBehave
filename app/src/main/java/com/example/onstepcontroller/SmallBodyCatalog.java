package com.example.onstepcontroller;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-memory store of bundled famous asteroids/comets plus any orbital elements
 * the user has downloaded through the in-app refresh interface. Persists the
 * user-downloaded portion to a TSV file under the app's filesDir; the bundled
 * portion lives in {@link BundledSmallBodies}.
 */
final class SmallBodyCatalog {
    private static final String USER_FILE_NAME = "small-bodies-user.tsv";

    private final List<SmallBody> bundled;
    private final File userFile;

    private volatile List<SmallBody> userDownloaded = Collections.emptyList();

    SmallBodyCatalog(File filesDir) {
        this.bundled = Collections.unmodifiableList(BundledSmallBodies.all());
        this.userFile = filesDir == null ? null : new File(filesDir, USER_FILE_NAME);
        this.userDownloaded = loadUserBodies();
    }

    int bundledCount() {
        return bundled.size();
    }

    int userCount() {
        return userDownloaded.size();
    }

    int totalCount() {
        return combined().size();
    }

    /** All small bodies with computable positions at {@code when}; chart layer flags gate rendering. */
    List<SolarSystemEphemeris.Body> bodies(Instant when) {
        return SmallBodyEphemeris.bodies(when, combined(), Double.POSITIVE_INFINITY);
    }

    /** Match by Chinese name, English name, designation, or numeric prefix. */
    SolarSystemEphemeris.Body findByName(Instant when, String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = normalizeName(trimmed);
        for (SmallBody body : combined()) {
            if (matches(body, trimmed, normalized)) {
                return SmallBodyEphemeris.computeOne(body, when);
            }
        }
        return null;
    }

    SmallBody findRaw(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = normalizeName(trimmed);
        for (SmallBody body : combined()) {
            if (matches(body, trimmed, normalized)) {
                return body;
            }
        }
        return null;
    }

    /** Replace the user-downloaded set and persist to disk. Bundled set is unchanged. */
    synchronized void replaceUserBodies(List<SmallBody> newList) throws IOException {
        List<SmallBody> snapshot = newList == null ? Collections.<SmallBody>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(newList));
        this.userDownloaded = snapshot;
        saveUserBodies(snapshot);
    }

    /** Append a single body to the user list, replacing any prior entry with
     *  the same designation+kind. Persists to disk. */
    synchronized void addUserBody(SmallBody body) throws IOException {
        if (body == null) {
            return;
        }
        List<SmallBody> next = new ArrayList<>(userDownloaded.size() + 1);
        for (SmallBody u : userDownloaded) {
            if (!(u.designation.equalsIgnoreCase(body.designation) && u.isComet == body.isComet)) {
                next.add(u);
            }
        }
        next.add(body);
        this.userDownloaded = Collections.unmodifiableList(next);
        saveUserBodies(this.userDownloaded);
    }

    /** Replace only user-downloaded asteroids; preserve user-downloaded comets. */
    synchronized void replaceUserAsteroids(List<SmallBody> asteroids) throws IOException {
        List<SmallBody> next = new ArrayList<>();
        for (SmallBody u : userDownloaded) {
            if (u.isComet) {
                next.add(u);
            }
        }
        if (asteroids != null) {
            for (SmallBody a : asteroids) {
                if (!a.isComet) {
                    next.add(a);
                }
            }
        }
        this.userDownloaded = Collections.unmodifiableList(next);
        saveUserBodies(this.userDownloaded);
    }

    /** Replace only user-downloaded comets; preserve user-downloaded asteroids. */
    synchronized void replaceUserComets(List<SmallBody> comets) throws IOException {
        List<SmallBody> next = new ArrayList<>();
        for (SmallBody u : userDownloaded) {
            if (!u.isComet) {
                next.add(u);
            }
        }
        if (comets != null) {
            for (SmallBody c : comets) {
                if (c.isComet) {
                    next.add(c);
                }
            }
        }
        this.userDownloaded = Collections.unmodifiableList(next);
        saveUserBodies(this.userDownloaded);
    }

    int userAsteroidCount() {
        int n = 0;
        for (SmallBody b : userDownloaded) {
            if (!b.isComet) {
                n++;
            }
        }
        return n;
    }

    int userCometCount() {
        int n = 0;
        for (SmallBody b : userDownloaded) {
            if (b.isComet) {
                n++;
            }
        }
        return n;
    }

    synchronized void clearUserBodies() throws IOException {
        this.userDownloaded = Collections.emptyList();
        if (userFile != null && userFile.exists()) {
            if (!userFile.delete()) {
                throw new IOException("Cannot delete " + userFile);
            }
        }
    }

    private List<SmallBody> combined() {
        List<SmallBody> users = userDownloaded;
        if (users.isEmpty()) {
            return bundled;
        }
        // User entries override bundled entries with the same designation+kind so
        // that a refreshed comet/asteroid uploaded by the user is the one rendered.
        Set<String> userKeys = new HashSet<>(users.size() * 2);
        for (SmallBody user : users) {
            userKeys.add(combinedKey(user));
        }
        List<SmallBody> merged = new ArrayList<>(bundled.size() + users.size());
        for (SmallBody body : bundled) {
            if (!userKeys.contains(combinedKey(body))) {
                merged.add(body);
            }
        }
        merged.addAll(users);
        return merged;
    }

    private static String combinedKey(SmallBody body) {
        return (body.isComet ? "c:" : "a:") + body.designation.toLowerCase(Locale.US);
    }

    private static boolean matches(SmallBody body, String trimmed, String normalized) {
        if (body.designation.equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (!body.nameZh.isEmpty() && body.nameZh.equals(trimmed)) {
            return true;
        }
        if (!body.nameEn.isEmpty() && body.nameEn.equalsIgnoreCase(trimmed)) {
            return true;
        }
        String dn = normalizeName(body.designation);
        if (!dn.isEmpty() && dn.equals(normalized)) {
            return true;
        }
        String en = normalizeName(body.nameEn);
        if (!en.isEmpty() && en.equals(normalized)) {
            return true;
        }
        String zh = normalizeName(body.nameZh);
        return !zh.isEmpty() && zh.equals(normalized);
    }

    private static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private List<SmallBody> loadUserBodies() {
        if (userFile == null || !userFile.exists()) {
            return Collections.emptyList();
        }
        List<SmallBody> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(userFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                SmallBody body = parseTsvLine(line);
                if (body != null) {
                    result.add(body);
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(result);
    }

    private void saveUserBodies(List<SmallBody> bodies) throws IOException {
        if (userFile == null) {
            return;
        }
        File parent = userFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(userFile), StandardCharsets.UTF_8))) {
            writer.write("# designation\tname_zh\tname_en\tis_comet\tepoch_jd\tq\te\ti\tom\tw\ttp\tH\tG\n");
            for (SmallBody body : bodies) {
                writer.write(toTsvLine(body));
                writer.newLine();
            }
        }
    }

    private static SmallBody parseTsvLine(String line) {
        String[] parts = line.split("\t");
        if (parts.length < 13) {
            return null;
        }
        try {
            return new SmallBody(
                    parts[0],
                    parts[1],
                    parts[2],
                    Boolean.parseBoolean(parts[3]),
                    Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]),
                    Double.parseDouble(parts[6]),
                    Double.parseDouble(parts[7]),
                    Double.parseDouble(parts[8]),
                    Double.parseDouble(parts[9]),
                    Double.parseDouble(parts[10]),
                    Double.parseDouble(parts[11]),
                    Double.parseDouble(parts[12])
            );
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String toTsvLine(SmallBody body) {
        return body.designation + "\t" + body.nameZh + "\t" + body.nameEn + "\t" + body.isComet
                + "\t" + body.epochJd + "\t" + body.perihelionDistanceAu + "\t" + body.eccentricity
                + "\t" + body.inclinationDeg + "\t" + body.ascendingNodeDeg + "\t" + body.argumentPerihelionDeg
                + "\t" + body.tpJd + "\t" + body.absoluteMagnitude + "\t" + body.slope;
    }
}
