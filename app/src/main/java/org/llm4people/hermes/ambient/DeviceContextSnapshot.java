package org.llm4people.hermes.ambient;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import org.llm4people.hermes.Diagnostics;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Opt-in, zero-runtime-permission local device context snapshot.
 * Reports battery %, charging state, network type, local ISO date/time + timezone,
 * phone/tablet form factor, and resolved language tag — no PII, no device IDs, no coordinates.
 */
public final class DeviceContextSnapshot {
    public static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;

    public enum NetworkKind {
        WIFI,
        CELLULAR,
        ETHERNET,
        OFFLINE,
        UNKNOWN
    }

    public enum FormFactor {
        PHONE,
        TABLET
    }

    public record Snapshot(
            int batteryPercent,
            boolean charging,
            NetworkKind network,
            String localIsoDateTime,
            String timezoneId,
            FormFactor formFactor,
            String languageTag) {
        public Snapshot {
            batteryPercent = batteryPercent < 0 ? -1 : Math.min(100, batteryPercent);
            network = network == null ? NetworkKind.UNKNOWN : network;
            localIsoDateTime = localIsoDateTime == null ? "" : localIsoDateTime;
            timezoneId = timezoneId == null ? "" : timezoneId;
            formFactor = formFactor == null ? FormFactor.PHONE : formFactor;
            languageTag = languageTag == null || languageTag.trim().isEmpty() ? "fr" : languageTag.trim();
        }

        public String toSanitizedJson() {
            return "{"
                    + "\"battery_percent\":" + batteryPercent
                    + ",\"charging\":" + charging
                    + ",\"network\":" + AmbientJson.quote(network.name().toLowerCase(Locale.ROOT))
                    + ",\"local_datetime\":" + AmbientJson.quote(localIsoDateTime)
                    + ",\"timezone\":" + AmbientJson.quote(timezoneId)
                    + ",\"form_factor\":" + AmbientJson.quote(formFactor.name().toLowerCase(Locale.ROOT))
                    + ",\"language\":" + AmbientJson.quote(languageTag)
                    + "}";
        }
    }

    private DeviceContextSnapshot() {}

    /** Deterministic pure builder for host JVM tests and production snapshot assembly. */
    public static Snapshot build(
            int batteryPercent,
            boolean charging,
            NetworkKind network,
            Clock clock,
            ZoneId zone,
            int smallestScreenWidthDp,
            Locale locale) {
        ZoneId effectiveZone = zone == null ? ZoneId.systemDefault() : zone;
        Clock effectiveClock = clock == null ? Clock.system(effectiveZone) : clock;
        ZonedDateTime now = ZonedDateTime.ofInstant(effectiveClock.instant(), effectiveZone)
                .withNano(0);
        FormFactor factor = smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP
                ? FormFactor.TABLET
                : FormFactor.PHONE;
        String lang = resolveLanguageTag(locale);
        return new Snapshot(
                batteryPercent,
                charging,
                network,
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                effectiveZone.getId(),
                factor,
                lang);
    }

    /** Capture current local snapshot only when opted in for the active profile. */
    public static Snapshot capture(Context context, boolean optInEnabled) {
        if (!optInEnabled || context == null) return null;
        int batteryPercent = -1;
        boolean charging = false;
        try {
            Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    batteryPercent = Math.round((level * 100f) / scale);
                }
                int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "battery_read_failed", ex);
        }

        NetworkKind network = readNetworkKind(context);
        Configuration cfg = context.getResources() == null ? null : context.getResources().getConfiguration();
        int swDp = cfg == null ? 0 : cfg.smallestScreenWidthDp;
        Locale locale = (cfg != null && !cfg.getLocales().isEmpty())
                ? cfg.getLocales().get(0)
                : Locale.getDefault();
        return build(batteryPercent, charging, network, Clock.systemDefaultZone(), ZoneId.systemDefault(), swDp, locale);
    }

    static String resolveLanguageTag(Locale locale) {
        if (locale == null) return "fr";
        String lang = locale.getLanguage();
        if (lang == null || lang.isEmpty()) return "fr";
        return lang.toLowerCase(Locale.ROOT);
    }

    private static NetworkKind readNetworkKind(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            return NetworkKind.UNKNOWN;
        }
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return NetworkKind.UNKNOWN;
            Network active = cm.getActiveNetwork();
            if (active == null) return NetworkKind.OFFLINE;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null) return NetworkKind.OFFLINE;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetworkKind.WIFI;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return NetworkKind.CELLULAR;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetworkKind.ETHERNET;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ? NetworkKind.UNKNOWN
                    : NetworkKind.OFFLINE;
        } catch (SecurityException ex) {
            Diagnostics.warning("ambient", "network_read_denied", ex);
            return NetworkKind.UNKNOWN;
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "network_read_failed", ex);
            return NetworkKind.UNKNOWN;
        }
    }
}
