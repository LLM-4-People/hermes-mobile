package org.llm4people.hermes;

import android.util.Log;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Centralized, privacy-safe diagnostics at the lowest choking point.
 * Records bounded operation identifiers and sanitized reason codes only.
 * Never pass credentials, URLs, IDs, coordinates, or user content.
 */
public final class Diagnostics {
    private static final String TAG = "HermesMobile";
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public enum Level {
        DEBUG('D'),
        INFO('I'),
        WARN('W'),
        ERROR('E');

        final char code;
        Level(char code) { this.code = code; }
    }

    public record Entry(long epochMillis, Level level, String category, String action, String reason) {
        public String message() {
            return reason == null ? category + "." + action : category + "." + action + " reason=" + reason;
        }

        public String formatted() {
            return Instant.ofEpochMilli(epochMillis) + " " + level.code + " " + message();
        }
    }

    public interface Sink {
        void write(Level level, String tag, String message);
    }

    private static final Sink ANDROID_SINK = (level, tag, message) -> {
        try {
            switch (level) {
                case DEBUG -> Log.d(tag, message);
                case INFO -> Log.i(tag, message);
                case WARN -> Log.w(tag, message);
                case ERROR -> Log.e(tag, message);
            }
        } catch (RuntimeException | LinkageError ignoredOnHostJvm) {
            // Host JVM unit tests run without Android Log implementation; ring buffer still captures entries.
        }
    };

    private static final Object LOCK = new Object();
    private static final Deque<Entry> RING = new ArrayDeque<>(HermesConfig.DEFAULT_DIAGNOSTICS_RING_CAPACITY);
    private static int capacity = HermesConfig.DEFAULT_DIAGNOSTICS_RING_CAPACITY;
    private static volatile boolean debugEnabled = false;
    private static volatile Sink sink = ANDROID_SINK;

    private Diagnostics() {}

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void setCapacity(int maxEntries) {
        if (maxEntries <= 0) throw new IllegalArgumentException("Diagnostics capacity must be positive");
        synchronized (LOCK) {
            capacity = maxEntries;
            while (RING.size() > capacity) RING.removeFirst();
        }
    }

    public static void setSink(Sink customSink) {
        sink = customSink == null ? ANDROID_SINK : customSink;
    }

    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
        }
    }

    public static void debug(String category, String action) {
        if (debugEnabled) record(Level.DEBUG, category, action, null);
    }

    public static void debug(String category, String action, String reason) {
        if (debugEnabled) record(Level.DEBUG, category, action, reason);
    }

    public static void debug(String category, String action, Throwable cause) {
        if (debugEnabled) record(Level.DEBUG, category, action, reasonOf(cause));
    }

    public static void event(String category, String action) {
        record(Level.INFO, category, action, null);
    }

    public static void event(String category, String action, String reason) {
        record(Level.INFO, category, action, reason);
    }

    public static void warning(String category, String action) {
        record(Level.WARN, category, action, null);
    }

    public static void warning(String category, String action, String reason) {
        record(Level.WARN, category, action, reason);
    }

    public static void warning(String category, String action, Throwable cause) {
        record(Level.WARN, category, action, reasonOf(cause));
    }

    public static void error(String category, String action) {
        record(Level.ERROR, category, action, null);
    }

    public static void error(String category, String action, String reason) {
        record(Level.ERROR, category, action, reason);
    }

    public static void error(String category, String action, Throwable cause) {
        record(Level.ERROR, category, action, reasonOf(cause));
    }

    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(RING));
        }
    }

    public static String exportRedacted() {
        List<Entry> entries = snapshot();
        if (entries.isEmpty()) return "";
        StringBuilder out = new StringBuilder(entries.size() * 48);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(entries.get(i).formatted());
        }
        return out.toString();
    }

    private static void record(Level level, String category, String action, String reason) {
        String safeCategory = sanitize(category, "unknown");
        String safeAction = sanitize(action, "event");
        String safeReason = reason == null ? null : sanitize(reason, "redacted");
        Entry entry = new Entry(System.currentTimeMillis(), level, safeCategory, safeAction, safeReason);
        synchronized (LOCK) {
            while (RING.size() >= capacity) RING.removeFirst();
            RING.addLast(entry);
        }
        sink.write(level, TAG, entry.message());
    }

    private static String reasonOf(Throwable cause) {
        if (cause == null) return "unknown";
        String simple = cause.getClass().getSimpleName();
        return simple.isEmpty() ? "error" : simple;
    }

    static String sanitize(String raw, String fallback) {
        if (raw == null) return fallback;
        String trimmed = raw.trim();
        return SAFE_TOKEN.matcher(trimmed).matches() ? trimmed : fallback;
    }
}
