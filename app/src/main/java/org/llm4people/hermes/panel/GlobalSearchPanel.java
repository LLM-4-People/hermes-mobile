package org.llm4people.hermes.panel;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native cross-thread FTS5 search panel (/api/sessions/search) with snippet highlights and 1-tap thread jump. */
final class GlobalSearchPanel {
    private final SessionPanel panel;
    private final Consumer<String> openThread;
    private final Runnable parent;
    private EditText queryField;
    private LinearLayout filterRow;
    private LinearLayout resultsBox;
    private String roleFilter = "";
    private String activeQuery = "";
    private int totalMatches;
    private boolean canLoadMore;
    private int requestSeq;
    private boolean loadingMore;
    private final List<SearchResultModel.Hit> loadedHits = new ArrayList<>();
    private Boolean supported;

    GlobalSearchPanel(SessionPanel panel, Consumer<String> openThread, Runnable parent) {
        this.panel = panel;
        this.openThread = openThread;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("brain_search", R.string.brain_search_title, parent);
        LinearLayout searchRow = Ui.row(panel.activity);
        queryField = BrainUi.searchField(panel.activity, R.id.brain_search_field, R.string.brain_search_hint);
        searchRow.addView(queryField, new LinearLayout.LayoutParams(0, -2, 1));
        searchRow.addView(Ui.button(panel.activity, panel.t(R.string.brain_search_action), v -> submitSearch()));
        page.addView(searchRow);

        queryField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            InputMethodManager imm = panel.activity.getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            submitSearch();
            return true;
        });

        filterRow = Ui.row(panel.activity);
        page.addView(BrainUi.chipBar(panel.activity, filterRow));
        renderFilters();

        resultsBox = Ui.column(panel.activity);
        resultsBox.setId(R.id.brain_search_results);
        page.addView(resultsBox);
        checkCapability();
    }

    private void checkCapability() {
        resultsBox.removeAllViews();
        panel.request(resultsBox, () -> BrainJson.parseCapabilities(panel.api.capabilities()).sessionSearch(),
                available -> {
                    supported = available;
                    if (!available) {
                        Diagnostics.event("search", "capability_absent");
                        BrainUi.showUnavailable(panel, resultsBox, R.string.brain_search_unavailable, this::checkCapability);
                    } else {
                        panel.note(resultsBox, panel.t(R.string.brain_search_prompt));
                    }
                }, error -> {
                    Diagnostics.warning("search", "capability_check_failed", error);
                    panel.failure(resultsBox, error, this::checkCapability);
                });
    }

    private void renderFilters() {
        filterRow.removeAllViews();
        filterRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_search_filter_all),
                roleFilter.isEmpty(), () -> setRoleFilter("")));
        filterRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_search_filter_user),
                "user".equals(roleFilter), () -> setRoleFilter("user")));
        filterRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_search_filter_hermes),
                "assistant".equals(roleFilter), () -> setRoleFilter("assistant")));
    }

    private void setRoleFilter(String nextRole) {
        String normalized = SearchResultModel.validateRoleFilter(nextRole);
        if (roleFilter.equals(normalized)) return;
        roleFilter = normalized;
        renderFilters();
        if (!activeQuery.isEmpty() && Boolean.TRUE.equals(supported)) {
            executeSearch(activeQuery, 0, false);
        }
    }

    private void submitSearch() {
        if (Boolean.FALSE.equals(supported)) {
            BrainUi.showUnavailable(panel, resultsBox, R.string.brain_search_unavailable, this::checkCapability);
            return;
        }
        String query = SearchResultModel.normalizeQuery(queryField.getText().toString());
        activeQuery = query;
        if (query.isEmpty()) {
            requestSeq++;
            loadingMore = false;
            loadedHits.clear();
            resultsBox.removeAllViews();
            panel.note(resultsBox, panel.t(R.string.brain_search_prompt));
            return;
        }
        executeSearch(query, 0, false);
    }

    /**
     * Every new query, role switch, or page request supersedes the previous one: only the newest
     * response ({@code seq == requestSeq}) may touch {@link #loadedHits}, so a slow earlier search can
     * never append stale hits under a newer query, and a double tap cannot fetch the same page twice.
     */
    private void executeSearch(String query, int offset, boolean append) {
        if (append && loadingMore) return;
        int seq = ++requestSeq;
        loadingMore = append;
        if (!append) {
            totalMatches = 0;
            canLoadMore = false;
            resultsBox.removeAllViews();
        }
        StringBuilder path = new StringBuilder("/api/sessions/search?q=")
                .append(HermesApi.queryValue(query))
                .append("&limit=").append(SearchResultModel.DEFAULT_LIMIT)
                .append("&offset=").append(Math.max(0, offset));
        if (!roleFilter.isEmpty()) {
            path.append("&role=").append(HermesApi.queryValue(roleFilter));
        }
        panel.request(resultsBox, () -> BrainJson.parseSearchPage(panel.api.get(path.toString())),
                result -> {
                    if (seq != requestSeq) return;
                    loadingMore = false;
                    Diagnostics.event("search", "completed");
                    if (!append) loadedHits.clear();
                    loadedHits.addAll(result.hits());
                    totalMatches = Math.max(append ? totalMatches : 0, Math.max(result.total(), loadedHits.size()));
                    canLoadMore = !result.hits().isEmpty() && result.hasMore(loadedHits.size());
                    renderResults();
                }, error -> {
                    if (seq != requestSeq) return;
                    loadingMore = false;
                    if (error instanceof HermesApi.HttpError http && (http.status == 404 || http.status == 405 || http.status == 501)) {
                        Diagnostics.warning("search", "endpoint_absent", "http_" + http.status);
                        BrainUi.showUnavailable(panel, resultsBox, R.string.brain_search_unavailable, this::submitSearch);
                    } else {
                        Diagnostics.warning("search", "query_failed", error);
                        panel.failure(resultsBox, error, () -> executeSearch(query, offset, append));
                    }
                });
    }

    private void renderResults() {
        resultsBox.removeAllViews();
        if (loadedHits.isEmpty()) {
            BrainUi.liveNote(panel, resultsBox, panel.t(R.string.brain_search_empty));
            return;
        }
        BrainUi.liveNote(panel, resultsBox, panel.t(R.string.brain_search_count, totalMatches));
        ZoneId zone = ZoneId.systemDefault();
        for (SearchResultModel.Hit hit : loadedHits) {
            resultsBox.addView(hitCard(hit, zone));
        }
        if (canLoadMore) {
            panel.action(resultsBox, R.string.brain_search_more,
                    () -> executeSearch(activeQuery, loadedHits.size(), true));
        }
    }

    private View hitCard(SearchResultModel.Hit hit, ZoneId zone) {
        LinearLayout card = BrainUi.card(panel.activity);
        TextView title = Ui.text(panel.activity, hit.displayTitle(panel.activity), 17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        String speaker = SearchResultModel.roleLabel(panel.activity, hit.role());
        String date = SearchResultModel.formatTimestamp(hit.timestamp(), zone, ReminderPanel.activeLocale(panel.activity));
        String meta = speaker.isEmpty() ? date : date.isEmpty() ? speaker : speaker + " · " + date;
        if (!meta.isEmpty()) {
            TextView metaView = Ui.text(panel.activity, meta, 13);
            metaView.setTextColor(panel.activity.getColor(R.color.muted));
            Ui.pad(metaView, 0, 2);
            card.addView(metaView);
        }

        if (!hit.snippet().plainText().isEmpty()) {
            TextView snippet = Ui.text(panel.activity, "", 15);
            snippet.setText(renderSnippet(hit.snippet()));
            Ui.pad(snippet, 0, 4);
            card.addView(snippet);
        }

        String targetSession = hit.targetSessionId();
        if (!targetSession.isEmpty() && openThread != null) {
            card.setFocusable(true);
            card.setOnClickListener(v -> jumpToThread(targetSession));
            panel.action(card, R.string.brain_search_open_thread, () -> jumpToThread(targetSession));
        }
        return card;
    }

    private void jumpToThread(String sessionId) {
        Diagnostics.event("search", "jump_to_thread");
        panel.close();
        openThread.accept(sessionId);
    }

    private CharSequence renderSnippet(SearchResultModel.HighlightedSnippet snippet) {
        SpannableString styled = new SpannableString(snippet.plainText());
        int bgColor = panel.activity.getColor(R.color.accent_surface);
        for (SearchResultModel.Span span : snippet.highlights()) {
            styled.setSpan(new BackgroundColorSpan(bgColor), span.start(), span.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(Typeface.BOLD), span.start(), span.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }
}
