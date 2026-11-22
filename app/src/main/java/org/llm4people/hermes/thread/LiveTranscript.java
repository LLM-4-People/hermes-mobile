package org.llm4people.hermes.thread;

/** A rolling, selectable preview. Reparse the last two display pages as Markdown delimiters arrive. */
final class LiveTranscript {
    static final int PAGE_CHARS = 1200;
    private String prior = "";
    private TranscriptPage.Cursor window = new TranscriptPage.Cursor();
    TranscriptPage preceding;
    TranscriptPage latest;
    boolean earlier;
    boolean later;

    String source() { return prior; }
    int windowStart() { return window.offset; }

    boolean update(String source) {
        if (source.equals(prior)) return false;
        later = false;
        if (source.length() > RunFollower.PREVIEW_LIMIT) {
            // A reconciled terminal response may be much larger than a streaming preview.
            // Its complete text is available in the paged reader.
            prior = source;
            window = new TranscriptPage.Cursor();
            preceding = null;
            latest = page(source, window);
            earlier = false;
            later = latest.hasMore();
            return true;
        }
        if (!source.startsWith(prior)) window = new TranscriptPage.Cursor();
        else window.extendLine(source, prior.length());
        prior = source;
        preceding = null;
        TranscriptPage.Cursor pageStart = window;
        latest = page(source, pageStart);
        while (latest.hasMore()) {
            window = pageStart;
            preceding = latest;
            pageStart = latest.next;
            latest = page(source, pageStart);
        }
        earlier = window.offset > 0;
        return true;
    }

    private TranscriptPage page(String source, TranscriptPage.Cursor at) {
        return new TranscriptPage(source, at, true, PAGE_CHARS);
    }
}
