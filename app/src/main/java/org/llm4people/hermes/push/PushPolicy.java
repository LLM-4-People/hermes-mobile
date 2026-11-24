package org.llm4people.hermes.push;

import android.content.Context;
import android.content.SharedPreferences;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.profile.PreferenceTransaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-profile notification category preferences and Quiet Hours (Heures calmes).
 * Stores ONLY non-PII booleans and minute-of-day bounds so wakeups can be filtered
 * locally while the profile remains locked, with strict cross-profile isolation.
 */
public final class PushPolicy {
    public static final int DEFAULT_QUIET_START_MINUTE = 22 * 60; // 22:00
    public static final int DEFAULT_QUIET_END_MINUTE = 7 * 60;    // 07:00
    private static final String PREFS_NAME = "hermes_push_policy_v1";

    public enum Decision {
        NOTIFY("notify"),
        SILENT_INBOX_ONLY("silent_inbox_only"),
        SUPPRESSED_CATEGORY("suppressed_category"),
        SUPPRESSED_QUIET_HOURS("suppressed_quiet_hours");

        public final String reasonCode;

        Decision(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }

    private final EnumSet<DeliveryItem.Category> enabledCategories;
    public final boolean quietHoursEnabled;
    public final int quietStartMinute;
    public final int quietEndMinute;
    public final boolean allowHighUrgencyDuringQuietHours;

    public PushPolicy(
            Set<DeliveryItem.Category> enabledCategories,
            boolean quietHoursEnabled,
            int quietStartMinute,
            int quietEndMinute,
            boolean allowHighUrgencyDuringQuietHours) {
        if (quietStartMinute < 0 || quietStartMinute >= 1440) {
            throw new IllegalArgumentException("Quiet start minute out of range");
        }
        if (quietEndMinute < 0 || quietEndMinute >= 1440) {
            throw new IllegalArgumentException("Quiet end minute out of range");
        }
        this.enabledCategories = enabledCategories == null || enabledCategories.isEmpty()
                ? EnumSet.noneOf(DeliveryItem.Category.class)
                : EnumSet.copyOf(enabledCategories);
        this.quietHoursEnabled = quietHoursEnabled;
        this.quietStartMinute = quietStartMinute;
        this.quietEndMinute = quietEndMinute;
        this.allowHighUrgencyDuringQuietHours = allowHighUrgencyDuringQuietHours;
    }

    public static PushPolicy defaults() {
        return new PushPolicy(
                EnumSet.allOf(DeliveryItem.Category.class),
                false,
                DEFAULT_QUIET_START_MINUTE,
                DEFAULT_QUIET_END_MINUTE,
                true);
    }

    public boolean isCategoryEnabled(DeliveryItem.Category category) {
        return category != null && enabledCategories.contains(category);
    }

    public PushPolicy withCategory(DeliveryItem.Category category, boolean enabled) {
        Objects.requireNonNull(category, "category");
        EnumSet<DeliveryItem.Category> next = EnumSet.copyOf(enabledCategories);
        if (enabled) next.add(category);
        else next.remove(category);
        return new PushPolicy(next, quietHoursEnabled, quietStartMinute, quietEndMinute,
                allowHighUrgencyDuringQuietHours);
    }

    public PushPolicy withQuietHours(boolean enabled, int startMinute, int endMinute, boolean allowHighUrgency) {
        return new PushPolicy(enabledCategories, enabled, startMinute, endMinute, allowHighUrgency);
    }

    public boolean isQuietAt(LocalTime localTime) {
        Objects.requireNonNull(localTime, "localTime");
        return isQuietAt(localTime.getHour() * 60 + localTime.getMinute());
    }

    public boolean isQuietAt(int minuteOfDay) {
        if (!quietHoursEnabled) return false;
        if (minuteOfDay < 0 || minuteOfDay >= 1440) {
            throw new IllegalArgumentException("Minute of day out of range");
        }
        if (quietStartMinute == quietEndMinute) return false;
        if (quietStartMinute < quietEndMinute) {
            return minuteOfDay >= quietStartMinute && minuteOfDay < quietEndMinute;
        }
        // Overnight wrap-around window, e.g. 22:00 -> 07:00.
        return minuteOfDay >= quietStartMinute || minuteOfDay < quietEndMinute;
    }

    public Decision evaluate(String wireKind, DeliveryItem.Urgency urgency, int minuteOfDay) {
        DeliveryItem.Urgency effective = urgency == null ? DeliveryItem.Urgency.NORMAL : urgency;
        if (effective == DeliveryItem.Urgency.SILENT) {
            return Decision.SILENT_INBOX_ONLY;
        }
        DeliveryItem.Category category = DeliveryItem.Category.forWireKind(wireKind);
        if (category != null && !isCategoryEnabled(category)) {
            return Decision.SUPPRESSED_CATEGORY;
        }
        if (isQuietAt(minuteOfDay)
                && !(effective == DeliveryItem.Urgency.HIGH && allowHighUrgencyDuringQuietHours)) {
            return Decision.SUPPRESSED_QUIET_HOURS;
        }
        return Decision.NOTIFY;
    }

    public Decision evaluate(String wireKind, DeliveryItem.Urgency urgency, LocalTime localTime) {
        return evaluate(wireKind, urgency, localTime.getHour() * 60 + localTime.getMinute());
    }

    public static String formatMinute(Context context, int minuteOfDay) {
        if (minuteOfDay < 0 || minuteOfDay >= 1440) {
            throw new IllegalArgumentException("Minute out of range");
        }
        return AppLanguage.wrapContext(context).getString(
                R.string.push_quiet_time_format, minuteOfDay / 60, minuteOfDay % 60);
    }

    public static String formatMinute(int minuteOfDay, String formatPattern, Locale locale) {
        if (minuteOfDay < 0 || minuteOfDay >= 1440) {
            throw new IllegalArgumentException("Minute out of range");
        }
        return String.format(locale == null ? Locale.ROOT : locale, formatPattern,
                minuteOfDay / 60, minuteOfDay % 60);
    }

    /** Compact, deterministic, pure-Java serialization (no PII, host-JVM testable). */
    public String encode() {
        StringBuilder cats = new StringBuilder();
        for (DeliveryItem.Category c : enabledCategories) {
            if (cats.length() > 0) cats.append(',');
            cats.append(c.wireKey);
        }
        return "v1|" + cats + "|" + (quietHoursEnabled ? "1" : "0")
                + "|" + quietStartMinute + "|" + quietEndMinute
                + "|" + (allowHighUrgencyDuringQuietHours ? "1" : "0");
    }

    public static PushPolicy decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return defaults();
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 6 || !"v1".equals(parts[0])) {
            Diagnostics.warning("push", "policy_decode_invalid");
            return defaults();
        }
        try {
            EnumSet<DeliveryItem.Category> cats = EnumSet.noneOf(DeliveryItem.Category.class);
            if (!parts[1].isEmpty()) {
                for (String token : parts[1].split(",")) {
                    DeliveryItem.Category parsed = DeliveryItem.Category.fromWireKey(token.trim());
                    if (parsed != null) cats.add(parsed);
                }
            }
            boolean quiet = parseBit(parts[2]);
            int start = Integer.parseInt(parts[3]);
            int end = Integer.parseInt(parts[4]);
            boolean allowHigh = parseBit(parts[5]);
            return new PushPolicy(cats, quiet, start, end, allowHigh);
        } catch (IllegalArgumentException invalid) {
            Diagnostics.warning("push", "policy_decode_invalid");
            return defaults();
        }
    }

    private static boolean parseBit(String raw) {
        if ("1".equals(raw)) return true;
        if ("0".equals(raw)) return false;
        throw new IllegalArgumentException("Invalid boolean bit");
    }

    static String storageKey(String profileId) {
        if (profileId == null || profileId.trim().isEmpty()) {
            throw new IllegalArgumentException("Profile id required");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(("push-policy-v1\0" + profileId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            return "p_" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static PushPolicy load(Context context, String profileId) {
        if (context == null || profileId == null || profileId.trim().isEmpty()) return defaults();
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return loadFromPrefs(prefs, profileId);
    }

    static PushPolicy loadFromPrefs(SharedPreferences prefs, String profileId) {
        if (prefs == null || profileId == null || profileId.trim().isEmpty()) return defaults();
        synchronized (prefs) {
            try {
                return decode(prefs.getString(storageKey(profileId), null));
            } catch (ClassCastException corrupt) {
                Diagnostics.warning("push", "policy_read_corrupt");
                return defaults();
            }
        }
    }

    public static void save(Context context, String profileId, PushPolicy policy) {
        if (context == null || profileId == null || profileId.trim().isEmpty() || policy == null) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        saveToPrefs(prefs, profileId, policy);
    }

    static boolean saveToPrefs(SharedPreferences prefs, String profileId, PushPolicy policy) {
        if (prefs == null || profileId == null || profileId.trim().isEmpty() || policy == null) return false;
        synchronized (prefs) {
            try {
                PreferenceTransaction.commit(prefs,
                        Collections.singletonMap(storageKey(profileId), policy.encode()));
                Diagnostics.event("push", "policy_updated");
                return true;
            } catch (IllegalStateException failed) {
                Diagnostics.warning("push", "policy_save_commit_failed");
                return false;
            }
        }
    }

    public static void clear(Context context, String profileId) {
        if (context == null || profileId == null || profileId.trim().isEmpty()) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, String> removals = new HashMap<>();
        removals.put(storageKey(profileId), null);
        synchronized (prefs) {
            try {
                PreferenceTransaction.commit(prefs, removals);
            } catch (IllegalStateException fallback) {
                Diagnostics.warning("push", "policy_clear_commit_failed");
            }
        }
        Diagnostics.debug("push", "policy_cleared");
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PushPolicy o)) return false;
        return quietHoursEnabled == o.quietHoursEnabled
                && quietStartMinute == o.quietStartMinute
                && quietEndMinute == o.quietEndMinute
                && allowHighUrgencyDuringQuietHours == o.allowHighUrgencyDuringQuietHours
                && enabledCategories.equals(o.enabledCategories);
    }

    @Override public int hashCode() {
        return Objects.hash(enabledCategories, quietHoursEnabled, quietStartMinute,
                quietEndMinute, allowHighUrgencyDuringQuietHours);
    }
}
