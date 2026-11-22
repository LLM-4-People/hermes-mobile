package org.llm4people.hermes.nearby;

import org.llm4people.hermes.HermesConfig;

import java.util.function.Consumer;

/** A single foreground operation. All commands and provider callbacks run on the UI thread. */
final class NearbyRequest {
    interface Source {
        boolean available();
        long elapsedMillis();
        void after(long millis, Runnable action);
        void read(boolean fused, Consumer<CoarseArea> received);
        void close();
    }

    private static final int TIMEOUT_MS = HermesConfig.DEFAULT_NEARBY_TIMEOUT_MS;
    private static final int FUSED_GRACE_MS = HermesConfig.DEFAULT_NEARBY_FUSED_GRACE_MS;
    private final Source source;
    private final Consumer<CoarseArea> result;
    private boolean closed;
    private boolean fusedPending;
    private boolean networkStarted;
    private boolean networkPending;
    private long started;

    NearbyRequest(Source source, Consumer<CoarseArea> result) {
        this.source = source;
        this.result = result;
    }

    void start(boolean supportsFused) {
        if (closed) return;
        started = source.elapsedMillis();
        if (!source.available()) { finish(null); return; }
        source.after(TIMEOUT_MS, () -> finish(null));
        if (supportsFused) {
            fusedPending = true;
            source.after(FUSED_GRACE_MS, this::network);
            source.read(true, area -> receive(true, area));
        } else {
            network();
        }
    }

    private void network() {
        if (closed || networkStarted) return;
        if (!usable()) { finish(null); return; }
        networkStarted = true;
        networkPending = true;
        source.read(false, area -> receive(false, area));
    }

    private void receive(boolean fused, CoarseArea area) {
        if (closed || !(fused ? fusedPending : networkPending)) return;
        if (fused) fusedPending = false;
        else networkPending = false;
        if (!usable()) { finish(null); return; }
        if (area != null) { finish(area); return; }
        network();
        if (!fusedPending && !networkPending) finish(null);
    }

    private boolean usable() {
        return source.elapsedMillis() - started < TIMEOUT_MS && source.available();
    }

    void cancel() {
        if (closed) return;
        closed = true;
        source.close();
    }

    private void finish(CoarseArea area) {
        if (closed) return;
        cancel();
        result.accept(area);
    }
}
