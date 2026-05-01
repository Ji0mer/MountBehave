package com.example.onstepcontroller;

import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

final class LogExporter {
    private static final byte[] CRASH_SEPARATOR =
            "\n\n----- MountBehave crash log -----\n".getBytes(StandardCharsets.UTF_8);

    private LogExporter() {
    }

    static String defaultExportFileName() {
        String name = Logger.todayLogFile().getName();
        return name.endsWith(".log") ? name.substring(0, name.length() - 4) + ".txt" : name + ".txt";
    }

    static String displayNameFor(File file) {
        String name = file == null ? defaultExportFileName() : file.getName();
        return name.endsWith(".log") ? name.substring(0, name.length() - 4) + ".txt" : name;
    }

    static Intent createShareIntent(Context context) {
        ArrayList<Uri> uris = new ArrayList<>();
        File logFile = Logger.todayLogFile();
        ensureExists(logFile);
        uris.add(LogShareProvider.uriFor(context, logFile));

        File crashFile = Logger.todayCrashFile();
        if (crashFile.isFile() && crashFile.length() > 0) {
            uris.add(LogShareProvider.uriFor(context, crashFile));
        }

        Intent share = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "MountBehave log " + defaultExportFileName());
        if (uris.size() > 1) {
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        } else {
            share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return share;
    }

    static void writeToUri(Context context, Uri destination) throws IOException {
        if (context == null || destination == null) {
            throw new IOException("Missing export destination");
        }
        File logFile = Logger.todayLogFile();
        ensureExists(logFile);
        try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) {
                throw new IOException("Cannot open export destination");
            }
            copy(logFile, out);
            File crashFile = Logger.todayCrashFile();
            if (crashFile.isFile() && crashFile.length() > 0) {
                out.write(CRASH_SEPARATOR);
                copy(crashFile, out);
            }
            out.flush();
        }
    }

    static ExportResult writeToDownloads(Context context) throws IOException {
        if (context == null) {
            throw new IOException("Missing context");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("System Downloads export requires Android 10+");
        }
        ContentResolver resolver = context.getContentResolver();
        String fileName = defaultExportFileName();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/MountBehave";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Cannot create Downloads entry");
        }
        boolean committed = false;
        try {
            writeToUri(context, uri);
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
            committed = true;
            return new ExportResult(fileName, relativePath);
        } finally {
            if (!committed) {
                resolver.delete(uri, null, null);
            }
        }
    }

    private static void copy(File file, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void ensureExists(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (Exception ignored) {
            // The provider/open step will surface the actual failure if sharing proceeds.
        }
    }

    static final class ExportResult {
        final String fileName;
        final String relativePath;

        ExportResult(String fileName, String relativePath) {
            this.fileName = fileName;
            this.relativePath = relativePath;
        }
    }
}
