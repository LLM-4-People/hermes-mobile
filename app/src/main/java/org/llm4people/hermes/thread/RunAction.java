package org.llm4people.hermes.thread;

import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** A delayed UI action belongs to the receipt and observation that originally offered it. */
final class RunAction {
    final String run;
    private final BooleanSupplier valid;

    private RunAction(String run, BooleanSupplier valid) { this.run = run; this.valid = valid; }

    static RunAction capture(ThreadJournal journal, IntSupplier view, LongSupplier observation) {
        JSONObject receipt = journal.pending();
        String run = ThreadData.string(receipt, "run_id");
        int opened = view.getAsInt();
        long observed = observation.getAsLong();
        return new RunAction(run, () -> receipt != null && receipt == journal.pending()
                && opened == view.getAsInt() && observed == observation.getAsLong()
                && run.equals(ThreadData.string(receipt, "run_id")));
    }

    RunAction when(BooleanSupplier condition) { return new RunAction(run, () -> current() && condition.getAsBoolean()); }
    boolean current() { return valid.getAsBoolean(); }
    String path() { return "/v1/runs/" + HermesApi.segment(run); }
    Runnable guard(Runnable action) { return () -> { if (current()) action.run(); }; }
    <T> Consumer<T> guard(Consumer<T> action) { return value -> { if (current()) action.accept(value); }; }

    boolean stoppable(JSONObject status) {
        if (status == null || !run.equals(status.opt("run_id"))) return false;
        return switch (ThreadData.string(status, "status")) {
            case "queued", "running", "waiting_for_approval", "waiting_for_input" -> true;
            default -> false;
        };
    }
}
