package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.EditText;
import org.json.JSONArray;

import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;

/** Manages exactly one mobile-originated run in the selected thread. */
final class ThreadRun {
    private final ThreadWorkspace page;
    private final ThreadJournal journal;
    private final ThreadViews ui;
    private RunFollower follower;
    private RunFollower.Frame frame;
    private boolean submitting;
    private long started = -1;
    private long subscription;
    private boolean canStop;
    private RunAction owner() { return RunAction.capture(journal, page::token, () -> subscription); }
    private String runId() { return ThreadData.string(journal.pending(), "run_id"); }

    ThreadRun(ThreadWorkspace page) {
        this.page = page;
        journal = page.journal;
        ui = page.screen;
    }

    void send() {
        String text = ui.draft();
        if (submitting || journal.pending() != null || text.trim().isEmpty()) return;
        JSONObject options;
        try {
            options = page.host.options(page.sessionId());
            if (options == null) throw new IllegalStateException("Missing session settings");
        }
        catch (RuntimeException unreadable) {
            ui.notice("Réglages illisibles. Vérifiez le modèle et les outils avant l’envoi.", "", null);
            return;
        }
        submitWithCapabilities(ThreadData.body(page.remoteSessionId(), text, options), true);
    }

    void restore() {
        JSONObject pending = journal.pending();
        if (pending == null) return;
        if (!journal.sameOwner()) { uncertain(); return; }
        ui.outgoing(ThreadData.string(pending.optJSONObject("body"), "input"));
        canStop = !runId().isEmpty();
        ui.busy(true, canStop);
        if (runId().isEmpty()) submitWithCapabilities(pending.optJSONObject("body"), false);
        else if (journal.reconnectsExhausted()) trackingInterrupted();
        else follow();
    }

    private void submitWithCapabilities(JSONObject body, boolean fresh) {
        if (submitting) return;
        submitting = true;
        ui.busy(true, false);
        ui.notice(fresh ? "Préparation de l’envoi…" : "Vérification de l’envoi…", "", null);
        page.request(() -> page.api.get("/v1/capabilities"), capabilities -> {
            JSONObject features = capabilities.optJSONObject("features");
            JSONObject idem = features == null ? null : features.optJSONObject("runs_idempotency");
            if (body == null || idem == null || !idem.optBoolean("supported")) {
                submitting = false;
                if (!fresh) { uncertain(); return; }
                ui.busy(false, false);
                ui.notice("Cette passerelle doit prendre en charge les envois identifiables. Mettez Hermes à jour.", "", null);
                return;
            }
            boolean ignoresToolsets = body.has("allowed_toolsets") && !features.optBoolean("run_toolset_selection");
            boolean ignoresExclusions = body.has("excluded_tools") && !features.optBoolean("run_tool_exclusions");
            if (ignoresToolsets || ignoresExclusions) {
                submitting = false;
                if (!fresh) { uncertain(); return; }
                ui.busy(false, false);
                ui.notice("Cette passerelle ne peut pas appliquer la sélection d’outils. Vérifiez les réglages de la conversation.", "", null);
                return;
            }
            if (fresh) {
                // Edits while capability discovery was in flight must stay in the composer.
                String snapshot = ThreadData.string(body, "input");
                if (!snapshot.equals(ui.draft())) {
                    submitting = false;
                    ui.busy(false, false);
                    ui.notice("Le brouillon a changé. Vous pouvez l’envoyer.", "", null);
                    return;
                }
                try {
                    journal.stage(body, SystemClock.elapsedRealtime(), bootCount(), idem.optLong("retention_seconds"),
                            idem.optBoolean("durable"));
                } catch (IllegalStateException e) {
                    submitting = false;
                    ui.busy(false, false);
                    page.storageError();
                    return;
                }
                ui.clearDraft();
                ui.outgoing(snapshot);
                started = SystemClock.elapsedRealtime();
            } else {
                JSONObject pending = journal.pending();
                long ttl = Math.min(pending.optLong("ttl"), idem.optLong("retention_seconds"));
                if (!ThreadData.canReplay(SystemClock.elapsedRealtime(), pending.optLong("elapsed", -1),
                        bootCount(), pending.optInt("boot", -1), ttl,
                        pending.optBoolean("durable") && idem.optBoolean("durable"))) {
                    submitting = false;
                    uncertain();
                    return;
                }
            }
            admit(body, fresh);
        }, error -> {
            submitting = false;
            ui.busy(!fresh, false);
            page.error(error, fresh ? this::send : this::restore);
        });
    }

    private int bootCount() {
        return Settings.Global.getInt(page.activity.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private void admit(JSONObject body, boolean fresh) {
        String key = journal.pending().optString("key");
        ui.notice("Envoi à Hermes…", "", null);
        page.request(() -> page.api.post("/v1/runs", body, key), response -> {
            submitting = false;
            String id = ThreadData.string(response, "run_id");
            if (!ThreadData.validId(id)) { uncertain(); return; }
            journal.setRunId(id);
            ui.notice("", "", null);
            ui.busy(true, true);
            follow();
        }, error -> {
            submitting = false;
            started = -1;
            // Only a definitive rejection of the first attempt can return the text to the draft.
            if (fresh && error instanceof HermesApi.HttpError http
                    && (http.status == 400 || http.status == 401 || http.status == 403 || http.status == 422)) {
                journal.reject();
                ui.displayDraft(journal.draft());
                ui.outgoing("");
                ui.busy(false, false);
                page.error(error, this::send);
            } else {
                ui.notice("Accusé de réception manquant. L’envoi garde le même identifiant.",
                        "Vérifier l’envoi", this::restore);
            }
        });
    }

    private void uncertain() {
        RunAction owner = owner();
        canStop = false;
        ui.busy(true, false);
        ui.notice("Hermes a peut-être reçu le message. Vérifiez la conversation avant un nouvel envoi.",
                "Vérifier la conversation", owner.guard(() -> page.loadMessages(false,
                        owner.guard(() -> offerDismiss(owner)))));
    }

    private void offerDismiss(RunAction owner) {
        ui.show(new AlertDialog.Builder(page.activity)
                .setTitle("Clore le suivi de cet envoi ?")
                .setMessage("L’envoi a un résultat inconnu. Hermes peut encore le traiter. Vous pourrez écrire un nouveau message.")
                .setNegativeButton("Continuer le suivi", (d, w) -> { if (owner.current()) uncertain(); })
                .setPositiveButton("Clore le suivi", (d, w) -> {
                    if (!owner.current()) return;
                    journal.finish();
                    canStop = false;
                    ui.outgoing("");
                    ui.busy(false, false);
                    ui.notice("", "", null);
                    ui.live("", "", false);
                }));
    }

    private void follow() {
        if (follower != null) follower.close();
        int token = page.token();
        long serial = ++subscription;
        JSONObject receipt = journal.pending();
        String id = runId();
        if (id.isEmpty()) return;
        frame = null;
        canStop = true;
        ui.busy(true, true);
        ui.live("Hermes travaille…", ThreadData.string(journal.pending(), "preview"), false);
        RunFollower nextFollower = new RunFollower(page.api, id, receipt, started, new RunFollower.Listener() {
            private boolean current() { return serial == subscription && receipt == journal.pending(); }

            private void deliver(Runnable update) {
                page.deliver(token, () -> { if (current()) update.run(); });
            }

            @Override public void update(RunFollower.Frame next) {
                deliver(() -> {
                    frame = next;
                    ui.live(next.activity, next.text, next.approval != null);
                    ui.notice("", "", null);
                });
            }
            @Override public void complete(JSONObject status, long firstText, long elapsed) {
                deliver(() -> completed(status, firstText, elapsed));
            }
            @Override public void unknown(JSONObject status) {
                deliver(() -> unresolved(status));
            }
            @Override public void disconnected() {
                deliver(() -> {
                    checkpoint();
                    started = -1;
                    long delay = journal.nextReconnectDelayMillis();
                    if (delay < 0) { trackingInterrupted(); return; }
                    ui.notice("Connexion interrompue. Reconnexion en cours…", "", null);
                    page.main.postDelayed(() -> { if (token == page.token() && current()) follow(); }, delay);
                });
            }
        });
        follower = nextFollower;
        page.workers.execute(nextFollower::follow);
    }

    private void unresolved(JSONObject status) {
        ++subscription;
        canStop = false;
        frame = null;
        started = -1;
        ui.busy(true, false);
        ui.live("Traitement interrompu", "", false);
        RunAction owner = owner();
        JSONObject receipt = ThreadData.unknownReceipt(status);
        if (receipt == null) {
            ui.notice("La passerelle doit confirmer l’identité du traitement.", "Actualiser", owner.guard(this::follow));
            return;
        }
        ui.notice("La passerelle attend votre décision pour débloquer la conversation.", "Examiner", owner.guard(() ->
            ui.show(new AlertDialog.Builder(page.activity).setTitle("Débloquer la conversation ?")
                    .setMessage("Hermes a perdu le suivi de ce traitement. Le clore permet à la passerelle de "
                            + "poursuivre les messages suivants. Les opérations déjà effectuées restent en place.")
                    .setNegativeButton("Attendre", (d,w) -> { if (owner.current()) follow(); })
                    .setPositiveButton("Clore le traitement", (d,w) -> { if (owner.current()) resolve(owner, receipt); }))));
    }

    private void resolve(RunAction owner, JSONObject receipt) {
        String path = owner.path();
        ui.notice("Vérification du traitement…", "", null);
        page.request(() -> page.api.get(path), owner.guard(status -> {
            JSONObject current = ThreadData.unknownReceipt(status);
            if (!owner.run.equals(status.opt("run_id")) || current == null
                    || !current.optString("admission_id").equals(receipt.optString("admission_id"))
                    || current.optLong("execution_generation") != receipt.optLong("execution_generation")) {
                follow();
                return;
            }
            page.request(() -> page.api.post(path + "/resolve-unknown", receipt),
                    owner.guard(done -> follow()), owner.guard(error -> page.error(error, owner.guard(this::follow))));
        }), owner.guard(error -> page.error(error, owner.guard(this::follow))));
    }

    private void trackingInterrupted() {
        RunAction owner = owner().when(journal::reconnectsExhausted);
        ui.notice("Suivi interrompu. Vous pouvez consulter la conversation.",
                "Vérifier la conversation", owner.guard(() -> page.loadMessages(false,
                        owner.guard(() -> reviewLost(owner)))));
    }

    private void reviewLost(RunAction owner) {
        Runnable resume = owner.guard(() -> {
            try { journal.resetReconnects(); }
            catch (IllegalStateException unavailable) { page.storageError(); return; }
            follow();
        });
        ui.notice("Le résultat reste inconnu.", "Reprendre le suivi", resume);
        ui.show(new AlertDialog.Builder(page.activity).setTitle("Suivi interrompu")
                .setMessage("Reprenez le suivi pour retrouver l’état du traitement sur la passerelle.")
                .setNegativeButton("Clore le suivi…", (d,w) -> { if (owner.current()) offerDismiss(owner); })
                .setPositiveButton("Reprendre", (d,w) -> resume.run()));
    }

    private void completed(JSONObject status, long firstText, long elapsed) {
        ++subscription;
        canStop = false;
        RunAction owner = owner();
        String outcome = status.optString("status");
        String output = ThreadData.string(status, "output");
        String label = switch (outcome) {
            case "completed" -> "Réponse terminée";
            case "cancelled" -> "Traitement arrêté";
            case "interrupted" -> "Traitement interrompu par la passerelle";
            default -> "Le traitement a échoué";
        };
        String detail = "failed".equals(outcome) ? page.failureDetail(status) : "";
        String summary = detail.isEmpty() ? label : label + "\n" + detail;
        if ("completed".equals(outcome) && elapsed >= 0 && firstText >= 0) {
            JSONObject usage = status.optJSONObject("usage");
            long tokens = usage != null && "run".equals(usage.optString("scope"))
                    ? usage.optLong("output_tokens", -1) : -1;
            page.host.timing(page.sessionId(), firstText, elapsed, tokens);
        }
        frame = null;
        ui.busy(true, false);
        ui.live(summary, output, false);
        page.loadMessages(false, owner.guard(() -> {
            journal.finish();
            ui.outgoing("");
            ui.busy(false, false);
            boolean visible = false;
            for (int i = page.messages().length() - 1; i >= 0; i--) {
                JSONObject row = page.messages().optJSONObject(i);
                if (row != null && "assistant".equals(row.optString("role"))) {
                    visible = output.equals(ThreadData.content(row));
                    break;
                }
            }
            ui.live("completed".equals(outcome) && (output.isEmpty() || visible) ? "" : summary,
                    visible ? "" : output, false);
            ui.notice("", "", null);
        }));
    }

    void stop() {
        RunAction owner = owner().when(() -> canStop && journal.sameOwner());
        if (owner.current() && !owner.run.isEmpty()) stop(owner, false);
    }

    private void stop(RunAction owner, boolean retry) {
        if (retry) {
            page.request(() -> page.api.get(owner.path()), owner.guard(status -> {
                if (owner.stoppable(status)) stop(owner, false);
                else follow();
            }), owner.guard(error -> page.error(error, owner.guard(() -> stop(owner, true)))));
            return;
        }
        control(owner, "/stop", new JSONObject(), "Demande d’arrêt envoyée.", () -> stop(owner, true));
    }

    void approve() {
        JSONObject approval = frame == null ? null : frame.approval;
        if (approval == null) return;
        RunAction owner = owner().when(() -> journal.sameOwner() && frame != null && RunPrompts.same(approval, frame.approval));
        if (owner.current()) approve(owner, approval);
    }

    private void approve(RunAction owner, JSONObject approval) {
        if (RunPrompts.question(approval)) {
            clarify(owner, approval);
            return;
        }
        String detail = ThreadData.string(approval, "description");
        String command = ThreadData.string(approval, "command");
        if (!command.isEmpty()) detail += (detail.isEmpty() ? "" : "\n\n") + command;
        AlertDialog.Builder dialog = new AlertDialog.Builder(page.activity).setTitle("Approbation requise")
                .setMessage(detail.isEmpty() ? "Hermes demande votre autorisation." : detail)
                .setNegativeButton("Refuser", (d, w) -> decision(owner, approval, "deny"))
                .setNeutralButton("Plus tard", null);
        if (!detail.isEmpty()) dialog.setPositiveButton("Autoriser une fois", (d, w) -> decision(owner, approval, "once"));
        ui.show(dialog);
    }

    private void clarify(RunAction owner, JSONObject prompt) {
        EditText answer = new EditText(page.activity);
        answer.setHint("Votre réponse");
        answer.setSaveEnabled(false);
        JSONArray choices = prompt.optJSONArray("choices");
        StringBuilder question = new StringBuilder(ThreadData.string(prompt, "question"));
        if (choices != null) {
            for (int i = 0; i < choices.length(); i++) {
                question.append("\n• ").append(choices.optString(i));
            }
        }
        ui.show(new AlertDialog.Builder(page.activity).setTitle("Question de Hermes")
                .setMessage(question).setView(answer).setNegativeButton("Plus tard", null)
                .setNeutralButton("Passer", (d,w) -> decision(owner, prompt, ""))
                .setPositiveButton("Répondre", (d,w) -> {
                    String reply = answer.getText().toString().trim();
                    if (!reply.isEmpty()) decision(owner, prompt, reply);
                }));
    }

    private void decision(RunAction owner, JSONObject prompt, String value) {
        if (!owner.current()) return;
        control(owner, RunPrompts.question(prompt) ? "/clarify" : "/approval", RunPrompts.response(prompt, value),
                "Décision transmise.", () -> reviewDecision(owner, prompt));
    }

    private void reviewDecision(RunAction owner, JSONObject prompt) {
        page.request(() -> page.api.get(owner.path()), owner.guard(status -> {
            RunPrompts waiting = new RunPrompts();
            waiting.restore(status);
            JSONObject current = waiting.match(prompt);
            if (owner.stoppable(status) && current != null) approve(owner, current);
            else follow();
        }), owner.guard(error -> page.error(error, owner.guard(() -> reviewDecision(owner, prompt)))));
    }

    private void control(RunAction owner, String route, JSONObject body, String message, Runnable retry) {
        String path = owner.path() + route;
        page.request(() -> page.api.post(path, body),
                owner.guard(ok -> ui.notice(message, "", null)),
                owner.guard(error -> page.error(error, owner.guard(retry))));
    }

    private void checkpoint() {
        if (frame == null || journal.pending() == null) return;
        journal.checkpoint(frame.seq, frame.text);
    }

    void detach() {
        ++subscription;
        canStop = false;
        if (follower != null) follower.close();
        checkpoint();
    }
}
