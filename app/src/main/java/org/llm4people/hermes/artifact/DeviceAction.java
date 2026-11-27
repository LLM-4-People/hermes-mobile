package org.llm4people.hermes.artifact;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.util.JsonReader;
import android.util.JsonToken;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.places.NearbyResult;

import java.io.IOException;
import java.io.StringReader;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.llm4people.hermes.artifact.WorkspaceLayout.invalid;
import static org.llm4people.hermes.artifact.WorkspaceLayout.require;

/** A fixed Android operation. Review and delivery always read the same immutable values. */
public sealed interface DeviceAction {
    Pattern PHONE_PATTERN = Pattern.compile("[+0-9#*(). -]{3,32}");
    Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]{1,64}@[^\\s@]{1,253}\\.[^\\s@]{2,63}");

    enum Kind {
        CALENDAR(R.string.ws_dev_calendar_title, R.string.ws_dev_calendar_review, R.string.ws_dev_calendar_open),
        SHARE(R.string.ws_dev_share_title, R.string.ws_dev_share_review, R.string.ws_dev_share_open),
        MAP(R.string.ws_dev_map_title, R.string.ws_dev_map_review, R.string.ws_dev_map_open),
        OSM(R.string.ws_dev_osm_title, R.string.ws_dev_osm_review, R.string.ws_dev_osm_open),
        ALARM(R.string.ws_dev_alarm_title, R.string.ws_dev_alarm_review, R.string.ws_dev_alarm_open),
        TIMER(R.string.ws_dev_timer_title, R.string.ws_dev_timer_review, R.string.ws_dev_timer_open),
        DIAL(R.string.ws_dev_dial_title, R.string.ws_dev_dial_review, R.string.ws_dev_dial_open),
        SMS(R.string.ws_dev_sms_title, R.string.ws_dev_sms_review, R.string.ws_dev_sms_open),
        EMAIL(R.string.ws_dev_email_title, R.string.ws_dev_email_review, R.string.ws_dev_email_open),
        CONTACT(R.string.ws_dev_contact_title, R.string.ws_dev_contact_review, R.string.ws_dev_contact_open),
        NAVIGATION(R.string.ws_dev_nav_title, R.string.ws_dev_nav_review, R.string.ws_dev_nav_open),
        CLIPBOARD(R.string.ws_dev_clip_title, R.string.ws_dev_clip_review, R.string.ws_dev_clip_open);

        public final int titleRes, reviewRes, openRes;
        Kind(int titleRes, int reviewRes, int openRes) {
            this.titleRes = titleRes;
            this.reviewRes = reviewRes;
            this.openRes = openRes;
        }
        public String title(Context context) { return context.getString(titleRes); }
        public String review(Context context) { return context.getString(reviewRes); }
        public String open(Context context) { return context.getString(openRes); }
    }

    Kind kind();
    String preview(Context context);
    Intent target();
    default Intent fallbackTarget() { return null; }

    record CalendarEvent(String title, OffsetDateTime start, OffsetDateTime end, String location) implements DeviceAction {
        private static final DateTimeFormatter WIRE = new DateTimeFormatterBuilder()
                .appendValue(ChronoField.YEAR, 4).appendPattern("-MM-dd'T'HH:mm:ssXXX")
                .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

        String startSummary(Context context) {
            Locale loc = AppLanguage.resolvedLocale(context);
            return start.format(DateTimeFormatter.ofPattern(
                    context.getString(R.string.ws_dev_date_short_pattern), loc));
        }

        @Override public Kind kind() { return Kind.CALENDAR; }
        @Override public String preview(Context context) {
            Locale loc = AppLanguage.resolvedLocale(context);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(
                    context.getString(R.string.ws_dev_date_full_pattern), loc);
            StringBuilder out = new StringBuilder(title)
                    .append("\n\n").append(context.getString(R.string.ws_dev_cal_start, start.format(fmt)))
                    .append('\n').append(context.getString(R.string.ws_dev_cal_end, end.format(fmt)));
            if (location != null) {
                out.append('\n').append(context.getString(R.string.ws_dev_cal_location, location));
            }
            return out.toString();
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
        @Override public String preview(Context context) { return text; }
        @Override public Intent target() {
            return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        }
    }

    record MapSearch(String query) implements DeviceAction {
        @Override public Kind kind() { return Kind.MAP; }
        @Override public String preview(Context context) { return query; }
        @Override public Intent target() {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        }
    }

    record PlaceMap(NearbyResult.Place place) implements DeviceAction {
        @Override public Kind kind() { return Kind.MAP; }
        @Override public String preview(Context context) {
            return place.name + "\n\n" + context.getString(R.string.ws_dev_place_coords, place.coordinates());
        }
        @Override public Intent target() { return new MapSearch(place.coordinates()).target(); }
    }

    record PlaceSource(NearbyResult.Place place) implements DeviceAction {
        @Override public Kind kind() { return Kind.OSM; }
        @Override public String preview(Context context) { return place.name + "\n\n" + place.sourceUrl; }
        @Override public Intent target() { return new Intent(Intent.ACTION_VIEW, Uri.parse(place.sourceUrl)); }
    }

    record SetAlarm(int hour, int minute, String label, List<Integer> days) implements DeviceAction {
        @Override public Kind kind() { return Kind.ALARM; }
        @Override public String preview(Context context) {
            String time = context.getString(R.string.ws_dev_time_fmt, hour, minute);
            StringBuilder out = new StringBuilder(context.getString(R.string.ws_dev_alarm_time, time));
            if (label != null) out.append('\n').append(context.getString(R.string.ws_dev_alarm_label, label));
            if (!days.isEmpty()) {
                out.append('\n').append(context.getString(R.string.ws_dev_alarm_days, formatDays(context, days)));
            }
            return out.toString();
        }
        @Override public Intent target() {
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            if (!days.isEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, new ArrayList<>(days));
            return intent;
        }
    }

    record SetTimer(int seconds, String label) implements DeviceAction {
        @Override public Kind kind() { return Kind.TIMER; }
        @Override public String preview(Context context) {
            String duration = context.getString(R.string.ws_dev_timer_duration, formatDuration(seconds));
            return label == null ? duration : label + "\n\n" + duration;
        }
        @Override public Intent target() {
            Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            return intent;
        }
    }

    record DialDraft(String number) implements DeviceAction {
        @Override public Kind kind() { return Kind.DIAL; }
        @Override public String preview(Context context) {
            return context.getString(R.string.ws_dev_dial_preview, number);
        }
        @Override public Intent target() {
            return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        }
    }

    record SmsDraft(String recipient, String message) implements DeviceAction {
        @Override public Kind kind() { return Kind.SMS; }
        @Override public String preview(Context context) {
            return context.getString(R.string.ws_dev_sms_recipient, recipient) + "\n\n" + message;
        }
        @Override public Intent target() {
            return new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(recipient)))
                    .putExtra("sms_body", message);
        }
    }

    record EmailDraft(String recipient, String subject, String body) implements DeviceAction {
        @Override public Kind kind() { return Kind.EMAIL; }
        @Override public String preview(Context context) {
            StringBuilder out = new StringBuilder(context.getString(R.string.ws_dev_email_to, recipient));
            if (subject != null) out.append('\n').append(context.getString(R.string.ws_dev_email_subject, subject));
            if (body != null) out.append("\n\n").append(body);
            return out.toString();
        }
        @Override public Intent target() {
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_EMAIL, new String[]{recipient});
            if (subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            if (body != null) intent.putExtra(Intent.EXTRA_TEXT, body);
            return intent;
        }
    }

    record InsertContact(String name, String phone, String email, String company) implements DeviceAction {
        @Override public Kind kind() { return Kind.CONTACT; }
        @Override public String preview(Context context) {
            List<String> lines = new ArrayList<>();
            if (name != null) lines.add(context.getString(R.string.ws_dev_contact_name, name));
            if (phone != null) lines.add(context.getString(R.string.ws_dev_contact_phone, phone));
            if (email != null) lines.add(context.getString(R.string.ws_dev_contact_email, email));
            if (company != null) lines.add(context.getString(R.string.ws_dev_contact_org, company));
            return String.join("\n", lines);
        }
        @Override public Intent target() {
            Intent intent = new Intent(ContactsContract.Intents.Insert.ACTION)
                    .setType(ContactsContract.RawContacts.CONTENT_TYPE);
            if (name != null) intent.putExtra(ContactsContract.Intents.Insert.NAME, name);
            if (phone != null) intent.putExtra(ContactsContract.Intents.Insert.PHONE, phone);
            if (email != null) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, email);
            if (company != null) intent.putExtra(ContactsContract.Intents.Insert.COMPANY, company);
            return intent;
        }
    }

    record MapNavigation(String destination, String mode) implements DeviceAction {
        @Override public Kind kind() { return Kind.NAVIGATION; }
        @Override public String preview(Context context) {
            int modeRes = switch (mode) {
                case "walking" -> R.string.ws_dev_nav_walking;
                case "cycling" -> R.string.ws_dev_nav_cycling;
                case "transit" -> R.string.ws_dev_nav_transit;
                default -> R.string.ws_dev_nav_driving;
            };
            return destination + "\n\n" + context.getString(R.string.ws_dev_nav_mode, context.getString(modeRes));
        }
        @Override public Intent target() {
            String code = switch (mode) {
                case "walking" -> "w";
                case "cycling" -> "b";
                case "transit" -> "r";
                default -> "d";
            };
            return new Intent(Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=" + code));
        }
        @Override public Intent fallbackTarget() {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination)));
        }
    }

    record CopySensitive(String text) implements DeviceAction {
        @Override public Kind kind() { return Kind.CLIPBOARD; }
        @Override public String preview(Context context) { return text; }
        @Override public Intent target() { return null; }
    }

    static DeviceAction parseJson(String rawJson) throws IOException {
        require(rawJson != null && rawJson.length() <= 8192);
        try (JsonReader in = new JsonReader(new StringReader(rawJson))) {
            in.setLenient(false);
            DeviceAction action = read(in);
            require(in.peek() == JsonToken.END_DOCUMENT);
            return action;
        } catch (IllegalStateException | NumberFormatException malformed) {
            throw invalid();
        }
    }

    static DeviceAction read(JsonReader in) throws IOException {
        Map<String, String> values = new HashMap<>();
        Map<String, Integer> ints = new HashMap<>();
        List<Integer> days = null;
        in.beginObject();
        while (in.hasNext()) {
            String key = in.nextName();
            if ("hour".equals(key) || "minute".equals(key) || "seconds".equals(key)) {
                require(!ints.containsKey(key) && in.peek() == JsonToken.NUMBER);
                ints.put(key, readExactInt(in));
                continue;
            }
            if ("days".equals(key)) {
                require(days == null && in.peek() == JsonToken.BEGIN_ARRAY);
                Set<Integer> unique = new LinkedHashSet<>();
                in.beginArray();
                while (in.hasNext()) {
                    require(in.peek() == JsonToken.NUMBER);
                    int day = readExactInt(in);
                    require(day >= 1 && day <= 7 && unique.add(day));
                }
                in.endArray();
                require(!unique.isEmpty());
                days = Collections.unmodifiableList(new ArrayList<>(unique));
                continue;
            }
            int max = switch (key) {
                case "kind" -> 24;
                case "title", "label", "name", "company" -> 120;
                case "start", "end" -> 25;
                case "location", "query", "destination", "subject" -> 200;
                case "number", "phone" -> 32;
                case "recipient", "email" -> 320;
                case "mode" -> 16;
                case "text", "message" -> 2000;
                case "body" -> 4000;
                default -> throw invalid();
            };
            require(!values.containsKey(key) && in.peek() == JsonToken.STRING);
            String value = in.nextString();
            require(value.length() <= max && PlainTextSanitizer.hasVisibleText(value));
            boolean multiline = "text".equals(key) || "message".equals(key) || "body".equals(key);
            require(value.codePoints().allMatch(cp -> displayable(cp) || (multiline && cp == '\n')));
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
            case "set_alarm" -> {
                Integer hour = ints.remove("hour"), minute = ints.remove("minute");
                require(hour != null && minute != null && hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59);
                List<Integer> selectedDays = days == null ? Collections.emptyList() : days;
                days = null;
                yield new SetAlarm(hour, minute, values.remove("label"), selectedDays);
            }
            case "set_timer" -> {
                Integer seconds = ints.remove("seconds");
                require(seconds != null && seconds >= 1 && seconds <= 86_400);
                yield new SetTimer(seconds, values.remove("label"));
            }
            case "dial_draft" -> {
                String number = values.remove("number");
                require(number != null && validPhone(number));
                yield new DialDraft(number.trim());
            }
            case "sms_draft" -> {
                String recipient = values.remove("recipient"), message = values.remove("message");
                require(recipient != null && validPhone(recipient) && message != null);
                yield new SmsDraft(recipient.trim(), message);
            }
            case "email_draft" -> {
                String recipient = values.remove("recipient");
                require(recipient != null && EMAIL_PATTERN.matcher(recipient.trim()).matches());
                yield new EmailDraft(recipient.trim(), values.remove("subject"), values.remove("body"));
            }
            case "insert_contact" -> {
                String name = values.remove("name");
                String phone = values.remove("phone");
                String email = values.remove("email");
                String company = values.remove("company");
                require(name != null || phone != null || email != null || company != null);
                if (phone != null) require(validPhone(phone));
                if (email != null) require(EMAIL_PATTERN.matcher(email.trim()).matches());
                yield new InsertContact(name, phone == null ? null : phone.trim(),
                        email == null ? null : email.trim(), company);
            }
            case "map_navigation" -> {
                String destination = values.remove("destination");
                String mode = values.remove("mode");
                if (mode == null) mode = "driving";
                require(destination != null && ("driving".equals(mode) || "walking".equals(mode)
                        || "cycling".equals(mode) || "transit".equals(mode)));
                yield new MapNavigation(destination, mode);
            }
            case "copy_sensitive" -> {
                String text = values.remove("text");
                require(text != null);
                yield new CopySensitive(text);
            }
            default -> throw invalid();
        };
        require(values.isEmpty() && ints.isEmpty() && days == null);
        return action;
    }

    private static int readExactInt(JsonReader in) throws IOException {
        String raw = in.nextString();
        require(raw.matches("-?[0-9]{1,9}"));
        return Integer.parseInt(raw);
    }

    private static boolean validPhone(String raw) {
        String trimmed = raw.trim();
        return PHONE_PATTERN.matcher(trimmed).matches() && trimmed.chars().anyMatch(Character::isDigit);
    }

    private static String formatDuration(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        List<String> parts = new ArrayList<>();
        if (hours > 0) parts.add(hours + " h");
        if (minutes > 0) parts.add(minutes + " min");
        if (seconds > 0 || parts.isEmpty()) parts.add(seconds + " s");
        return String.join(" ", parts);
    }

    private static String formatDays(Context context, List<Integer> days) {
        Locale locale = context == null ? Locale.getDefault() : AppLanguage.resolvedLocale(context);
        List<String> names = new ArrayList<>(days.size());
        for (int day : days) {
            DayOfWeek dow = switch (day) {
                case 1 -> DayOfWeek.SUNDAY;
                case 2 -> DayOfWeek.MONDAY;
                case 3 -> DayOfWeek.TUESDAY;
                case 4 -> DayOfWeek.WEDNESDAY;
                case 5 -> DayOfWeek.THURSDAY;
                case 6 -> DayOfWeek.FRIDAY;
                case 7 -> DayOfWeek.SATURDAY;
                default -> null;
            };
            if (dow == null) {
                names.add(String.valueOf(day));
            } else {
                String shortName = dow.getDisplayName(TextStyle.SHORT_STANDALONE, locale);
                if (shortName.endsWith(".")) shortName = shortName.substring(0, shortName.length() - 1);
                names.add(shortName.isEmpty() ? shortName
                        : Character.toUpperCase(shortName.charAt(0)) + shortName.substring(1));
            }
        }
        return String.join(", ", names);
    }

    private static boolean displayable(int cp) {
        int type = Character.getType(cp);
        return !Character.isISOControl(cp) && type != Character.SURROGATE
                && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR
                && !PlainTextSanitizer.isStrippableInvisible(cp)
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
