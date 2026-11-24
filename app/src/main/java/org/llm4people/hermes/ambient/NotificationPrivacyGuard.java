package org.llm4people.hermes.ambient;

import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.SecretPatterns;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed privacy guard at the lowest choking point for third-party notifications.
 * Hard-blocks Hermes itself, OTP/2FA verification codes, banking/finance/password-manager apps,
 * secret lockscreen visibility, and ongoing system noise before any content enters memory.
 */
public final class NotificationPrivacyGuard {
    public static final String SELF_PACKAGE = "org.llm4people.hermes";
    public static final int VISIBILITY_SECRET = -1;
    public static final int MAX_BUFFER_SIZE = 40;
    public static final int MAX_LABEL_CHARS = 80;
    public static final int MAX_SENDER_CHARS = 120;
    public static final int MAX_TITLE_CHARS = 160;
    public static final int MAX_TEXT_CHARS = 500;

    public enum Decision {
        ALLOW,
        BLOCK_EMPTY,
        BLOCK_SELF,
        BLOCK_SENSITIVE_PACKAGE,
        BLOCK_PRIVATE_VISIBILITY,
        BLOCK_ONGOING_OR_SYSTEM,
        BLOCK_NOT_ALLOWLISTED,
        BLOCK_OTP_OR_SECRET
    }

    /** Pure data descriptor evaluated before accepting any notification into memory. */
    public record Candidate(
            String packageName,
            String title,
            String text,
            int visibility,
            boolean ongoing,
            boolean foregroundService,
            boolean groupSummary,
            String category) {}

    private static final Set<String> BLOCKED_SYSTEM_PACKAGES = Set.of(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.android.providers.downloads",
            "com.google.android.gms",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
    );

    private static final Set<String> BLOCKED_CATEGORIES = Set.of(
            "sys", "service", "progress", "transport", "alarm", "call", "navigation", "status"
    );

    /** Substrings identifying password managers, authenticators, banking, finance, and crypto wallets. */
    private static final String[] SENSITIVE_PACKAGE_TOKENS = {
            // Password managers & 2FA authenticators
            "1password", "bitwarden", "keepass", "dashlane", "lastpass", "enpass",
            "proton.pass", "protonpass", "nordpass", "roboform", "keepersecurity",
            "authenticator", "authy", "duo.mobile", "yubico", "freeotp", "aegis",
            "ente.auth", "totp", "passkey", "password", "keychain", "vault",
            // Banking, finance, payments & wallets
            "bank", "banque", "desjardins", "rbc.", ".rbc", "td.com", "bmo.", ".bmo",
            "scotiabank", "cibc", "tangerine", "eqbank", "nationalbank", "bnc.ca",
            "chase", "wellsfargo", "capitalone", "citibank", "amex", "americanexpress",
            "revolut", "wise.", "transferwise", "paypal", "venmo", "cashapp",
            "squareup.cash", "interac", "wealthsimple", "questrade", "robinhood",
            "fidelity", "schwab", "vanguard", "monzo", "starling", "n26.",
            "boursorama", "creditagricole", "societegenerale", "bnpparibas", "hsbc",
            "stripe", "wallet", "coinbase", "kraken", "binance", "metamask",
            "ledger.", "trezor", "exodus", "trustwallet", "phantom.app"
    };

    private static final Pattern OTP_KEYWORDS = Pattern.compile(
            "(?i)(?:\\botp\\b|\\b2fa\\b|\\bmfa\\b|\\btotp\\b|"
                    + "verification\\s+code|security\\s+code|login\\s+code|sign[-\\s]?in\\s+code|"
                    + "one[-\\s]?time\\s+(?:code|pass(?:word|code))|passcode|auth(?:entication)?\\s+code|"
                    + "confirm(?:ation)?\\s+code|temporary\\s+password|do\\s+not\\s+share|"
                    + "never\\s+share\\s+(?:this|your)\\s+code|seed\\s+phrase|recovery\\s+phrase|"
                    + "code\\s+de\\s+v[ée]rification|code\\s+de\\s+s[ée]curit[ée]|"
                    + "code\\s+[àa]\\s+usage\\s+unique|code\\s+de\\s+connexion|"
                    + "code\\s+d['’]authentification|code\\s+temporaire|mot\\s+de\\s+passe\\s+temporaire|"
                    + "ne\\s+(?:le\\s+)?partagez\\s+pas|phrase\\s+de\\s+r[ée]cup[ée]ration)");

    private static final Pattern GOOGLE_OTP = Pattern.compile("(?:^|[^A-Za-z0-9])G-\\d{5,8}(?:$|[^A-Za-z0-9])");

    private static final Pattern SECRET_LABEL = Pattern.compile(
            "(?i)(?:\\bcvv\\b|\\bcvc\\b|card\\s+pin|nip\\s+de\\s+carte|private\\s+key|cl[ée]\\s+priv[ée]e)");

    private NotificationPrivacyGuard() {}

    /** True when the package is Hermes itself, a system/noise app, or a banking/password/2FA app. */
    public static boolean isHardBlockedPackage(String packageName) {
        if (packageName == null) return true;
        String pkg = packageName.trim().toLowerCase(Locale.ROOT);
        if (pkg.isEmpty()) return true;
        if (pkg.equals(SELF_PACKAGE) || pkg.startsWith(SELF_PACKAGE + ".")) return true;
        if (BLOCKED_SYSTEM_PACKAGES.contains(pkg)) return true;
        for (String token : SENSITIVE_PACKAGE_TOKENS) {
            if (pkg.contains(token)) return true;
        }
        return false;
    }

    /** True when title or text looks like an OTP/2FA code, recovery phrase, temporary password, or API/JWT secret. */
    public static boolean containsOtpOrSecret(String title, String text) {
        if (SecretPatterns.containsCredential(title) || SecretPatterns.containsCredential(text)) {
            return true;
        }
        String combined = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).trim();
        if (combined.isEmpty()) return false;
        if (SecretPatterns.containsCredential(combined)) return true;
        if (matchesOtpOrLabel(combined)) return true;
        String collapsed = PlainTextSanitizer.collapseBounded(combined, combined.length());
        return !collapsed.isEmpty() && matchesOtpOrLabel(collapsed);
    }

    private static boolean matchesOtpOrLabel(String text) {
        if (GOOGLE_OTP.matcher(text).find()) return true;
        if (SECRET_LABEL.matcher(text).find()) return true;
        // Every phrase in OTP_KEYWORDS is an explicit OTP/2FA/temporary-password/do-not-share indicator;
        // hard-block immediately even when the secret/code is alphanumeric (no numeric CODE_TOKEN required).
        return OTP_KEYWORDS.matcher(text).find();
    }

    /** Evaluate a notification candidate against hard blocks and the active profile's allowlist. */
    public static Decision evaluate(Candidate candidate, Set<String> allowlistedPackages) {
        if (candidate == null || candidate.packageName() == null || candidate.packageName().trim().isEmpty()) {
            return Decision.BLOCK_EMPTY;
        }
        String pkg = candidate.packageName().trim().toLowerCase(Locale.ROOT);
        if (pkg.equals(SELF_PACKAGE) || pkg.startsWith(SELF_PACKAGE + ".")) {
            return Decision.BLOCK_SELF;
        }
        if (BLOCKED_SYSTEM_PACKAGES.contains(pkg)) {
            return Decision.BLOCK_ONGOING_OR_SYSTEM;
        }
        if (isHardBlockedPackage(pkg)) {
            return Decision.BLOCK_SENSITIVE_PACKAGE;
        }
        if (candidate.visibility() == VISIBILITY_SECRET) {
            return Decision.BLOCK_PRIVATE_VISIBILITY;
        }
        if (candidate.ongoing() || candidate.foregroundService() || candidate.groupSummary()) {
            return Decision.BLOCK_ONGOING_OR_SYSTEM;
        }
        if (candidate.category() != null
                && BLOCKED_CATEGORIES.contains(candidate.category().trim().toLowerCase(Locale.ROOT))) {
            return Decision.BLOCK_ONGOING_OR_SYSTEM;
        }
        if (allowlistedPackages == null || !allowlistedPackages.contains(pkg)) {
            return Decision.BLOCK_NOT_ALLOWLISTED;
        }
        String cleanTitle = sanitizeText(candidate.title(), MAX_TITLE_CHARS);
        String cleanText = sanitizeText(candidate.text(), MAX_TEXT_CHARS);
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) {
            return Decision.BLOCK_EMPTY;
        }
        if (containsOtpOrSecret(candidate.title(), candidate.text())
                || containsOtpOrSecret(cleanTitle, cleanText)) {
            return Decision.BLOCK_OTP_OR_SECRET;
        }
        return Decision.ALLOW;
    }

    /** Strip control characters, collapse whitespace runs, and enforce a hard length cap. */
    public static String sanitizeText(String raw, int maxChars) {
        if (raw == null || maxChars <= 0) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxChars));
        boolean prevSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (out.length() > 0 && !prevSpace) {
                    out.append(' ');
                    prevSpace = true;
                }
            } else {
                out.append(c);
                prevSpace = false;
            }
            if (out.length() > maxChars) break;
        }
        String trimmed = out.toString().trim();
        if (trimmed.length() <= maxChars) return trimmed;
        if (maxChars == 1) return "…";
        return trimmed.substring(0, maxChars - 1).trim() + "…";
    }
}
