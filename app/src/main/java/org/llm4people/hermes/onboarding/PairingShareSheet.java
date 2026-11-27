package org.llm4people.hermes.onboarding;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * Reusable single-source-of-truth modal displaying a scannable vector QR code ({@link QrCodeView}),
 * live 1-second expiry countdown, 1-tap Android Share sheet, Copy link, and optional Revoke action.
 * Always wrapped in {@link Ui#secureDialog} so pairing tickets never leak into screenshots/recents.
 */
public final class PairingShareSheet implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private Runnable tick;
    private boolean closed;

    private PairingShareSheet() {}

    /** Formats a countdown duration cleanly as {@code M:SS} (never negative). */
    public static String formatCountdown(long remainingSeconds) {
        long clamped = Math.max(0L, remainingSeconds);
        long minutes = clamped / 60L;
        long seconds = clamped % 60L;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /** Copies a pairing link cleanly to the system clipboard as sensitive content. Returns true on success. */
    public static boolean copyLink(Context context, String deepLink) {
        if (context == null || deepLink == null || deepLink.trim().isEmpty()) return false;
        try {
            return Ui.copySensitiveText(context, context.getString(R.string.app_name), deepLink.trim());
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("onboarding", "clipboard_copy_failed");
            return false;
        }
    }

    /** Opens Android's native share chooser for a pairing or invite deep link. */
    public static void shareLink(Context context, String deepLink) {
        if (context == null || deepLink == null || deepLink.trim().isEmpty()) return;
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.profile_hub_qr_share_subject));
            share.putExtra(Intent.EXTRA_TEXT, deepLink.trim());
            Intent chooser = Intent.createChooser(share, context.getString(R.string.profile_hub_qr_share_action));
            if (!(context instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            Diagnostics.event("onboarding", "pairing_link_shared");
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("onboarding", "share_intent_unavailable");
        }
    }

    public static PairingShareSheet show(
            Activity activity,
            OnboardingModels.PairingTicket ticket,
            HermesApi api,
            ExecutorService io,
            BooleanSupplier canAct,
            Runnable onRevoked) {
        return show(activity, ticket, api, io, canAct, onRevoked, null);
    }

    public static PairingShareSheet show(
            Activity activity,
            OnboardingModels.PairingTicket ticket,
            HermesApi api,
            ExecutorService io,
            BooleanSupplier canAct,
            Runnable onRevoked,
            Runnable onDismissed) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(ticket, "ticket");
        PairingShareSheet sheet = new PairingShareSheet();
        sheet.open(activity, ticket, api, io, canAct, onRevoked, onDismissed);
        return sheet;
    }

    private void open(
            Activity activity,
            OnboardingModels.PairingTicket ticket,
            HermesApi api,
            ExecutorService io,
            BooleanSupplier canAct,
            Runnable onRevoked,
            Runnable onDismissed) {
        String link = ticket.deepLink();
        boolean isInvite = "invite".equalsIgnoreCase(ticket.mode());
        String targetName = !ticket.displayName().isEmpty() ? ticket.displayName() : ticket.profileSlug();
        String title = isInvite && !targetName.isEmpty()
                ? activity.getString(R.string.profile_hub_qr_title_invite, targetName)
                : activity.getString(R.string.profile_hub_qr_title_device);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = Ui.column(activity);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(body, 20, 14);
        scroll.addView(body);

        TextView hint = Ui.text(activity, activity.getString(R.string.profile_hub_qr_hint), 14);
        hint.setTextColor(activity.getColor(R.color.muted));
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(hint);

        QrCodeView qr = new QrCodeView(activity);
        qr.setContentDescription(title);
        boolean rendered = !link.isEmpty() && qr.setPayload(link);
        if (rendered) {
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                    Ui.dp(activity, 232), Ui.dp(activity, 232));
            qrLp.topMargin = Ui.dp(activity, 14);
            qrLp.bottomMargin = Ui.dp(activity, 12);
            body.addView(qr, qrLp);
        }

        TextView countdownView = Ui.text(activity, "", 14);
        countdownView.setTypeface(Ui.mediumTypeface());
        countdownView.setPaddingRelative(Ui.dp(activity, 12), Ui.dp(activity, 6),
                Ui.dp(activity, 12), Ui.dp(activity, 6));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(Ui.dp(activity, 999));
        pillBg.setColor(activity.getColor(R.color.accent_surface));
        countdownView.setBackground(pillBg);
        countdownView.setTextColor(activity.getColor(R.color.accent));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.bottomMargin = Ui.dp(activity, 14);
        body.addView(countdownView, pillLp);

        int touchMin = Ui.dp(activity, 48);
        LinearLayout actions = Ui.column(activity);
        Button shareBtn = Ui.button(activity, activity.getString(R.string.profile_hub_qr_share_action),
                v -> shareLink(activity, link));
        shareBtn.setMinHeight(touchMin);
        shareBtn.setMinimumHeight(touchMin);
        shareBtn.setEnabled(!link.isEmpty());
        actions.addView(shareBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button copyBtn = Ui.button(activity, activity.getString(R.string.profile_hub_qr_copy_action), v -> {
            if (copyLink(activity, link)) {
                Toast.makeText(activity, R.string.profile_hub_qr_copied, Toast.LENGTH_SHORT).show();
            }
        });
        copyBtn.setMinHeight(touchMin);
        copyBtn.setMinimumHeight(touchMin);
        copyBtn.setEnabled(!link.isEmpty());
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = Ui.dp(activity, 8);
        actions.addView(copyBtn, copyLp);

        if (api != null && io != null && !ticket.ticketId().isEmpty()) {
            Button revokeBtn = Ui.button(activity, activity.getString(R.string.profile_hub_qr_revoke_action), null);
            revokeBtn.setMinHeight(touchMin);
            revokeBtn.setMinimumHeight(touchMin);
            revokeBtn.setTextColor(Ui.dangerInk(activity));
            revokeBtn.setOnClickListener(v -> {
                if (closed || io.isShutdown()) return;
                revokeBtn.setEnabled(false);
                if (!submitRevoke(closed, io, () -> {
                    boolean ok = false;
                    try {
                        if (!api.isRevoked()) {
                            api.revokePairing(ticket.ticketId());
                            ok = true;
                        }
                    } catch (Exception err) {
                        Diagnostics.warning("onboarding", "pairing_revoke_failed");
                    }
                    boolean revoked = ok;
                    main.post(() -> {
                        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                        if (canAct != null && !canAct.getAsBoolean()) return;
                        if (revoked) {
                            Toast.makeText(activity, R.string.profile_hub_qr_revoked, Toast.LENGTH_SHORT).show();
                            if (onRevoked != null) onRevoked.run();
                            close();
                        } else {
                            revokeBtn.setEnabled(true);
                        }
                    });
                })) {
                    revokeBtn.setEnabled(true);
                }
            });
            LinearLayout.LayoutParams revLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            revLp.topMargin = Ui.dp(activity, 8);
            actions.addView(revokeBtn, revLp);
        }
        body.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        long fallbackExpiry = (System.currentTimeMillis() / 1000L)
                + (ticket.ttlSeconds() > 0 ? ticket.ttlSeconds() : 300);
        long expiresAt = ticket.expiresAtEpochSec() > 0L ? ticket.expiresAtEpochSec() : fallbackExpiry;

        tick = new Runnable() {
            @Override public void run() {
                if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                long nowSec = System.currentTimeMillis() / 1000L;
                long remaining = Math.max(0L, expiresAt - nowSec);
                if (remaining <= 0L) {
                    countdownView.setText(activity.getString(R.string.profile_hub_qr_expired));
                    countdownView.setTextColor(activity.getColor(R.color.danger));
                    pillBg.setColor(activity.getColor(R.color.danger_surface));
                    shareBtn.setEnabled(false);
                    copyBtn.setEnabled(false);
                    return;
                }
                countdownView.setText(activity.getString(R.string.profile_hub_qr_expires, formatCountdown(remaining)));
                main.postDelayed(this, 1000L);
            }
        };
        tick.run();

        dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.ws_close, null)
                .setOnDismissListener(d -> {
                    closed = true;
                    stopTicker();
                    dialog = null;
                    if (onDismissed != null) onDismissed.run();
                }));
    }

    /** Pure-JVM testable executor submission guard preventing post-close RejectedExecutionException. */
    public static boolean submitRevoke(boolean isClosed, ExecutorService io, Runnable task) {
        if (isClosed || io == null || io.isShutdown() || task == null) return false;
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            Diagnostics.warning("onboarding", "pairing_revoke_rejected", rejected);
            return false;
        }
    }

    private void stopTicker() {
        if (tick != null) {
            main.removeCallbacks(tick);
            tick = null;
        }
    }

    @Override public void close() {
        closed = true;
        stopTicker();
        if (dialog != null) {
            try { dialog.dismiss(); } catch (RuntimeException ignored) {}
            dialog = null;
        }
    }
}
