package com.example.onstepcontroller;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class LogExporter {
    private static final byte[] CRASH_SEPARATOR =
            "\n\n----- MountBehave crash log -----\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MEMORY_SEPARATOR =
            "\n\n----- MountBehave recent in-memory log -----\n".getBytes(StandardCharsets.UTF_8);

    private LogExporter() {
    }

    static String defaultExportFileName() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "mountbehave-" + timestamp + ".txt";
    }

    static String displayNameFor(File file) {
        String name = file == null ? defaultExportFileName() : file.getName();
        return name.endsWith(".log") ? name.substring(0, name.length() - 4) + ".txt" : name;
    }

    static Intent createShareIntent(Context context) throws IOException {
        File exportFile = prepareExportFile(context);
        Uri uri = LogShareProvider.uriFor(context, exportFile);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "MountBehave log " + exportFile.getName());
        share.putExtra(Intent.EXTRA_TITLE, exportFile.getName());
        share.putExtra(Intent.EXTRA_TEXT, "MountBehave 日志文件见附件：" + exportFile.getName());
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.setClipData(ClipData.newUri(context.getContentResolver(), exportFile.getName(), uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        grantReadPermissionToShareTargets(context, share, uri);
        return share;
    }

    private static void grantReadPermissionToShareTargets(Context context, Intent share, Uri uri) {
        List<ResolveInfo> targets = context.getPackageManager().queryIntentActivities(share, 0);
        for (ResolveInfo target : targets) {
            if (target.activityInfo != null && target.activityInfo.packageName != null) {
                context.grantUriPermission(
                        target.activityInfo.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        }
    }

    static void writeToUri(Context context, Uri destination) throws IOException {
        if (context == null || destination == null) {
            throw new IOException("Missing export destination");
        }
        try (OutputStream out = openTruncatingOutputStream(context, destination)) {
            if (out == null) {
                throw new IOException("Cannot open export destination");
            }
            writeCombinedLog(out);
            out.flush();
        }
    }

    private static OutputStream openTruncatingOutputStream(Context context, Uri destination) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try {
            OutputStream out = resolver.openOutputStream(destination, "rwt");
            if (out != null) {
                return out;
            }
        } catch (IllegalArgumentException | SecurityException | IOException ignored) {
            // Fall through to the most widely supported mode.
        }
        return resolver.openOutputStream(destination, "w");
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
        String mediaStoreRelativePath = relativePath + "/";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, mediaStoreRelativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        long nowSeconds = System.currentTimeMillis() / 1000L;
        values.put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds);
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Cannot create Downloads entry");
        }
        boolean committed = false;
        try {
            writeToUri(context, uri);
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            complete.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L);
            resolver.update(uri, complete, null, null);
            committed = true;
            return queryExportResult(resolver, uri, fileName, relativePath);
        } finally {
            if (!committed) {
                resolver.delete(uri, null, null);
            }
        }
    }

    private static ExportResult queryExportResult(
            ContentResolver resolver,
            Uri uri,
            String fallbackFileName,
            String relativePath
    ) throws IOException {
        String fileName = fallbackFileName;
        long sizeBytes = -1L;
        String[] projection = {
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    fileName = cursor.getString(nameIndex);
                }
                int sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex);
                }
            }
        }
        return new ExportResult(fileName, relativePath, sizeBytes);
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

    private static File prepareExportFile(Context context) throws IOException {
        if (context == null) {
            throw new IOException("Missing context");
        }
        File exportFile = new File(Logger.logsDir(), defaultExportFileName());
        File parent = exportFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create log export directory");
        }
        try (OutputStream out = new FileOutputStream(exportFile, false)) {
            writeCombinedLog(out);
            out.flush();
        }
        return exportFile;
    }

    private static void writeCombinedLog(OutputStream out) throws IOException {
        out.write(("MountBehave log export\nExported: " + new Date() + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        File logFile = Logger.todayLogFile();
        ensureExists(logFile);
        copy(logFile, out);
        File crashFile = Logger.todayCrashFile();
        if (crashFile.isFile() && crashFile.length() > 0) {
            out.write(CRASH_SEPARATOR);
            copy(crashFile, out);
        }
        List<LogEntry> snapshot = Logger.snapshot(1000);
        if (!snapshot.isEmpty()) {
            out.write(MEMORY_SEPARATOR);
            for (LogEntry entry : snapshot) {
                out.write(entry.formatted().getBytes(StandardCharsets.UTF_8));
                out.write('\n');
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
        final long sizeBytes;

        ExportResult(String fileName, String relativePath) {
            this(fileName, relativePath, -1L);
        }

        ExportResult(String fileName, String relativePath, long sizeBytes) {
            this.fileName = fileName;
            this.relativePath = relativePath;
            this.sizeBytes = sizeBytes;
        }
    }
}
