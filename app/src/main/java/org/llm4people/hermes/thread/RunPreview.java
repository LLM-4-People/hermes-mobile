package org.llm4people.hermes.thread;

import org.json.JSONObject;
import org.llm4people.hermes.artifact.Fences;

/** Bounded UTF-16 tail. Evict a batch so a token at the cap never shifts the buffer on every delta. */
final class RunPreview {
    static final int LIMIT = 65_536;

    /** Parser context at the first retained character. Shared unchanged between evictions. */
    static final class Origin {
        static final Origin FIRST = new Origin(0, null, "", false);
        final long offset;
        final Fences.Marker fence;
        final String continuedLine;
        final boolean image;

        Origin(long offset, Fences.Marker fence, String continuedLine, boolean image) {
            this.offset = offset;
            this.fence = fence;
            this.continuedLine = continuedLine;
            this.image = image;
        }

        static Origin read(JSONObject receipt) {
            JSONObject row = receipt == null ? null : receipt.optJSONObject("preview_origin");
            if (row == null || row.optLong("offset") <= 0) return FIRST;
            String lead = row.optString("line");
            Fences.Marker fence = Fences.continuation(row.optString("delimiter"), row.optInt("width"), row.optString("language"));
            return new Origin(row.optLong("offset"), fence, lead.length() == 1 ? lead : "", row.optBoolean("image"));
        }

        JSONObject json() {
            JSONObject row = ThreadData.put(new JSONObject(), "offset", offset);
            if (!continuedLine.isEmpty()) ThreadData.put(row, "line", continuedLine);
            if (image) ThreadData.put(row, "image", true);
            if (fence != null) {
                ThreadData.put(row, "delimiter", String.valueOf(fence.symbol));
                ThreadData.put(row, "width", fence.width);
                ThreadData.put(row, "language", fence.language);
            }
            return row;
        }

        TranscriptPage.Cursor cursor(String source) {
            TranscriptPage.Cursor c = new TranscriptPage.Cursor();
            c.fence = fence;
            if (!continuedLine.isEmpty()) {
                int end = source.indexOf('\n');
                c.lineEnd = end < 0 ? source.length() : end;
                if (fence != null) c.lineStyle = TranscriptPage.fencedStyle(fence, continuedLine.charAt(0));
                c.image = image;
            }
            return c;
        }
    }

    private final StringBuilder tail = new StringBuilder();
    private String cached;
    private Origin origin;

    RunPreview(JSONObject receipt) {
        origin = Origin.read(receipt);
        String prior = ThreadData.string(receipt, "preview");
        // Older clients kept the first 64 KiB as their event cursor advanced beyond it.
        if (prior.length() == LIMIT && receipt != null && !receipt.has("preview_origin")) {
            origin = new Origin(LIMIT, null, "", false);
        } else {
            append(prior);
            int pending = receipt == null ? 0 : receipt.optInt("preview_high_surrogate");
            if (pending >= Character.MIN_HIGH_SURROGATE && pending <= Character.MAX_HIGH_SURROGATE)
                append(String.valueOf((char) pending));
        }
    }

    Origin origin() { return origin; }

    void gap() {
        origin = new Origin(Math.max(1, origin.offset + tail.length()), null, "", false);
        tail.setLength(0);
        cached = "";
    }

    void append(String delta) {
        for (int start = 0; start < delta.length();) {
            if (tail.length() == LIMIT) discardPrefix();
            int end = Math.min(delta.length(), start + LIMIT - tail.length());
            tail.append(delta, start, end);
            start = end;
            cached = null;
        }
    }

    private void discardPrefix() {
        String source = toString();
        int cut = LIMIT / 2;
        int nearby = source.indexOf('\n', cut);
        if (nearby >= 0 && nearby - cut < 2048) cut = nearby + 1;
        if (Character.isHighSurrogate(source.charAt(cut - 1)) && Character.isLowSurrogate(source.charAt(cut))) cut++;
        // Keep an image header together even if the natural cut falls inside it.
        cut = TranscriptPage.mediaBoundary(source, cut);
        int line = 0;
        boolean partial = !origin.continuedLine.isEmpty();
        Fences.Marker fence = origin.fence;
        for (int end; (end = source.indexOf('\n', line)) >= 0 && end < cut; line = end + 1) {
            if (!partial) {
                Fences.Marker marker = Fences.marker(source, line, end);
                if (marker != null && (fence == null || marker.closes(fence))) fence = fence == null ? marker : null;
            }
            partial = false;
        }
        String lead = "";
        boolean image = false;
        if (line < cut) {
            char first = partial ? origin.continuedLine.charAt(0) : source.charAt(line);
            lead = first == '+' || first == '-' ? String.valueOf(first) : " ";
            image = fence == null && TranscriptPage.cutsMedia(source, line, cut, partial && origin.image);
        }
        origin = new Origin(origin.offset + cut, fence, lead, image);
        tail.delete(0, cut);
        cached = null;
    }

    int pendingHighSurrogate() {
        if (tail.length() == 0) return 0;
        char last = tail.charAt(tail.length() - 1);
        return Character.isHighSurrogate(last) ? last : 0;
    }

    @Override public String toString() {
        if (cached == null) cached = pendingHighSurrogate() == 0 ? tail.toString() : tail.substring(0, tail.length() - 1);
        return cached;
    }
}
