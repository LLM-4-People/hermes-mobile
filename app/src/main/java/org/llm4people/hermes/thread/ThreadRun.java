package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.LiveRunNotification;

/** Manages exactly one mobile-originated run in the selected thread. */
final class ThreadRun {
    private final ThreadWorkspace page;
    private final ThreadJournal journal;
    private final ThreadViews ui;
    private RunFollower follower;
    private RunFollower.Frame frame;
    private RunDecisions decisions;
    private boolean submitting;
    private Runnable afterAdmission;
    private boolean keepCompletionPosition;
    private long started = -1;
    private long subscription;
    private boolean canStop;
    private LiveRunNotification.Snapshot lastProgress = LiveRunNotification.Snapshot.idle();
    private RunAction owner() { return RunAction.capture(journal, page::token, () -> subscription); }
    private String runId() { return ThreadData.string(journal.pending(), "run_id"); }

    private void reportProgress(LiveRunNotification.Snapshot next) {
        LiveRunNotification.Snapshot safe = next == null ? LiveRunNotification.Snapshot.idle() : next;
        if (!LiveRunNotification.shouldEmit(lastProgress, safe)) return;
        lastProgress = safe;
        page.host.runProgress(safe);
    }

    ThreadRun(ThreadWorkspace page) {
        this.page = page;
        journal = page.journal;
        ui = page.screen;
    }

    boolean submitting() { return submitting; }

    void send() {
        String text = ui.draft();
        if (submitting || page.importingPhoto || journal.pending() != null || VisibleText.isBlank(text)) return;
        if (!journal.photoOwned()) {
            ui.notice("Le profil a changé. Retirez l’image et sélectionnez-la de nouveau.", "", null);
            return;
        }
        int token = page.token();
        java.util.function.BooleanSupplier original = journal.unchangedDraft(text);
        if (!page.nearby.beforeSend(text, () -> {
            if (page.token() == token && page.journal == journal && !page.importingPhoto && original.getAsBoolean()
                    && ui.root.isShown() && text.equals(ui.draft())) send();
        })) return;
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
        ui.outgoing(outgoing(pending));
        canStop = !runId().isEmpty();
        ui.busy(true, canStop);
        if (runId().isEmpty()) submitWithCapabilities(pending.optJSONObject("body"), false);
        else if (journal.reconnectsExhausted()) trackingInterrupted();
        else follow();
    }

    boolean afterAdmission(Runnable next) {
        if (!submitting) return false;
        afterAdmission = next;
        return true;
    }

    private void admissionFinished() {
        submitting = false;
        if (afterAdmission != null) page.main.post(afterAdmission);
        afterAdmission = null;
    }

    void refreshNotice(String notifiedRun, String observed) {
        // A completion refresh must never replay an unacknowledged send from the editor.
        if (submitting || observed == null && (runId().isEmpty() || !runId().equals(notifiedRun) || !journal.sameOwner())) return;
        if (observed != null && (!observed.equals(runId()) || !journal.sameOwner())) {
            detach();
            frame = null;
            started = -1;
            journal.observe(observed);
        } else checkpoint();
        keepCompletionPosition = true;
        if (journal.reconnectsExhausted()) journal.resetReconnects();
        restore();
    }

    private void submitWithCapabilities(JSONObject body, boolean fresh) {
        if (submitting) return;
        JSONObject selectedPhoto = journal.photo();
        long nearbyVersion = page.nearby.version();
        submitting = true;
        ui.busy(true, false);
        ui.notice(fresh ? "Préparation de l’envoi…" : "Vérification de l’envoi…", "", null);
        reportProgress(new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.STARTING, 0, 0, false, false));
        page.request(() -> page.api.get("/v1/capabilities"), capabilities -> {
            JSONObject features = capabilities.optJSONObject("features");
            JSONObject idem = features == null ? null : features.optJSONObject("runs_idempotency");
            if (body == null || idem == null || !idem.optBoolean("supported")) {
                admissionFinished();
                reportProgress(LiveRunNotification.Snapshot.idle());
                if (!fresh) { uncertain(); return; }
                ui.busy(false, false);
                ui.notice("Cette passerelle doit prendre en charge les envois identifiables. Mettez Hermes à jour.", "", null);
                return;
            }
            if (!fresh && !NearbyTurn.canReplay(journal.pending(), features)) {
                admissionFinished();
                reportProgress(LiveRunNotification.Snapshot.idle());
                uncertain();
                return;
            }
            boolean ignoresToolsets = body.has("allowed_toolsets") && !features.optBoolean("run_toolset_selection");
            boolean ignoresExclusions = body.has("excluded_tools") && !features.optBoolean("run_tool_exclusions");
            if (ignoresToolsets || ignoresExclusions) {
                admissionFinished();
                reportProgress(LiveRunNotification.Snapshot.idle());
                if (!fresh) { uncertain(); return; }
                ui.busy(false, false);
                ui.notice("Cette passerelle ne peut pas appliquer la sélection d’outils. Vérifiez les réglages de la conversation.", "", null);
                return;
            }
            if (fresh) {
                keepCompletionPosition = false;
                // Edits while capability discovery was in flight must stay in the composer.
                String snapshot = ThreadData.string(body, "input");
                JSONObject area = page.nearby.frozen();
                if (!snapshot.equals(ui.draft()) || selectedPhoto != journal.photo() || !page.nearby.stillCurrent(nearbyVersion, area)) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    ui.busy(false, false);
                    ui.notice("Le brouillon a changé. Vous pouvez l’envoyer.", "", null);
                    return;
                }
                if (area != null && !NearbyTurn.supportsScope(features)) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    ui.busy(false, false);
                    int token = page.token();
                    java.util.function.BooleanSupplier unchanged = journal.unchangedDraft(snapshot);
                    ui.notice("Mettez Hermes à jour pour partager une zone avec ce seul message.", "Envoyer sans position", () -> {
                        if (page.token() == token && page.journal == journal && unchanged.getAsBoolean()
                                && snapshot.equals(ui.draft())) page.nearby.omit(this::send);
                    });
                    return;
                }
                try {
                    NearbyTurn.attach(body, area);
                    journal.stage(body, SystemClock.elapsedRealtime(), bootCount(), idem.optLong("retention_seconds"),
                            idem.optBoolean("durable"), area);
                    page.nearby.clearTurn();
                } catch (IllegalStateException e) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    ui.busy(false, false);
                    page.storageError();
                    return;
                }
                ui.clearDraft();
                page.refreshPhoto();
                ui.outgoing(outgoing(journal.pending()));
                started = SystemClock.elapsedRealtime();
            } else {
                JSONObject pending = journal.pending();
                long ttl = Math.min(pending.optLong("ttl"), idem.optLong("retention_seconds"));
                if (!ThreadData.canReplay(SystemClock.elapsedRealtime(), pending.optLong("elapsed", -1),
                        bootCount(), pending.optInt("boot", -1), ttl,
                        pending.optBoolean("durable") && idem.optBoolean("durable"))) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    uncertain();
                    return;
                }
            }
            admit(body, fresh);
        }, error -> {
            admissionFinished();
            ui.busy(!fresh, false);
            reportProgress(LiveRunNotification.Snapshot.idle());
            page.error(error, fresh ? this::send : this::restore);
        });
    }

    private static String outgoing(JSONObject receipt) {
        String caption = ThreadData.string(receipt.optJSONObject("body"), "input");
        return receipt.has("photo") ? caption + "\nImage jointe" : caption;
    }

    private int bootCount() {
        return Settings.Global.getInt(page.activity.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private void admit(JSONObject body, boolean fresh) {
        String key = journal.pending().optString("key");
        ui.notice("Envoi à Hermes…", "", null);
        JSONObject image = journal.pending().optJSONObject("photo");
        page.request(() -> page.api.post("/v1/runs", journal.wire(body, image), key), response -> {
            admissionFinished();
            String id = ThreadData.string(response, "run_id");
            if (!ThreadData.validId(id)) { uncertain(); return; }
            journal.setRunId(id);
            ui.notice("", "", null);
            ui.busy(true, true);
            follow();
        }, error -> {
            admissionFinished();
            reportProgress(LiveRunNotification.Snapshot.idle());
            started = -1;
            // Only a definitive rejection of the first attempt can return the text to the draft.
            boolean imageUnavailable = image != null && error instanceof DiscussionPhoto.Unavailable;
            if (!fresh && imageUnavailable) { uncertain(); return; }
            if (fresh && (imageUnavailable || error instanceof HermesApi.HttpError http
                    && (http.status == 400 || http.status == 401 || http.status == 403 || http.status == 413 || http.status == 422))) {
                journal.reject();
                page.nearby.restore();
                ui.displayDraft(journal.draft());
                page.refreshPhoto();
                ui.outgoing("");
                ui.busy(false, false);
                if (imageUnavailable) ui.notice("L’image enregistrée est illisible. Retirez-la et sélectionnez-la de nouveau.", "", null);
                else if (image != null && error instanceof HermesApi.HttpError http
                        && (http.status == 400 || http.status == 413 || http.status == 422))
                    ui.notice("Hermes a refusé l’image. Vérifiez le modèle ou choisissez une autre image.", "", null);
                else page.error(error, this::send);
            } else {
                ui.notice("Accusé de réception manquant. L’envoi garde le même identifiant.",
                        "Vérifier l’envoi", this::restore);
            }
        });
    }

    private void uncertain() {
        reportProgress(LiveRunNotification.Snapshot.idle());
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
                    canStop = false;
                    if (finishTracking()) return;
                    ui.outgoing("");
                    ui.busy(false, false);
                    ui.notice("", "", null);
                    ui.live("", "", false);
                }));
    }

    private boolean finishTracking() {
        journal.finish();
        if (journal.pending() == null) return false;
        // Resume the preserved local receipt with its own cursor and retry state.
        frame = null;
        started = -1;
        ui.live("", "", false);
        restore();
        return true;
    }

    private void follow() {
        if (follower != null) follower.close();
        int token = page.token();
        long serial = ++subscription;
        JSONObject receipt = journal.pending();
        String id = runId();
        if (id.isEmpty()) return;
        if (decisions == null || !decisions.current()) decisions = new RunDecisions(page);
        decisions.observe(owner());
        frame = null;
        canStop = true;
        ui.busy(true, true);
        RunPreview saved = new RunPreview(receipt);
        ui.live("Hermes travaille…", saved.toString(), false, saved.origin());
        reportProgress(new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true));
        RunFollower nextFollower = new RunFollower(page.api, id, receipt, started, new RunFollower.Listener() {
            private boolean current() { return serial == subscription && receipt == journal.pending(); }

            private void deliver(Runnable update) {
                page.deliver(token, () -> { if (current()) update.run(); });
            }

            @Override public void update(RunFollower.Frame next) {
                deliver(() -> {
                    frame = next;
                    int count = next.prompts.size();
                    String activity = count == 0 ? next.activity : count == 1
                            ? "Hermes attend votre réponse" : count + " demandes en attente";
                    ui.live(activity, next.text, count > 0, next.origin);
                    decisions.present(next.prompts);
                    ui.notice("", "", null);
                    reportProgress(new LiveRunNotification.Snapshot(
                            count > 0 ? LiveRunNotification.Phase.AWAITING_DECISION
                                    : next.toolSteps > 0 ? LiveRunNotification.Phase.TOOL_RUNNING
                                    : LiveRunNotification.Phase.WORKING,
                            next.toolSteps, 0, count > 0, canStop));
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
                    decisions.disconnected();
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
        reportProgress(LiveRunNotification.Snapshot.idle());
        decisions.clear();
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
        reportProgress(LiveRunNotification.Snapshot.idle());
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
        reportProgress(LiveRunNotification.Snapshot.idle());
        decisions.clear();
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
        String visionHint = "failed".equals(outcome) && journal.pending().has("photo")
                ? "\nVérifiez le modèle ou le service de vision configuré sur la passerelle." : "";
        String summary = (detail.isEmpty() ? label : label + "\n" + detail) + visionHint;
        if ("completed".equals(outcome) && elapsed >= 0 && firstText >= 0) {
            JSONObject usage = status.optJSONObject("usage");
            long tokens = usage != null && "run".equals(usage.optString("scope"))
                    ? usage.optLong("output_tokens", -1) : -1;
            page.host.timing(page.sessionId(), firstText, elapsed, tokens);
        }
        frame = null;
        ui.busy(true, false);
        ui.live(summary, output, false);
        boolean keepPosition = keepCompletionPosition;
        keepCompletionPosition = false;
        page.loadMessages(false, owner.guard(() -> {
            JSONObject receipt = journal.pending();
            boolean enrolled = journal.sameOwner() && receipt.has("nearby_guide");
            if (finishTracking()) return;
            if (enrolled && page.nearby.granted()) {
                NearbyTurn.Guide guide = NearbyTurn.response(receipt, status, page.messages(), SystemClock.elapsedRealtime(), bootCount());
                if (guide != null) page.host.nearbyGuide(page, page.sessionId(), owner.run, guide.row(), guide.area());
            }
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
        }), keepPosition);
    }

    void stop() {
        RunAction owner = owner().when(() -> canStop && journal.sameOwner());
        if (owner.current() && !owner.run.isEmpty()) stop(owner, false);
    }

    private void stop(RunAction owner, boolean retry) {
        Runnable check = owner.guard(() -> stop(owner, true));
        if (retry) {
            page.request(() -> page.api.get(owner.path()), owner.guard(status -> {
                if (owner.stoppable(status)) stop(owner, false);
                else follow();
            }), owner.guard(error -> page.error(error, check)));
            return;
        }
        String path = owner.path() + "/stop";
        page.request(() -> page.api.post(path, new JSONObject()),
                owner.guard(ok -> ui.notice("Demande d’arrêt envoyée.", "", null)),
                owner.guard(error -> page.error(error, check)));
    }

    private void checkpoint() {
        if (frame == null || journal.pending() == null) return;
        journal.checkpoint(frame.seq, frame.text, frame.origin, frame.highSurrogate);
    }

    void detach() {
        ++subscription;
        canStop = false;
        reportProgress(LiveRunNotification.Snapshot.idle());
        if (decisions != null) decisions.clear();
        if (follower != null) follower.close();
        checkpoint();
    }
}
