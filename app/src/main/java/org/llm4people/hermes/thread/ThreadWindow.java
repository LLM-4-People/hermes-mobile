package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/** Read the latest transcript through the previously loaded window, preserving authoritative rows and order. */
record ThreadWindow(JSONObject metadata, JSONArray rows, int rawCount, boolean more) {
    static ThreadWindow read(ThreadContext.Read api, String query, int start, int loaded, long priorTotal,
                             ThreadData.RowId oldest, BooleanSupplier current, int pageSize) throws IOException {
        int limit = Math.max(1, pageSize);
        JSONObject metadata = null;
        ArrayDeque<JSONArray> pages = new ArrayDeque<>();
        long total = -1;
        int offset = start;
        boolean more, reached = false;
        String previousPage = null;
        do {
            if (!current.getAsBoolean()) throw new IOException("Conversation changed");
            JSONObject response = api.get(query + limit + "&offset=" + offset);
            if (metadata == null) {
                metadata = response;
                total = ThreadContext.visibleTotal(response);
            }
            JSONArray rows = response.optJSONArray("data");
            if (rows == null) throw new IOException("Missing transcript");
            if (loaded > 0) {
                // Legacy gateways can ignore pagination. Compare the page boundaries without
                // serializing every row of a long conversation on each ordinary refresh.
                ThreadData.RowId first = ThreadData.rowId(rows.optJSONObject(0));
                ThreadData.RowId last = ThreadData.rowId(rows.optJSONObject(rows.length() - 1));
                String boundary = first == null || last == null ? rows.toString() : first + "/" + last;
                if (boundary.equals(previousPage)) throw new IOException("Repeated transcript page");
                previousPage = boundary;
            }
            pages.addFirst(rows);
            offset += rows.length();
            more = rows.length() > 0 && (total < 0 ? rows.length() == limit : offset < total);
            if (oldest != null) {
                for (int i = 0; i < rows.length(); i++)
                    if (oldest.equals(ThreadData.rowId(rows.optJSONObject(i)))) { reached = true; break; }
            }
            // The prior total tells us exactly how much history preceded this window, including
            // when a previous row was deleted. Legacy servers only expose the source row IDs.
            long wanted = loaded + (total >= 0 && priorTotal >= 0 ? Math.max(0, total - priorTotal) : 0);
            if (reached || offset >= wanted && (total >= 0 && priorTotal >= 0 || oldest == null)) break;
        } while (more && loaded > 0);

        JSONArray all = new JSONArray();
        for (JSONArray page : pages)
            for (int i = 0; i < page.length(); i++) all.put(page.opt(i));
        return new ThreadWindow(metadata, all, offset - start, more);
    }
}
