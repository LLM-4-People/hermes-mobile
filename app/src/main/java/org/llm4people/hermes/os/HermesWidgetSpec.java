package org.llm4people.hermes.os;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Declarative, privacy-enforced specification for a dynamically pinned Android Home Screen widget.
 * Only specs explicitly flagged {@code homeScreenSafe = true} expose custom text on the home screen;
 * all other specs automatically redact to generic French copy.
 */
public final class HermesWidgetSpec {
    public static final int MAX_ITEMS = 4;
    public static final int MAX_TITLE_CHARS = 48;
    public static final int MAX_SUBTITLE_CHARS = 80;
    public static final int MAX_LABEL_CHARS = 48;
    public static final int MAX_VALUE_CHARS = 32;
    public static final int MAX_DRAFT_CHARS = 240;

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{1,64}");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+[a-z0-9._\\-]{8,}|sk-[a-z0-9]{12,}|ghp_[a-z0-9]{12,}|eyJ[a-zA-Z0-9_\\-]{12,}|-----BEGIN )");

    public enum Template {
        METRIC("metric"),
        CHECKLIST("checklist"),
        QUICK_ACTIONS("quick_actions"),
        STATUS("status"),
        BRIEFING("briefing");

        public final String wire;

        Template(String wire) { this.wire = wire; }

        public static Template parse(String raw) {
            if (raw == null || raw.isEmpty()) return STATUS;
            for (Template value : values()) {
                if (value.wire.equals(raw)) return value;
            }
            return null;
        }
    }

    public record Item(String label, String value, String actionDraft) {}

    public final String widgetId;
    public final String title;
    public final String subtitle;
    public final Template template;
    public final List<Item> items;
    public final String accentHex;
    public final boolean homeScreenSafe;

    public HermesWidgetSpec(String widgetId, String title, String subtitle, Template template,
                            List<Item> items, String accentHex, boolean homeScreenSafe) {
        if (!validWidgetId(widgetId)) throw new IllegalArgumentException("Invalid widget_id");
        if (template == null) throw new IllegalArgumentException("Invalid widget template");
        this.widgetId = widgetId;
        String cleanTitle = sanitizeHomeText(title, MAX_TITLE_CHARS);
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("Missing widget title");
        this.title = cleanTitle;
        this.subtitle = sanitizeHomeText(subtitle, MAX_SUBTITLE_CHARS);
        this.template = template;
        List<Item> safeItems = new ArrayList<>();
        if (items != null) {
            for (Item item : items) {
                if (item == null || safeItems.size() >= MAX_ITEMS) break;
                String label = sanitizeHomeText(item.label(), MAX_LABEL_CHARS);
                if (label.isEmpty()) continue;
                String value = sanitizeHomeText(item.value(), MAX_VALUE_CHARS);
                String draft = sanitizeDraft(item.actionDraft());
                safeItems.add(new Item(label, value, draft));
            }
        }
        this.items = Collections.unmodifiableList(safeItems);
        this.accentHex = validAccentHex(accentHex) ? accentHex.toUpperCase(Locale.ROOT) : null;
        this.homeScreenSafe = homeScreenSafe;
    }

    public static boolean validWidgetId(String value) {
        return value != null && ID_PATTERN.matcher(value).matches();
    }

    public static boolean validAccentHex(String hex) {
        return hex != null && HEX_COLOR.matcher(hex).matches();
    }

    public static boolean containsSecretLikePattern(String text) {
        return text != null && SECRET_PATTERN.matcher(text).find();
    }

    /** Strips control/bidi chars, redacts secret-like patterns, and bounds length for home-screen safety. */
    public static String sanitizeHomeText(String raw, int maxChars) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || isBidiControl(c)) continue;
            out.append(c);
        }
        String trimmed = out.toString().trim();
        if (trimmed.isEmpty() || containsSecretLikePattern(trimmed)) return "";
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars).trim();
    }

    private static String sanitizeDraft(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((Character.isISOControl(c) && c != '\n') || isBidiControl(c)) continue;
            out.append(c);
        }
        String trimmed = out.toString().trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.length() <= MAX_DRAFT_CHARS ? trimmed : trimmed.substring(0, MAX_DRAFT_CHARS).trim();
    }

    private static boolean isBidiControl(char c) {
        return (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')
                || c == '\u200E' || c == '\u200F' || c == '\u061C';
    }

    /**
     * Returns a strictly home-screen-safe copy. If {@code homeScreenSafe} is false, all custom
     * titles, subtitles, and items are replaced with generic French privacy copy.
     */
    public HermesWidgetSpec redactedForHomeScreen() {
        if (homeScreenSafe) return this;
        return new HermesWidgetSpec(
                widgetId,
                "Hermes",
                "Contenu masqué · Ouvrez l’application",
                Template.STATUS,
                Collections.emptyList(),
                null,
                false);
    }

    public String formattedItemLine(Item item) {
        if (item == null) return "";
        String prefix = switch (template) {
            case CHECKLIST -> "☐ ";
            case QUICK_ACTIONS -> "› ";
            default -> "";
        };
        if (item.value() == null || item.value().isEmpty()) return prefix + item.label();
        return prefix + item.label() + " · " + item.value();
    }

    /** Pure Java lossless persistence codec usable identically on Android and host JVM tests. */
    public String encode() {
        StringBuilder out = new StringBuilder("v1\n");
        out.append(b64(widgetId)).append('\n');
        out.append(b64(title)).append('\n');
        out.append(b64(subtitle)).append('\n');
        out.append(template.wire).append('\n');
        out.append(homeScreenSafe ? '1' : '0').append('\n');
        out.append(b64(accentHex == null ? "" : accentHex)).append('\n');
        out.append(items.size());
        for (Item item : items) {
            out.append('\n')
                    .append(b64(item.label())).append('\t')
                    .append(b64(item.value())).append('\t')
                    .append(b64(item.actionDraft()));
        }
        return out.toString();
    }

    public static HermesWidgetSpec decode(String raw) {
        if (raw == null || !raw.startsWith("v1\n")) {
            throw new IllegalArgumentException("Unsupported widget encoding");
        }
        String[] lines = raw.split("\n", -1);
        if (lines.length < 8) throw new IllegalArgumentException("Truncated widget record");
        String id = unb64(lines[1]);
        String title = unb64(lines[2]);
        String subtitle = unb64(lines[3]);
        Template template = Template.parse(lines[4]);
        if (template == null) throw new IllegalArgumentException("Unsupported widget template");
        if (!"0".equals(lines[5]) && !"1".equals(lines[5])) {
            throw new IllegalArgumentException("Invalid safety flag");
        }
        boolean safe = "1".equals(lines[5]);
        String accent = unb64(lines[6]);
        int count;
        try {
            count = Integer.parseInt(lines[7]);
        } catch (NumberFormatException badCount) {
            throw new IllegalArgumentException("Invalid item count", badCount);
        }
        if (count < 0 || count > MAX_ITEMS || lines.length != 8 + count) {
            throw new IllegalArgumentException("Mismatched item count");
        }
        List<Item> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String[] cols = lines[8 + i].split("\t", -1);
            if (cols.length != 3) throw new IllegalArgumentException("Malformed item row");
            items.add(new Item(unb64(cols[0]), unb64(cols[1]), unb64(cols[2])));
        }
        return new HermesWidgetSpec(id, title, subtitle, template, items, accent.isEmpty() ? null : accent, safe);
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    public JSONObject toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("widget_id", widgetId);
            obj.put("title", title);
            obj.put("subtitle", subtitle);
            obj.put("template", template.wire);
            obj.put("home_screen_safe", homeScreenSafe);
            if (accentHex != null) obj.put("accent_hex", accentHex);
            JSONArray array = new JSONArray();
            for (Item item : items) {
                JSONObject row = new JSONObject();
                row.put("label", item.label());
                if (!item.value().isEmpty()) row.put("value", item.value());
                if (!item.actionDraft().isEmpty()) row.put("action_draft", item.actionDraft());
                array.put(row);
            }
            obj.put("items", array);
            return obj;
        } catch (JSONException unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    public static HermesWidgetSpec fromJson(JSONObject obj) {
        if (obj == null) throw new IllegalArgumentException("Missing widget spec");
        String id = obj.optString("widget_id", "");
        String title = obj.optString("title", "");
        String subtitle = obj.optString("subtitle", "");
        Template template = Template.parse(obj.optString("template", "status"));
        if (template == null) throw new IllegalArgumentException("Unsupported widget template");
        boolean safe = obj.optBoolean("home_screen_safe", false);
        String accent = obj.isNull("accent_hex") ? null : obj.optString("accent_hex", null);
        List<Item> items = new ArrayList<>();
        JSONArray array = obj.optJSONArray("items");
        if (array != null) {
            for (int i = 0; i < Math.min(array.length(), MAX_ITEMS); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                items.add(new Item(
                        row.optString("label", ""),
                        row.optString("value", ""),
                        row.optString("action_draft", "")));
            }
        }
        return new HermesWidgetSpec(id, title, subtitle, template, items, accent, safe);
    }
}
