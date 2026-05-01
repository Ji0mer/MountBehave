package com.example.onstepcontroller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class Logger {
    enum Level { TRACE, TX, RX, USER, INFO, WARN, ERROR, DIAG, FATAL }

    private static final int MEMORY_LIMIT = 1000;
    private static final int EARLY_LIMIT = 1000;
    private static final int MAX_LINE_CHARS = 2048;
    private static final long EXPORT_FLUSH_TIMEOUT_MS = 3000L;
    private static final long RETAIN_LOG_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<LogEntry> BUFFER = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<LogEntry> EARLY_BUFFER = new ConcurrentLinkedQueue<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CALLBACK_PENDING = new AtomicBoolean(false);
    private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("yyyyMMdd", Locale.US);

    private static volatile boolean initialized;
    private static volatile boolean enabled = true;
    private static Context appContext;
    private static ExecutorService fileExecutor;
    private static Runnable uiCallback;
    private static BufferedWriter writer;
    private static String writerDay;
    private static Thread.UncaughtExceptionHandler previousUncaughtHandler;
    private static boolean fatalHandlerInstalled;
    private static final AtomicBoolean handlingFatal = new AtomicBoolean(false);

    static {
        DAY_FORMAT.setTimeZone(TimeZone.getDefault());
    }

    private Logger() {
    }

    static void init(Context context) {
        if (context == null || initialized) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            appContext = context.getApplicationContext();
            fileExecutor = Executors.newSingleThreadExecutor(new LoggerThreadFactory());
            initialized = true;
            installFatalHandler();
        }
        cleanOldLogs();
        LogEntry entry;
        while ((entry = EARLY_BUFFER.poll()) != null) {
            appendEntry(entry);
        }
        info("logger initialized");
    }

    static void setUiCallback(Runnable callback) {
        uiCallback = callback;
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean enabledState) {
        boolean changed = enabled != enabledState;
        enabled = enabledState;
        if (!enabledState) {
            ExecutorService executor = fileExecutor;
            if (executor != null) {
                executor.execute(() -> {
                    synchronized (LOCK) {
                        closeWriterLocked();
                    }
                });
            }
        }
        if (changed) {
            notifyUi();
        }
    }

    static void txOk(String command) {
        log(Level.TX, command + " OK");
    }

    static void txFail(String command, Throwable throwable) {
        log(Level.ERROR, "TX_FAIL " + command + " " + throwableSummary(throwable), null);
    }

    static void rx(String command, String reply) {
        log(Level.RX, command + " -> " + reply);
    }

    static void rxFail(String command, Throwable throwable) {
        log(Level.ERROR, "RX_FAIL " + command + " " + throwableSummary(throwable), null);
    }

    static void user(String message) {
        log(Level.USER, message);
    }

    static void info(String message) {
        log(Level.INFO, message);
    }

    static void warn(String message) {
        log(Level.WARN, message);
    }

    static void warn(String message, Throwable throwable) {
        log(Level.WARN, message, throwable);
    }

    static void error(String message) {
        log(Level.ERROR, message);
    }

    static void error(String message, Throwable throwable) {
        log(Level.ERROR, message, throwable);
    }

    static void diag(String message) {
        log(Level.DIAG, message);
    }

    static List<LogEntry> snapshot(int maxLines) {
        synchronized (LOCK) {
            int size = BUFFER.size();
            int skip = Math.max(0, size - Math.max(0, maxLines));
            List<LogEntry> result = new ArrayList<>(size - skip);
            int index = 0;
            for (LogEntry entry : BUFFER) {
                if (index++ >= skip) {
                    result.add(entry);
                }
            }
            return result;
        }
    }

    static String recentText(int maxLines) {
        StringBuilder builder = new StringBuilder();
        for (LogEntry entry : snapshot(maxLines)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.formatted());
        }
        return builder.toString();
    }

    static File logsDir() {
        Context context = appContext;
        File root = context == null ? null : context.getFilesDir();
        File dir = root == null ? new File("logs") : new File(root, "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    static File todayLogFile() {
        return new File(logsDir(), "mountbehave-" + todayString() + ".log");
    }

    static File todayCrashFile() {
        return new File(logsDir(), "crash-" + todayString() + ".log");
    }

    static void clearToday() {
        synchronized (LOCK) {
            BUFFER.clear();
        }
        notifyUi();
        ExecutorService executor = fileExecutor;
        if (executor != null) {
            executor.execute(() -> {
                synchronized (LOCK) {
                    closeWriterLocked();
                }
                truncate(todayLogFile());
                truncate(todayCrashFile());
            });
        } else {
            truncate(todayLogFile());
            truncate(todayCrashFile());
        }
    }

    static boolean flushForExport() {
        if (!initialized) {
            return true;
        }
        ExecutorService executor = fileExecutor;
        if (executor == null) {
            return true;
        }
        Future<?> barrier;
        try {
            barrier = executor.submit(() -> {
                synchronized (LOCK) {
                    try {
                        if (writer != null) {
                            writer.flush();
                        }
                    } catch (IOException ignored) {
                        // Export should still proceed; the share/open step will surface hard failures.
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            return false;
        }
        try {
            barrier.get(EXPORT_FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException ex) {
            return false;
        }
    }

    private static void log(Level level, String message) {
        log(level, message, null);
    }

    private static void log(Level level, String message, Throwable throwable) {
        if (!enabled && level != Level.FATAL) {
            return;
        }
        String fullMessage = message == null ? "" : message;
        if (throwable != null) {
            fullMessage = fullMessage + " " + throwableSummary(throwable);
        }
        appendEntry(new LogEntry(
                System.currentTimeMillis(),
                System.nanoTime(),
                level,
                sanitize(fullMessage)
        ));
    }

    private static void appendEntry(LogEntry entry) {
        if (!initialized) {
            EARLY_BUFFER.add(entry);
            while (EARLY_BUFFER.size() > EARLY_LIMIT) {
                EARLY_BUFFER.poll();
            }
            return;
        }
        synchronized (LOCK) {
            BUFFER.addLast(entry);
            while (BUFFER.size() > MEMORY_LIMIT) {
                BUFFER.removeFirst();
            }
        }
        ExecutorService executor = fileExecutor;
        if (executor != null) {
            executor.execute(() -> writeEntry(entry));
        }
        notifyUi();
    }

    private static void writeEntry(LogEntry entry) {
        synchronized (LOCK) {
            try {
                BufferedWriter out = writerForDayLocked(dayString(entry.wallTimeMillis));
                out.write(entry.formatted());
                out.newLine();
                out.flush();
            } catch (IOException ignored) {
                // Logging must never break telescope control.
            }
        }
    }

    private static BufferedWriter writerForDayLocked(String day) throws IOException {
        if (writer != null && day.equals(writerDay)) {
            return writer;
        }
        closeWriterLocked();
        File file = new File(logsDir(), "mountbehave-" + day + ".log");
        writer = new BufferedWriter(new FileWriter(file, true), 4096);
        writerDay = day;
        return writer;
    }

    private static void notifyUi() {
        Runnable callback = uiCallback;
        if (callback == null || !CALLBACK_PENDING.compareAndSet(false, true)) {
            return;
        }
        MAIN_HANDLER.post(() -> {
            CALLBACK_PENDING.set(false);
            Runnable latest = uiCallback;
            if (latest != null) {
                latest.run();
            }
        });
    }

    private static void cleanOldLogs() {
        ExecutorService executor = fileExecutor;
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            File[] files = logsDir().listFiles();
            if (files == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - RETAIN_LOG_MILLIS;
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoff) {
                    file.delete();
                }
            }
        });
    }

    private static void installFatalHandler() {
        if (fatalHandlerInstalled) {
            return;
        }
        fatalHandlerInstalled = true;
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (handlingFatal.compareAndSet(false, true)) {
                LogEntry entry = new LogEntry(
                        System.currentTimeMillis(),
                        System.nanoTime(),
                        Level.FATAL,
                        sanitize("uncaught " + thread.getName() + " " + throwableSummary(throwable))
                );
                writeEntrySync(entry);
                writeCrashSync(throwable);
            }
            if (previousUncaughtHandler != null) {
                previousUncaughtHandler.uncaughtException(thread, throwable);
            }
            terminateAfterFatal();
        });
    }

    private static void terminateAfterFatal() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }

    private static void writeEntrySync(LogEntry entry) {
        synchronized (LOCK) {
            try {
                BufferedWriter out = writerForDayLocked(dayString(entry.wallTimeMillis));
                out.write(entry.formatted());
                out.newLine();
                out.flush();
            } catch (IOException ignored) {
                // Last-ditch logging; nothing useful can be surfaced here.
            }
        }
    }

    private static void writeCrashSync(Throwable throwable) {
        try (PrintWriter out = new PrintWriter(new FileWriter(todayCrashFile(), true))) {
            out.println("----- " + new Date() + " -----");
            throwable.printStackTrace(out);
            out.flush();
        } catch (IOException ignored) {
            // Last-ditch logging.
        }
    }

    private static String throwableSummary(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        String summary = throwable.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        StackTraceElement[] stack = throwable.getStackTrace();
        if (stack != null && stack.length > 0) {
            summary += " at " + stack[0];
        }
        return sanitize(summary);
    }

    private static String sanitize(String message) {
        String oneLine = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() <= MAX_LINE_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, MAX_LINE_CHARS - 3) + "...";
    }

    private static String todayString() {
        return dayString(System.currentTimeMillis());
    }

    private static String dayString(long wallTimeMillis) {
        synchronized (DAY_FORMAT) {
            return DAY_FORMAT.format(new Date(wallTimeMillis));
        }
    }

    private static void closeWriterLocked() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Closing best-effort.
        } finally {
            writer = null;
            writerDay = null;
        }
    }

    private static void truncate(File file) {
        try (FileWriter ignored = new FileWriter(file, false)) {
            // Opening in non-append mode truncates the file.
        } catch (IOException ignored) {
            // Logging clear is best-effort.
        }
    }

    private static final class LoggerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MountBehaveLogger");
            thread.setDaemon(true);
            return thread;
        }
    }
}
