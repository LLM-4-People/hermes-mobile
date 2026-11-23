package org.llm4people.hermes.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict SemVer 2.0.0 release version comparator supporting optional {@code v} prefix,
 * pre-release precedence, and ignored build metadata. Never treats equal or older tags as updates.
 */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    private static final Pattern IDENTIFIER = Pattern.compile("[0-9A-Za-z-]+");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    public final int major;
    public final int minor;
    public final int patch;
    public final List<String> preRelease;
    public final String canonical;

    private ReleaseVersion(int major, int minor, int patch, List<String> preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = Collections.unmodifiableList(preRelease);
        StringBuilder sb = new StringBuilder();
        sb.append(major).append('.').append(minor).append('.').append(patch);
        if (!preRelease.isEmpty()) {
            sb.append('-').append(String.join(".", preRelease));
        }
        this.canonical = sb.toString();
    }

    /** Parse a release tag or version name (e.g. {@code v1.2.3}, {@code 1.2.3-rc.1+42}). */
    public static ReleaseVersion parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing version");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("Control character in version");
            }
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new IllegalArgumentException("Invalid version length");
        }
        if (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V') {
            trimmed = trimmed.substring(1);
        }
        int plus = trimmed.indexOf('+');
        if (plus >= 0) {
            String build = trimmed.substring(plus + 1);
            if (build.isEmpty() || !validDotSeparated(build, false)) {
                throw new IllegalArgumentException("Invalid build metadata");
            }
            trimmed = trimmed.substring(0, plus);
        }
        List<String> pre = Collections.emptyList();
        int dash = trimmed.indexOf('-');
        if (dash >= 0) {
            String prePart = trimmed.substring(dash + 1);
            if (prePart.isEmpty()) throw new IllegalArgumentException("Empty pre-release");
            pre = parsePreRelease(prePart);
            trimmed = trimmed.substring(0, dash);
        }
        String[] core = trimmed.split("\\.", -1);
        if (core.length < 2 || core.length > 3) {
            throw new IllegalArgumentException("Invalid version core");
        }
        int major = parsePart(core[0]);
        int minor = parsePart(core[1]);
        int patch = core.length == 3 ? parsePart(core[2]) : 0;
        return new ReleaseVersion(major, minor, patch, pre);
    }

    /** Parse a version or return null if malformed. */
    public static ReleaseVersion parseOrNull(String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    public boolean isNewerThan(ReleaseVersion other) {
        return other != null && compareTo(other) > 0;
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        Objects.requireNonNull(other, "other");
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(minor, other.minor);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(patch, other.patch);
        if (cmp != 0) return cmp;
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0;
        if (preRelease.isEmpty()) return 1;
        if (other.preRelease.isEmpty()) return -1;
        int len = Math.min(preRelease.size(), other.preRelease.size());
        for (int i = 0; i < len; i++) {
            cmp = comparePreIdentifier(preRelease.get(i), other.preRelease.get(i));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }

    private static int comparePreIdentifier(String a, String b) {
        boolean aNum = DIGITS.matcher(a).matches();
        boolean bNum = DIGITS.matcher(b).matches();
        if (aNum && bNum) {
            if (a.length() != b.length()) return Integer.compare(a.length(), b.length());
            return a.compareTo(b);
        }
        if (aNum) return -1;
        if (bNum) return 1;
        return a.compareTo(b);
    }

    private static int parsePart(String token) {
        if (token == null || token.isEmpty() || !DIGITS.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid numeric version component");
        }
        if (token.length() > 1 && token.charAt(0) == '0') {
            throw new IllegalArgumentException("Leading zero in version component");
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("Version component overflow", overflow);
        }
    }

    private static List<String> parsePreRelease(String prePart) {
        if (!validDotSeparated(prePart, true)) {
            throw new IllegalArgumentException("Invalid pre-release identifier");
        }
        String[] tokens = prePart.split("\\.", -1);
        List<String> list = new ArrayList<>(tokens.length);
        Collections.addAll(list, tokens);
        return list;
    }

    private static boolean validDotSeparated(String value, boolean forbidLeadingZeroNumeric) {
        String[] tokens = value.split("\\.", -1);
        for (String token : tokens) {
            if (token.isEmpty() || !IDENTIFIER.matcher(token).matches()) return false;
            if (forbidLeadingZeroNumeric && DIGITS.matcher(token).matches()
                    && token.length() > 1 && token.charAt(0) == '0') {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReleaseVersion that)) return false;
        return major == that.major
                && minor == that.minor
                && patch == that.patch
                && preRelease.equals(that.preRelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }

    @Override
    public String toString() {
        return canonical;
    }
}
