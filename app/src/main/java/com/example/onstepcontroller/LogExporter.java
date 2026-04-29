package com.example.onstepcontroller;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;

final class LogExporter {
    private LogExporter() {
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
        share.putExtra(Intent.EXTRA_SUBJECT, "MountBehave log " + logFile.getName());
        if (uris.size() > 1) {
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        } else {
            share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return share;
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
}
