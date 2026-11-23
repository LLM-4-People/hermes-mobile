package org.llm4people.hermes.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.IncomingMediaShare;
import org.llm4people.hermes.thread.ThreadWorkspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates inbound shared photos and documents from ShareMediaActivity through profile unlock,
 * explicit destination confirmation, ShareDraftTray + SmartIntake staging, and guaranteed cleanup.
 */
public final class SharedMediaFlow {
    private final Activity activity;
    private final Runnable chooseDiscussion;
    private final Runnable showComposer;
    private String ticket;
    private String shownSession;
    private String shownTitle;
    private Account owner;
    private ThreadWorkspace workspace;
    private AlertDialog dialog;
    private boolean choosing;

    public SharedMediaFlow(Activity activity, Runnable chooseDiscussion, Runnable showComposer) {
        this.activity = activity;
        this.chooseDiscussion = chooseDiscussion;
        this.showComposer = showComposer;
    }

    public void restore(Bundle state, Intent intent) {
        if (state != null) ticket = state.getString(ShareMediaActivity.MEDIA_RECEIPT);
        else receive(intent);
    }

    public boolean receive(Intent intent) {
        if (intent == null || !ShareMediaActivity.REVIEW_MEDIA.equals(intent.getAction())) return false;
        try {
            String key = intent.getStringExtra(ShareMediaActivity.MEDIA_RECEIPT);
            if (key == null || key.length() != 36 || !key.equals(IncomingMediaShare.pending(activity))) return false;
            ticket = key;
            return true;
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "media_receive_failed", unavailable);
            return false;
        }
    }

    public void save(Bundle state) {
        if (ticket != null) state.putString(ShareMediaActivity.MEDIA_RECEIPT, ticket);
    }

    public String requestedProfile() {
        if (ticket == null) return null;
        try { return IncomingMediaShare.owner(activity, ticket); }
        catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "media_owner_lookup_failed", unavailable);
            return null;
        }
    }

    public void opened(Account account, ThreadWorkspace threads) {
        if (ticket == null) return;
        owner = account;
        workspace = threads;
        present();
    }

    public void selected(String session) {
        if (owner == null || ticket == null) return;
        if (choosing && session == null) return;
        if (dialog != null && dialog.isShowing() && Objects.equals(session, shownSession)
                && Objects.equals(workspace.title(), shownTitle)) return;
        present();
    }

    private void present() {
        dismiss();
        choosing = false;
        String key = ticket;
        Account account = owner;
        String session = workspace.sessionId();
        shownSession = session;
        shownTitle = workspace.title();
        IncomingMediaShare.Batch batch;
        try {
            batch = IncomingMediaShare.open(activity, key, account);
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "media_open_failed", unavailable);
            failed("Impossible d’ouvrir les fichiers partagés. Réessayez depuis l’application d’origine.");
            return;
        }
        if (batch == null) {
            ticket = null;
            failed("Le partage a expiré ou appartient à un autre profil. Repartagez les fichiers depuis l’application d’origine.");
            return;
        }
        StringBuilder names = new StringBuilder();
        int count = batch.items().size();
        for (int i = 0; i < count; i++) {
            if (i > 0) names.append('\n');
            IncomingMediaShare.StagedFile f = batch.items().get(i);
            names.append("• ").append(f.name());
        }
        String destination = session == null ? "" : "\nDiscussion : " + shownTitle
                + "\nIdentifiant : " + HermesApi.segment(session);
        String summary = count == 1 ? "1 élément partagé :" : count + " éléments partagés :";
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(session == null ? "Choisir une discussion" : "Joindre au brouillon ?")
                .setMessage("Profil : " + account.label + destination
                        + "\n\n" + summary + "\n" + names
                        + "\n\nLes éléments seront ajoutés à votre brouillon. Rien n’est envoyé avant votre clic sur Envoyer.")
                .setNegativeButton("Annuler", (d, w) -> { if (owns(d, key, account, session)) cancel(); })
                .setOnCancelListener(d -> { if (d == dialog && key.equals(ticket) && owner == account) cancel(); });
        if (session != null) {
            builder.setPositiveButton("Ajouter au brouillon", (d, w) -> {
                if (!owns(d, key, account, session)) return;
                try {
                    if (!IncomingMediaShare.target(activity, key, account, session)) {
                        failed("Le partage a expiré ou a déjà été confirmé pour une autre discussion.");
                        return;
                    }
                    List<CapturedMedia.Item> items = toCapturedItems(batch.items());
                    if (!session.equals(workspace.sessionId())) return;
                    workspace.attachMedia(items);
                } catch (RuntimeException unavailable) {
                    Diagnostics.warning("share", "media_stage_failed", unavailable);
                    failed("Les fichiers n’ont pas pu être ajoutés au brouillon. Réessayez.");
                    return;
                }
                completed(key);
            }).setNeutralButton("Autre discussion", (d, w) -> choose(d, key, account, session));
        } else {
            builder.setPositiveButton("Choisir une discussion", (d, w) -> choose(d, key, account, null));
        }
        dialog = Ui.secureDialog(builder);
    }

    private static List<CapturedMedia.Item> toCapturedItems(List<IncomingMediaShare.StagedFile> files) {
        List<CapturedMedia.Item> out = new ArrayList<>(files.size());
        for (IncomingMediaShare.StagedFile f : files) {
            out.add(new CapturedMedia.Item(f.kind(), f.name(), f.mimeType(), f.bytes(), f.sha256Hex(), f.thumbnailBase64()));
        }
        return out;
    }

    private void completed(String key) {
        ticket = null;
        dismiss();
        try { IncomingMediaShare.discard(activity, key); }
        catch (RuntimeException cleanupFailed) { Diagnostics.warning("share", "media_cleanup_failed", cleanupFailed); }
        showComposer.run();
    }

    private void choose(DialogInterface source, String key, Account account, String session) {
        if (!owns(source, key, account, session)) return;
        dismiss();
        choosing = true;
        chooseDiscussion.run();
    }

    private boolean owns(DialogInterface source, String key, Account account, String session) {
        return source == dialog && dialog.isShowing() && key.equals(ticket) && account == owner
                && workspace != null && Objects.equals(session, workspace.sessionId());
    }

    private void failed(String message) {
        dismiss();
        AlertDialog.Builder alert = new AlertDialog.Builder(activity)
                .setTitle("Partage indisponible")
                .setMessage(message)
                .setPositiveButton("Fermer", (d, w) -> leave())
                .setOnCancelListener(d -> leave());
        dialog = Ui.secureDialog(alert);
    }

    private void cancel() {
        String key = ticket;
        ticket = null;
        dismiss();
        if (key != null) {
            try { IncomingMediaShare.discard(activity, key); }
            catch (RuntimeException failure) { Diagnostics.warning("share", "media_cancel_cleanup_failed", failure); }
        }
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    public void lock() {
        dismiss();
        owner = null;
        workspace = null;
        choosing = false;
        shownSession = shownTitle = null;
    }

    public void leave() {
        lock();
        ticket = null;
    }
}
