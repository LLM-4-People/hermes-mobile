package org.llm4people.hermes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Shared SHA-256 digest and lowercase hexadecimal encoding helpers. */
public final class Digests {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Digests() {}

    /** Returns a fresh SHA-256 {@link MessageDigest} instance. */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Encodes raw bytes as lowercase hexadecimal. */
    public static String toHex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** Computes the lowercase 64-char SHA-256 hex digest of {@code bytes}. */
    public static String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return toHex(sha256().digest(bytes));
    }

    /** Computes the lowercase 64-char SHA-256 hex digest of UTF-8 {@code text}. */
    public static String sha256Hex(String text) {
        Objects.requireNonNull(text, "text");
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses a strict unsigned ASCII hexadecimal slice {@code s[start .. start+length)} (1..8 chars).
     * Rejects signs ({@code +}/{@code -}), whitespace, and non-ASCII digits fail-closed.
     */
    public static int parseUnsignedHex(String s, int start, int length) {
        if (s == null || length <= 0 || length > 8 || start < 0 || start > s.length() - length) {
            throw new IllegalArgumentException("Invalid hex slice");
        }
        int value = 0;
        for (int i = 0; i < length; i++) {
            char c = s.charAt(start + i);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                throw new IllegalArgumentException("Invalid hex digit");
            }
            value = (value << 4) | digit;
        }
        return value;
    }
}
