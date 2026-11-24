package org.llm4people.hermes.search;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.panel.BrainCapabilities;
import org.llm4people.hermes.panel.SearchResultModel;
import org.llm4people.hermes.panel.UniversalShareModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Material 3 FLAG_SECURE Universal Search overlay accessible from any screen.
 * Combines 0 ms local search (chats, settings, apps, templates, tools) with debounced server search
 * across `/api/sessions/search`, `/api/notes`, and `/api/shares/incoming`.
 */
public final class UniversalSearchSheet implements AutoCloseable {
    private static final long DEBOUNCE_MS = 250L;

    public interface Callbacks {
        void openThread(String id, String title);
        void openNote(String noteId);
        void openSharedItem(UniversalSearchModel.Result item);
        void runAction(String actionId);
        void onDismissed();
    }

    private final Activity activity;
    private final Context localized;
    private final HermesApi api;
    private final Callbacks callbacks;
    private final UniversalSearchModel.SearchSession session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-universal-search");
        t.setDaemon(true);
        return t;
    });

    private Dialog dialog;
    private EditText queryField;
    private ImageButton clearButton;
    private LinearLayout chipsRow;
    private LinearLayout statusSlot;
    private LinearLayout resultsContainer;
    private Runnable pendingDebounce;
    private volatile boolean closed;
    private volatile Boolean sessionSearchSupported;
    private volatile boolean catalogLoaded;
    private volatile List<UniversalSearchModel.Result> cachedNotes;
    private volatile List<UniversalSearchModel.Result> cachedShared;

    private UniversalSearchSheet(
            Activity activity,
            HermesApi api,
            List<UniversalSearchModel.LocalEntry> localEntries,
            Callbacks callbacks) {
        this.activity = activity;
        this.localized = AppLanguage.wrapContext(activity);
        this.api = api;
        this.callbacks = callbacks;
        this.session = new UniversalSearchModel.SearchSession(localEntries);
    }

    public static UniversalSearchSheet show(
            Activity activity,
            HermesApi api,
            List<UniversalSearchModel.LocalEntry> localEntries,
            Callbacks callbacks) {
        UniversalSearchSheet sheet = new UniversalSearchSheet(activity, api, localEntries, callbacks);
        sheet.present();
        return sheet;
    }

    private void present() {
        dialog = new Dialog(activity, R.style.Theme_Hermes);
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        LinearLayout root = Ui.column(localized);
        root.setBackgroundColor(localized.getColor(R.color.paper));
        root.setAccessibilityPaneTitle(localized.getString(R.string.universal_search_title));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                @SuppressWarnings("deprecation")
                int left = insets.getSystemWindowInsetLeft();
                @SuppressWarnings("deprecation")
                int top = insets.getSystemWindowInsetTop();
                @SuppressWarnings("deprecation")
                int right = insets.getSystemWindowInsetRight();
                @SuppressWarnings("deprecation")
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(left, top, right, bottom);
            }
            return insets;
        });

        root.addView(buildHeader());
        root.addView(Ui.divider(localized));
        root.addView(buildCategoryBar());
        root.addView(Ui.divider(localized));

        ScrollView scroll = new ScrollView(localized);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout body = Ui.column(localized);
        Ui.pad(body, 16, 16);

        statusSlot = Ui.column(localized);
        body.addView(statusSlot);

        resultsContainer = Ui.column(localized);
        body.addView(resultsContainer);

        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> teardown());
        dialog.show();

        renderCategoryChips();
        renderResults();
        prefetchRemoteCatalog();

        queryField.post(() -> {
            if (closed || queryField == null) return;
            queryField.requestFocus();
            InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(queryField, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private View buildHeader() {
        LinearLayout header = Ui.row(localized);
        header.setBackgroundColor(localized.getColor(R.color.surface));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPaddingRelative(Ui.dp(localized, 6), Ui.dp(localized, 8),
                Ui.dp(localized, 12), Ui.dp(localized, 8));

        ImageButton back = new ImageButton(localized);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(localized.getColorStateList(R.color.ink));
        back.setBackground(Ui.roundedRipple(localized, 24, null));
        back.setContentDescription(localized.getString(R.string.universal_search_close));
        back.setTooltipText(back.getContentDescription());
        back.setOnClickListener(v -> close());
        header.addView(back, Ui.dp(localized, 48), Ui.dp(localized, 48));

        LinearLayout fieldShell = Ui.row(localized);
        fieldShell.setGravity(Gravity.CENTER_VERTICAL);
        fieldShell.setBackground(Ui.shape(localized, Ui.BUTTON_CORNER_DP,
                R.color.surface_container, R.color.line, 1));
        fieldShell.setPaddingRelative(Ui.dp(localized, 12), 0, Ui.dp(localized, 4), 0);

        ImageView searchIcon = new ImageView(localized);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setImageTintList(localized.getColorStateList(R.color.muted));
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(localized, 20), Ui.dp(localized, 20));
        iconLp.setMarginEnd(Ui.dp(localized, 8));
        fieldShell.addView(searchIcon, iconLp);

        queryField = new EditText(localized);
        queryField.setTag("universal_search:query");
        queryField.setSaveEnabled(false);
        queryField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        queryField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        queryField.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        queryField.setSingleLine(true);
        queryField.setBackground(null);
        queryField.setMinHeight(Ui.dp(localized, 48));
        queryField.setTextSize(16);
        queryField.setTextColor(localized.getColor(R.color.ink));
        queryField.setHintTextColor(localized.getColor(R.color.muted));
        queryField.setHint(R.string.universal_search_hint);
        queryField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onQueryInputChanged(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        queryField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard(v);
            triggerRemoteNow(session.epoch(), session.activeQuery());
            return true;
        });
        fieldShell.addView(queryField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clearButton = new ImageButton(localized);
        clearButton.setImageResource(R.drawable.ic_prompt_deny);
        clearButton.setImageTintList(localized.getColorStateList(R.color.muted));
        clearButton.setBackground(Ui.roundedRipple(localized, 24, null));
        clearButton.setContentDescription(localized.getString(R.string.universal_search_clear));
        clearButton.setTooltipText(clearButton.getContentDescription());
        clearButton.setVisibility(View.GONE);
        clearButton.setOnClickListener(v -> {
            if (queryField != null) {
                queryField.setText("");
                queryField.requestFocus();
            }
        });
        fieldShell.addView(clearButton, Ui.dp(localized, 48), Ui.dp(localized, 48));

        LinearLayout.LayoutParams shellLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shellLp.setMarginStart(Ui.dp(localized, 8));
        header.addView(fieldShell, shellLp);
        return header;
    }

    private View buildCategoryBar() {
        HorizontalScrollView bar = new HorizontalScrollView(localized);
        bar.setHorizontalScrollBarEnabled(false);
        bar.setBackgroundColor(localized.getColor(R.color.surface));
        bar.setFillViewport(false);

        chipsRow = Ui.row(localized);
        chipsRow.setPaddingRelative(Ui.dp(localized, 16), Ui.dp(localized, 10),
                Ui.dp(localized, 16), Ui.dp(localized, 10));
        bar.addView(chipsRow);
        return bar;
    }

    private void renderCategoryChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        UniversalSearchModel.Category active = session.activeCategory();
        int gap = Ui.dp(localized, 8);
        UniversalSearchModel.Category[] cats = UniversalSearchModel.Category.values();
        for (int i = 0; i < cats.length; i++) {
            UniversalSearchModel.Category cat = cats[i];
            boolean selected = cat == active;
            Button chip = buildFilterChip(localized.getString(cat.labelRes), selected, () -> {
                if (session.activeCategory() == cat) return;
                session.setCategory(cat);
                renderCategoryChips();
                renderResults();
                if ((cat == UniversalSearchModel.Category.NOTES || cat == UniversalSearchModel.Category.SHARED)
                        && !catalogLoaded) {
                    prefetchRemoteCatalog();
                }
            });
            chip.setTag("universal_search:cat:" + cat.name().toLowerCase(java.util.Locale.ROOT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < cats.length - 1) lp.setMarginEnd(gap);
            chipsRow.addView(chip, lp);
        }
    }

    private Button buildFilterChip(String label, boolean selected, Runnable onClick) {
        Button chip = new Button(localized, null, android.R.attr.borderlessButtonStyle);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(14);
        chip.setTypeface(Ui.mediumTypeface());
        chip.setStateListAnimator(null);
        chip.setMinHeight(Ui.dp(localized, 48));
        chip.setMinimumHeight(Ui.dp(localized, 48));
        chip.setPaddingRelative(Ui.dp(localized, 16), Ui.dp(localized, 10),
                Ui.dp(localized, 16), Ui.dp(localized, 10));
        chip.setSelected(selected);
        chip.setContentDescription(label);
        chip.setTextColor(localized.getColor(selected ? R.color.on_accent : R.color.ink));
        chip.setBackground(Ui.roundedRipple(localized, Ui.BUTTON_CORNER_DP,
                selected
                        ? Ui.shape(localized, Ui.BUTTON_CORNER_DP, R.color.accent, 0, 0)
                        : Ui.shape(localized, Ui.BUTTON_CORNER_DP, R.color.surface_container, R.color.line, 1)));
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    private void onQueryInputChanged(String raw) {
        if (closed) return;
        if (clearButton != null) {
            clearButton.setVisibility(raw.isEmpty() ? View.GONE : View.VISIBLE);
        }
        int epoch = session.setQuery(raw);
        cancelPendingDebounce();
        renderResults();

        String query = session.activeQuery();
        if (api == null || query.isEmpty()) {
            session.markRemoteLoading(epoch, false);
            renderResults();
            return;
        }
        pendingDebounce = () -> triggerRemoteNow(epoch, query);
        main.postDelayed(pendingDebounce, DEBOUNCE_MS);
    }

    private void cancelPendingDebounce() {
        if (pendingDebounce != null) {
            main.removeCallbacks(pendingDebounce);
            pendingDebounce = null;
        }
    }

    private void prefetchRemoteCatalog() {
        if (closed || api == null || catalogLoaded) return;
        io.execute(() -> {
            if (closed || catalogLoaded) return;
            CatalogFetch fetch = fetchCatalogSafe();
            if (closed) return;
            catalogLoaded = !fetch.degraded;
            main.post(() -> {
                if (closed) return;
                session.updateRemoteCatalog(fetch.notes, fetch.shared);
                renderResults();
            });
        });
    }

    private void triggerRemoteNow(int epoch, String query) {
        cancelPendingDebounce();
        if (closed || api == null || query.isEmpty()) return;
        session.markRemoteLoading(epoch, true);
        renderStatus();
        io.execute(() -> {
            if (closed || epoch != session.epoch()) return;
            boolean degraded = false;
            List<UniversalSearchModel.Result> sessionHits = Collections.emptyList();
            try {
                if (supportsSessionSearch()) {
                    String path = "/api/sessions/search?q=" + HermesApi.queryValue(query)
                            + "&limit=" + UniversalSearchModel.MAX_REMOTE_SESSION_HITS
                            + "&offset=0";
                    JSONObject reply = api.get(path);
                    sessionHits = UniversalSearchModel.parseSessionSearchHits(
                            reply, localized.getString(R.string.ws_search_untitled_thread));
                }
            } catch (HermesApi.HttpError http) {
                if (http.status == 404 || http.status == 405 || http.status == 501) {
                    sessionSearchSupported = Boolean.FALSE;
                    Diagnostics.debug("universal_search", "session_search_absent");
                } else {
                    degraded = true;
                    Diagnostics.warning("universal_search", "session_search_http_" + http.status);
                }
            } catch (Exception err) {
                degraded = true;
                Diagnostics.warning("universal_search", "session_search_failed", err);
            }

            List<UniversalSearchModel.Result> notes = cachedNotes;
            List<UniversalSearchModel.Result> shared = cachedShared;
            if (!catalogLoaded) {
                CatalogFetch catalog = fetchCatalogSafe();
                notes = catalog.notes;
                shared = catalog.shared;
                if (catalog.degraded) degraded = true;
                catalogLoaded = !catalog.degraded;
            }

            final List<UniversalSearchModel.Result> finalSessions = sessionHits;
            final List<UniversalSearchModel.Result> finalNotes = notes;
            final List<UniversalSearchModel.Result> finalShared = shared;
            final boolean finalDegraded = degraded;
            main.post(() -> {
                if (closed) return;
                if (session.acceptRemote(epoch, finalSessions, finalNotes, finalShared, finalDegraded)) {
                    renderResults();
                }
            });
        });
    }

    private boolean supportsSessionSearch() {
        Boolean cached = sessionSearchSupported;
        if (cached != null) return cached;
        try {
            JSONObject caps = api.capabilities();
            JSONObject features = caps == null ? null : caps.optJSONObject("features");
            if (features != null && features.has(BrainCapabilities.SESSION_SEARCH)) {
                boolean enabled = features.optBoolean(BrainCapabilities.SESSION_SEARCH, false);
                sessionSearchSupported = enabled;
                return enabled;
            }
        } catch (Exception ignored) {
            Diagnostics.debug("universal_search", "capabilities_probe_skipped");
        }
        return true;
    }

    private record CatalogFetch(
            List<UniversalSearchModel.Result> notes,
            List<UniversalSearchModel.Result> shared,
            boolean degraded) {}

    private CatalogFetch fetchCatalogSafe() {
        boolean degraded = false;
        List<UniversalSearchModel.Result> notes = cachedNotes != null ? cachedNotes : Collections.emptyList();
        List<UniversalSearchModel.Result> shared = cachedShared != null ? cachedShared : Collections.emptyList();

        if (cachedNotes == null) {
            try {
                JSONObject resp = api.get("/api/notes");
                notes = UniversalSearchModel.parseNotesResults(
                        resp, localized.getString(R.string.ws_note_untitled));
                cachedNotes = notes;
            } catch (HermesApi.HttpError http) {
                if (http.status == 404 || http.status == 405 || http.status == 501) {
                    cachedNotes = Collections.emptyList();
                    Diagnostics.debug("universal_search", "notes_endpoint_absent");
                } else {
                    degraded = true;
                    Diagnostics.warning("universal_search", "notes_http_" + http.status);
                }
            } catch (Exception err) {
                degraded = true;
                Diagnostics.warning("universal_search", "notes_fetch_failed", err);
            }
        }

        if (cachedShared == null) {
            try {
                JSONObject resp = api.get("/api/shares/incoming");
                shared = UniversalSearchModel.parseSharedResults(resp);
                cachedShared = shared;
            } catch (HermesApi.HttpError http) {
                if (http.status == 404 || http.status == 405 || http.status == 501) {
                    cachedShared = Collections.emptyList();
                    Diagnostics.debug("universal_search", "shares_endpoint_absent");
                } else {
                    degraded = true;
                    Diagnostics.warning("universal_search", "shares_http_" + http.status);
                }
            } catch (Exception err) {
                degraded = true;
                Diagnostics.warning("universal_search", "shares_fetch_failed", err);
            }
        }
        return new CatalogFetch(notes, shared, degraded);
    }

    private void renderStatus() {
        if (statusSlot == null) return;
        statusSlot.removeAllViews();
        if (session.isRemoteLoading()) {
            LinearLayout row = Ui.row(localized);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(Ui.dp(localized, 4), 0, Ui.dp(localized, 4), Ui.dp(localized, 12));
            ProgressBar spinner = new ProgressBar(localized, null, android.R.attr.progressBarStyleSmall);
            LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(
                    Ui.dp(localized, 18), Ui.dp(localized, 18));
            spinLp.setMarginEnd(Ui.dp(localized, 10));
            row.addView(spinner, spinLp);
            TextView label = Ui.text(localized, localized.getString(R.string.universal_search_searching), 13);
            label.setTextColor(localized.getColor(R.color.muted));
            row.addView(label);
            statusSlot.addView(row);
        } else if (session.isRemoteDegraded() && !session.activeQuery().isEmpty()) {
            LinearLayout banner = Ui.row(localized);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setBackground(Ui.shape(localized, Ui.BUTTON_CORNER_DP,
                    R.color.warning_surface, 0, 0));
            Ui.pad(banner, 14, 10);
            TextView msg = Ui.text(localized, localized.getString(R.string.universal_search_offline_local_only), 13);
            msg.setTextColor(localized.getColor(R.color.warning));
            banner.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button retry = Ui.button(localized, localized.getString(R.string.panel_retry),
                    v -> triggerRemoteNow(session.epoch(), session.activeQuery()));
            banner.addView(retry);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(localized, 14);
            statusSlot.addView(banner, lp);
        }
    }

    private void renderResults() {
        renderStatus();
        if (resultsContainer == null) return;
        resultsContainer.removeAllViews();

        List<UniversalSearchModel.Result> results = session.results();
        if (results.isEmpty()) {
            if (!session.isRemoteLoading()) {
                resultsContainer.addView(buildEmptyState());
            }
            return;
        }

        if (session.activeQuery().isEmpty() && session.activeCategory() == UniversalSearchModel.Category.ALL) {
            TextView suggestedHeader = sectionHeader(localized.getString(R.string.universal_search_suggested));
            resultsContainer.addView(suggestedHeader);
        }

        Map<UniversalSearchModel.Category, List<UniversalSearchModel.Result>> grouped =
                UniversalSearchModel.groupByCategory(results);
        boolean firstGroup = true;
        for (Map.Entry<UniversalSearchModel.Category, List<UniversalSearchModel.Result>> entry : grouped.entrySet()) {
            TextView header = sectionHeader(localized.getString(entry.getKey().labelRes));
            if (!firstGroup) {
                header.setPaddingRelative(Ui.dp(localized, 4), Ui.dp(localized, 22),
                        Ui.dp(localized, 4), Ui.dp(localized, 8));
            }
            resultsContainer.addView(header);
            resultsContainer.addView(buildGroupCard(entry.getValue()));
            firstGroup = false;
        }
    }

    private TextView sectionHeader(String label) {
        TextView heading = Ui.text(localized, label, 14);
        heading.setTypeface(Ui.mediumTypeface());
        heading.setTextColor(localized.getColor(R.color.muted));
        heading.setAccessibilityHeading(true);
        heading.setPaddingRelative(Ui.dp(localized, 4), Ui.dp(localized, 4),
                Ui.dp(localized, 4), Ui.dp(localized, 8));
        return heading;
    }

    private View buildGroupCard(List<UniversalSearchModel.Result> items) {
        LinearLayout card = Ui.card(localized);
        card.setPadding(0, Ui.dp(localized, 4), 0, Ui.dp(localized, 4));
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) card.addView(Ui.insetDivider(localized, 16));
            card.addView(buildResultRow(items.get(i)));
        }
        return card;
    }

    private View buildResultRow(UniversalSearchModel.Result item) {
        LinearLayout row = Ui.column(localized);
        row.setMinimumHeight(Ui.dp(localized, 56));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.roundedRipple(localized, 12, null));
        Ui.pad(row, 16, 12);
        row.setFocusable(true);
        row.setClickable(true);

        LinearLayout top = Ui.row(localized);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        TextView title = Ui.text(localized, item.title(), 16);
        title.setTypeface(Ui.mediumTypeface());
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginEnd(Ui.dp(localized, 10));
        top.addView(title, titleLp);

        String pillLabel = badgeLabelFor(item);
        TextView pill = Ui.pill(localized, pillLabel, R.color.accent_surface, R.color.accent);
        top.addView(pill);
        row.addView(top);

        String subtitle = subtitleFor(item);
        if (!subtitle.isEmpty()) {
            TextView sub = Ui.text(localized, "", 14);
            sub.setTextColor(localized.getColor(R.color.muted));
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPaddingRelative(0, Ui.dp(localized, 4), 0, 0);
            sub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (!item.snippetHighlights().isEmpty()) {
                sub.setText(renderHighlightedSnippet(subtitle, item.snippetHighlights()));
            } else {
                sub.setText(subtitle);
            }
            row.addView(sub);
        }

        String baseDesc = localized.getString(
                R.string.universal_search_open_result_cd, item.title(), pillLabel);
        row.setContentDescription(subtitle.isEmpty() ? baseDesc : baseDesc + ". " + subtitle);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setOnClickListener(v -> dispatchResult(item));
        return row;
    }

    private String badgeLabelFor(UniversalSearchModel.Result item) {
        if (item.category() == UniversalSearchModel.Category.SHARED && !item.resourceKind().isEmpty()) {
            return UniversalShareModel.kindLabel(localized, item.resourceKind());
        }
        return localized.getString(item.category().labelRes);
    }

    private String subtitleFor(UniversalSearchModel.Result item) {
        if (item.category() == UniversalSearchModel.Category.SHARED) {
            String perm = item.permission().isEmpty()
                    ? ""
                    : UniversalShareModel.permissionLabel(localized, item.permission());
            String owner = item.ownerProfile().isEmpty()
                    ? ""
                    : localized.getString(R.string.ws_shared_by, item.ownerProfile());
            if (!owner.isEmpty() && !perm.isEmpty()) return owner + " · " + perm;
            if (!owner.isEmpty()) return owner;
            return perm;
        }
        return item.subtitle();
    }

    private CharSequence renderHighlightedSnippet(String plain, List<SearchResultModel.Span> spans) {
        SpannableString styled = new SpannableString(plain);
        int bg = localized.getColor(R.color.accent_surface);
        for (SearchResultModel.Span span : spans) {
            int s = Math.max(0, Math.min(plain.length(), span.start()));
            int e = Math.max(s, Math.min(plain.length(), span.end()));
            if (e > s) {
                styled.setSpan(new BackgroundColorSpan(bg), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return styled;
    }

    private View buildEmptyState() {
        LinearLayout card = Ui.tonalCard(localized);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(card, 24, 28);

        ImageView icon = new ImageView(localized);
        icon.setImageResource(R.drawable.ic_search);
        icon.setImageTintList(localized.getColorStateList(R.color.accent));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(localized.getColor(R.color.accent_surface));
        icon.setBackground(circle);
        int pad = Ui.dp(localized, 10);
        icon.setPadding(pad, pad, pad, pad);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(localized, 44), Ui.dp(localized, 44));
        iconLp.bottomMargin = Ui.dp(localized, 12);
        card.addView(icon, iconLp);

        boolean degradedOnly = session.isRemoteDegraded() && !session.activeQuery().isEmpty();
        String titleText = localized.getString(degradedOnly
                ? R.string.universal_search_error
                : R.string.universal_search_empty_title);
        TextView title = Ui.text(localized, titleText, 16);
        title.setTypeface(Ui.mediumTypeface());
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(title);

        int hintRes = session.activeCategory() == UniversalSearchModel.Category.SHARED
                && session.activeQuery().isEmpty()
                ? R.string.universal_search_empty_shared
                : R.string.universal_search_empty_hint;
        TextView hint = Ui.text(localized, localized.getString(hintRes), 14);
        hint.setTextColor(localized.getColor(R.color.muted));
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPaddingRelative(0, Ui.dp(localized, 6), 0, Ui.dp(localized, 16));
        card.addView(hint);

        if (degradedOnly) {
            card.addView(Ui.outlinedButton(localized, localized.getString(R.string.panel_retry),
                    v -> triggerRemoteNow(session.epoch(), session.activeQuery())));
        } else if (!session.activeQuery().isEmpty()
                || session.activeCategory() != UniversalSearchModel.Category.ALL) {
            card.addView(Ui.outlinedButton(localized, localized.getString(R.string.universal_search_clear), v -> {
                session.setCategory(UniversalSearchModel.Category.ALL);
                if (queryField != null) queryField.setText("");
                renderCategoryChips();
                renderResults();
            }));
        }
        return card;
    }

    private void dispatchResult(UniversalSearchModel.Result item) {
        Diagnostics.event("universal_search", "result_selected_" + item.category().name().toLowerCase(java.util.Locale.ROOT));
        close();
        if (callbacks == null) return;
        switch (item.category()) {
            case CHATS -> {
                if (!item.targetId().isEmpty()) callbacks.openThread(item.targetId(), item.title());
                else if (!item.actionId().isEmpty()) callbacks.runAction(item.actionId());
            }
            case NOTES -> {
                if (!item.targetId().isEmpty()) callbacks.openNote(item.targetId());
                else if (!item.actionId().isEmpty()) callbacks.runAction(item.actionId());
            }
            case SHARED -> callbacks.openSharedItem(item);
            case SETTINGS, TOOLS, ALL -> {
                if (!item.actionId().isEmpty()) callbacks.runAction(item.actionId());
            }
        }
    }

    private void hideKeyboard(View anchor) {
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        if (imm != null && anchor != null) {
            imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        }
    }

    private void teardown() {
        if (closed) return;
        closed = true;
        cancelPendingDebounce();
        if (queryField != null) {
            queryField.setText("");
        }
        io.shutdownNow();
        dialog = null;
        if (callbacks != null) {
            callbacks.onDismissed();
        }
    }

    @Override
    public void close() {
        Dialog active = dialog;
        teardown();
        if (active != null && active.isShowing()) {
            try { active.dismiss(); } catch (RuntimeException ignored) {}
        }
    }

}
