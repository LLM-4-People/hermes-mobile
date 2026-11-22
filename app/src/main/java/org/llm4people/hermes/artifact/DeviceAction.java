package org.llm4people.hermes.artifact;

import android.content.Intent;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.JsonReader;
import android.util.JsonToken;
import org.llm4people.hermes.VisibleText;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.llm4people.hermes.artifact.WorkspaceLayout.invalid;
import static org.llm4people.hermes.artifact.WorkspaceLayout.require;

/** A fixed Android operation. Review and delivery always read the same immutable values. */
sealed interface DeviceAction {
    enum Kind {
        CALENDAR("Événement d’agenda", "Vérifier l’événement", "Ouvrir l’agenda"),
        SHARE("Texte à partager", "Vérifier le partage", "Choisir une application"),
        MAP("Lieu à rechercher", "Vérifier le lieu", "Ouvrir la carte");

        final String title, review, open;
        Kind(String title, String review, String open) { this.title = title; this.review = review; this.open = open; }
    }

    Kind kind();
    String preview();
    Intent target();

    record CalendarEvent(String title, OffsetDateTime start, OffsetDateTime end, String location) implements DeviceAction {
        private static final DateTimeFormatter WIRE = new DateTimeFormatterBuilder()
                .appendValue(ChronoField.YEAR, 4).appendPattern("-MM-dd'T'HH:mm:ssXXX")
                .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
        private static final DateTimeFormatter FRENCH = DateTimeFormatter.ofPattern(
                "d MMMM uuuu 'à' HH:mm:ss '(UTC'xxx')'", Locale.FRENCH);
        private static final DateTimeFormatter SHORT_START = DateTimeFormatter.ofPattern(
                "d MMMM uuuu 'à' HH 'h' mm '(UTC'xxx')'", Locale.FRENCH);

        String startSummary() { return start.format(SHORT_START); }

        @Override public Kind kind() { return Kind.CALENDAR; }
        @Override public String preview() {
            return title + "\n\nDébut · " + start.format(FRENCH) + "\nFin · " + end.format(FRENCH)
                    + (location == null ? "" : "\nLieu · " + location);
        }
        @Override public Intent target() {
            Intent target = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toInstant().toEpochMilli());
            if (location != null) target.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
            return target;
        }
    }

    record ShareText(String text) implements DeviceAction {
        @Override public Kind kind() { return Kind.SHARE; }
        @Override public String preview() { return text; }
        @Override public Intent target() {
            return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        }
    }

    record MapSearch(String query) implements DeviceAction {
        @Override public Kind kind() { return Kind.MAP; }
        @Override public String preview() { return query; }
        @Override public Intent target() {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        }
    }

    static DeviceAction read(JsonReader in) throws IOException {
        Map<String,String> values = new HashMap<>();
        in.beginObject();
        while (in.hasNext()) {
            String key = in.nextName();
            int max = switch (key) {
                case "kind" -> 20;
                case "title" -> 120;
                case "start", "end" -> 25;
                case "location", "query" -> 200;
                case "text" -> 2000;
                default -> throw invalid();
            };
            require(!values.containsKey(key) && in.peek() == JsonToken.STRING);
            String value = in.nextString();
            require(value.length() <= max && !VisibleText.isBlank(value));
            require(value.codePoints().allMatch(cp -> displayable(cp) || ("text".equals(key) && cp == '\n')));
            values.put(key, value);
        }
        in.endObject();
        String kind = values.remove("kind");
        require(kind != null);
        DeviceAction action = switch (kind) {
            case "calendar_event" -> {
                String title = values.remove("title");
                OffsetDateTime start = instant(values.remove("start")), end = instant(values.remove("end"));
                require(title != null && end.isAfter(start));
                yield new CalendarEvent(title, start, end, values.remove("location"));
            }
            case "share_text" -> {
                String text = values.remove("text");
                require(text != null);
                yield new ShareText(text);
            }
            case "map_search" -> {
                String query = values.remove("query");
                require(query != null);
                yield new MapSearch(query);
            }
            default -> throw invalid();
        };
        require(values.isEmpty());
        return action;
    }

    private static boolean displayable(int cp) {
        int type = Character.getType(cp);
        return !Character.isISOControl(cp) && type != Character.SURROGATE
                && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR
                && (type != Character.FORMAT || cp == 0x200C || cp == 0x200D);
    }

    private static OffsetDateTime instant(String value) throws IOException {
        require(value != null && (value.length() == 20 || value.length() == 25));
        try {
            OffsetDateTime date = OffsetDateTime.parse(value, CalendarEvent.WIRE);
            require(date.getYear() >= 1 && date.getYear() <= 9999);
            return date;
        } catch (DateTimeException invalidDate) { throw invalid(); }
    }
}
