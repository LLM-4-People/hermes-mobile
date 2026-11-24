package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONArray;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Native, profile-scoped artifact reader. Construct from the current ordered transcript. */
public final class ArtifactDesk {
    private static final Set<HermesApi> ACTIVE_REVERTS = ConcurrentHashMap.newKeySet();
    public interface Host {
        BooleanSupplier deviceActionOrigin();
        /** Only a verified proposal in its originating discussion can mount a nearby guide. */
        default View nearbyGuide() { return null; }
        void onAction(String actionId);
        void onLayoutChanged();
        void openWorkspace(String pageId);
        void stageDraft(String text);
        String workspaceValue(String source);
        void threadSlots(List<String> order);
    }

    private final Activity activity;
    private final Account account;
    private final Host host;
    private final ArtifactCatalog catalog;
    private final boolean threadSelected;

    public ArtifactDesk(Activity activity, Account account, String threadId, JSONArray messages, Host host) {
        this.activity = activity;
        this.account = account;
        this.host = host;
        threadSelected = threadId != null && !threadId.isEmpty();
        catalog = new ArtifactCatalog(activity, messages == null ? new JSONArray() : messages);
    }

    public static String proposalInstructions(Account account, String threadId) {
        String instructions = WorkspaceLayout.instructions();
        try {
            ThreadInstruments.Pin pin = new ThreadInstruments(account, threadId).state().current;
            if (pin != null) instructions += WorkspaceLayout.pinnedReference(pin.pageId, pin.messageId, pin.fenceOrdinal);
        } catch (IllegalStateException unreadable) {
            Diagnostics.warning("workspace", "instrument_reference_unavailable");
        }
        return instructions;
    }

    public static boolean supportsSurface(String surface) {
        return surface != null && (WorkspaceLayout.SURFACES.containsKey(surface) || WorkspaceLayout.custom(surface));
    }

    public static View decorate(Activity activity, Account account, String surface, View nativeContent, Host host) {
        return SurfaceRenderer.decorate(activity, account, surface, nativeContent, host);
    }

    public static View pages(Activity activity, Account account, Host host) {
        return WorkspacePages.gallery(activity, account, host);
    }

    /** Returns active custom screen titles for the Apps launcher without duplicating page index decoding. */
    public static Map<String, String> savedPageTitles(android.content.Context context, Account account) {
        if (context == null || account == null) return java.util.Collections.emptyMap();
        try {
            return PageLayouts.titles(context, account, false);
        } catch (IllegalStateException unavailable) {
            Diagnostics.warning("workspace", "saved_page_titles_unavailable");
            return java.util.Collections.emptyMap();
        }
    }

    public static View page(Activity activity, Account account, String id, Host host) {
        if (!WorkspaceLayout.custom(id)) return null;
        return SurfaceRenderer.decorate(activity, account, id, null, host);
    }

    public static void refreshBindings(View root, Host host) {
        DeskDialogs.dismissStale();
        WorkspaceBlocks.refresh(root,host);
    }

    public static void dismiss(Activity activity) {
        revokeAll();
        DeskDialogs.dismiss(activity);
    }

    /** Revokes any in-flight live-UI gateway revert clients on lock, profile switch, or destroy. */
    public static void revokeAll() {
        for (HermesApi client : new ArrayList<>(ACTIVE_REVERTS)) {
            ACTIVE_REVERTS.remove(client);
            client.revoke();
        }
    }

    static void trackRevertClientForTest(HermesApi client) {
        if (client != null) ACTIVE_REVERTS.add(client);
    }

    static int activeRevertCountForTest() {
        return ACTIVE_REVERTS.size();
    }
    public static void dismissReviews(Activity activity, Host owner) {
        if (owner != null) DeskDialogs.dismiss(activity, owner);
    }

    /** Recovery is always reachable from native navigation, even after a layout has been restored. */
    public static void manage(Activity activity, Account account, Host host) {
        Map<String,String> all = WorkspaceLayout.surfaceLabels(activity);
        AlertDialog.Builder controls = new AlertDialog.Builder(activity)
                .setTitle(R.string.ws_art_screen_layouts);
        try {
            PageLayouts.titles(activity, account, true)
                    .forEach((id, title) -> all.put(id, title + " (" + id.substring(5) + ")"));
        } catch (PageLayouts.Damaged damaged) {
            controls.setNeutralButton(R.string.ws_pages_repair_action, (dialog, which) ->
                    WorkspacePages.confirmRecovery(activity, damaged, host));
        } catch (IllegalStateException error) {
            Diagnostics.warning("workspace","page_index_unavailable");
        }
        String[] surfaces = all.keySet().toArray(new String[0]);
        String[] labels = all.values().toArray(new String[0]);
        LiveUiPatch.StateManager liveUi = LiveUiPatch.StateManager.forAccount(account, ThemePalette.isNight(activity));
        if (liveUi.isCorrupted()) {
            controls.setNeutralButton(R.string.ws_art_reset_history_action, (d, w) ->
                    DeskDialogs.open(activity, host, new AlertDialog.Builder(activity)
                            .setTitle(R.string.ws_art_reset_history_title)
                            .setMessage(R.string.ws_art_reset_history_message)
                            .setNegativeButton(R.string.ws_cancel, null)
                            .setPositiveButton(R.string.ws_reset, (dlg, which) -> {
                                if (!isOpen(activity, account)) return;
                                SurfaceRenderer.change(activity, host, liveUi::resetCorrupted);
                            })));
        } else if (liveUi.canRollback()) {
            controls.setNeutralButton(R.string.ws_art_change_history, (d, w) ->
                    LiveUiPatchBar.showHistoryDialog(activity, liveUi, revId -> {
                        if (!isOpen(activity, account)) return;
                        List<String> revertedIds = new java.util.ArrayList<>();
                        SurfaceRenderer.change(activity, host, () -> {
                            if (!isOpen(activity, account)) return;
                            java.util.Set<String> affectedWidgets = new java.util.HashSet<>(liveUi.widgetIds());
                            try {
                                List<LiveUiPatch.StateManager.AppliedRevision> reverted = liveUi.rollbackAllTo(revId);
                                if (reverted.isEmpty()) return;
                                affectedWidgets.addAll(liveUi.widgetIds());
                                HomePinBridge.syncWidgets(activity, liveUi.widgets(), affectedWidgets, account.id);
                                for (LiveUiPatch.StateManager.AppliedRevision rev : reverted) {
                                    revertedIds.add(rev.revisionId);
                                }
                            } catch (IOException e) {
                                Diagnostics.warning("live_ui", "rollback_failed");
                            }
                        });
                        if (!revertedIds.isEmpty() && isOpen(activity, account)) {
                            syncGatewayReverts(activity, account, revertedIds);
                        }
                    }));
        }
        DeskDialogs.open(activity, host, controls.setItems(labels, (d, which) -> SurfaceRenderer.controls(activity,
                        new LayoutHistory(account, surfaces[which]), host))
                .setNegativeButton(R.string.ws_close, null));
    }

    private static boolean isOpen(Activity activity, Account account) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || account == null) return false;
        try {
            account.read("workspace.live_ui.v2");
            return true;
        } catch (IllegalStateException lockedOrClosed) {
            return false;
        }
    }

    private static void syncGatewayReverts(android.content.Context context, Account account, List<String> revisionIds) {
        if (account == null || revisionIds == null || revisionIds.isEmpty()) return;
        String endpoint = account.endpoint;
        String bearer = account.bearer;
        HermesApi.Limits limits = HermesApi.Limits.from(HermesConfig.load(context));
        List<String> ordered = List.copyOf(revisionIds);
        final HermesApi api = new HermesApi(endpoint, bearer, limits);
        ACTIVE_REVERTS.add(api);
        Thread worker = new Thread(() -> {
            try {
                if (api.isRevoked()) return;
                for (String revisionId : ordered) {
                    if (api.isRevoked()) break;
                    if (revisionId == null || revisionId.isEmpty()) continue;
                    try {
                        api.revertMobileUiRevision(revisionId);
                        Diagnostics.event("live_ui", "server_reverted");
                    } catch (IOException | IllegalArgumentException e) {
                        Diagnostics.event("live_ui", "server_revert_skipped");
                        break;
                    }
                }
            } finally {
                ACTIVE_REVERTS.remove(api);
                api.revoke();
            }
        }, "hermes-ui-revert");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | Error failed) {
            ACTIVE_REVERTS.remove(api);
            api.revoke();
            throw failed;
        }
    }

    public void show() {
        LinearLayout list = Ui.column(activity);
        Ui.pad(list, 18, 8);
        if (threadSelected) list.addView(Ui.button(activity, activity.getString(R.string.ws_art_published_files), v -> {
            DeskDialogs.navigate(activity, () -> host.onAction("published_files"));
        }));
        if (catalog.records.isEmpty()) list.addView(Ui.text(activity,
                activity.getString(R.string.ws_art_empty_desk), 16));
        for (ArtifactCatalog.Record record : catalog.records) {
            int versions = record.versions.size();
            String label = versions > 1
                    ? activity.getString(R.string.ws_art_versions_suffix, record.title, versions)
                    : record.title;
            Button b = Ui.button(activity, label, v -> detail(record, versions - 1));
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setAllCaps(false);
            list.addView(b);
            list.addView(Ui.divider(activity));
        }
        if (catalog.limited) list.addView(Ui.text(activity,
                activity.getString(R.string.ws_art_catalog_limited), 14));
        ScrollView nativeList = scroll(list);
        DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle(R.string.ws_art_thread_artifacts)
                .setView(decorate(activity, account, "artifacts", nativeList, host))
                .setNegativeButton(R.string.ws_close, null));
    }

    private void detail(ArtifactCatalog.Record record, int version) {
        LinearLayout box = Ui.column(activity);
        Ui.pad(box, 16, 8);
        ScrollView scroll = scroll(box);
        presentVersion(box, scroll, record, version);
        DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle(record.title)
                .setView(scroll).setNegativeButton(R.string.ws_close, null));
    }

    private void presentVersion(LinearLayout box, ScrollView scroll, ArtifactCatalog.Record record, int version) {
        box.removeAllViews();
        String content = record.versions.get(version);
        if (record.versions.size() > 1) {
            String label = activity.getString(R.string.ws_art_version_of, version + 1, record.versions.size());
            box.addView(Ui.button(activity, label, v -> {
                String[] options = new String[record.versions.size()];
                for (int n = 0; n < options.length; n++) {
                    options[n] = activity.getString(R.string.ws_art_version_item, n + 1);
                }
                DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                        .setTitle(R.string.ws_art_version_picker_title)
                        .setSingleChoiceItems(options, version, (d, n) -> {
                            d.dismiss();
                            presentVersion(box, scroll, record, n);
                            scroll.scrollTo(0, 0);
                        }).setNegativeButton(R.string.ws_close, null));
            }));
        }
        if (WorkspaceLayout.FENCE.equals(record.language)) {
            try {
                WorkspaceLayout layout = WorkspaceLayout.parse(content);
                String surface = WorkspaceLayout.screenName(activity, layout.surface);
                box.addView(Ui.text(activity, activity.getString(R.string.ws_art_target_screen, surface), 16));
                if ("replace".equals(layout.mode)) box.addView(Ui.text(activity, activity.getString(
                        WorkspaceLayout.custom(layout.surface)
                                ? R.string.ws_art_personal_saved_notice : R.string.ws_art_replace_notice), 15));
                box.addView(SurfaceRenderer.preview(activity, layout));
                box.addView(Ui.button(activity, activity.getString(R.string.ws_art_apply_layout_action),
                        v -> confirm(layout, content)));
                return;
            } catch (IOException e) {
                Diagnostics.warning("workspace", "proposal_rejected");
                box.addView(Ui.text(activity, activity.getString(R.string.ws_art_invalid_layout), 16));
            }
        }
        CodeReader.contents(box, record.title, record.language, content);
    }

    private void confirm(WorkspaceLayout layout, String raw) {
        String screen = WorkspaceLayout.screenName(activity, layout.surface);
        DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.ws_art_confirm_modify_title, screen))
                .setMessage(R.string.ws_art_confirm_modify_message)
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.ws_apply, (d, w) ->
                        SurfaceRenderer.change(activity, host, () -> new LayoutHistory(account, layout.surface).replace(raw))));
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }
}
