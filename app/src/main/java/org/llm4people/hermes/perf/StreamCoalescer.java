package org.llm4people.hermes.perf;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deterministic, frame-budgeted streaming text coalescer.
 * Flushes the first non-empty update immediately to preserve TTFT, coalesces rapid intermediate
 * token bursts within {@code frameBudgetMs} without dropping any character, and guarantees exact
 * final delivery on {@link #flush()}. Pure Java (host-JVM testable, zero sleeps or polling).
 */
public final class StreamCoalescer {
    public static final long DEFAULT_FRAME_BUDGET_MS = 64L;
    public static final long MIN_FRAME_BUDGET_MS = 16L;
    public static final long MAX_FRAME_BUDGET_MS = 250L;

    private final long frameBudgetMs;
    private final Consumer<String> sink;
    private final StringBuilder accumulated = new StringBuilder();
    private String pending;
    private String lastFlushed;
    private long lastFlushMs = Long.MIN_VALUE;
    private boolean emittedNonEmpty;
    private int offered;
    private int flushed;
    private int coalesced;

    public StreamCoalescer(Consumer<String> sink) {
        this(DEFAULT_FRAME_BUDGET_MS, sink);
    }

    public StreamCoalescer(long frameBudgetMs, Consumer<String> sink) {
        if (frameBudgetMs < MIN_FRAME_BUDGET_MS || frameBudgetMs > MAX_FRAME_BUDGET_MS) {
            throw new IllegalArgumentException("Frame budget must be within 16..250 ms");
        }
        this.frameBudgetMs = frameBudgetMs;
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public long frameBudgetMs() { return frameBudgetMs; }
    public int offeredCount() { return offered; }
    public int flushedCount() { return flushed; }
    public int coalescedCount() { return coalesced; }
    public boolean hasPending() { return pending != null; }

    /**
     * Offer a cumulative snapshot of streamed text at monotonic timestamp {@code nowMs}.
     * Returns {@code true} when flushed immediately to the sink, {@code false} when coalesced or unchanged.
     */
    public boolean offer(String cumulativeText, long nowMs) {
        String text = cumulativeText == null ? "" : cumulativeText;
        offered++;
        if (text.equals(pending) || (pending == null && text.equals(lastFlushed))) {
            return false;
        }
        boolean firstEmit = lastFlushMs == Long.MIN_VALUE;
        boolean firstNonEmpty = !emittedNonEmpty && !text.isEmpty();
        boolean due = firstEmit || firstNonEmpty || (nowMs - lastFlushMs >= frameBudgetMs);
        if (due) {
            if (pending != null) coalesced++;
            pending = null;
            emit(text, nowMs);
            return true;
        }
        if (pending != null) coalesced++;
        pending = text;
        return false;
    }

    /**
     * Append an incremental token delta to the internal buffer and offer the updated snapshot.
     * Preserves every character in order across arbitrarily fast bursts.
     */
    public boolean offerDelta(String delta, long nowMs) {
        if (delta == null || delta.isEmpty()) return false;
        accumulated.append(delta);
        return offer(accumulated.toString(), nowMs);
    }

    /** Remaining milliseconds until a pending update becomes due, or 0 if already due / nothing pending. */
    public long pendingDelayMs(long nowMs) {
        if (pending == null || lastFlushMs == Long.MIN_VALUE) return 0L;
        long elapsed = nowMs - lastFlushMs;
        return elapsed >= frameBudgetMs ? 0L : (frameBudgetMs - elapsed);
    }

    /** Flush any pending update immediately (e.g. on completion, pause, or scheduled frame timer). */
    public boolean flush(long nowMs) {
        if (pending == null) return false;
        String text = pending;
        pending = null;
        if (text.equals(lastFlushed)) return false;
        emit(text, nowMs);
        return true;
    }

    /** Flush any pending update using the last recorded timestamp (for terminal completion hooks). */
    public boolean flush() {
        return flush(lastFlushMs == Long.MIN_VALUE ? 0L : lastFlushMs + frameBudgetMs);
    }

    /** Clear buffered state between runs. */
    public void reset() {
        accumulated.setLength(0);
        pending = null;
        lastFlushed = null;
        lastFlushMs = Long.MIN_VALUE;
        emittedNonEmpty = false;
        offered = 0;
        flushed = 0;
        coalesced = 0;
    }

    private void emit(String text, long nowMs) {
        lastFlushed = text;
        lastFlushMs = nowMs;
        if (!text.isEmpty()) emittedNonEmpty = true;
        flushed++;
        sink.accept(text);
    }
}
