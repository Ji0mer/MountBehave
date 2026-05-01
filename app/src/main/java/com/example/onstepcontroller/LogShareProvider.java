package com.example.onstepcontroller;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class LogShareProvider extends ContentProvider {
    private static final String PATH_LOGS = "logs";

    static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".logprovider")
                .appendPath(PATH_LOGS)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("Log provider is read-only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException ex) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
        });
        cursor.addRow(new Object[]{LogExporter.displayNameFor(file), file.length()});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Log provider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Log provider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Log provider is read-only");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null || uri == null || uri.getPathSegments().size() != 2
                || !PATH_LOGS.equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Invalid log uri");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Invalid log file name");
        }
        try {
            File root = new File(context.getFilesDir(), PATH_LOGS).getCanonicalFile();
            File file = new File(root, name).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath()) || !file.isFile()) {
                throw new FileNotFoundException(name);
            }
            return file;
        } catch (IOException ex) {
            FileNotFoundException wrapped = new FileNotFoundException(name);
            wrapped.initCause(ex);
            throw wrapped;
        }
    }
}
