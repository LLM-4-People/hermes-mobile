package org.llm4people.hermes.ambient;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.profile.Account;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Encrypted per-profile opt-in settings and app allowlist for ambient phone context.
 * Strictly OFF and empty by default; hard-blocked sensitive packages can never be allowlisted.
 */
public final class NotificationAllowlist {
    static final String KEY_ALLOWLIST = "ambient/notification_allowlist_v1";
    static final String KEY_SETTINGS = "ambient/settings_v1";
    static final int MAX_ALLOWED_APPS = 128;

    private static final Pattern PACKAGE_NAME = Pattern.compile("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+");

    /** Encrypted key-value backing store abstraction (wraps {@link Account} in production). */
    public interface Store {
        String read(String key);
        void write(String key, String value);
        void remove(String key);
    }

    public record Settings(
            boolean notificationsEnabled,
            boolean mediaEnabled,
            boolean calendarEnabled,
            boolean contactsEnabled,
            boolean deviceContextEnabled) {
        public static final Settings ALL_OFF = new Settings(false, false, false, false, false);
    }

    private final Store store;

    private NotificationAllowlist(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public static NotificationAllowlist forAccount(Account account) {
        Objects.requireNonNull(account, "account");
        return new NotificationAllowlist(new Store() {
            @Override public String read(String key) { return account.read(key); }
            @Override public void write(String key, String value) { account.write(key, value); }
            @Override public void remove(String key) { account.remove(key); }
        });
    }

    public static NotificationAllowlist forStore(Store store) {
        return new NotificationAllowlist(store);
    }

    /** Erase all ambient preferences and allowlist entries for a forgotten/removed profile. */
    public static void wipe(Account account) {
        if (account != null) {
            forAccount(account).clear();
        }
        HermesNotificationListener.clearSession();
    }

    public synchronized Settings settings() {
        String raw = store.read(KEY_SETTINGS);
        if (raw == null || raw.trim().isEmpty()) return Settings.ALL_OFF;
        boolean notifications = false;
        boolean media = false;
        boolean calendar = false;
        boolean contacts = false;
        boolean device = false;
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            boolean v = "1".equals(line.substring(eq + 1).trim());
            switch (k) {
                case "notifications" -> notifications = v;
                case "media" -> media = v;
                case "calendar" -> calendar = v;
                case "contacts" -> contacts = v;
                case "device_context" -> device = v;
                default -> { /* ignore unknown keys */ }
            }
        }
        return new Settings(notifications, media, calendar, contacts, device);
    }

    public synchronized void saveSettings(Settings next) {
        Objects.requireNonNull(next, "next");
        String encoded = "notifications=" + (next.notificationsEnabled() ? "1" : "0")
                + "\nmedia=" + (next.mediaEnabled() ? "1" : "0")
                + "\ncalendar=" + (next.calendarEnabled() ? "1" : "0")
                + "\ncontacts=" + (next.contactsEnabled() ? "1" : "0")
                + "\ndevice_context=" + (next.deviceContextEnabled() ? "1" : "0");
        store.write(KEY_SETTINGS, encoded);
        Diagnostics.event("ambient", "settings_updated");
    }

    public synchronized Set<String> allowedPackages() {
        String raw = store.read(KEY_ALLOWLIST);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String normalized = normalizePackage(line);
            if (normalized != null && !NotificationPrivacyGuard.isHardBlockedPackage(normalized)) {
                result.add(normalized);
                if (result.size() >= MAX_ALLOWED_APPS) break;
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Active allowlist enforced by the listener: always empty while notifications opt-in is OFF. */
    public synchronized Set<String> effectiveAllowedPackages() {
        if (!settings().notificationsEnabled()) return Collections.emptySet();
        return allowedPackages();
    }

    /** Add or remove one package. Throws {@link IllegalArgumentException} for invalid or hard-blocked packages. */
    public synchronized void setAllowed(String packageName, boolean allowed) {
        String normalized = normalizePackage(packageName);
        if (normalized == null) {
            Diagnostics.warning("ambient", "allowlist_rejected", "invalid_package");
            throw new IllegalArgumentException("Invalid Android package name");
        }
        if (allowed && NotificationPrivacyGuard.isHardBlockedPackage(normalized)) {
            Diagnostics.warning("ambient", "allowlist_rejected", "hard_blocked");
            throw new IllegalArgumentException("Sensitive package cannot be allowlisted");
        }
        Set<String> updated = new LinkedHashSet<>(allowedPackages());
        if (allowed) {
            if (!updated.contains(normalized) && updated.size() >= MAX_ALLOWED_APPS) {
                throw new IllegalStateException("Allowlist capacity reached");
            }
            updated.add(normalized);
        } else {
            updated.remove(normalized);
        }
        if (updated.isEmpty()) {
            store.remove(KEY_ALLOWLIST);
        } else {
            store.write(KEY_ALLOWLIST, String.join("\n", updated));
        }
        Diagnostics.event("ambient", allowed ? "allowlist_added" : "allowlist_removed");
    }

    /** Wipe both opt-in settings and allowlisted packages for this profile. */
    public synchronized void clear() {
        store.remove(KEY_ALLOWLIST);
        store.remove(KEY_SETTINGS);
        Diagnostics.event("ambient", "profile_wiped");
    }

    static String normalizePackage(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > 160) return null;
        return PACKAGE_NAME.matcher(trimmed).matches() ? trimmed : null;
    }
}
