package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingShareSheet;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Dedicated fail-closed native Admin Console shown strictly when the active profile has
 * {@code is_admin == true}. Provides two crisp native tabs:
 * <ul>
 *   <li><b>Users</b>: View all isolated profiles with Admin/Member roles, live run &amp; thread
 *       counts, 1-step "+ Invite user" (with live vector QR + Share link + countdown), inline edit,
 *       promote/demote Admin (fail-closed last-admin guard), key rotation, and confirm-slug delete.</li>
 *   <li><b>Server settings</b>: Live editable defaults (language, model, global/per-profile
 *       concurrency caps, pairing TTL, self-device-pairing toggle) plus active pairing links with
 *       1-tap Revoke.</li>
 * </ul>
 */
public final class AdminConsoleSheet implements AutoCloseable {
    private static volatile Typeface mediumTypeface;

    private static Typeface mediumTypeface() {
        Typeface cached = mediumTypeface;
        if (cached == null) {
            cached = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            mediumTypeface = cached;
        }
        return cached;
    }

    public enum Tab { USERS, SETTINGS }

    public enum UsersLoadState { LOADING, LOADED, ERROR }

    private final Activity activity;
    private final Account account;
    private final HermesApi api;
    private final boolean callerIsAdmin;
    private final BooleanSupplier canAct;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-admin-console");
        t.setDaemon(true);
        return t;
    });

    private AlertDialog consoleDialog;
    private AlertDialog childDialog;
    private PairingShareSheet shareSheet;
    private Tab activeTab = Tab.USERS;
    private UsersLoadState usersLoadState = UsersLoadState.LOADING;
    private Button usersTabBtn;
    private Button settingsTabBtn;
    private TextView summaryLine;
    private LinearLayout contentSlot;
    private List<OnboardingModels.RemoteProfile> profiles = Collections.emptyList();
    private OnboardingModels.AdminSettings settings;
    private List<OnboardingModels.PairingTicket> activeTickets = Collections.emptyList();
    private boolean closed;

    public AdminConsoleSheet(
            Activity activity,
            Account account,
            boolean callerIsAdmin,
            BooleanSupplier canAct) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.account = Objects.requireNonNull(account, "account");
        this.callerIsAdmin = callerIsAdmin;
        this.canAct = Objects.requireNonNull(canAct, "canAct");
        this.api = new HermesApi(account.endpoint, account.bearer,
                HermesApi.Limits.from(HermesConfig.load(activity)));
    }

    /** Pure-Java state transition for the Users tab load lifecycle (host-JVM testable). */
    public static UsersLoadState resolveUsersLoadState(boolean fetchedSuccessfully, boolean hasCachedProfiles) {
        if (fetchedSuccessfully) return UsersLoadState.LOADED;
        return hasCachedProfiles ? UsersLoadState.LOADED : UsersLoadState.ERROR;
    }

    /**
     * Pure-Java fail-closed validator for editable Admin Settings form inputs.
     * Returns null when any numeric bound is invalid or {@code perProfile > global}.
     */
    public static OnboardingModels.AdminSettings validateSettingsInput(
            String language,
            String defaultModel,
            String globalRunsRaw,
            String perProfileRunsRaw,
            String pairingTtlRaw,
            boolean allowSelfDevicePairing) {
        int globalRuns = parsePositiveInt(globalRunsRaw);
        int perProfileRuns = parsePositiveInt(perProfileRunsRaw);
        int pairingTtl = parsePositiveInt(pairingTtlRaw);
        if (globalRuns < OnboardingModels.AdminSettings.MIN_CONCURRENT_RUNS
                || globalRuns > OnboardingModels.AdminSettings.MAX_CONCURRENT_RUNS) {
            return null;
        }
        if (perProfileRuns < OnboardingModels.AdminSettings.MIN_PER_PROFILE_RUNS
                || perProfileRuns > OnboardingModels.AdminSettings.MAX_PER_PROFILE_RUNS
                || perProfileRuns > globalRuns) {
            return null;
        }
        if (pairingTtl < OnboardingModels.AdminSettings.MIN_PAIRING_TTL_SEC
                || pairingTtl > OnboardingModels.AdminSettings.MAX_PAIRING_TTL_SEC) {
            return null;
        }
        return new OnboardingModels.AdminSettings(
                language,
                defaultModel == null ? "" : defaultModel.trim(),
                globalRuns,
                perProfileRuns,
                pairingTtl,
                allowSelfDevicePairing,
                0, 0, 0, 0, 0);
    }

    /** Pure-Java fail-closed confirmation matcher requiring exact slug match before deletion. */
    public static boolean canConfirmDelete(String targetSlug, String typedConfirmation) {
        if (targetSlug == null || typedConfirmation == null) return false;
        String cleanTarget = targetSlug.trim();
        return !cleanTarget.isEmpty() && cleanTarget.equals(typedConfirmation.trim());
    }

    private static int parsePositiveInt(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    public boolean show() {
        if (closed || !canAct.getAsBoolean()) return false;
        if (!callerIsAdmin) {
            Diagnostics.warning("admin", "non_admin_console_blocked");
            Toast.makeText(activity, R.string.admin_console_forbidden, Toast.LENGTH_LONG).show();
            return false;
        }

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 18, 12);
        scroll.addView(body);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.admin_console_subtitle), 14);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        body.addView(subtitle);

        summaryLine = Ui.text(activity, activity.getString(R.string.profile_hub_loading), 13);
        summaryLine.setTypeface(mediumTypeface());
        summaryLine.setTextColor(activity.getColor(R.color.accent));
        body.addView(summaryLine, topMargin(8));

        LinearLayout tabs = Ui.row(activity);
        usersTabBtn = tabButton(activity.getString(R.string.admin_console_tab_users), () -> selectTab(Tab.USERS));
        settingsTabBtn = tabButton(activity.getString(R.string.admin_console_tab_settings), () -> selectTab(Tab.SETTINGS));
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        t2.setMarginStart(Ui.dp(activity, 8));
        tabs.addView(usersTabBtn, t1);
        tabs.addView(settingsTabBtn, t2);
        body.addView(tabs, topMargin(12));

        contentSlot = Ui.column(activity);
        body.addView(contentSlot, topMargin(12));

        styleTabs();
        consoleDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.admin_console_title)
                .setView(scroll)
                .setPositiveButton(R.string.ws_close, null)
                .setOnDismissListener(d -> {
                    if (!closed) consoleDialog = null;
                }));

        refreshAll();
        return true;
    }

    private void selectTab(Tab next) {
        activeTab = next;
        styleTabs();
        renderActiveTab();
    }

    private void styleTabs() {
        styleTabButton(usersTabBtn, activeTab == Tab.USERS);
        styleTabButton(settingsTabBtn, activeTab == Tab.SETTINGS);
    }

    private void styleTabButton(Button btn, boolean selected) {
        if (btn == null) return;
        btn.setBackground(Ui.roundedRipple(activity, Ui.BUTTON_CORNER_DP, Ui.shape(
                activity,
                Ui.BUTTON_CORNER_DP,
                selected ? R.color.accent_surface : R.color.surface,
                selected ? R.color.accent : R.color.line,
                selected ? 2 : 1)));
        btn.setTextColor(activity.getColor(selected ? R.color.accent : R.color.ink));
        btn.setSelected(selected);
        btn.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(selected);
            }
        });
    }

    private Button tabButton(String label, Runnable onClick) {
        Button btn = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(mediumTypeface());
        btn.setStateListAnimator(null);
        Ui.pad(btn, 14, 10);
        int touchMin = Ui.dp(activity, 48);
        btn.setMinHeight(touchMin);
        btn.setMinimumHeight(touchMin);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void refreshAll() {
        if (closed || api.isRevoked()) return;
        if (profiles.isEmpty()) {
            usersLoadState = UsersLoadState.LOADING;
            if (activeTab == Tab.USERS) renderActiveTab();
        }
        io.execute(() -> {
            List<OnboardingModels.RemoteProfile> loadedProfiles = null;
            OnboardingModels.AdminSettings loadedSettings = null;
            List<OnboardingModels.PairingTicket> loadedTickets = null;
            boolean forbidden = false;
            try {
                loadedProfiles = OnboardingModels.parseProfiles(api.listProfiles());
            } catch (HermesApi.HttpError http) {
                if (http.status == 403) forbidden = true;
                Diagnostics.warning("admin", "profiles_load_http_" + http.status);
            } catch (Exception err) {
                Diagnostics.warning("admin", "profiles_load_failed");
            }
            if (!forbidden) {
                try {
                    loadedSettings = OnboardingModels.parseAdminSettings(api.adminSettings());
                } catch (HermesApi.HttpError http) {
                    if (http.status == 403) forbidden = true;
                    Diagnostics.warning("admin", "settings_load_http_" + http.status);
                } catch (Exception err) {
                    Diagnostics.warning("admin", "settings_load_failed");
                }
                try {
                    loadedTickets = OnboardingModels.parseActivePairingTickets(api.activePairings(true));
                } catch (Exception ignored) {
                    loadedTickets = Collections.emptyList();
                }
            }
            List<OnboardingModels.RemoteProfile> finalProfiles = loadedProfiles;
            OnboardingModels.AdminSettings finalSettings = loadedSettings;
            List<OnboardingModels.PairingTicket> finalTickets = loadedTickets;
            boolean finalForbidden = forbidden;
            main.post(() -> {
                if (closed || !canAct.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
                if (finalForbidden) {
                    Toast.makeText(activity, R.string.admin_console_forbidden, Toast.LENGTH_LONG).show();
                    close();
                    return;
                }
                if (finalProfiles != null) profiles = finalProfiles;
                usersLoadState = resolveUsersLoadState(finalProfiles != null, !profiles.isEmpty());
                if (finalSettings != null) settings = finalSettings;
                if (finalTickets != null) activeTickets = finalTickets;
                updateSummary();
                renderActiveTab();
            });
        });
    }

    private void updateSummary() {
        if (summaryLine == null) return;
        if (usersLoadState == UsersLoadState.ERROR && settings == null) {
            summaryLine.setText(R.string.admin_console_users_load_failed);
            summaryLine.setTextColor(activity.getColor(R.color.danger));
            return;
        }
        summaryLine.setTextColor(activity.getColor(R.color.accent));
        int profileCount = settings != null && settings.profileCount() > 0 ? settings.profileCount() : profiles.size();
        int activeRuns = settings != null ? settings.activeRunsCount() : 0;
        int ticketCount = settings != null ? Math.max(settings.activePairingTickets(), activeTickets.size()) : activeTickets.size();
        summaryLine.setText(activity.getString(R.string.admin_console_summary, profileCount, activeRuns, ticketCount));
    }

    private void renderActiveTab() {
        if (contentSlot == null) return;
        contentSlot.removeAllViews();
        if (activeTab == Tab.USERS) renderUsersTab();
        else renderSettingsTab();
    }

    private void renderUsersTab() {
        Button inviteBtn = Ui.filledButton(activity, activity.getString(R.string.admin_console_invite_user_action),
                v -> showInviteUserDialog());
        contentSlot.addView(inviteBtn);

        if (usersLoadState == UsersLoadState.LOADING && profiles.isEmpty()) {
            TextView loading = Ui.text(activity, activity.getString(R.string.profile_hub_loading), 13);
            loading.setTextColor(activity.getColor(R.color.muted));
            contentSlot.addView(loading, topMargin(12));
            return;
        }
        if (usersLoadState == UsersLoadState.ERROR && profiles.isEmpty()) {
            LinearLayout errorCard = Ui.card(activity);
            TextView errText = Ui.text(activity, activity.getString(R.string.admin_console_users_load_failed), 14);
            errText.setTextColor(activity.getColor(R.color.danger));
            errorCard.addView(errText);
            Button retryBtn = Ui.outlinedButton(activity, activity.getString(R.string.instrument_retry),
                    v -> refreshAll());
            errorCard.addView(retryBtn, topMargin(10));
            contentSlot.addView(errorCard, topMargin(12));
            return;
        }
        if (profiles.isEmpty()) {
            TextView empty = Ui.text(activity, activity.getString(R.string.admin_console_users_empty), 13);
            empty.setTextColor(activity.getColor(R.color.muted));
            contentSlot.addView(empty, topMargin(12));
            return;
        }
        for (OnboardingModels.RemoteProfile profile : profiles) {
            contentSlot.addView(buildUserCard(profile), topMargin(10));
        }
    }

    private LinearLayout buildUserCard(OnboardingModels.RemoteProfile profile) {
        LinearLayout card = Ui.card(activity);

        LinearLayout top = Ui.row(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ProfileHubSheet.avatarBadge(activity, profile.initials(), profile.isAdmin()));

        LinearLayout info = Ui.column(activity);
        TextView title = Ui.text(activity, profile.displayName() + " (@" + profile.slug() + ")", 15);
        title.setTypeface(mediumTypeface());
        info.addView(title);

        HorizontalScrollView pillsScroll = new HorizontalScrollView(activity);
        pillsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pillsRow = Ui.row(activity);
        pillsRow.setGravity(Gravity.CENTER_VERTICAL);
        String role = activity.getString(profile.isAdmin()
                ? R.string.admin_console_role_admin : R.string.admin_console_role_member);
        addPill(pillsRow, role, profile.isAdmin());
        addPill(pillsRow, profile.preferredLanguage().toUpperCase(Locale.ROOT), false);
        if (profile.activeRuns() > 0) {
            addPill(pillsRow, activity.getString(R.string.profile_hub_runs_badge, profile.activeRuns()), true);
        }
        pillsScroll.addView(pillsRow);
        info.addView(pillsScroll, topMargin(6));

        TextView threadsCaption = Ui.text(activity,
                activity.getString(R.string.profile_hub_threads_count, profile.threadCount()), 12);
        threadsCaption.setTextColor(activity.getColor(R.color.muted));
        info.addView(threadsCaption, topMargin(4));

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.setMarginStart(Ui.dp(activity, 12));
        top.addView(info, infoLp);
        card.addView(top);

        boolean canChangeAdminOrDelete = ProfileHubSheet.canDemoteOrDeleteAdmin(profiles, profile);
        if (profile.isAdmin() && !canChangeAdminOrDelete) {
            TextView guard = Ui.text(activity, activity.getString(R.string.admin_console_last_admin_guard), 12);
            guard.setTextColor(activity.getColor(R.color.muted));
            card.addView(guard, topMargin(8));
        }

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = Ui.row(activity);

        addRowAction(actions, R.string.profile_hub_pair_device_action, true, true, false,
                () -> pairProfileDevice(profile.slug()));
        addRowAction(actions, R.string.profile_hub_edit_action, true, false, false,
                () -> showEditUserDialog(profile));
        addRowAction(actions,
                profile.isAdmin() ? R.string.admin_console_demote_action : R.string.admin_console_promote_action,
                canChangeAdminOrDelete, false, false,
                () -> toggleAdminRole(profile));
        addRowAction(actions, R.string.profile_hub_rotate_key_action, true, false, false,
                () -> confirmRotateKey(profile));
        addRowAction(actions, R.string.profile_hub_remove_action, canChangeAdminOrDelete, false, true,
                () -> confirmDeleteProfile(profile));

        scroll.addView(actions);
        card.addView(scroll, topMargin(10));
        return card;
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

    private ColorStateList dangerTextColors() {
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{activity.getColor(R.color.disabled_ink), activity.getColor(R.color.danger)});
    }

    private void addRowAction(
            LinearLayout row,
            int labelRes,
            boolean enabled,
            boolean tonal,
            boolean danger,
            Runnable onClick) {
        String text = activity.getString(labelRes);
        Button btn = tonal
                ? Ui.tonalButton(activity, text, v -> onClick.run())
                : Ui.outlinedButton(activity, text, v -> onClick.run());
        btn.setEnabled(enabled);
        if (danger) btn.setTextColor(dangerTextColors());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(activity, 8));
        row.addView(btn, lp);
    }

    private void renderSettingsTab() {
        OnboardingModels.AdminSettings cur = settings != null
                ? settings
                : new OnboardingModels.AdminSettings("auto", "", 32, 12, 300, true, 0, 0, 0, 0, 0);

        String[] langHolder = {cur.defaultLanguage()};
        contentSlot.addView(label(R.string.admin_settings_language_label));
        HorizontalScrollView langScroll = new HorizontalScrollView(activity);
        langScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout langRow = Ui.row(activity);
        String[] labels = {
                activity.getString(R.string.onboarding_language_auto),
                activity.getString(R.string.onboarding_language_en),
                activity.getString(R.string.onboarding_language_fr)
        };
        String[] codes = {"auto", "en", "fr"};
        Button[] langBtns = new Button[3];
        Runnable paintLang = () -> {
            for (int i = 0; i < 3; i++) {
                styleTabButton(langBtns[i], codes[i].equals(langHolder[0]));
            }
        };
        for (int i = 0; i < 3; i++) {
            final String code = codes[i];
            Button b = tabButton(labels[i], () -> {
                langHolder[0] = code;
                paintLang.run();
            });
            langBtns[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(Ui.dp(activity, 8));
            langRow.addView(b, lp);
        }
        paintLang.run();
        langScroll.addView(langRow);
        contentSlot.addView(langScroll, topMargin(6));

        EditText modelField = labeledEdit(contentSlot,
                R.string.admin_settings_model_label,
                cur.defaultModel(),
                R.string.admin_settings_model_hint,
                InputType.TYPE_CLASS_TEXT,
                12);
        EditText globalField = labeledEdit(contentSlot,
                R.string.admin_settings_global_runs_label,
                Integer.toString(cur.maxConcurrentRuns()),
                0,
                InputType.TYPE_CLASS_NUMBER,
                10);
        EditText perProfileField = labeledEdit(contentSlot,
                R.string.admin_settings_profile_runs_label,
                Integer.toString(cur.perProfileConcurrentLimit()),
                0,
                InputType.TYPE_CLASS_NUMBER,
                10);
        EditText ttlField = labeledEdit(contentSlot,
                R.string.admin_settings_pairing_ttl_label,
                Integer.toString(cur.pairingTtlSeconds()),
                0,
                InputType.TYPE_CLASS_NUMBER,
                10);

        Switch selfPairToggle = new Switch(activity);
        selfPairToggle.setText(R.string.admin_settings_self_pairing_label);
        selfPairToggle.setTextColor(activity.getColor(R.color.ink));
        selfPairToggle.setTextSize(15);
        selfPairToggle.setChecked(cur.allowSelfDevicePairing());
        selfPairToggle.setMinHeight(Ui.dp(activity, 48));
        contentSlot.addView(selfPairToggle, topMargin(10));

        TextView settingsError = Ui.text(activity, "", 13);
        settingsError.setTextColor(activity.getColor(R.color.danger));
        settingsError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        settingsError.setVisibility(View.GONE);
        contentSlot.addView(settingsError, topMargin(6));

        Button saveBtn = Ui.filledButton(activity, activity.getString(R.string.admin_settings_save_action), null);
        saveBtn.setOnClickListener(v -> {
            OnboardingModels.AdminSettings validated = validateSettingsInput(
                    langHolder[0],
                    modelField.getText().toString(),
                    globalField.getText().toString(),
                    perProfileField.getText().toString(),
                    ttlField.getText().toString(),
                    selfPairToggle.isChecked());
            if (validated == null) {
                settingsError.setText(R.string.admin_settings_invalid_number);
                settingsError.setVisibility(View.VISIBLE);
                return;
            }
            settingsError.setText("");
            settingsError.setVisibility(View.GONE);
            saveBtn.setEnabled(false);
            io.execute(() -> {
                OnboardingModels.AdminSettings updated = null;
                try {
                    updated = OnboardingModels.parseAdminSettings(api.updateAdminSettings(validated.toPatchJson()));
                    Diagnostics.event("admin", "settings_saved");
                } catch (Exception err) {
                    Diagnostics.warning("admin", "settings_save_failed");
                }
                OnboardingModels.AdminSettings result = updated;
                main.post(() -> {
                    if (closed || !canAct.getAsBoolean()) return;
                    saveBtn.setEnabled(true);
                    if (result != null) {
                        settings = result;
                        updateSummary();
                        Toast.makeText(activity, R.string.admin_settings_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity, R.string.admin_settings_save_failed, Toast.LENGTH_LONG).show();
                    }
                });
            });
        });
        contentSlot.addView(saveBtn, topMargin(12));

        // Active pairing tickets (metadata only, 1-tap revoke)
        TextView linksHeading = label(R.string.admin_settings_active_links_heading);
        linksHeading.setAccessibilityHeading(true);
        contentSlot.addView(linksHeading, topMargin(18));
        if (activeTickets.isEmpty()) {
            TextView none = Ui.text(activity, activity.getString(R.string.admin_settings_no_active_links), 13);
            none.setTextColor(activity.getColor(R.color.muted));
            contentSlot.addView(none, topMargin(6));
            return;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        for (OnboardingModels.PairingTicket ticket : activeTickets) {
            LinearLayout card = Ui.card(activity);
            LinearLayout row = Ui.row(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout metaCol = Ui.column(activity);
            String who = !ticket.displayName().isEmpty() ? ticket.displayName() : ticket.profileSlug();
            TextView whoView = Ui.text(activity, who, 14);
            whoView.setTypeface(mediumTypeface());
            metaCol.addView(whoView);

            HorizontalScrollView badgeScroll = new HorizontalScrollView(activity);
            badgeScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout badgeRow = Ui.row(activity);
            badgeRow.setGravity(Gravity.CENTER_VERTICAL);
            boolean isInvite = "invite".equalsIgnoreCase(ticket.mode());
            String modeLabel = activity.getString(
                    isInvite ? R.string.card_pairing_mode_invite : R.string.card_pairing_mode_device);
            addPill(badgeRow, modeLabel, isInvite);
            String exp = PairingShareSheet.formatCountdown(ticket.remainingSeconds(nowSec));
            addPill(badgeRow, activity.getString(R.string.card_pairing_expires, exp), false);
            badgeScroll.addView(badgeRow);
            metaCol.addView(badgeScroll, topMargin(6));

            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            metaLp.setMarginEnd(Ui.dp(activity, 8));
            row.addView(metaCol, metaLp);

            Button revoke = Ui.outlinedButton(activity, activity.getString(R.string.profile_hub_qr_revoke_action),
                    v -> revokeTicket(ticket.ticketId()));
            revoke.setTextColor(dangerTextColors());
            row.addView(revoke);
            card.addView(row);
            contentSlot.addView(card, topMargin(8));
        }
    }

    private void revokeTicket(String ticketId) {
        if (closed || ticketId == null || ticketId.isEmpty()) return;
        io.execute(() -> {
            boolean ok = false;
            try {
                api.revokePairing(ticketId);
                ok = true;
            } catch (Exception err) {
                Diagnostics.warning("admin", "ticket_revoke_failed");
            }
            boolean done = ok;
            main.post(() -> {
                if (closed || !canAct.getAsBoolean()) return;
                if (done) {
                    Toast.makeText(activity, R.string.profile_hub_qr_revoked, Toast.LENGTH_SHORT).show();
                    refreshAll();
                } else {
                    Toast.makeText(activity, R.string.admin_settings_revoke_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void pairProfileDevice(String slug) {
        io.execute(() -> {
            OnboardingModels.PairingTicket ticket = null;
            try {
                JSONObject req = new JSONObject();
                req.put("mode", "device");
                ticket = OnboardingModels.parsePairingTicket(api.inviteProfile(slug, req));
            } catch (Exception err) {
                Diagnostics.warning("admin", "pair_device_failed");
            }
            OnboardingModels.PairingTicket created = ticket;
            main.post(() -> {
                if (closed || !canAct.getAsBoolean()) return;
                if (created == null) {
                    Toast.makeText(activity, R.string.profile_hub_qr_unavailable, Toast.LENGTH_LONG).show();
                    return;
                }
                if (shareSheet != null) shareSheet.close();
                shareSheet = PairingShareSheet.show(activity, created, api, io, canAct, this::refreshAll);
                refreshAll();
            });
        });
    }

    private void toggleAdminRole(OnboardingModels.RemoteProfile profile) {
        if (!ProfileHubSheet.canDemoteOrDeleteAdmin(profiles, profile)) {
            Toast.makeText(activity, R.string.admin_console_last_admin_guard, Toast.LENGTH_LONG).show();
            return;
        }
        boolean nextAdmin = !profile.isAdmin();
        io.execute(() -> {
            boolean ok = false;
            try {
                JSONObject patch = new JSONObject();
                patch.put("is_admin", nextAdmin);
                api.updateProfile(profile.slug(), patch);
                ok = true;
                Diagnostics.event("admin", "role_updated");
            } catch (Exception err) {
                Diagnostics.warning("admin", "role_update_failed");
            }
            boolean done = ok;
            main.post(() -> {
                if (closed || !canAct.getAsBoolean()) return;
                Toast.makeText(activity,
                        done ? R.string.admin_console_role_updated : R.string.admin_console_role_failed,
                        Toast.LENGTH_SHORT).show();
                if (done) refreshAll();
            });
        });
    }

    private void showEditUserDialog(OnboardingModels.RemoteProfile profile) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 12);
        scroll.addView(form);

        EditText nameField = labeledEdit(form,
                R.string.profile_hub_edit_name_hint,
                profile.displayName(),
                R.string.profile_hub_edit_name_hint,
                InputType.TYPE_CLASS_TEXT,
                0);
        EditText modelField = labeledEdit(form,
                R.string.admin_settings_model_label,
                profile.defaultModel(),
                R.string.profile_hub_edit_model_hint,
                InputType.TYPE_CLASS_TEXT,
                10);

        dismissChildDialog();
        childDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.profile_hub_edit_title)
                .setView(scroll)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_save_action, (d, w) -> {
                    String nextName = nameField.getText().toString().trim();
                    String nextModel = modelField.getText().toString().trim();
                    io.execute(() -> {
                        boolean ok = false;
                        try {
                            JSONObject patch = new JSONObject();
                            if (!nextName.isEmpty()) patch.put("display_name", nextName);
                            patch.put("default_model", nextModel);
                            api.updateProfile(profile.slug(), patch);
                            ok = true;
                        } catch (Exception err) {
                            Diagnostics.warning("admin", "edit_user_failed");
                        }
                        boolean done = ok;
                        main.post(() -> {
                            if (closed || !canAct.getAsBoolean()) return;
                            Toast.makeText(activity,
                                    done ? R.string.profile_hub_edit_saved : R.string.profile_hub_edit_failed,
                                    Toast.LENGTH_SHORT).show();
                            if (done) refreshAll();
                        });
                    });
                }));
    }

    private void showInviteUserDialog() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 12);
        scroll.addView(form);

        TextView detail = Ui.text(activity, activity.getString(R.string.profile_hub_invite_detail), 13);
        detail.setTextColor(activity.getColor(R.color.muted));
        form.addView(detail);

        EditText nameField = labeledEdit(form,
                R.string.profile_hub_edit_name_hint,
                "",
                R.string.profile_hub_invite_name_hint,
                InputType.TYPE_CLASS_TEXT,
                10);
        EditText slugField = labeledEdit(form,
                R.string.profile_hub_slug_label,
                "",
                R.string.profile_hub_invite_slug_hint,
                InputType.TYPE_CLASS_TEXT,
                10);

        TextView inlineError = Ui.text(activity, "", 13);
        inlineError.setTextColor(activity.getColor(R.color.danger));
        inlineError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        inlineError.setVisibility(View.GONE);
        form.addView(inlineError, topMargin(8));

        boolean[] slugEdited = {false};
        nameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!slugEdited[0]) slugField.setText(ProfileHubSheet.suggestSlug(s.toString()));
            }
        });
        slugField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (inlineError.getVisibility() == View.VISIBLE) {
                    inlineError.setText("");
                    inlineError.setVisibility(View.GONE);
                }
            }
        });
        slugField.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) slugEdited[0] = true; });

        Switch adminToggle = new Switch(activity);
        adminToggle.setText(R.string.admin_console_grant_admin_toggle);
        adminToggle.setTextColor(activity.getColor(R.color.ink));
        adminToggle.setTextSize(15);
        adminToggle.setMinHeight(Ui.dp(activity, 48));
        form.addView(adminToggle, topMargin(10));

        dismissChildDialog();
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.admin_console_invite_user_action)
                .setView(scroll)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_hub_invite_create_action, null));
        childDialog = dialog;
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String name = nameField.getText().toString().trim();
                String slug = slugField.getText().toString().trim().toLowerCase(Locale.ROOT);
                if (!ProfileHubSheet.isValidNewSlug(slug)) {
                    inlineError.setText(R.string.profile_hub_invite_invalid_slug);
                    inlineError.setVisibility(View.VISIBLE);
                    slugField.requestFocus();
                    return;
                }
                inlineError.setText("");
                inlineError.setVisibility(View.GONE);
                boolean grantAdmin = adminToggle.isChecked();
                dialog.dismiss();
                io.execute(() -> {
                    OnboardingModels.PairingTicket ticket = null;
                    try {
                        JSONObject req = new JSONObject();
                        req.put("slug", slug);
                        req.put("display_name", name.isEmpty() ? slug : name);
                        req.put("preferred_language", AppLanguage.resolvedTag(activity));
                        req.put("is_admin", grantAdmin);
                        req.put("create_invite", true);
                        JSONObject resp = api.createProfile(req);
                        JSONObject rawTicket = resp.optJSONObject("pairing_ticket");
                        if (rawTicket == null) rawTicket = resp.optJSONObject("ticket");
                        if (rawTicket == null) rawTicket = resp;
                        ticket = OnboardingModels.parsePairingTicket(rawTicket);
                        Diagnostics.event("admin", "user_invited");
                    } catch (Exception err) {
                        Diagnostics.warning("admin", "user_invite_failed");
                    }
                    OnboardingModels.PairingTicket created = ticket;
                    main.post(() -> {
                        if (closed || !canAct.getAsBoolean()) return;
                        if (created == null) {
                            Toast.makeText(activity, R.string.profile_hub_invite_failed, Toast.LENGTH_LONG).show();
                            return;
                        }
                        refreshAll();
                        if (shareSheet != null) shareSheet.close();
                        shareSheet = PairingShareSheet.show(activity, created, api, io, canAct, this::refreshAll);
                    });
                });
            });
        }
    }

    private void confirmRotateKey(OnboardingModels.RemoteProfile profile) {
        dismissChildDialog();
        childDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.profile_hub_rotate_title, profile.displayName()))
                .setMessage(R.string.profile_hub_rotate_body)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_hub_rotate_confirm, (d, w) -> io.execute(() -> {
                    OnboardingModels.RotateKeyResult rotated = null;
                    try {
                        JSONObject body = new JSONObject();
                        body.put("create_invite", true);
                        rotated = OnboardingModels.parseRotateKey(api.rotateProfileKey(profile.slug(), body));
                        Diagnostics.event("admin", "key_rotated");
                    } catch (Exception err) {
                        Diagnostics.warning("admin", "key_rotate_failed");
                    }
                    OnboardingModels.RotateKeyResult result = rotated;
                    main.post(() -> {
                        if (closed || !canAct.getAsBoolean()) return;
                        if (result == null) {
                            Toast.makeText(activity, R.string.profile_hub_rotate_failed, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(activity, R.string.profile_hub_rotate_done, Toast.LENGTH_SHORT).show();
                        if (result.pairingTicket() != null) {
                            if (shareSheet != null) shareSheet.close();
                            shareSheet = PairingShareSheet.show(
                                    activity, result.pairingTicket(), api, io, canAct, this::refreshAll);
                        }
                        refreshAll();
                    });
                })));
    }

    private void confirmDeleteProfile(OnboardingModels.RemoteProfile profile) {
        if (!ProfileHubSheet.canDemoteOrDeleteAdmin(profiles, profile)) {
            Toast.makeText(activity, R.string.admin_console_last_admin_guard, Toast.LENGTH_LONG).show();
            return;
        }
        ScrollView scroll = new ScrollView(activity);
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 12);
        scroll.addView(form);

        TextView body = Ui.text(activity,
                activity.getString(R.string.profile_hub_delete_body, profile.slug()), 14);
        form.addView(body);

        EditText confirmInput = labeledEdit(form,
                R.string.profile_hub_slug_label,
                "",
                R.string.profile_hub_delete_confirm_hint,
                InputType.TYPE_CLASS_TEXT,
                10);

        TextView inlineError = Ui.text(activity, "", 13);
        inlineError.setTextColor(activity.getColor(R.color.danger));
        inlineError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        inlineError.setVisibility(View.GONE);
        form.addView(inlineError, topMargin(8));

        confirmInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (inlineError.getVisibility() == View.VISIBLE) {
                    inlineError.setText("");
                    inlineError.setVisibility(View.GONE);
                }
            }
        });

        dismissChildDialog();
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.profile_hub_delete_title, profile.displayName()))
                .setView(scroll)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_hub_delete_confirm_action, null));
        childDialog = dialog;
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String typed = confirmInput.getText().toString();
                if (!canConfirmDelete(profile.slug(), typed)) {
                    inlineError.setText(activity.getString(
                            R.string.admin_console_delete_slug_mismatch, profile.slug()));
                    inlineError.setVisibility(View.VISIBLE);
                    confirmInput.requestFocus();
                    return;
                }
                inlineError.setText("");
                inlineError.setVisibility(View.GONE);
                dialog.dismiss();
                io.execute(() -> {
                    boolean ok = false;
                    try {
                        api.deleteProfile(profile.slug(), profile.slug());
                        ok = true;
                        Diagnostics.event("admin", "profile_deleted");
                    } catch (Exception err) {
                        Diagnostics.warning("admin", "profile_delete_failed");
                    }
                    boolean done = ok;
                    main.post(() -> {
                        if (closed || !canAct.getAsBoolean()) return;
                        Toast.makeText(activity,
                                done ? R.string.profile_hub_delete_done : R.string.profile_hub_delete_failed,
                                Toast.LENGTH_SHORT).show();
                        if (done) refreshAll();
                    });
                });
            });
        }
    }

    private TextView label(int resId) {
        TextView tv = Ui.text(activity, activity.getString(resId), 14);
        tv.setTypeface(mediumTypeface());
        return tv;
    }

    private EditText labeledEdit(
            LinearLayout parent,
            int labelRes,
            String initial,
            int hintRes,
            int inputType,
            int topGapDp) {
        TextView lbl = label(labelRes);
        if (topGapDp > 0) parent.addView(lbl, topMargin(topGapDp));
        else parent.addView(lbl);

        EditText e = edit(initial, hintRes, inputType);
        e.setId(View.generateViewId());
        lbl.setLabelFor(e.getId());
        parent.addView(e, topMargin(6));
        return e;
    }

    private EditText edit(String initial, int hintRes, int inputType) {
        EditText e = new EditText(activity);
        e.setText(initial == null ? "" : initial);
        if (hintRes != 0) e.setHint(hintRes);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackgroundResource(R.drawable.profile_input);
        e.setBackgroundTintList(null);
        Ui.pad(e, 12, 10);
        e.setTextColor(activity.getColor(R.color.ink));
        e.setHintTextColor(activity.getColor(R.color.muted));
        e.setMinHeight(Ui.dp(activity, 48));
        e.setSaveEnabled(false);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        return e;
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
        closed = true;
        api.revoke();
        io.shutdownNow();
        if (shareSheet != null) {
            shareSheet.close();
            shareSheet = null;
        }
        dismissChildDialog();
        if (consoleDialog != null) {
            try { consoleDialog.dismiss(); } catch (RuntimeException ignored) {}
            consoleDialog = null;
        }
    }
}
