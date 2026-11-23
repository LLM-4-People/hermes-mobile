package org.llm4people.hermes.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict, bounded pure-Java JSON parser so GitHub release metadata parses identically
 * on Android devices and host JVM unit tests with zero external dependencies.
 */
final class UpdateJson {
    private static final int MAX_DEPTH = 16;

    private final String src;
    private final int len;
    private int pos;

    private UpdateJson(String src) {
        this.src = src;
        this.len = src.length();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String raw) throws IOException {
        if (raw == null) throw new IOException("Empty JSON");
        UpdateJson parser = new UpdateJson(raw);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.pos != parser.len || !(value instanceof Map<?, ?>)) {
            throw new IOException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    private Object readValue(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("JSON nesting too deep");
        skipWhitespace();
        if (pos >= len) throw new IOException("Unexpected end of JSON");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject(depth + 1);
            case '[' -> readArray(depth + 1);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
                throw new IOException("Unexpected JSON token");
            }
        };
    }

    private Map<String, Object> readObject(int depth) throws IOException {
        pos++; // '{'
        skipWhitespace();
        Map<String, Object> map = new LinkedHashMap<>();
        if (pos < len && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= len || src.charAt(pos) != '"') throw new IOException("Expected object key");
            String key = readString();
            skipWhitespace();
            if (pos >= len || src.charAt(pos) != ':') throw new IOException("Expected ':'");
            pos++;
            map.put(key, readValue(depth));
            skipWhitespace();
            if (pos >= len) throw new IOException("Unterminated object");
            char next = src.charAt(pos++);
            if (next == '}') return map;
            if (next != ',') throw new IOException("Expected ',' or '}'");
        }
    }

    private List<Object> readArray(int depth) throws IOException {
        pos++; // '['
        skipWhitespace();
        List<Object> list = new ArrayList<>();
        if (pos < len && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue(depth));
            skipWhitespace();
            if (pos >= len) throw new IOException("Unterminated array");
            char next = src.charAt(pos++);
            if (next == ']') return list;
            if (next != ',') throw new IOException("Expected ',' or ']'");
        }
    }

    private String readString() throws IOException {
        pos++; // '"'
        StringBuilder out = new StringBuilder();
        while (pos < len) {
            char c = src.charAt(pos++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw new IOException("Unescaped control character in string");
            if (c == '\\') {
                if (pos >= len) throw new IOException("Unterminated escape");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> out.append(esc);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > len) throw new IOException("Truncated unicode escape");
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException badHex) {
                            throw new IOException("Invalid unicode escape", badHex);
                        }
                    }
                    default -> throw new IOException("Invalid escape character");
                }
            } else {
                out.append(c);
            }
        }
        throw new IOException("Unterminated string");
    }

    private Object readLiteral(String literal, Object value) throws IOException {
        if (!src.startsWith(literal, pos)) throw new IOException("Invalid literal");
        pos += literal.length();
        return value;
    }

    private Number readNumber() throws IOException {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        if (pos >= len || src.charAt(pos) < '0' || src.charAt(pos) > '9') {
            throw new IOException("Invalid number");
        }
        if (src.charAt(pos) == '0') {
            pos++;
        } else {
            while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        }
        boolean decimal = false;
        if (pos < len && src.charAt(pos) == '.') {
            decimal = true;
            pos++;
            int fracStart = pos;
            while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            if (pos == fracStart) throw new IOException("Invalid fractional number");
        }
        if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            decimal = true;
            pos++;
            if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            int expStart = pos;
            while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            if (pos == expStart) throw new IOException("Invalid exponent");
        }
        String token = src.substring(start, pos);
        try {
            return decimal ? Double.valueOf(token) : Long.valueOf(token);
        } catch (NumberFormatException invalid) {
            throw new IOException("Number out of range", invalid);
        }
    }

    private void skipWhitespace() {
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
            else break;
        }
    }
}
