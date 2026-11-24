package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.CodeReader;
import org.llm4people.hermes.net.HermesApi;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Read-only Live Workspace File & Diff Explorer (/api/sessions/{id}/workspace/{tree,file,diff}).
 * Enforces WorkspacePathGuard, opens files and diffs natively in CodeReader, and stages editable drafts.
 */
final class WorkspaceFilesPanel {
    private static final int TAB_TREE = 0;
    private static final int TAB_DIFF = 1;

    private final SessionPanel panel;
    private final String sessionId;
    private final BooleanSupplier activeThread;
    private final Consumer<String> stageDraft;
    private final Runnable parent;
    private int activeTab = TAB_TREE;
    private String currentPath = "";
    private LinearLayout tabsRow;
    private LinearLayout body;

    WorkspaceFilesPanel(SessionPanel panel, String sessionId, BooleanSupplier activeThread,
                        Consumer<String> stageDraft, Runnable parent) {
        this.panel = panel;
        this.sessionId = sessionId;
        this.activeThread = activeThread == null ? () -> false : activeThread;
        this.stageDraft = stageDraft;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("brain_files", R.string.brain_files_title, parent);
        if (sessionId == null || sessionId.isEmpty()) {
            panel.note(page, panel.t(R.string.panel_no_session));
            return;
        }
        tabsRow = Ui.row(panel.activity);
        page.addView(BrainUi.chipBar(panel.activity, tabsRow));
        renderTabs();
        body = Ui.column(panel.activity);
        body.setId(R.id.brain_files_list);
        page.addView(body);
        loadActiveTab();
    }

    private void renderTabs() {
        tabsRow.removeAllViews();
        tabsRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_files_tab_tree),
                activeTab == TAB_TREE, () -> { activeTab = TAB_TREE; renderTabs(); loadActiveTab(); }));
        tabsRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_files_tab_diff),
                activeTab == TAB_DIFF, () -> { activeTab = TAB_DIFF; renderTabs(); loadActiveTab(); }));
    }

    private void loadActiveTab() {
        body.removeAllViews();
        if (activeTab == TAB_TREE) loadTree(currentPath);
        else loadDiff();
    }

    private void loadTree(String relativePath) {
        String safeDir;
        try {
            safeDir = WorkspacePathGuard.normalizeRelativePath(relativePath, true);
        } catch (IllegalArgumentException rejected) {
            Diagnostics.warning("workspace", "invalid_tree_path");
            BrainUi.liveNote(panel, body, panel.t(R.string.brain_files_restricted));
            return;
        }
        currentPath = safeDir;
        body.removeAllViews();
        panel.request(body, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.workspaceFiles()) return null;
            String url = "/api/sessions/" + HermesApi.segment(sessionId)
                    + "/workspace/tree?depth=" + WorkspacePathGuard.DEFAULT_TREE_DEPTH
                    + "&limit=" + WorkspacePathGuard.DEFAULT_TREE_LIMIT
                    + (safeDir.isEmpty() ? "" : "&path=" + HermesApi.queryValue(safeDir));
            return BrainJson.parseWorkspaceTree(panel.api.get(url));
        }, tree -> {
            if (tree == null) {
                Diagnostics.event("workspace", "capability_absent");
                BrainUi.showUnavailable(panel, body, R.string.brain_files_unavailable, () -> loadTree(safeDir));
                return;
            }
            renderTree(tree);
        }, error -> handleWorkspaceError(error, () -> loadTree(safeDir)));
    }

    private void renderTree(BrainJson.WorkspaceTree tree) {
        body.removeAllViews();
        String location = currentPath.isEmpty()
                ? (tree.rootLabel().isEmpty() ? panel.t(R.string.brain_files_root) : tree.rootLabel())
                : currentPath;
        LinearLayout header = Ui.row(panel.activity);
        TextView pathLabel = Ui.text(panel.activity, location, 15);
        pathLabel.setTypeface(Typeface.MONOSPACE);
        pathLabel.setTextColor(panel.activity.getColor(R.color.muted));
        pathLabel.setMaxLines(2);
        pathLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathLabel.setContentDescription(location);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0, -2, 1);
        pathLp.setMarginEnd(Ui.dp(panel.activity, 8));
        header.addView(pathLabel, pathLp);
        header.addView(Ui.outlinedButton(panel.activity, panel.t(R.string.panel_refresh), v -> loadTree(currentPath)));
        body.addView(header);

        if (!currentPath.isEmpty()) {
            panel.action(body, R.string.brain_files_parent,
                    () -> loadTree(WorkspacePathGuard.parentDirectory(currentPath)));
        }
        if (tree.entries().isEmpty()) {
            panel.note(body, panel.t(R.string.brain_files_empty));
            return;
        }
        for (BrainJson.WorkspaceEntry entry : tree.entries()) {
            body.addView(entryRow(entry));
        }
    }

    private View entryRow(BrainJson.WorkspaceEntry entry) {
        LinearLayout card = interactiveCard();
        LinearLayout top = Ui.row(panel.activity);
        LinearLayout copy = Ui.column(panel.activity);
        String displayPath = entry.isDirectory()
                ? panel.t(R.string.ws_files_dir_prefix, entry.path()) : entry.path();
        TextView name = Ui.text(panel.activity, displayPath, 16);
        name.setTypeface(entry.isDirectory() ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE);
        copy.addView(name);
        if (!entry.isDirectory() && entry.sizeBytes() > 0) {
            TextView size = Ui.text(panel.activity, Formatter.formatShortFileSize(panel.activity, entry.sizeBytes()), 13);
            size.setTextColor(panel.activity.getColor(R.color.muted));
            copy.addView(size);
        }
        top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        String gitBadge = WorkspacePathGuard.gitStatusLabel(panel.activity, entry.gitStatus());
        if (!gitBadge.isEmpty()) {
            top.addView(BrainUi.badge(panel.activity, gitBadge, true));
        }
        top.addView(forwardChevron(), chevronParams());
        card.addView(top);
        card.setOnClickListener(v -> {
            if (entry.isDirectory()) loadTree(entry.path());
            else openFile(entry.path());
        });
        return card;
    }

    private void openFile(String rawPath) {
        String safePath;
        try {
            safePath = WorkspacePathGuard.normalizeRelativePath(rawPath, false);
        } catch (IllegalArgumentException rejected) {
            Diagnostics.warning("workspace", "blocked_sensitive_path");
            BrainUi.liveNote(panel, body, panel.t(R.string.brain_files_restricted));
            return;
        }
        int endLine = WorkspacePathGuard.clampEndLine(1, WorkspacePathGuard.DEFAULT_FILE_LINES);
        String url = "/api/sessions/" + HermesApi.segment(sessionId)
                + "/workspace/file?path=" + HermesApi.queryValue(safePath)
                + "&start_line=1&end_line=" + endLine;
        panel.request(body, () -> BrainJson.parseWorkspaceFile(panel.api.get(url)),
                file -> {
                    Diagnostics.event("workspace", "file_opened");
                    String title = file.truncated()
                            ? file.path() + " (" + panel.t(R.string.brain_files_truncated) + ")"
                            : file.path();
                    AlertDialog.Builder reader = CodeReader.dialog(panel.activity, title, file.language(), file.content());
                    if (stageDraft != null && activeThread.getAsBoolean()) {
                        reader.setPositiveButton(R.string.brain_files_discuss, (d, w) -> {
                            panel.close();
                            stageDraft.accept(panel.t(R.string.brain_files_discuss_prompt, file.path()));
                        });
                    }
                    panel.present(reader.create());
                }, error -> {
                    if (error instanceof HermesApi.HttpError http) {
                        Diagnostics.warning("workspace", "file_http_error", "http_" + http.status);
                        if (http.status == 415) {
                            BrainUi.liveNote(panel, body, panel.t(R.string.brain_files_binary));
                            return;
                        }
                        if (http.status == 403) {
                            BrainUi.liveNote(panel, body, panel.t(R.string.brain_files_restricted));
                            return;
                        }
                    }
                    handleWorkspaceError(error, () -> openFile(safePath));
                });
    }

    private void loadDiff() {
        body.removeAllViews();
        panel.request(body, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.workspaceFiles()) return null;
            String url = "/api/sessions/" + HermesApi.segment(sessionId) + "/workspace/diff";
            return BrainJson.parseWorkspaceDiff(panel.api.get(url));
        }, diff -> {
            if (diff == null) {
                Diagnostics.event("workspace", "capability_absent");
                BrainUi.showUnavailable(panel, body, R.string.brain_files_unavailable, this::loadDiff);
                return;
            }
            renderDiff(diff);
        }, error -> handleWorkspaceError(error, this::loadDiff));
    }

    private void renderDiff(BrainJson.WorkspaceDiff diff) {
        body.removeAllViews();
        if (!diff.branch().isEmpty() || diff.ahead() > 0 || diff.behind() > 0) {
            LinearLayout summaryCard = Ui.tonalCard(panel.activity);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.setMargins(0, Ui.dp(panel.activity, 6), 0, Ui.dp(panel.activity, 8));
            LinearLayout pills = Ui.row(panel.activity);
            int gap = Ui.dp(panel.activity, 6);
            if (!diff.branch().isEmpty()) {
                TextView branchPill = Ui.pill(panel.activity,
                        panel.t(R.string.brain_files_branch, diff.branch()),
                        R.color.surface, R.color.ink);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMarginEnd(gap);
                pills.addView(branchPill, lp);
            }
            if (diff.ahead() > 0) {
                TextView aheadPill = Ui.pill(panel.activity,
                        panel.t(R.string.ws_files_ahead, diff.ahead()),
                        R.color.accent_surface, R.color.accent);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMarginEnd(gap);
                pills.addView(aheadPill, lp);
            }
            if (diff.behind() > 0) {
                TextView behindPill = Ui.pill(panel.activity,
                        panel.t(R.string.ws_files_behind, diff.behind()),
                        R.color.warning_surface, R.color.warning);
                pills.addView(behindPill);
            }
            summaryCard.addView(BrainUi.chipBar(panel.activity, pills));
            body.addView(summaryCard, cardLp);
        }
        if (!diff.diffText().isEmpty()) {
            Button viewAll = Ui.tonalButton(panel.activity, panel.t(R.string.brain_files_view_all_diff),
                    v -> panel.present(CodeReader.dialog(
                            panel.activity, panel.t(R.string.brain_files_tab_diff), "diff", diff.diffText()).create()));
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
            btnLp.setMargins(0, Ui.dp(panel.activity, 4), 0, Ui.dp(panel.activity, 8));
            body.addView(viewAll, btnLp);
        }
        if (diff.files().isEmpty()) {
            panel.note(body, panel.t(R.string.brain_files_no_changes));
            return;
        }
        for (BrainJson.DiffFile file : diff.files()) {
            LinearLayout card = interactiveCard();
            LinearLayout row = Ui.row(panel.activity);
            LinearLayout copy = Ui.column(panel.activity);
            TextView name = Ui.text(panel.activity, file.path(), 15);
            name.setTypeface(Typeface.MONOSPACE);
            copy.addView(name);

            LinearLayout badges = Ui.row(panel.activity);
            String label = WorkspacePathGuard.gitStatusLabel(panel.activity, file.status());
            if (!label.isEmpty()) badges.addView(BrainUi.badge(panel.activity, label, false));
            badges.addView(BrainUi.badge(panel.activity, "+" + file.additions() + " / -" + file.deletions(), true));
            copy.addView(BrainUi.chipBar(panel.activity, badges));

            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(forwardChevron(), chevronParams());
            card.addView(row);
            card.setOnClickListener(v -> openFileDiff(file.path()));
            body.addView(card);
        }
    }

    private LinearLayout interactiveCard() {
        LinearLayout card = BrainUi.card(panel.activity);
        card.setBackground(Ui.roundedRipple(panel.activity, Ui.CARD_CORNER_DP,
                Ui.shape(panel.activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1)));
        card.setFocusable(true);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        return card;
    }

    private ImageView forwardChevron() {
        ImageView arrow = new ImageView(panel.activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImageTintList(ColorStateList.valueOf(panel.activity.getColor(R.color.muted)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return arrow;
    }

    private LinearLayout.LayoutParams chevronParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(panel.activity, 18), Ui.dp(panel.activity, 18));
        lp.setMarginStart(Ui.dp(panel.activity, 8));
        return lp;
    }

    private void openFileDiff(String rawPath) {
        String safePath;
        try {
            safePath = WorkspacePathGuard.normalizeRelativePath(rawPath, false);
        } catch (IllegalArgumentException rejected) {
            Diagnostics.warning("workspace", "blocked_diff_path");
            BrainUi.liveNote(panel, body, panel.t(R.string.brain_files_restricted));
            return;
        }
        String url = "/api/sessions/" + HermesApi.segment(sessionId)
                + "/workspace/diff?path=" + HermesApi.queryValue(safePath);
        panel.request(body, () -> BrainJson.parseWorkspaceDiff(panel.api.get(url)),
                diff -> {
                    String patch = diff.diffText().isEmpty() ? panel.t(R.string.brain_files_no_changes) : diff.diffText();
                    AlertDialog.Builder reader = CodeReader.dialog(panel.activity, safePath, "diff", patch);
                    if (stageDraft != null && activeThread.getAsBoolean()) {
                        reader.setPositiveButton(R.string.brain_files_discuss, (d, w) -> {
                            panel.close();
                            stageDraft.accept(panel.t(R.string.brain_files_discuss_prompt, safePath));
                        });
                    }
                    panel.present(reader.create());
                }, error -> handleWorkspaceError(error, () -> openFileDiff(safePath)));
    }

    private void handleWorkspaceError(Exception error, Runnable retry) {
        if (error instanceof HermesApi.HttpError http && (http.status == 404 || http.status == 405 || http.status == 501)) {
            Diagnostics.warning("workspace", "endpoint_absent", "http_" + http.status);
            BrainUi.showUnavailable(panel, body, R.string.brain_files_unavailable, retry);
        } else {
            Diagnostics.warning("workspace", "request_failed", error);
            panel.failure(body, error, retry);
        }
    }
}
