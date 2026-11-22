package org.llm4people.hermes.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.DialogInterface;
import android.os.Bundle;

import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.IncomingShare;
import org.llm4people.hermes.thread.ThreadWorkspace;

import java.util.Objects;

/** Binds a share to one unlock and confirms the exact destination before changing its draft. */
public final class SharedTextFlow {
    private final Activity activity;
    private final Runnable chooseDiscussion, showComposer;
    private String ticket, cleanupTicket, shownSession;
    private Account owner;
    private ThreadWorkspace workspace;
    private AlertDialog dialog;
    private boolean choosing;
    private String shownTitle;

    public SharedTextFlow(Activity activity, Runnable chooseDiscussion, Runnable showComposer) {
        this.activity = activity;
        this.chooseDiscussion = chooseDiscussion;
        this.showComposer = showComposer;
    }

    public void restore(Bundle state, Intent intent) {
        if (state != null) ticket = state.getString(ShareTextActivity.RECEIPT);
        else receive(intent);
    }

    public boolean receive(Intent intent) {
        if (intent == null || !ShareTextActivity.REVIEW.equals(intent.getAction())) return false;
        try {
            String key = intent.getStringExtra(ShareTextActivity.RECEIPT);
            if (key == null || key.length() != 36 || !key.equals(IncomingShare.pending(activity))) return false;
            cleanupTicket = null;
            ticket = key;
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }

    public void save(Bundle state) {
        String pending = ticket != null ? ticket : cleanupTicket;
        if (pending != null) state.putString(ShareTextActivity.RECEIPT, pending);
    }

    public String requestedProfile() {
        try { return IncomingShare.owner(activity, ticket != null ? ticket : cleanupTicket); }
        catch (RuntimeException unavailable) { return null; }
    }

    public void opened(Account account, ThreadWorkspace threads) {
        if (ticket == null) ticket = cleanupTicket;
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
        IncomingShare.Entry entry;
        String target;
        boolean imported;
        try {
            entry = IncomingShare.open(activity, key, account);
            target = entry == null ? session : entry.confirmedThread();
            imported = workspace.containsShare(target, key);
        } catch (RuntimeException unavailable) {
            failed("Impossible de vérifier le partage et son brouillon. Réessayez lorsque le stockage sera disponible.");
            return;
        }
        if (imported) {
            completed(key, target);
            return;
        }
        if (entry == null) {
            // A different unlock must neither display nor discard the owning profile's share.
            ticket = cleanupTicket = null;
            failed("Le partage a expiré ou appartient à un autre profil. Repartagez le texte depuis l’application d’origine.");
            return;
        }
        String text = entry.text();
        int end = Math.min(480, text.length());
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        String preview = end == text.length() ? text : text.substring(0, end) + "…";
        String destination = session == null ? "" : "\nDiscussion : " + shownTitle
                + "\nIdentifiant : " + HermesApi.segment(session);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(session == null ? "Choisir une discussion" : "Ajouter au brouillon ?")
                .setMessage("Profil : " + account.label + destination
                        + "\n\n" + preview + "\n\nLe texte sera ajouté à la suite du brouillon. Vous pourrez le modifier avant l’envoi.")
                .setNegativeButton("Annuler", (d,w) -> { if (owns(d, key, account, session)) cancel(); })
                .setOnCancelListener(d -> { if (d == dialog && key.equals(ticket) && owner == account) cancel(); });
        if (session != null) {
            builder.setPositiveButton("Ajouter au brouillon", (d,w) -> {
                if (!owns(d, key, account, session)) return;
                try {
                    if (!IncomingShare.target(activity, key, account, session)) {
                        failed("Le partage a expiré ou a déjà été confirmé pour une autre discussion.");
                        return;
                    }
                    if (!workspace.appendShare(session, key, text)) return;
                } catch (RuntimeException unavailable) {
                    failed("Le brouillon n’a pas pu être confirmé. Vérifiez le stockage puis réessayez.");
                    return;
                }
                completed(key, session);
            }).setNeutralButton("Autre discussion", (d,w) -> choose(d, key, account, session));
        } else builder.setPositiveButton("Choisir une discussion", (d,w) -> choose(d, key, account, null));
        dialog = Ui.secureDialog(builder);
    }

    private void completed(String key, String target) {
        ticket = null;
        cleanupTicket = key;
        dismiss();
        workspace.openSharedDraft(target);
        showComposer.run();
        cleanup(key);
    }

    private void cleanup(String key) {
        try {
            IncomingShare.discard(activity, key);
            cleanupTicket = null;
        } catch (RuntimeException unavailable) {
            Account account = owner;
            dialog = Ui.secureDialog(new AlertDialog.Builder(activity).setTitle("Texte ajouté au brouillon")
                    .setMessage("Votre brouillon est enregistré. La copie temporaire du partage n’a pas pu être effacée.")
                    .setPositiveButton("Modifier le brouillon", null)
                    .setNegativeButton("Réessayer le nettoyage", (d,w) -> {
                        if (d == dialog && owner == account && key.equals(cleanupTicket)) cleanup(key);
                    }));
        }
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
        AlertDialog.Builder alert = new AlertDialog.Builder(activity).setTitle("Partage indisponible").setMessage(message);
        if (ticket == null) alert.setPositiveButton("Fermer", null);
        else alert.setPositiveButton("Réessayer", (d,w) -> { if (owner != null && d == dialog) present(); })
                .setNegativeButton("Fermer", (d,w) -> { if (owner != null && d == dialog) leave(); })
                .setOnCancelListener(d -> { if (d == dialog) leave(); });
        dialog = Ui.secureDialog(alert);
    }

    private void cancel() {
        try { IncomingShare.discard(activity, ticket); }
        catch (RuntimeException failure) { failed("La copie temporaire du partage n’a pas pu être effacée."); return; }
        ticket = null;
        dismiss();
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

    public void leave() { lock(); ticket = cleanupTicket = null; }
}
