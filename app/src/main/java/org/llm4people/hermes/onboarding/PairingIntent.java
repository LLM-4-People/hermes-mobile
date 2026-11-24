package org.llm4people.hermes.onboarding;

import android.content.Intent;
import android.net.Uri;

import org.llm4people.hermes.Diagnostics;

/**
 * Extracts and validates incoming {@code hermes://pair?host=...&code=...} Android deep-link intents.
 * Fail-closed: only accepts {@link Intent#ACTION_VIEW} targeting {@code hermes://pair} with valid
 * HTTPS/loopback host and bounded high-entropy pairing code. Never logs secrets.
 */
public final class PairingIntent {
    private PairingIntent() {}

    /** Parse a pairing deep-link intent, returning {@code null} when absent or invalid. */
    public static PairingLinkParser.ParsedInput parse(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        if (data == null) return null;
        return parseUriString(data.toString());
    }

    /** Pure-Java entry point for host-JVM testing. */
    public static PairingLinkParser.ParsedInput parseUriString(String rawUri) {
        if (rawUri == null || !rawUri.regionMatches(true, 0, "hermes://pair", 0, 13)) {
            return null;
        }
        PairingLinkParser.ParsedInput parsed = PairingLinkParser.parseOrNull(rawUri);
        if (parsed == null || parsed.kind() != PairingLinkParser.Kind.PAIRING_TICKET) {
            Diagnostics.warning("onboarding", "deep_link_rejected");
            return null;
        }
        Diagnostics.event("onboarding", "deep_link_received");
        return parsed;
    }
}
