package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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

import java.text.Normalizer;
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
    private static volatile Typeface mediumTypeface;

    private static Typeface mediumTypeface() {
        Typeface cached = mediumTypeface;
        if (cached == null) {
            cached = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            mediumTypeface = cached;
        }
        return cached;
    }
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
    private TextView heroMeta;
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

    /** Crisp 1-2 character avatar initials for profile cards (host-JVM testable). */
    public static String avatarInitials(String label) {
        return Ui.initials(label);
    }

    /** Validates a newly created server profile slug (2..32 lowercase ASCII letters, digits, hyphens). */
    public static boolean isValidNewSlug(String raw) {
        return raw != null && VALID_SLUG.matcher(raw.trim()).matches();
    }

    /** Derives a safe lowercase slug suggestion from a human display name (accent-folded). */
    public static String suggestSlug(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";
        String norm = Normalizer.normalize(displayName.trim(), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder();
        boolean prevDash = true;
        for (int i = 0; i < norm.length() && sb.length() < 32; i++) {
            char c = norm.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) continue;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(Character.toLowerCase(c));
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
        localHeading.setTypeface(mediumTypeface());
        localHeading.setAccessibilityHeading(true);
        body.addView(localHeading, topMargin(18));

        Map<String, String> localProfiles = new AccountStore(activity).profiles();
        for (Map.Entry<String, String> entry : localProfiles.entrySet()) {
            if (account.id.equals(entry.getKey())) continue;
            body.addView(buildLocalProfileRow(entry.getKey(), entry.getValue()), topMargin(8));
        }

        Button scanQrBtn = Ui.button(activity, activity.getString(R.string.profile_hub_scan_qr_action), v -> {
            close();
            callbacks.onScanQrCode();
        });
        body.addView(scanQrBtn, topMargin(10));

        Button addLocalBtn = Ui.button(activity, activity.getString(R.string.profile_add_action), v -> {
            close();
            callbacks.onAddProfile();
        });
        body.addView(addLocalBtn, topMargin(8));

        // Server profiles & remote status section
        TextView serverHeading = Ui.text(activity, activity.getString(R.string.profile_hub_server_section), 14);
        serverHeading.setTypeface(mediumTypeface());
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
                    if (!closed) {
                        sheetDialog = null;
                    }
                }));

        refreshRemoteState();
    }

    /** Immediately triggers the 1-tap "Add another device" 5-min QR pairing flow. */
    public void triggerPairDevice() {
        if (closed || !canAct.getAsBoolean()) return;
        io.execute(() -> {
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
                if (closed || !canAct.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
                if (created == null) {
                    Toast.makeText(activity, R.string.profile_hub_qr_unavailable, Toast.LENGTH_LONG).show();
                    return;
                }
                if (shareSheet != null) shareSheet.close();
                shareSheet = PairingShareSheet.show(activity, created, api, io, canAct, this::refreshRemoteState);
            });
        });
    }

    /** Immediately opens the admin 1-step "Invite someone" flow. */
    public void triggerInvitePerson() {
        if (closed || !canAct.getAsBoolean()) return;
        showInviteDialog(isAdmin);
    }

    private LinearLayout buildActiveHeroCard() {
        LinearLayout card = Ui.column(activity);
        Ui.pad(card, 14, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(activity, 14));
        bg.setColor(activity.getColor(R.color.surface));
        bg.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.accent));
        card.setBackground(bg);

        LinearLayout top = Ui.row(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(avatarBadge(activity, avatarInitials(account.label), true));

        LinearLayout info = Ui.column(activity);
        TextView name = Ui.text(activity, account.label, 17);
        name.setTypeface(mediumTypeface());
        info.addView(name);

        heroMeta = Ui.text(activity, buildHeroMetaText(), 13);
        heroMeta.setTextColor(activity.getColor(R.color.muted));
        info.addView(heroMeta);

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.setMarginStart(Ui.dp(activity, 12));
        top.addView(info, infoLp);
        card.addView(top);

        Button pairBtn = Ui.button(activity, activity.getString(R.string.profile_hub_pair_device_action),
                v -> triggerPairDevice());
        card.addView(pairBtn, topMargin(12));

        inviteButton = Ui.button(activity, activity.getString(R.string.profile_hub_invite_action),
                v -> showInviteDialog(isAdmin));
        inviteButton.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        card.addView(inviteButton, topMargin(6));

        adminButton = Ui.button(activity, activity.getString(R.string.admin_console_open_action), v -> {
            close();
            callbacks.onOpenAdminConsole();
        });
        adminButton.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        card.addView(adminButton, topMargin(6));

        return card;
    }

    private String buildHeroMetaText() {
        StringBuilder sb = new StringBuilder(activity.getString(R.string.profile_hub_active_badge));
        if (isAdmin) {
            sb.append(" · ").append(activity.getString(R.string.profile_hub_admin_badge));
        }
        sb.append(" · ").append(AppLanguage.readMode(activity).wire.toUpperCase(Locale.ROOT));
        if (activeRunCount > 0) {
            sb.append(" · ").append(activity.getString(R.string.profile_hub_runs_badge, activeRunCount));
        }
        return sb.toString();
    }

    private LinearLayout buildLocalProfileRow(String profileId, String label) {
        LinearLayout row = Ui.row(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 12, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(activity, 12));
        bg.setColor(activity.getColor(R.color.surface));
        bg.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.line));
        row.setBackground(bg);

        row.addView(avatarBadge(activity, avatarInitials(label), false));

        TextView name = Ui.text(activity, label, 15);
        name.setTypeface(mediumTypeface());
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(Ui.dp(activity, 12));
        row.addView(name, nameLp);

        Button switchBtn = Ui.button(activity, activity.getString(R.string.profile_hub_switch_action), v -> {
            close();
            callbacks.onSwitchProfile(profileId);
        });
        row.addView(switchBtn);
        return row;
    }

    private void refreshRemoteState() {
        if (closed || api.isRevoked()) return;
        io.execute(() -> {
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
                if (heroMeta != null) heroMeta.setText(buildHeroMetaText());
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
        LinearLayout card = Ui.column(activity);
        Ui.pad(card, 12, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(activity, 12));
        bg.setColor(activity.getColor(R.color.surface));
        bg.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.line));
        card.setBackground(bg);

        LinearLayout header = Ui.row(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(avatarBadge(activity, profile.initials(), profile.isAdmin()));

        LinearLayout textCol = Ui.column(activity);
        TextView title = Ui.text(activity, profile.displayName() + " (@" + profile.slug() + ")", 15);
        title.setTypeface(mediumTypeface());
        textCol.addView(title);

        String roleLabel = activity.getString(
                profile.isAdmin() ? R.string.admin_console_role_admin : R.string.admin_console_role_member);
        String meta = roleLabel
                + " · " + profile.preferredLanguage().toUpperCase(Locale.ROOT)
                + " · " + activity.getString(R.string.profile_hub_threads_count, profile.threadCount());
        if (profile.activeRuns() > 0) {
            meta += " · " + activity.getString(R.string.profile_hub_runs_badge, profile.activeRuns());
        }
        TextView sub = Ui.text(activity, meta, 12);
        sub.setTextColor(activity.getColor(R.color.muted));
        textCol.addView(sub);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(Ui.dp(activity, 10));
        header.addView(textCol, colLp);
        card.addView(header);

        LinearLayout actions = Ui.row(activity);
        Button editBtn = Ui.button(activity, activity.getString(R.string.profile_hub_edit_action),
                v -> showEditDialog(profile));
        actions.addView(editBtn);

        Button pairBtn = Ui.button(activity, activity.getString(R.string.profile_hub_pair_device_action),
                v -> inviteExistingProfile(profile.slug()));
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pairLp.setMarginStart(Ui.dp(activity, 8));
        actions.addView(pairBtn, pairLp);

        card.addView(actions, topMargin(8));
        return card;
    }

    private void inviteExistingProfile(String slug) {
        if (closed || api.isRevoked()) return;
        io.execute(() -> {
            OnboardingModels.PairingTicket ticket = null;
            try {
                JSONObject req = new JSONObject();
                req.put("mode", "device");
                ticket = OnboardingModels.parsePairingTicket(api.inviteProfile(slug, req));
            } catch (Exception err) {
                Diagnostics.warning("profile_hub", "invite_existing_failed");
            }
            OnboardingModels.PairingTicket created = ticket;
            main.post(() -> {
                if (closed || !canAct.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
                if (created == null) {
                    Toast.makeText(activity, R.string.profile_hub_qr_unavailable, Toast.LENGTH_LONG).show();
                    return;
                }
                if (shareSheet != null) shareSheet.close();
                shareSheet = PairingShareSheet.show(activity, created, api, io, canAct, this::refreshRemoteState);
            });
        });
    }

    private void showEditDialog(OnboardingModels.RemoteProfile profile) {
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 10);
        EditText nameField = new EditText(activity);
        nameField.setHint(R.string.profile_hub_edit_name_hint);
        nameField.setText(profile.displayName());
        nameField.setSingleLine(true);
        nameField.setSaveEnabled(false);
        form.addView(nameField);

        EditText modelField = new EditText(activity);
        modelField.setHint(R.string.profile_hub_edit_model_hint);
        modelField.setText(profile.defaultModel());
        modelField.setSingleLine(true);
        modelField.setSaveEnabled(false);
        form.addView(modelField, topMargin(8));

        String[] lang = {profile.preferredLanguage()};
        String[] labels = {
                activity.getString(R.string.onboarding_language_auto),
                activity.getString(R.string.onboarding_language_en),
                activity.getString(R.string.onboarding_language_fr)
        };
        String[] codes = {"auto", "en", "fr"};
        LinearLayout langRow = Ui.row(activity);
        Button[] btns = new Button[3];
        Runnable paint = () -> {
            for (int i = 0; i < 3; i++) {
                boolean sel = codes[i].equals(lang[0]);
                btns[i].setSelected(sel);
                btns[i].setTextColor(activity.getColor(sel ? R.color.accent : R.color.ink));
            }
        };
        for (int i = 0; i < 3; i++) {
            final String code = codes[i];
            Button b = Ui.button(activity, labels[i], v -> {
                lang[0] = code;
                paint.run();
            });
            btns[i] = b;
            langRow.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        paint.run();
        form.addView(langRow, topMargin(10));

        dismissChildDialog();
        childDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.profile_hub_edit_title)
                .setView(form)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_save_action, (d, w) -> {
                    String nextName = nameField.getText().toString().trim();
                    String nextModel = modelField.getText().toString().trim();
                    io.execute(() -> {
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
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 10);

        TextView detail = Ui.text(activity, activity.getString(R.string.profile_hub_invite_detail), 13);
        detail.setTextColor(activity.getColor(R.color.muted));
        form.addView(detail);

        EditText nameField = new EditText(activity);
        nameField.setHint(R.string.profile_hub_invite_name_hint);
        nameField.setSingleLine(true);
        nameField.setSaveEnabled(false);
        form.addView(nameField, topMargin(10));

        EditText slugField = new EditText(activity);
        slugField.setHint(R.string.profile_hub_invite_slug_hint);
        slugField.setSingleLine(true);
        slugField.setSaveEnabled(false);
        form.addView(slugField, topMargin(8));

        boolean[] slugManuallyEdited = {false};
        nameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!slugManuallyEdited[0]) {
                    slugField.setText(suggestSlug(s.toString()));
                }
            }
        });
        slugField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) slugManuallyEdited[0] = true;
        });

        Switch adminToggle = new Switch(activity);
        adminToggle.setText(R.string.admin_console_grant_admin_toggle);
        adminToggle.setMinHeight(Ui.dp(activity, 48));
        if (allowAdminToggle) form.addView(adminToggle, topMargin(8));

        dismissChildDialog();
        childDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.profile_hub_invite_title)
                .setView(form)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_hub_invite_create_action, (d, w) -> {
                    String displayName = nameField.getText().toString().trim();
                    String slug = slugField.getText().toString().trim().toLowerCase(Locale.ROOT);
                    if (!isValidNewSlug(slug)) {
                        Toast.makeText(activity, R.string.profile_hub_invite_invalid_slug, Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean grantAdmin = allowAdminToggle && adminToggle.isChecked();
                    io.execute(() -> {
                        OnboardingModels.PairingTicket ticket = null;
                        try {
                            JSONObject req = new JSONObject();
                            req.put("slug", slug);
                            req.put("display_name", displayName.isEmpty() ? slug : displayName);
                            req.put("preferred_language", AppLanguage.resolvedTag(activity));
                            req.put("is_admin", grantAdmin);
                            req.put("create_invite", true);
                            JSONObject resp = api.createProfile(req);
                            JSONObject rawTicket = resp.optJSONObject("pairing_ticket");
                            if (rawTicket == null) rawTicket = resp.optJSONObject("ticket");
                            if (rawTicket == null) rawTicket = resp;
                            ticket = OnboardingModels.parsePairingTicket(rawTicket);
                            Diagnostics.event("profile_hub", "invite_created");
                        } catch (Exception err) {
                            Diagnostics.warning("profile_hub", "invite_create_failed");
                        }
                        OnboardingModels.PairingTicket created = ticket;
                        main.post(() -> {
                            if (closed || !canAct.getAsBoolean()) return;
                            if (created == null) {
                                Toast.makeText(activity, R.string.profile_hub_invite_failed, Toast.LENGTH_LONG).show();
                                return;
                            }
                            refreshRemoteState();
                            if (shareSheet != null) shareSheet.close();
                            shareSheet = PairingShareSheet.show(activity, created, api, io, canAct, this::refreshRemoteState);
                        });
                    });
                }));
    }

    static TextView avatarBadge(Activity activity, String initials, boolean accent) {
        return Ui.avatarBadge(activity, initials, 40, accent);
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
        if (sheetDialog != null) {
            try { sheetDialog.dismiss(); } catch (RuntimeException ignored) {}
            sheetDialog = null;
        }
    }
}
