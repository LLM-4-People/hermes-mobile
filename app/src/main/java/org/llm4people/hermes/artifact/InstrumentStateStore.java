package org.llm4people.hermes.artifact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.profile.Account;

/**
 * Encrypted, profile-scoped persistent local tool state so Hermes-built checklists, trackers,
 * toggles, sliders, and filter dashboards remember state across restarts without leaking across profiles.
 */
public final class InstrumentStateStore {
    public interface BackingStore {
        String read(String key);
        void write(String key, String value);
        void remove(String key);
    }

    public static final int MAX_ENTRIES = 64;
    public static final int MAX_STRING_LEN = 512;
    public static final int MAX_LIST_ITEMS = 16;
    public static final int MAX_PAYLOAD_CHARS = 16 * 1024;
    private static final String PREFIX = "instrument.state.v1.";

    private final BackingStore store;

    public InstrumentStateStore(BackingStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public static InstrumentStateStore forAccount(Account account) {
        Objects.requireNonNull(account);
        return new InstrumentStateStore(new BackingStore() {
            @Override public String read(String key) { return account.read(key); }
            @Override public void write(String key, String value) { account.write(key, value); }
            @Override public void remove(String key) { account.remove(key); }
        });
    }

    public static String storageKey(String scope, String surfaceId) throws IOException {
        String safeScope = validSegment(scope);
        String safeSurface = surfaceId == null ? "" : surfaceId.startsWith("page/") ? surfaceId.substring(5) : surfaceId;
        safeSurface = validSegment(safeSurface);
        return PREFIX + safeScope + "." + safeSurface;
    }

    private static String validSegment(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.length() > 64) throw new IOException("Invalid state segment");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) throw new IOException("Disallowed character in state segment");
        }
        return raw;
    }

    public Map<String, Object> load(String scope, String surfaceId) {
        try {
            String key = storageKey(scope, surfaceId);
            String raw = store.read(key);
            if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
            return decode(raw);
        } catch (IOException | IllegalStateException e) {
            warnUnreadable();
            return new LinkedHashMap<>();
        }
    }

    public void save(String scope, String surfaceId, Map<String, Object> values) throws IOException {
        String key = storageKey(scope, surfaceId);
        if (values == null || values.isEmpty()) {
            store.remove(key);
            return;
        }
        if (values.size() > MAX_ENTRIES) throw new IOException("Too many state entries");
        String encoded = encode(values);
        if (encoded.length() > MAX_PAYLOAD_CHARS) throw new IOException("State payload exceeded limit");
        store.write(key, encoded);
    }

    public void putBoolean(String scope, String surfaceId, String id, boolean value) throws IOException {
        putValue(scope, surfaceId, id, Boolean.valueOf(value));
    }

    public void putNumber(String scope, String surfaceId, String id, double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("Non-finite number");
        putValue(scope, surfaceId, id, Double.valueOf(value));
    }

    public void putString(String scope, String surfaceId, String id, String value) throws IOException {
        if (value == null || value.length() > MAX_STRING_LEN) throw new IOException("Invalid string value");
        putValue(scope, surfaceId, id, value);
    }

    public void putStringList(String scope, String surfaceId, String id, List<String> values) throws IOException {
        if (values == null || values.size() > MAX_LIST_ITEMS) throw new IOException("Invalid list size");
        List<String> copy = new ArrayList<>(values.size());
        for (String item : values) {
            if (item == null || item.length() > MAX_STRING_LEN) throw new IOException("Invalid list item");
            copy.add(item);
        }
        putValue(scope, surfaceId, id, Collections.unmodifiableList(copy));
    }

    private void putValue(String scope, String surfaceId, String id, Object value) throws IOException {
        if (!DraftForm.validId(id)) throw new IOException("Invalid state field id");
        Map<String, Object> current = load(scope, surfaceId);
        current.put(id, value);
        save(scope, surfaceId, current);
    }

    public boolean getBoolean(String scope, String surfaceId, String id, boolean fallback) {
        Object val = load(scope, surfaceId).get(id);
        return val instanceof Boolean b ? b : fallback;
    }

    public double getNumber(String scope, String surfaceId, String id, double fallback) {
        Object val = load(scope, surfaceId).get(id);
        return val instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : fallback;
    }

    public String getString(String scope, String surfaceId, String id, String fallback) {
        Object val = load(scope, surfaceId).get(id);
        return val instanceof String s ? s : fallback;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String scope, String surfaceId, String id, List<String> fallback) {
        Object val = load(scope, surfaceId).get(id);
        return val instanceof List<?> list ? (List<String>) list : fallback;
    }

    public void clear(String scope, String surfaceId) throws IOException {
        store.remove(storageKey(scope, surfaceId));
    }

    /** Compact, unambiguous, pure-Java typed encoding (B:, N:, S:, L:). */
    static String encode(Map<String, Object> values) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String id = entry.getKey();
            if (!DraftForm.validId(id)) throw new IOException("Invalid field id");
            Object val = entry.getValue();
            if (out.length() > 0) out.append('\n');
            out.append(id).append('=');
            if (val instanceof Boolean b) {
                out.append("B:").append(b ? "1" : "0");
            } else if (val instanceof Number n) {
                double d = n.doubleValue();
                if (!Double.isFinite(d)) throw new IOException("Non-finite number");
                out.append("N:").append(d);
            } else if (val instanceof String s) {
                if (s.length() > MAX_STRING_LEN) throw new IOException("String too long");
                out.append("S:").append(escape(s));
            } else if (val instanceof List<?> list) {
                if (list.size() > MAX_LIST_ITEMS) throw new IOException("List too large");
                out.append("L:");
                for (int i = 0; i < list.size(); i++) {
                    if (!(list.get(i) instanceof String item) || item.length() > MAX_STRING_LEN) {
                        throw new IOException("Invalid list element");
                    }
                    if (i > 0) out.append(',');
                    out.append(escape(item));
                }
            } else {
                throw new IOException("Unsupported value type");
            }
        }
        return out.toString();
    }

    static Map<String, Object> decode(String raw) throws IOException {
        if (raw.length() > MAX_PAYLOAD_CHARS) throw new IOException("Payload too large");
        Map<String, Object> result = new LinkedHashMap<>();
        String[] lines = raw.split("\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0 || eq + 2 >= line.length() || line.charAt(eq + 2) != ':') {
                throw new IOException("Malformed state line");
            }
            String id = line.substring(0, eq);
            if (!DraftForm.validId(id) || result.size() >= MAX_ENTRIES) throw new IOException("Invalid state entry");
            char kind = line.charAt(eq + 1);
            String body = line.substring(eq + 3);
            switch (kind) {
                case 'B':
                    if (!"0".equals(body) && !"1".equals(body)) throw new IOException("Invalid boolean");
                    result.put(id, "1".equals(body));
                    break;
                case 'N':
                    try {
                        double d = Double.parseDouble(body);
                        if (!Double.isFinite(d)) throw new IOException("Non-finite number");
                        result.put(id, d);
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid number", e);
                    }
                    break;
                case 'S':
                    String s = unescape(body);
                    if (s.length() > MAX_STRING_LEN) throw new IOException("String too long");
                    result.put(id, s);
                    break;
                case 'L':
                    List<String> items = new ArrayList<>();
                    if (!body.isEmpty()) {
                        String[] parts = body.split(",", -1);
                        if (parts.length > MAX_LIST_ITEMS) throw new IOException("List too large");
                        for (String part : parts) {
                            String item = unescape(part);
                            if (item.length() > MAX_STRING_LEN) throw new IOException("List item too long");
                            items.add(item);
                        }
                    }
                    result.put(id, Collections.unmodifiableList(items));
                    break;
                default:
                    throw new IOException("Unknown value type");
            }
        }
        return result;
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case ',' -> sb.append("\\c");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String text) throws IOException {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= text.length()) throw new IOException("Dangling escape");
            switch (text.charAt(i)) {
                case '\\' -> sb.append('\\');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 'c' -> sb.append(',');
                default -> throw new IOException("Unknown escape");
            }
        }
        return sb.toString();
    }

    private static void warnUnreadable() {
        Diagnostics.warning("instrument_state", "unreadable");
    }
}
