package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingShareSheet;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Effortless native Profile &amp; Device Hub sheet accessible from header, Profile action, and
 * Command Palette ({@code Ctrl+K}). Displays visual avatar cards, active-run badges, 1-tap profile
 * switching, inline profile editing, 5-minute QR device pairing, and admin shortcuts into
 * {@link AdminConsoleSheet}.
 */
public final class ProfileHubSheet implements AutoCloseable {
    private static final Pattern VALID_SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,31}");

    public interface Callbacks {
        void onSwitchProfile(String profileId);
        void onAddProfile();
        void onScanQrCode();
        void onOpenAdminConsole();
        void onAdminStateResolved(boolean isAdmin);
    }

    private final Activity activity;
    private final Account account;
    private final HermesApi api;
    private final int activeRunCount;
    private final BooleanSupplier canAct;
    private final Callbacks callbacks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-profile-hub");
        t.setDaemon(true);
        return t;
    });

    private AlertDialog sheetDialog;
    private AlertDialog childDialog;
    private PairingShareSheet shareSheet;
    private LinearLayout serverSlot;
    private LinearLayout heroBadgesRow;
    private Button adminButton;
    private Button inviteButton;
    private boolean isAdmin;
    private boolean closed;

    public ProfileHubSheet(
            Activity activity,
            Account account,
            int activeRunCount,
            boolean knownAdmin,
            BooleanSupplier canAct,
            Callbacks callbacks) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.account = Objects.requireNonNull(account, "account");
        this.activeRunCount = Math.max(0, activeRunCount);
        this.isAdmin = knownAdmin;
        this.canAct = Objects.requireNonNull(canAct, "canAct");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.api = new HermesApi(account.endpoint, account.bearer,
                HermesApi.Limits.from(HermesConfig.load(activity)));
    }

    /** Validates a newly created server profile slug (2..32 lowercase ASCII letters, digits, hyphens). */
    public static boolean isValidNewSlug(String raw) {
        return raw != null && VALID_SLUG.matcher(raw.trim()).matches();
    }

    /** Derives a safe lowercase slug suggestion from a human display name (accent-folded). */
    public static String suggestSlug(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";
        String folded = PlainTextSanitizer.foldAccents(displayName.trim());
        StringBuilder sb = new StringBuilder();
        boolean prevDash = true;
        for (int i = 0; i < folded.length() && sb.length() < 32; i++) {
            char c = folded.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevDash = false;
            } else if (!prevDash && sb.length() > 0) {
                sb.append('-');
                prevDash = true;
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** Counts how many profiles currently hold admin privileges. */
    public static int countAdmins(List<OnboardingModels.RemoteProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) return 0;
        int count = 0;
        for (OnboardingModels.RemoteProfile p : profiles) {
            if (p != null && p.isAdmin()) count++;
        }
        return count;
    }

    /**
     * Fail-closed last-admin guard: returns false when {@code target} is the sole remaining admin,
     * matching Track 19A's server-side {@code 409 Conflict} invariant.
     */
    public static boolean canDemoteOrDeleteAdmin(
            List<OnboardingModels.RemoteProfile> profiles,
            OnboardingModels.RemoteProfile target) {
        if (target == null) return false;
        if (!target.isAdmin()) return true;
        return countAdmins(profiles) > 1;
    }

    public void show() {
        if (closed || !canAct.getAsBoolean()) return;
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 18, 14);
        scroll.addView(body);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.profile_hub_subtitle), 14);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        body.addView(subtitle);

        // Active profile hero card
        body.addView(buildActiveHeroCard(), topMargin(14));

        // Local profiles on this device
        TextView localHeading = Ui.text(activity, activity.getString(R.string.profile_hub_local_section), 14);
        localHeading.setTypeface(Ui.mediumTypeface());
        localHeading.setAccessibilityHeading(true);
        body.addView(localHeading, topMargin(18));

        Map<String, String> localProfiles = new AccountStore(activity).profiles();
        int otherLocalCount = 0;
        for (Map.Entry<String, String> entry : localProfiles.entrySet()) {
            if (account.id.equals(entry.getKey())) continue;
            otherLocalCount++;
            body.addView(buildLocalProfileRow(entry.getKey(), entry.getValue()), topMargin(8));
        }
        if (otherLocalCount == 0) {
            TextView emptyLocal = Ui.text(activity, activity.getString(R.string.profile_hub_local_empty), 13);
            emptyLocal.setTextColor(activity.getColor(R.color.muted));
            body.addView(emptyLocal, topMargin(6));
        }

        Button scanQrBtn = Ui.tonalButton(activity, activity.getString(R.string.profile_hub_scan_qr_action), v -> {
            close();
            callbacks.onScanQrCode();
        });
        body.addView(scanQrBtn, topMargin(12));

        Button addLocalBtn = Ui.outlinedButton(activity, activity.getString(R.string.profile_add_action), v -> {
            close();
            callbacks.onAddProfile();
        });
        body.addView(addLocalBtn, topMargin(8));

        // Server profiles & remote status section
        TextView serverHeading = Ui.text(activity, activity.getString(R.string.profile_hub_server_section), 14);
        serverHeading.setTypeface(Ui.mediumTypeface());
        serverHeading.setAccessibilityHeading(true);
        body.addView(serverHeading, topMargin(18));

        serverSlot = Ui.column(activity);
        TextView loading = Ui.text(activity, activity.getString(R.string.profile_hub_loading), 13);
        loading.setTextColor(activity.getColor(R.color.muted));
        serverSlot.addView(loading);
        body.addView(serverSlot, topMargin(8));

        sheetDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.profile_hub_title)
                .setView(scroll)
                .setPositiveButton(R.string.ws_close, null)
                .setOnDismissListener(d -> {
                    sheetDialog = null;
                    close();
                }));

        refreshRemoteState();
    }

    /** Immediately triggers the 1-tap "Add another device" 5-min QR pairing flow. */
    public void triggerPairDevice() {
        if (closed || !canAct.getAsBoolean() || io.isShutdown()) return;
        boolean standalone = sheetDialog == null;
        if (!submitIo(() -> {
            OnboardingModels.PairingTicket ticket = null;
            try {
                JSONObject req = new JSONObject();
                req.put("mode", "device");
                ticket = OnboardingModels.parsePairingTicket(api.createPairing(req));
                Diagnostics.event("profile_hub", "device_pairing_created");
            } catch (Exception err) {
                Diagnostics.warning("profile_hub", "device_pairing_failed");
            }
            OnboardingModels.PairingTicket created = ticket;
            main.post(() -> {
                if (closed) return;
                if (!canAct.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) {
                    if (standalone) close();
                    return;
                }
                if (created == null) {
                    Toast.makeText(activity, R.string.profile_hub_qr_unavailable, Toast.LENGTH_LONG).show();
                    if (standalone) close();
                    return;
                }
                if (shareSheet != null) shareSheet.close();
                shareSheet = PairingShareSheet.show(activity, created, api, io, canAct,
                        standalone ? null : this::refreshRemoteState, standalone ? this::close : null);
            });
        }) && standalone) {
            close();
        }
    }

    /** Immediately opens the admin 1-step "Invite someone" flow. */
    public void triggerInvitePerson() {
        if (closed || !canAct.getAsBoolean()) return;
        showInviteDialog(isAdmin);
    }

    private LinearLayout buildActiveHeroCard() {
        LinearLayout card = Ui.column(activity);
        Ui.pad(card, 16, 16);
        card.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.accent, 1));

        LinearLayout top = Ui.row(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.avatarBadge(activity, Ui.initials(account.label), 40, true));

        LinearLayout info = Ui.column(activity);
        TextView name = Ui.text(activity, account.label, 17);
        name.setTypeface(Ui.mediumTypeface());
        info.addView(name);

        HorizontalScrollView badgesScroll = new HorizontalScrollView(activity);
        badgesScroll.setHorizontalScrollBarEnabled(false);
        heroBadgesRow = Ui.row(activity);
        heroBadgesRow.setGravity(Gravity.CENTER_VERTICAL);
        badgesScroll.addView(heroBadgesRow);
        populateHeroBadges();
        info.addView(badgesScroll, topMargin(6));

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.setMarginStart(Ui.dp(activity, 12));
        top.addView(info, infoLp);
        card.addView(top);

        Button pairBtn = Ui.filledButton(activity, activity.getString(R.string.profile_hub_pair_device_action),
                v -> triggerPairDevice());
        card.addView(pairBtn, topMargin(14));

        inviteButton = Ui.outlinedButton(activity, activity.getString(R.string.profile_hub_invite_action),
                v -> showInviteDialog(isAdmin));
        inviteButton.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        card.addView(inviteButton, topMargin(8));

        adminButton = Ui.outlinedButton(activity, activity.getString(R.string.admin_console_open_action), v -> {
            close();
            callbacks.onOpenAdminConsole();
        });
        adminButton.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        card.addView(adminButton, topMargin(8));

        return card;
    }

    private void populateHeroBadges() {
        if (heroBadgesRow == null) return;
        heroBadgesRow.removeAllViews();
        addPill(heroBadgesRow, activity.getString(R.string.profile_hub_active_badge), true);
        if (isAdmin) {
            addPill(heroBadgesRow, activity.getString(R.string.profile_hub_admin_badge), true);
        }
        addPill(heroBadgesRow, AppLanguage.readMode(activity).wire.toUpperCase(Locale.ROOT), false);
        if (activeRunCount > 0) {
            addPill(heroBadgesRow, activity.getString(R.string.profile_hub_runs_badge, activeRunCount), true);
        }
    }

    private void addPill(LinearLayout row, String text, boolean accent) {
        TextView badge = Ui.pill(activity, text,
                accent ? R.color.accent_surface : R.color.surface_container,
                accent ? R.color.accent : R.color.ink);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(activity, 8));
        row.addView(badge, lp);
    }

    private LinearLayout buildLocalProfileRow(String profileId, String label) {
        LinearLayout row = Ui.row(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 16, 12);
        row.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1));

        row.addView(Ui.avatarBadge(activity, Ui.initials(label), 40, false));

        TextView name = Ui.text(activity, label, 15);
        name.setTypeface(Ui.mediumTypeface());
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(Ui.dp(activity, 12));
        nameLp.setMarginEnd(Ui.dp(activity, 8));
        row.addView(name, nameLp);

        Button switchBtn = Ui.tonalButton(activity, activity.getString(R.string.profile_hub_switch_action), v -> {
            close();
            callbacks.onSwitchProfile(profileId);
        });
        row.addView(switchBtn);
        return row;
    }

    private void refreshRemoteState() {
        if (closed || api.isRevoked()) return;
        submitIo(() -> {
            OnboardingModels.OnboardingStatus status = null;
            List<OnboardingModels.RemoteProfile> profiles = null;
            try {
                status = OnboardingModels.parseOnboardingStatus(api.onboardingStatus());
            } catch (Exception ignored) {
                Diagnostics.debug("profile_hub", "onboarding_status_unavailable");
            }
            try {
                profiles = OnboardingModels.parseProfiles(api.listProfiles());
            } catch (Exception ignored) {
                Diagnostics.debug("profile_hub", "remote_profiles_unavailable");
            }
            OnboardingModels.OnboardingStatus finalStatus = status;
            List<OnboardingModels.RemoteProfile> finalProfiles = profiles;
            main.post(() -> {
                if (closed || !canAct.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
                if (finalStatus != null) {
                    isAdmin = finalStatus.isAdmin();
                    callbacks.onAdminStateResolved(isAdmin);
                }
                populateHeroBadges();
                if (adminButton != null) adminButton.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
                if (inviteButton != null) inviteButton.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
                renderRemoteProfiles(finalProfiles);
            });
        });
    }

    private void renderRemoteProfiles(List<OnboardingModels.RemoteProfile> profiles) {
        if (serverSlot == null) return;
        serverSlot.removeAllViews();
        if (profiles == null || profiles.isEmpty()) {
            TextView offline = Ui.text(activity, activity.getString(R.string.profile_hub_offline_note), 13);
            offline.setTextColor(activity.getColor(R.color.muted));
            serverSlot.addView(offline);
            return;
        }
        for (OnboardingModels.RemoteProfile profile : profiles) {
            serverSlot.addView(buildRemoteProfileCard(profile), topMargin(8));
        }
    }

    private LinearLayout buildRemoteProfileCard(OnboardingModels.RemoteProfile profile) {
        LinearLayout card = Ui.card(activity);

        LinearLayout header = Ui.row(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.avatarBadge(activity, profile.initials(), 40, profile.isAdmin()));

        LinearLayout textCol = Ui.column(activity);
        TextView title = Ui.text(activity, profile.displayName() + " (@" + profile.slug() + ")", 15);
        title.setTypeface(Ui.mediumTypeface());
        textCol.addView(title);

        HorizontalScrollView pillsScroll = new HorizontalScrollView(activity);
        pillsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pillsRow = Ui.row(activity);
        pillsRow.setGravity(Gravity.CENTER_VERTICAL);
        String roleLabel = activity.getString(
                profile.isAdmin() ? R.string.admin_console_role_admin : R.string.admin_console_role_member);
        addPill(pillsRow, roleLabel, profile.isAdmin());
        addPill(pillsRow, profile.preferredLanguage().toUpperCase(Locale.ROOT), false);
        if (profile.activeRuns() > 0) {
            addPill(pillsRow, activity.getString(R.string.profile_hub_runs_badge, profile.activeRuns()), true);
        }
        pillsScroll.addView(pillsRow);
        textCol.addView(pillsScroll, topMargin(6));

        TextView threadsCaption = Ui.text(activity,
                activity.getString(R.string.profile_hub_threads_count, profile.threadCount()), 12);
        threadsCaption.setTextColor(activity.getColor(R.color.muted));
        textCol.addView(threadsCaption, topMargin(4));

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(Ui.dp(activity, 12));
        header.addView(textCol, colLp);
        card.addView(header);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(activity);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = Ui.row(activity);

        Button pairBtn = Ui.tonalButton(activity, activity.getString(R.string.profile_hub_pair_device_action),
                v -> inviteExistingProfile(profile.slug()));
        actions.addView(pairBtn);

        Button editBtn = Ui.outlinedButton(activity, activity.getString(R.string.profile_hub_edit_action),
                v -> showEditDialog(profile));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        editLp.setMarginStart(Ui.dp(activity, 8));
        actions.addView(editBtn, editLp);

        actionsScroll.addView(actions);
        card.addView(actionsScroll, topMargin(10));
        return card;
    }

    private void inviteExistingProfile(String slug) {
        ProfileInviteDialogs.pairExistingProfileDevice(
                activity, api, main, this::submitIo, () -> closed, canAct,
                slug, "profile_hub", "invite_existing_failed",
                created -> {
                    if (shareSheet != null) shareSheet.close();
                    shareSheet = PairingShareSheet.show(activity, created, api, io, canAct, this::refreshRemoteState);
                });
    }

    private void showEditDialog(OnboardingModels.RemoteProfile profile) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 12);
        scroll.addView(form);

        EditText nameField = styledLabeledField(form,
                R.string.profile_hub_edit_name_hint,
                profile.displayName(),
                R.string.profile_hub_edit_name_hint,
                0);
        EditText modelField = styledLabeledField(form,
                R.string.admin_settings_model_label,
                profile.defaultModel(),
                R.string.profile_hub_edit_model_hint,
                10);

        TextView langLabel = Ui.text(activity, activity.getString(R.string.profile_hub_language_label), 14);
        langLabel.setTypeface(Ui.mediumTypeface());
        form.addView(langLabel, topMargin(12));

        String[] lang = {profile.preferredLanguage()};
        form.addView(
                ProfileScreen.languageChoiceRow(activity, profile.preferredLanguage(), code -> lang[0] = code),
                topMargin(6));

        dismissChildDialog();
        childDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.profile_hub_edit_title)
                .setView(scroll)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_save_action, (d, w) -> {
                    String nextName = nameField.getText().toString().trim();
                    String nextModel = modelField.getText().toString().trim();
                    submitIo(() -> {
                        boolean ok = false;
                        try {
                            JSONObject patch = new JSONObject();
                            if (!nextName.isEmpty()) patch.put("display_name", nextName);
                            patch.put("preferred_language", lang[0]);
                            patch.put("default_model", nextModel);
                            api.updateProfile(profile.slug(), patch);
                            ok = true;
                        } catch (Exception err) {
                            Diagnostics.warning("profile_hub", "update_profile_failed");
                        }
                        boolean saved = ok;
                        main.post(() -> {
                            if (closed || !canAct.getAsBoolean()) return;
                            Toast.makeText(activity,
                                    saved ? R.string.profile_hub_edit_saved : R.string.profile_hub_edit_failed,
                                    Toast.LENGTH_SHORT).show();
                            if (saved) refreshRemoteState();
                        });
                    });
                }));
    }

    private void showInviteDialog(boolean allowAdminToggle) {
        boolean standalone = sheetDialog == null;
        dismissChildDialog();
        childDialog = ProfileInviteDialogs.showCreateAndInviteDialog(
                activity, api, main, this::submitIo, () -> closed, canAct,
                R.string.profile_hub_invite_title, allowAdminToggle,
                "profile_hub", "invite_created", "invite_create_failed",
                d -> { if (childDialog == d) childDialog = null; },
                standalone ? this::close : null,
                created -> {
                    if (!standalone) refreshRemoteState();
                    if (shareSheet != null) shareSheet.close();
                    shareSheet = PairingShareSheet.show(activity, created, api, io, canAct,
                            standalone ? null : this::refreshRemoteState, standalone ? this::close : null);
                });
    }

    private boolean submitIo(Runnable task) {
        return AdminConsoleSheet.submitTask(closed, io, task, "profile_hub");
    }

    private EditText styledLabeledField(
            LinearLayout parent,
            int labelRes,
            String initial,
            int hintRes,
            int topGapDp) {
        return ProfileInviteDialogs.labeledDialogField(
                activity, parent, labelRes, initial, hintRes, InputType.TYPE_CLASS_TEXT, topGapDp);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    private void dismissChildDialog() {
        if (childDialog != null) {
            try { childDialog.dismiss(); } catch (RuntimeException ignored) {}
            childDialog = null;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        api.revoke();
        io.shutdownNow();
        if (shareSheet != null) {
            shareSheet.close();
            shareSheet = null;
        }
        dismissChildDialog();
        if (sheetDialog != null) {
            AlertDialog toDismiss = sheetDialog;
            sheetDialog = null;
            try { toDismiss.dismiss(); } catch (RuntimeException ignored) {}
        }
    }
}
