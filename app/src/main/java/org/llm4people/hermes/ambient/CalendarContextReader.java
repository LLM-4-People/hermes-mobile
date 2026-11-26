package org.llm4people.hermes.ambient;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.JsonValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Strictly opt-in, read-only upcoming calendar agenda reader.
 * Bounded to a short lookahead window (default 7 days, max 14 days, max 25 events) and projects
 * ONLY title, start, end, and all-day flag — never attendees, descriptions, or private notes.
 */
public final class CalendarContextReader {
    public static final int DEFAULT_WINDOW_DAYS = 7;
    public static final int MAX_WINDOW_DAYS = 14;
    public static final int MAX_EVENTS = 25;
    public static final int MAX_TITLE_CHARS = 120;
    public static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private static final String[] PROJECTION = {
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
    };

    public record Window(long startMillis, long endMillis, int days) {}

    public record AgendaEvent(String title, long startMillis, long endMillis, boolean allDay) {
        public AgendaEvent {
            title = org.llm4people.hermes.PlainTextSanitizer.collapseWithEllipsis(title, MAX_TITLE_CHARS);
            if (endMillis < startMillis) endMillis = startMillis;
        }

        public String toSanitizedJson() {
            return "{"
                    + "\"title\":" + JsonValues.quote(title)
                    + ",\"start_ms\":" + startMillis
                    + ",\"end_ms\":" + endMillis
                    + ",\"all_day\":" + allDay
                    + "}";
        }
    }

    private CalendarContextReader() {}

    public static boolean hasPermission(Context context) {
        return context != null
                && context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Clamp requested lookahead days into [1, MAX_WINDOW_DAYS] (non-positive -> DEFAULT_WINDOW_DAYS). */
    public static Window resolveWindow(long nowMillis, int requestedDays) {
        int days = requestedDays <= 0 ? DEFAULT_WINDOW_DAYS : Math.min(requestedDays, MAX_WINDOW_DAYS);
        long safeStart = Math.max(0L, nowMillis);
        return new Window(safeStart, safeStart + days * MILLIS_PER_DAY, days);
    }

    /** Filter to overlapping window events, sort chronologically, and enforce the hard event cap. */
    public static List<AgendaEvent> boundAndSort(
            List<AgendaEvent> raw, long windowStartMillis, long windowEndMillis, int maxEvents) {
        if (raw == null || raw.isEmpty() || maxEvents <= 0 || windowEndMillis <= windowStartMillis) {
            return Collections.emptyList();
        }
        int cap = Math.min(maxEvents, MAX_EVENTS);
        List<AgendaEvent> filtered = new ArrayList<>();
        for (AgendaEvent event : raw) {
            if (event == null) continue;
            if (event.endMillis() <= windowStartMillis || event.startMillis() >= windowEndMillis) continue;
            filtered.add(event);
        }
        filtered.sort(Comparator.comparingLong(AgendaEvent::startMillis)
                .thenComparingLong(AgendaEvent::endMillis)
                .thenComparing(AgendaEvent::title));
        if (filtered.size() > cap) {
            filtered = new ArrayList<>(filtered.subList(0, cap));
        }
        return Collections.unmodifiableList(filtered);
    }

    /** Read bounded upcoming events only when the profile opted in AND Android READ_CALENDAR is granted. */
    public static List<AgendaEvent> readUpcoming(Context context, boolean optInEnabled, int requestedDays) {
        if (!optInEnabled || !hasPermission(context)) {
            return Collections.emptyList();
        }
        Window window = resolveWindow(System.currentTimeMillis(), requestedDays);
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, window.startMillis());
        ContentUris.appendId(builder, window.endMillis());
        List<AgendaEvent> collected = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                builder.build(),
                PROJECTION,
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (cursor == null) return Collections.emptyList();
            while (cursor.moveToNext() && collected.size() < MAX_EVENTS * 2) {
                String title = cursor.isNull(0) ? "" : cursor.getString(0);
                long begin = cursor.getLong(1);
                long end = cursor.getLong(2);
                boolean allDay = cursor.getInt(3) != 0;
                collected.add(new AgendaEvent(title, begin, end, allDay));
            }
            Diagnostics.event("ambient", "calendar_read");
            return boundAndSort(collected, window.startMillis(), window.endMillis(), MAX_EVENTS);
        } catch (SecurityException ex) {
            Diagnostics.warning("ambient", "calendar_permission_denied", ex);
            return Collections.emptyList();
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "calendar_read_failed", ex);
            return Collections.emptyList();
        }
    }
}
