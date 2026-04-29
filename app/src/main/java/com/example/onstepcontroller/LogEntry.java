package com.example.onstepcontroller;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class LogEntry {
    final long wallTimeMillis;
    final long monotonicNanos;
    final Logger.Level level;
    final String message;

    LogEntry(long wallTimeMillis, long monotonicNanos, Logger.Level level, String message) {
        this.wallTimeMillis = wallTimeMillis;
        this.monotonicNanos = monotonicNanos;
        this.level = level;
        this.message = message == null ? "" : message;
    }

    String formatted() {
        return String.format(
                Locale.US,
                "%s [%-5s] %s",
                DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(wallTimeMillis)),
                level.name(),
                message
        );
    }
}
