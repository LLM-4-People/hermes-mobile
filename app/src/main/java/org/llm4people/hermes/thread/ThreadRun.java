package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.os.SystemClock;

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
    private int retries;
    private String statusPath() { return "/v1/runs/" + HermesApi.segment(runId()); }
    private String runId() { return ThreadData.string(journal.pending(), "run_id"); }

    ThreadRun(ThreadWorkspace page) {
        this.page = page;
        journal = page.journal;
        ui = page.screen;
    }

    void send() {
        String text = ui.draft();
        if (submitting || journal.pending() != null || text.trim().isEmpty()) return;
        JSONObject body = ThreadData.body(page.sessionId(), text, page.host.options(page.sessionId()));
        submitWithCapabilities(body, true);
    }

    void restore() {
        JSONObject pending = journal.pending();
        if (pending == null) return;
        ui.outgoing(ThreadData.string(pending.optJSONObject("body"), "input"));
        ui.busy(true, !runId().isEmpty());
        if (runId().isEmpty()) submitWithCapabilities(pending.optJSONObject("body"), false);
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
                    journal.stage(body, System.currentTimeMillis(), idem.optLong("retention_seconds"),
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
                if (!ThreadData.canReplay(System.currentTimeMillis(), pending.optLong("at"), ttl,
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
                String original = ThreadData.string(body, "input");
                journal.finish();
                ui.restoreDraft(original);
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
        ui.busy(true, false);
        ui.notice("Hermes a peut-être reçu le message. Vérifiez la conversation avant un nouvel envoi.",
                "Vérifier la conversation", () -> page.loadMessages(false, this::offerDismiss));
    }

    private void offerDismiss() {
        ui.show(new AlertDialog.Builder(page.activity)
                .setTitle("Clore le suivi de cet envoi ?")
                .setMessage("L’envoi a un résultat inconnu. Hermes peut encore le traiter. Vous pourrez écrire un nouveau message.")
                .setNegativeButton("Continuer le suivi", (d, w) -> uncertain())
                .setPositiveButton("Clore le suivi", (d, w) -> {
                    journal.finish();
                    ui.outgoing("");
                    ui.busy(false, false);
                    ui.notice("", "", null);
                    ui.live("", "", false);
                }));
    }

    private void follow() {
        if (follower != null) follower.close();
        int token = page.token();
        String id = runId();
        if (id.isEmpty()) return;
        ui.busy(true, true);
        ui.live("Hermes travaille…", ThreadData.string(journal.pending(), "preview"), false);
        RunFollower subscription = new RunFollower(page.api, id, journal.pending(), started, new RunFollower.Listener() {
            @Override public void update(RunFollower.Frame next) {
                page.deliver(token, () -> {
                    if (journal.pending() == null) return;
                    frame = next;
                    ui.live(next.activity, next.text, next.approval != null);
                    ui.notice("", "", null);
                });
            }
            @Override public void complete(JSONObject status, long firstText, long elapsed) {
                page.deliver(token, () -> completed(status, firstText, elapsed));
            }
            @Override public void disconnected() {
                page.deliver(token, () -> {
                    checkpoint();
                    started = -1;
                    if (++retries <= 5) {
                        ui.notice("Connexion interrompue. Reconnexion en cours…", "", null);
                        page.main.postDelayed(() -> { if (token == page.token()) follow(); },
                                1000L << (retries - 1));
                    } else {
                        ui.notice("Suivi interrompu. Vous pouvez consulter la conversation.",
                                "Vérifier la conversation", () -> page.loadMessages(false, ThreadRun.this::reviewLost));
                    }
                });
            }
        });
        follower = subscription;
        page.workers.execute(subscription::follow);
    }

    private void reviewLost() {
        ui.notice("Le résultat reste inconnu.", "Reprendre le suivi", () -> { retries = 0; follow(); });
        ui.show(new AlertDialog.Builder(page.activity).setTitle("Suivi interrompu")
                .setMessage("Reprenez le suivi pour retrouver l’état du traitement sur la passerelle.")
                .setNegativeButton("Clore le suivi…", (d,w) -> offerDismiss())
                .setPositiveButton("Reprendre", (d,w) -> { retries = 0; follow(); }));
    }

    private void completed(JSONObject status, long firstText, long elapsed) {
        String outcome = status.optString("status");
        String output = ThreadData.string(status, "output");
        String label = switch (outcome) {
            case "completed" -> "Réponse terminée";
            case "cancelled" -> "Traitement arrêté";
            case "interrupted" -> "Traitement interrompu par la passerelle";
            default -> "Le traitement a échoué";
        };
        if ("completed".equals(outcome) && elapsed >= 0 && firstText >= 0) {
            JSONObject usage = status.optJSONObject("usage");
            long tokens = usage != null && "run".equals(usage.optString("scope"))
                    ? usage.optLong("output_tokens", -1) : -1;
            page.host.timing(page.sessionId(), firstText, elapsed, tokens);
        }
        frame = null;
        ui.busy(true, false);
        ui.live(label, output, false);
        page.loadMessages(false, () -> {
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
            ui.live("completed".equals(outcome) && (output.isEmpty() || visible) ? "" : label,
                    visible ? "" : output, false);
            ui.notice("", "", null);
        });
    }

    void stop() {
        if (runId().isEmpty()) return;
        control("/stop", new JSONObject(), "Demande d’arrêt envoyée.");
    }

    void approve() {
        JSONObject approval = frame == null ? null : frame.approval;
        if (approval == null) return;
        String request = ThreadData.string(approval, "request_id");
        if (request.isEmpty()) {
            ui.notice("Cette demande n’a pas d’identifiant. Examinez-la sur la passerelle.", "", null);
            return;
        }
        String detail = ThreadData.string(approval, "description");
        String command = ThreadData.string(approval, "command");
        if (!command.isEmpty()) detail += (detail.isEmpty() ? "" : "\n\n") + command;
        AlertDialog.Builder dialog = new AlertDialog.Builder(page.activity).setTitle("Approbation requise")
                .setMessage(detail.isEmpty() ? "Hermes demande votre autorisation." : detail)
                .setNegativeButton("Refuser", (d, w) -> decision(request, "deny"))
                .setNeutralButton("Plus tard", null);
        if (!detail.isEmpty()) dialog.setPositiveButton("Autoriser une fois", (d, w) -> decision(request, "once"));
        ui.show(dialog);
    }

    private void decision(String request, String choice) {
        if (frame == null || frame.approval == null || !request.equals(frame.approval.optString("request_id"))) return;
        JSONObject body = ThreadData.put(ThreadData.put(new JSONObject(), "request_id", request), "choice", choice);
        control("/approval", body, "Décision transmise.");
    }

    private void control(String route, JSONObject body, String message) {
        String path = statusPath() + route;
        page.request(() -> page.api.post(path, body),
                ok -> ui.notice(message, "", null),
                e -> page.error(e, () -> { if ("/approval".equals(route)) approve(); else stop(); }));
    }

    private void checkpoint() {
        if (frame == null || journal.pending() == null) return;
        journal.checkpoint(frame.seq, frame.text);
    }

    void detach() {
        if (follower != null) follower.close();
        checkpoint();
    }
}
