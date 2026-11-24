package org.llm4people.hermes.settings;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.os.PermissionsPanel;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.presence.PresenceSettingsPanel;
import org.llm4people.hermes.templates.TemplatesPanel;
import org.llm4people.hermes.update.UpdatePanel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Dedicated Material 3 Settings Hub with a Profile Identity hero card, instant accent-insensitive
 * settings search, notification permission banner, and five clean grouped setting categories.
 */
public final class SettingsHubPanel {
    public static final String ROUTE_ID = "settings";
    public static final String ACTION_VOICE_OPTIONS = "voice_options";
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public interface StateProvider {
        String profileName();
        String serverEndpoint();
        String lockModeLabel();
        String lockTimeoutLabel();
        String securitySummary();
        String themeModeLabel();
        String appearanceSummary();
        String languageSummary();
        String voiceLocaleLabel();
        String updateBadge();
        String versionName();
    }

    private final Activity activity;
    private final StateProvider state;
    private final Consumer<String> onAction;

    private LinearLayout heroSlot;
    private LinearLayout bannerSlot;
    private LinearLayout sectionsSlot;
    private String currentQuery = "";

    public SettingsHubPanel(Activity activity, StateProvider state, Consumer<String> onAction) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.state = Objects.requireNonNull(state, "state");
        this.onAction = Objects.requireNonNull(onAction, "onAction");
    }

    public View buildView() {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                float density = getResources().getDisplayMetrics().density;
                int gutter = Ui.dp(activity, AdaptiveLayout.centeredGutterDp(
                        MeasureSpec.getSize(widthSpec) / density, AdaptiveLayout.READING_COLUMN_MAX_DP, 20));
                int top = Ui.dp(activity, 14);
                int bottom = Ui.dp(activity, 28);
                if (list.getPaddingStart() != gutter || list.getPaddingTop() != top) {
                    list.setPaddingRelative(gutter, top, gutter, bottom);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setTag("settings:hub");

        TextView subtitle = Ui.text(activity, activity.getString(R.string.settings_subtitle), 15);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        subtitle.setLineSpacing(0f, 1.25f);
        subtitle.setPaddingRelative(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 12));
        list.addView(subtitle);

        heroSlot = Ui.column(activity);
        list.addView(heroSlot, new LinearLayout.LayoutParams(-1, -2));

        bannerSlot = Ui.column(activity);
        list.addView(bannerSlot, new LinearLayout.LayoutParams(-1, -2));

        EditText search = buildSearchBar();
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.topMargin = Ui.dp(activity, 14);
        searchLp.bottomMargin = Ui.dp(activity, 4);
        list.addView(search, searchLp);

        sectionsSlot = Ui.column(activity);
        list.addView(sectionsSlot, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        refresh();
        Diagnostics.debug("settings_hub", "opened");
        return scroll;
    }

    /** Rebuild live hero pills, notification banner, and grouped sections while preserving search query. */
    public void refresh() {
        if (heroSlot == null || bannerSlot == null || sectionsSlot == null) return;
        renderHero();
        renderBanner();
        renderSections();
    }

    private EditText buildSearchBar() {
        EditText query = new EditText(activity);
        query.setTag("settings:search");
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setSaveEnabled(false);
        query.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        query.setHint(R.string.settings_search_hint);
        query.setTextColor(activity.getColor(R.color.ink));
        query.setHintTextColor(activity.getColor(R.color.muted));
        query.setBackgroundResource(R.drawable.profile_input);
        query.setBackgroundTintList(null);
        query.setMinHeight(Ui.dp(activity, 48));
        Ui.pad(query, 14, 10);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s == null ? "" : s.toString();
                renderSections();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        return query;
    }

    private void renderHero() {
        heroSlot.removeAllViews();
        LinearLayout card = Ui.card(activity);
        card.setTag("settings:hero_card");

        String name = safeText(state.profileName());
        String host = SettingsHubModel.redactedHost(
                state.serverEndpoint(), activity.getString(R.string.settings_host_unknown));

        LinearLayout header = Ui.row(activity);
        TextView avatar = Ui.avatarBadge(activity, Ui.initials(name), 44, true);
        header.addView(avatar);

        LinearLayout identity = Ui.column(activity);
        identity.setPaddingRelative(Ui.dp(activity, 12), 0, 0, 0);
        TextView nameView = Ui.text(activity, name, 18);
        nameView.setTypeface(MEDIUM);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(nameView);

        TextView hostView = Ui.text(activity, host, 13);
        hostView.setTextColor(activity.getColor(R.color.muted));
        hostView.setMaxLines(1);
        hostView.setEllipsize(TextUtils.TruncateAt.END);
        hostView.setPaddingRelative(0, Ui.dp(activity, 2), 0, 0);
        identity.addView(hostView);

        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(header);

        SettingsWrapLayout pills = new SettingsWrapLayout(activity, 8, 6);
        String lockMode = safeText(state.lockModeLabel());
        String lockTimeout = safeText(state.lockTimeoutLabel());
        String themeMode = safeText(state.themeModeLabel());
        if (!lockMode.isEmpty()) {
            pills.addView(Ui.pill(activity, lockMode, R.color.accent_surface, R.color.accent));
        }
        if (!lockTimeout.isEmpty()) {
            pills.addView(Ui.pill(activity, lockTimeout, R.color.surface_container, R.color.muted));
        }
        if (!themeMode.isEmpty()) {
            pills.addView(Ui.pill(activity, themeMode, R.color.surface_container, R.color.muted));
        }
        if (pills.getChildCount() > 0) {
            LinearLayout.LayoutParams pillsLp = new LinearLayout.LayoutParams(-1, -2);
            pillsLp.topMargin = Ui.dp(activity, 12);
            card.addView(pills, pillsLp);
        }

        SettingsWrapLayout actions = new SettingsWrapLayout(activity, 10, 8);
        Button lockBtn = Ui.outlinedButton(activity, activity.getString(R.string.security_lock_now),
                v -> onAction.accept("profiles"));
        lockBtn.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{activity.getColor(R.color.disabled_ink), activity.getColor(R.color.danger)}));
        actions.addView(lockBtn);

        Button switchBtn = Ui.tonalButton(activity, activity.getString(R.string.settings_switch_profile),
                v -> onAction.accept("switch_profile"));
        actions.addView(switchBtn);

        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = Ui.dp(activity, 12);
        card.addView(actions, actionsLp);

        heroSlot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderBanner() {
        bannerSlot.removeAllViews();
        View notifBanner = PermissionsPanel.buildNotificationPromptBanner(
                activity, () -> onAction.accept(PermissionsPanel.ROUTE_ID));
        if (notifBanner != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(activity, 12);
            bannerSlot.addView(notifBanner, lp);
        }
    }

    private void renderSections() {
        sectionsSlot.removeAllViews();
        List<SettingsHubModel.SectionSpec> filtered = SettingsHubModel.filterSections(buildSections(), currentQuery);
        if (filtered.isEmpty()) {
            LinearLayout emptyCard = Ui.tonalCard(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(activity, 12);
            TextView empty = Ui.text(activity,
                    activity.getString(R.string.settings_search_empty, currentQuery.trim()), 15);
            empty.setTextColor(activity.getColor(R.color.muted));
            emptyCard.addView(empty);
            sectionsSlot.addView(emptyCard, lp);
            return;
        }
        for (SettingsHubModel.SectionSpec section : filtered) {
            TextView heading = Ui.text(activity, section.title(), 18);
            heading.setTypeface(MEDIUM);
            heading.setLetterSpacing(-0.01f);
            heading.setPaddingRelative(Ui.dp(activity, 4), Ui.dp(activity, 20), 0, Ui.dp(activity, 8));
            heading.setAccessibilityHeading(true);
            sectionsSlot.addView(heading);

            LinearLayout group = Ui.column(activity);
            group.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1));
            group.setClipToOutline(true);
            for (SettingsHubModel.ItemSpec item : section.items()) {
                if (group.getChildCount() > 0) {
                    group.addView(Ui.insetDivider(activity, 64));
                }
                group.addView(buildRow(item));
            }
            sectionsSlot.addView(group, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View buildRow(SettingsHubModel.ItemSpec item) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(Ui.dp(activity, 56));
        row.setPaddingRelative(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 14), Ui.dp(activity, 12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Ui.roundedRipple(activity, 0, null));
        row.setOnClickListener(v -> onAction.accept(item.actionId()));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setContentDescription(item.subtitle().isEmpty()
                ? item.title() : item.title() + ". " + item.subtitle());

        LinearLayout iconCircle = new LinearLayout(activity);
        iconCircle.setGravity(Gravity.CENTER);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(activity.getColor(R.color.accent_surface));
        iconCircle.setBackground(circleBg);
        iconCircle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int circlePx = Ui.dp(activity, 36);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(item.iconRes());
        icon.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.accent)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconPx = Ui.dp(activity, 20);
        iconCircle.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(circlePx, circlePx);
        iconLp.setMarginEnd(Ui.dp(activity, 12));
        row.addView(iconCircle, iconLp);

        LinearLayout copy = Ui.column(activity);
        TextView title = Ui.text(activity, item.title(), 16);
        title.setTypeface(MEDIUM);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(title);
        if (!item.subtitle().isEmpty()) {
            TextView detail = Ui.text(activity, item.subtitle(), 14);
            detail.setTextColor(activity.getColor(R.color.muted));
            detail.setLineSpacing(0f, 1.2f);
            detail.setPaddingRelative(0, Ui.dp(activity, 2), 0, 0);
            detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            copy.addView(detail);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.muted)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(Ui.dp(activity, 18), Ui.dp(activity, 18));
        arrowLp.setMarginStart(Ui.dp(activity, 8));
        row.addView(arrow, arrowLp);
        return row;
    }

    private List<SettingsHubModel.SectionSpec> buildSections() {
        List<SettingsHubModel.SectionSpec> sections = new ArrayList<>(5);

        String assistantKw = activity.getString(R.string.settings_kw_assistant);
        sections.add(new SettingsHubModel.SectionSpec(
                "assistant",
                activity.getString(R.string.settings_section_assistant),
                List.of(
                        new SettingsHubModel.ItemSpec(
                                BrainStudioRoutes.ROUTE_PROFILE_DEFAULTS,
                                R.drawable.ic_tune,
                                activity.getString(R.string.settings_item_profile_defaults_title),
                                activity.getString(R.string.settings_item_profile_defaults_hint),
                                assistantKw),
                        new SettingsHubModel.ItemSpec(
                                "models",
                                R.drawable.ic_tune,
                                activity.getString(R.string.settings_item_thread_model_title),
                                activity.getString(R.string.settings_item_thread_model_hint),
                                assistantKw),
                        new SettingsHubModel.ItemSpec(
                                ACTION_VOICE_OPTIONS,
                                R.drawable.ic_voice_settings,
                                activity.getString(R.string.settings_item_voice_title),
                                activity.getString(R.string.settings_item_voice_hint, safeText(state.voiceLocaleLabel())),
                                assistantKw),
                        new SettingsHubModel.ItemSpec(
                                BrainStudioRoutes.ROUTE_MEMORY,
                                R.drawable.ic_brain,
                                activity.getString(R.string.settings_item_memory_title),
                                activity.getString(R.string.brain_memory_card_hint),
                                assistantKw),
                        new SettingsHubModel.ItemSpec(
                                BrainStudioRoutes.ROUTE_SKILLS,
                                R.drawable.ic_tune,
                                activity.getString(R.string.settings_item_skills_title),
                                activity.getString(R.string.brain_skills_hint),
                                assistantKw)
                )));

        String appearanceKw = activity.getString(R.string.settings_kw_appearance);
        sections.add(new SettingsHubModel.SectionSpec(
                "appearance",
                activity.getString(R.string.settings_section_appearance),
                List.of(
                        new SettingsHubModel.ItemSpec(
                                AppearanceSettingsPanel.ROUTE_ID,
                                R.drawable.ic_palette,
                                activity.getString(R.string.settings_item_appearance_title),
                                safeText(state.appearanceSummary()),
                                appearanceKw),
                        new SettingsHubModel.ItemSpec(
                                "language",
                                R.drawable.ic_language,
                                activity.getString(R.string.settings_item_language_title),
                                safeText(state.languageSummary()),
                                appearanceKw)
                )));

        String securityKw = activity.getString(R.string.settings_kw_security);
        sections.add(new SettingsHubModel.SectionSpec(
                "security",
                activity.getString(R.string.settings_section_security),
                List.of(
                        new SettingsHubModel.ItemSpec(
                                "security",
                                R.drawable.ic_lock,
                                activity.getString(R.string.settings_item_security_title),
                                safeText(state.securitySummary()),
                                securityKw),
                        new SettingsHubModel.ItemSpec(
                                PermissionsPanel.ROUTE_ID,
                                R.drawable.ic_shield,
                                activity.getString(R.string.settings_item_permissions_title),
                                PermissionsPanel.summaryLine(activity),
                                securityKw),
                        new SettingsHubModel.ItemSpec(
                                AmbientAccessPanel.ROUTE_ID,
                                R.drawable.ic_shield,
                                activity.getString(R.string.settings_item_ambient_title),
                                activity.getString(R.string.ambient_route_hint),
                                securityKw),
                        new SettingsHubModel.ItemSpec(
                                PresenceSettingsPanel.ROUTE_ID,
                                R.drawable.ic_tune,
                                activity.getString(R.string.settings_item_presence_title),
                                activity.getString(R.string.presence_route_hint),
                                securityKw)
                )));

        String notifKw = activity.getString(R.string.settings_kw_notifications);
        sections.add(new SettingsHubModel.SectionSpec(
                "notifications",
                activity.getString(R.string.settings_section_notifications),
                List.of(
                        new SettingsHubModel.ItemSpec(
                                "notifications",
                                R.drawable.ic_notifications,
                                activity.getString(R.string.settings_item_notifications_title),
                                activity.getString(R.string.activity_notifications_hint),
                                notifKw),
                        new SettingsHubModel.ItemSpec(
                                "push_policy",
                                R.drawable.ic_notifications,
                                activity.getString(R.string.settings_item_push_policy_title),
                                activity.getString(R.string.activity_push_policy_hint),
                                notifKw)
                )));

        String systemKw = activity.getString(R.string.settings_kw_system);
        String version = safeText(state.versionName());
        String versionLabel = version.isEmpty()
                ? activity.getString(R.string.update_route_hint)
                : activity.getString(R.string.settings_version_format, version);
        String updateSub = SettingsHubModel.updateSubtitle(state.updateBadge(), versionLabel);
        sections.add(new SettingsHubModel.SectionSpec(
                "system",
                activity.getString(R.string.settings_section_system),
                List.of(
                        new SettingsHubModel.ItemSpec(
                                BrainStudioRoutes.ROUTE_WORKSPACE_FILES,
                                R.drawable.ic_widgets,
                                activity.getString(R.string.settings_item_files_title),
                                activity.getString(R.string.brain_files_card_hint),
                                systemKw),
                        new SettingsHubModel.ItemSpec(
                                TemplatesPanel.ROUTE_ID,
                                R.drawable.ic_apps,
                                activity.getString(R.string.settings_item_templates_title),
                                activity.getString(R.string.templates_route_hint),
                                systemKw),
                        new SettingsHubModel.ItemSpec(
                                "stats",
                                R.drawable.ic_activity,
                                activity.getString(R.string.settings_item_usage_title),
                                activity.getString(R.string.activity_usage_hint),
                                systemKw),
                        new SettingsHubModel.ItemSpec(
                                ConnectionDoctorPanel.ROUTE_ID,
                                R.drawable.ic_health,
                                activity.getString(R.string.settings_item_doctor_title),
                                activity.getString(R.string.doctor_route_hint),
                                systemKw),
                        new SettingsHubModel.ItemSpec(
                                UpdatePanel.ROUTE_ID,
                                R.drawable.ic_update,
                                activity.getString(R.string.settings_item_updates_title),
                                updateSub,
                                systemKw)
                )));

        return sections;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
