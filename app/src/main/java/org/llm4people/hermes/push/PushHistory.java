package org.llm4people.hermes.push;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class PushHistory {
    static final class Page {
        final List<String> ids = new ArrayList<>(), kinds = new ArrayList<>(), labels = new ArrayList<>();
        final long next;
        final boolean more;

        Page(JSONObject response, long cursor, String installation, Locale locale) throws IOException {
            JSONArray events = response.optJSONArray("events");
            next = response.optLong("next_cursor", -1);
            more = response.optBoolean("has_more");
            if (events == null || next < cursor || (more && next == cursor)) throw new IOException("events page");
            DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale);
            for (int i = 0; i < events.length(); i++) {
                JSONObject row = events.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("event_id"), kind = row.optString("kind");
                String title = PushNotices.title(kind);
                if (!PushNotices.validIds(installation, id) || title == null) continue;
                ids.add(id);
                kinds.add(kind);
                long time = row.optLong("created_at");
                labels.add(time > 0 ? title + "\n" + dates.format(new Date(time * 1000)) : title);
            }
        }
    }

    static Page load(Account account, PushStore.Device device, long cursor, Locale locale) throws IOException {
        JSONObject response = new HermesApi(account.endpoint, account.bearer)
                .get(PushConnection.BASE + "/events?after=" + cursor + "&limit=25");
        return new Page(response, cursor, device.installation, locale);
    }
}
