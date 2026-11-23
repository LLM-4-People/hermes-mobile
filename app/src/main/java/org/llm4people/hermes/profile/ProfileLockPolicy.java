package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.SharedPreferences;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.os.BiometricVault;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;

/**
 * Single Source of Truth for per-profile lock modes ({@link Mode#BIOMETRIC}, {@link Mode#PIN},
 * {@link Mode#PASSPHRASE}), credential validation, setup form readiness evaluation, and
 * 256-bit vault secret generation.
 */
public final class ProfileLockPolicy {
    public static final int MIN_PIN_DIGITS = 6;
    public static final int MAX_PIN_DIGITS = 12;
    public static final int MIN_PASSPHRASE_CHARS = ProfileGate.MIN_PASSPHRASE;
    public static final int GENERATED_SECRET_BYTES = 32;

    private static final String PREFS = "hermes.profile.lock_mode";
    private static final String PREFIX = "mode.";
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Mode {
        BIOMETRIC("biometric", R.string.lock_mode_biometric),
        PIN("pin", R.string.lock_mode_pin),
        PASSPHRASE("passphrase", R.string.lock_mode_passphrase);

        public final String key;
        public final int labelRes;

        Mode(String key, int labelRes) {
            this.key = key;
            this.labelRes = labelRes;
        }

        public static Mode fromKey(String raw) {
            if (raw == null || raw.isEmpty()) return PASSPHRASE;
            for (Mode value : values()) {
                if (value.key.equals(raw)) return value;
            }
            return PASSPHRASE;
        }
    }

    public enum CredentialState {
        EMPTY,
        INVALID_FORMAT,
        TOO_SHORT,
        AWAITING_CONFIRM,
        MISMATCH,
        READY
    }

    public enum SetupBlocker {
        READY,
        NAME_MISSING,
        NAME_CONTROL,
        NAME_DUPLICATE,
        SERVER_MISSING,
        SERVER_INVALID,
        SLUG_INVALID,
        SLUG_CONFLICT,
        KEY_MISSING,
        KEY_INVALID,
        BIOMETRIC_UNAVAILABLE,
        PIN_EMPTY,
        PIN_INVALID,
        PIN_TOO_SHORT,
        PIN_CONFIRM_NEEDED,
        PIN_MISMATCH,
        PASSPHRASE_EMPTY,
        PASSPHRASE_TOO_SHORT,
        PASSPHRASE_CONFIRM_NEEDED,
        PASSPHRASE_MISMATCH
    }

    private ProfileLockPolicy() {}

    /** Smart default lock mode for new profile setup: Biometric/Phone lock when available, else Passphrase. */
    public static Mode defaultModeForSetup(BiometricVault.Availability availability) {
        return (availability != null && availability != BiometricVault.Availability.UNAVAILABLE)
                ? Mode.BIOMETRIC : Mode.PASSPHRASE;
    }

    /** Returns true iff {@code chars} is non-empty and consists solely of ASCII decimal digits {@code 0-9}. */
    public static boolean isDigitsOnly(CharSequence chars) {
        if (chars == null || chars.length() == 0) return false;
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Pure in-place PIN readiness classifier (zero String heap copies of sensitive digits). */
    public static CredentialState evaluatePin(CharSequence primary, CharSequence confirm) {
        int len = primary == null ? 0 : primary.length();
        int confLen = confirm == null ? 0 : confirm.length();
        if (len == 0 && confLen == 0) return CredentialState.EMPTY;
        if (len > MAX_PIN_DIGITS || (len > 0 && !isDigitsOnly(primary))
                || (confLen > 0 && (!isDigitsOnly(confirm) || confLen > MAX_PIN_DIGITS))) {
            return CredentialState.INVALID_FORMAT;
        }
        if (len < MIN_PIN_DIGITS) return CredentialState.TOO_SHORT;
        if (confLen == 0) return CredentialState.AWAITING_CONFIRM;
        return ProfileGate.sameChars(primary, confirm) ? CredentialState.READY : CredentialState.MISMATCH;
    }

    /** Pure in-place passphrase readiness classifier (zero String heap copies). */
    public static CredentialState evaluatePassphrase(CharSequence primary, CharSequence confirm) {
        int len = primary == null ? 0 : primary.length();
        int confLen = confirm == null ? 0 : confirm.length();
        if (len == 0 && confLen == 0) return CredentialState.EMPTY;
        if (len < MIN_PASSPHRASE_CHARS) return CredentialState.TOO_SHORT;
        if (confLen == 0) return CredentialState.AWAITING_CONFIRM;
        return ProfileGate.sameChars(primary, confirm) ? CredentialState.READY : CredentialState.MISMATCH;
    }

    /**
     * Pure-Java setup form guard evaluating every mandatory field in top-to-bottom order.
     * Returns {@link SetupBlocker#READY} iff Save should be enabled.
     */
    public static SetupBlocker evaluateSetupForm(
            String rawLabel,
            Collection<String> existingLabels,
            String rawServer,
            String rawSlug,
            String rawKey,
            boolean hasMatchingStagedPairing,
            Mode lockMode,
            boolean biometricAvailable,
            CharSequence pin,
            CharSequence pinConfirm,
            CharSequence passphrase,
            CharSequence passphraseConfirm) {
        String label = rawLabel == null ? "" : rawLabel.trim();
        if (label.isEmpty()) return SetupBlocker.NAME_MISSING;
        if (label.chars().anyMatch(Character::isISOControl)) return SetupBlocker.NAME_CONTROL;
        if (existingLabels != null) {
            for (String existing : existingLabels) {
                if (existing != null && existing.equalsIgnoreCase(label)) {
                    return SetupBlocker.NAME_DUPLICATE;
                }
            }
        }

        String server = rawServer == null ? "" : rawServer.trim();
        if (server.isEmpty()) return SetupBlocker.SERVER_MISSING;
        try {
            GatewayAddress.normalize(server);
        } catch (IllegalArgumentException invalid) {
            return SetupBlocker.SERVER_INVALID;
        }
        String slug = rawSlug == null ? "" : rawSlug.trim();
        try {
            GatewayAddress.normalizeProfileSlug(slug);
        } catch (IllegalArgumentException invalid) {
            return SetupBlocker.SLUG_INVALID;
        }
        try {
            GatewayAddress.normalize(server, slug);
        } catch (IllegalArgumentException conflict) {
            return SetupBlocker.SLUG_CONFLICT;
        }

        if (!hasMatchingStagedPairing) {
            String key = rawKey == null ? "" : rawKey.trim();
            if (key.isEmpty()) return SetupBlocker.KEY_MISSING;
            if (!key.chars().allMatch(c -> c >= 33 && c <= 126)) return SetupBlocker.KEY_INVALID;
        }

        Mode effectiveMode = lockMode == null ? Mode.PASSPHRASE : lockMode;
        return switch (effectiveMode) {
            case BIOMETRIC -> biometricAvailable ? SetupBlocker.READY : SetupBlocker.BIOMETRIC_UNAVAILABLE;
            case PIN -> switch (evaluatePin(pin, pinConfirm)) {
                case EMPTY -> SetupBlocker.PIN_EMPTY;
                case INVALID_FORMAT -> SetupBlocker.PIN_INVALID;
                case TOO_SHORT -> SetupBlocker.PIN_TOO_SHORT;
                case AWAITING_CONFIRM -> SetupBlocker.PIN_CONFIRM_NEEDED;
                case MISMATCH -> SetupBlocker.PIN_MISMATCH;
                case READY -> SetupBlocker.READY;
            };
            case PASSPHRASE -> switch (evaluatePassphrase(passphrase, passphraseConfirm)) {
                case EMPTY -> SetupBlocker.PASSPHRASE_EMPTY;
                case INVALID_FORMAT, TOO_SHORT -> SetupBlocker.PASSPHRASE_TOO_SHORT;
                case AWAITING_CONFIRM -> SetupBlocker.PASSPHRASE_CONFIRM_NEEDED;
                case MISMATCH -> SetupBlocker.PASSPHRASE_MISMATCH;
                case READY -> SetupBlocker.READY;
            };
        };
    }

    /** Generate a fresh 256-bit high-entropy vault secret for biometric-primary profile sealing. */
    public static char[] generateVaultSecret() {
        byte[] raw = new byte[GENERATED_SECRET_BYTES];
        RANDOM.nextBytes(raw);
        byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(raw);
        java.util.Arrays.fill(raw, (byte) 0);
        char[] out = new char[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            out[i] = (char) encoded[i];
        }
        java.util.Arrays.fill(encoded, (byte) 0);
        return out;
    }

    public static Mode mode(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return Mode.PASSPHRASE;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            try {
                return Mode.fromKey(prefs.getString(PREFIX + profileId, Mode.PASSPHRASE.key));
            } catch (ClassCastException corrupt) {
                Diagnostics.warning("security", "lock_mode_read_corrupt");
                return Mode.PASSPHRASE;
            }
        }
    }

    public static void setMode(Context context, String profileId, Mode mode) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        Mode chosen = mode == null ? Mode.PASSPHRASE : mode;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            prefs.edit().putString(PREFIX + profileId, chosen.key).apply();
        }
        Diagnostics.event("security", "lock_mode_" + chosen.key);
    }

    public static void forget(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            try {
                PreferenceTransaction.commit(prefs, Collections.singletonMap(PREFIX + profileId, null));
            } catch (IllegalStateException fallback) {
                Diagnostics.warning("security", "lock_mode_forget_failed");
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
