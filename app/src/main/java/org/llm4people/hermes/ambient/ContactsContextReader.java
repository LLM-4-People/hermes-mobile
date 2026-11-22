package org.llm4people.hermes.ambient;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import org.llm4people.hermes.Diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strictly opt-in, query-only read-only contacts lookup.
 * Requires an explicit non-trivial search query (minimum 2 characters) and caps results to
 * {@link #MAX_RESULTS}; never dumps the entire address book and never writes contacts to disk.
 */
public final class ContactsContextReader {
    public static final int MIN_QUERY_CHARS = 2;
    public static final int MAX_QUERY_CHARS = 80;
    public static final int MAX_RESULTS = 10;

    private static final String[] PROJECTION = {
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
    };

    public record ContactMatch(String displayName, boolean hasPhoneNumber) {
        public ContactMatch {
            displayName = NotificationPrivacyGuard.sanitizeText(displayName, NotificationPrivacyGuard.MAX_SENDER_CHARS);
        }

        public String toSanitizedJson() {
            return "{"
                    + "\"name\":" + AmbientJson.quote(displayName)
                    + ",\"has_phone\":" + hasPhoneNumber
                    + "}";
        }
    }

    private ContactsContextReader() {}

    public static boolean hasPermission(Context context) {
        return context != null
                && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Normalize and validate an explicit search query; returns "" when too short or blank. */
    public static String normalizeQuery(String raw) {
        String clean = NotificationPrivacyGuard.sanitizeText(raw, MAX_QUERY_CHARS);
        return clean.length() < MIN_QUERY_CHARS ? "" : clean;
    }

    /** Deduplicate by display name (case-insensitive) and cap to {@link #MAX_RESULTS}. */
    public static List<ContactMatch> boundMatches(List<ContactMatch> raw, int limit) {
        if (raw == null || raw.isEmpty() || limit <= 0) return Collections.emptyList();
        int cap = Math.min(limit, MAX_RESULTS);
        Map<String, ContactMatch> unique = new LinkedHashMap<>();
        for (ContactMatch match : raw) {
            if (match == null || match.displayName().isEmpty()) continue;
            String key = match.displayName().toLowerCase(java.util.Locale.ROOT);
            unique.putIfAbsent(key, match);
            if (unique.size() >= cap) break;
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    /** Perform a bounded display-name search only when opted in and READ_CONTACTS is granted. */
    public static List<ContactMatch> search(Context context, boolean optInEnabled, String query, int limit) {
        if (!optInEnabled || !hasPermission(context)) return Collections.emptyList();
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty()) return Collections.emptyList();
        Uri filterUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(normalized));
        List<ContactMatch> found = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                filterUri,
                PROJECTION,
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC")) {
            if (cursor == null) return Collections.emptyList();
            while (cursor.moveToNext() && found.size() < MAX_RESULTS * 2) {
                String name = cursor.isNull(0) ? "" : cursor.getString(0);
                boolean hasPhone = cursor.getInt(1) > 0;
                found.add(new ContactMatch(name, hasPhone));
            }
            Diagnostics.event("ambient", "contacts_search");
            return boundMatches(found, limit);
        } catch (SecurityException ex) {
            Diagnostics.warning("ambient", "contacts_permission_denied", ex);
            return Collections.emptyList();
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "contacts_search_failed", ex);
            return Collections.emptyList();
        }
    }
}
