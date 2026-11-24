package org.llm4people.hermes.thread;

/** A rolling, selectable preview. Reparse the last two display pages as Markdown delimiters arrive. */
final class LiveTranscript {
    static final int PAGE_CHARS = 1200;
    private String prior = "";
    private RunPreview.Origin origin = RunPreview.Origin.FIRST;
    private TranscriptPage.Cursor window = new TranscriptPage.Cursor();
    TranscriptPage preceding;
    TranscriptPage latest;
    boolean earlier;
    boolean foldedWorkspace;
    boolean later;

    String source() { return prior; }
    long windowStart() { return origin.offset + window.offset; }
    TranscriptPage.Cursor readerStart() { return origin.cursor(prior); }
    boolean excerpt() { return origin.offset > 0; }

    boolean update(String source) { return update(source, RunPreview.Origin.FIRST); }

    boolean update(String source, RunPreview.Origin start) {
        boolean moved = start != origin;
        if (!moved && source.length() == prior.length() && source.equals(prior)) return false;
        origin = start;
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
        if (moved || !source.startsWith(prior)) window = start.cursor(source);
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
        earlier = excerpt() || window.offset > 0;
        return true;
    }

    private TranscriptPage page(String source, TranscriptPage.Cursor at) {
        return new TranscriptPage(source, at, true, PAGE_CHARS);
    }
}
