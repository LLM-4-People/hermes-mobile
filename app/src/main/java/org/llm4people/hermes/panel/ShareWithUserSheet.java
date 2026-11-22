package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.CodeReader;
import org.llm4people.hermes.net.HermesApi;

import java.util.List;
import java.util.function.Consumer;

/**
 * ONE reusable plain-French Same-Server Sharing Sheet & "Partagé avec moi" Inbox.
 * Works uniformly across Notes, Savoir-faire (Skills), Outils/Surfaces Atelier, Widgets, Artefacts, and Discussions.
 * Capability-gated on features.universal_sharing.
 */
public final class ShareWithUserSheet {
    private ShareWithUserSheet() {}

    /**
     * Open the reusable "Partager sur ce serveur" sheet for any owned resource.
     *
     * @param resourceKind one of UniversalShareModel.KIND_* (note, skill, surface, widget, tool, artifact, session)
     * @param resourceId   stable identifier of the resource
     * @param label        friendly display name shown to the recipient
     */
    public static void openShareSheet(SessionPanel panel, String resourceKind, String resourceId,
                                      String label, Runnable parent) {
        String safeKind = UniversalShareModel.validateKind(resourceKind);
        String safeId = UniversalShareModel.validateResourceId(resourceId);
        String displayLabel = label == null || label.trim().isEmpty() ? safeId : label.trim();
        new OutgoingController(panel, safeKind, safeId, displayLabel, parent).show();
    }

    /** Open the "Partagé avec moi" section inside Cerveau listing all incoming same-server shares. */
    public static void showSharedWithMe(SessionPanel panel, Consumer<String> openThread,
                                        Consumer<String> stageDraft, Runnable parent) {
        new IncomingController(panel, openThread, stageDraft, parent).show();
    }

    static String extractReadableContent(JSONObject reply) {
        if (reply == null) return "";
        for (String key : new String[]{"raw", "body", "content", "text", "markdown"}) {
            Object val = reply.opt(key);
            if (val instanceof String s && !s.trim().isEmpty()) {
                return WorkspacePathGuard.boundContent(s);
            }
        }
        JSONObject nestedContent = reply.optJSONObject("content");
        if (nestedContent != null) {
            String extracted = extractReadableContent(nestedContent);
            if (!extracted.isEmpty()) return extracted;
        }
        JSONObject nestedData = reply.optJSONObject("data");
        if (nestedData != null) {
            String extracted = extractReadableContent(nestedData);
            if (!extracted.isEmpty()) return extracted;
        }
        org.json.JSONArray messages = reply.optJSONArray("messages");
        if (messages != null && messages.length() > 0) {
            StringBuilder transcript = new StringBuilder();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row == null) continue;
                String role = SearchResultModel.roleLabel(PanelData.text(row, "role"));
                String text = PanelData.text(row, "content");
                if (text.isEmpty()) continue;
                if (transcript.length() > 0) transcript.append("\n\n");
                if (!role.isEmpty()) transcript.append(role).append(" :\n");
                transcript.append(text);
            }
            if (transcript.length() > 0) return WorkspacePathGuard.boundContent(transcript.toString());
        }
        String title = PanelData.text(reply, "title");
        return title.isEmpty() ? "" : title;
    }

    private static final class OutgoingController {
        private final SessionPanel panel;
        private final String kind;
        private final String resourceId;
        private final String label;
        private final Runnable parent;
        private String selectedPermission = UniversalShareModel.PERM_READ;
        private LinearLayout permRow;
        private LinearLayout body;

        OutgoingController(SessionPanel panel, String kind, String resourceId, String label, Runnable parent) {
            this.panel = panel;
            this.kind = kind;
            this.resourceId = resourceId;
            this.label = label;
            this.parent = parent;
        }

        void show() {
            LinearLayout page = panel.page("brain_share", R.string.brain_share_title, parent);
            panel.note(page, UniversalShareModel.kindLabel(kind) + " · " + label);
            body = Ui.column(panel.activity);
            page.addView(body);
            load();
        }

        private void load() {
            body.removeAllViews();
            panel.request(body, () -> {
                BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
                if (!caps.universalSharing()) return null;
                String url = "/api/shares/outgoing?resource_kind=" + HermesApi.queryValue(kind)
                        + "&resource_id=" + HermesApi.queryValue(resourceId);
                return BrainJson.parseShareList(panel.api.get(url));
            }, grants -> {
                if (grants == null) {
                    Diagnostics.event("sharing", "capability_absent");
                    BrainUi.showUnavailable(panel, body, R.string.brain_share_unavailable, this::load);
                    return;
                }
                render(grants);
            }, error -> handleError(error, this::load));
        }

        private void render(List<UniversalShareModel.ShareGrant> grants) {
            body.removeAllViews();
            panel.section(body, R.string.brain_share_grant_section);
            EditText profileField = BrainUi.searchField(panel.activity, 0, R.string.brain_share_target_hint);
            body.addView(profileField);

            permRow = Ui.row(panel.activity);
            body.addView(BrainUi.chipBar(panel.activity, permRow));
            renderPermissionChips();

            LinearLayout feedback = Ui.column(panel.activity);
            body.addView(feedback);

            panel.action(body, R.string.brain_share_send, () -> {
                feedback.removeAllViews();
                String targetProfile;
                try {
                    targetProfile = UniversalShareModel.validateTargetProfile(profileField.getText().toString());
                } catch (IllegalArgumentException invalid) {
                    TextView err = BrainUi.liveNote(panel, feedback, invalid.getMessage());
                    err.setTextColor(panel.activity.getColor(R.color.danger));
                    return;
                }
                JSONObject payload = new JSONObject();
                PanelData.put(payload, "resource_kind", kind);
                PanelData.put(payload, "resource_id", resourceId);
                PanelData.put(payload, "target_profile", targetProfile);
                PanelData.put(payload, "permission", selectedPermission);
                PanelData.put(payload, "label", label);
                panel.request(feedback, () -> panel.api.post("/api/shares", payload),
                        ok -> {
                            Diagnostics.event("sharing", "grant_created");
                            profileField.setText("");
                            load();
                        }, error -> handleErrorInto(feedback, error));
            });

            body.addView(Ui.divider(panel.activity));
            panel.section(body, R.string.brain_share_active_section);
            if (grants.isEmpty()) {
                panel.note(body, panel.t(R.string.brain_share_none_outgoing));
                return;
            }
            for (UniversalShareModel.ShareGrant grant : grants) {
                LinearLayout card = BrainUi.card(panel.activity);
                LinearLayout top = Ui.row(panel.activity);
                TextView who = Ui.text(panel.activity, grant.targetProfile(), 16);
                who.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                top.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
                top.addView(BrainUi.badge(panel.activity,
                        UniversalShareModel.permissionLabel(grant.permission()), true));
                card.addView(top);

                Button revoke = Ui.button(panel.activity, panel.t(R.string.brain_share_revoke),
                        v -> revokeShare(grant.shareId()));
                revoke.setTextColor(panel.activity.getColor(R.color.danger));
                card.addView(revoke);
                body.addView(card);
            }
        }

        private void renderPermissionChips() {
            permRow.removeAllViews();
            permRow.addView(permChip(UniversalShareModel.PERM_READ));
            permRow.addView(permChip(UniversalShareModel.PERM_EDIT));
            permRow.addView(permChip(UniversalShareModel.PERM_CLONE));
        }

        private View permChip(String perm) {
            return BrainUi.chip(panel.activity, UniversalShareModel.permissionLabel(perm),
                    perm.equals(selectedPermission), () -> {
                        selectedPermission = perm;
                        renderPermissionChips();
                    });
        }

        private void revokeShare(String shareId) {
            String safeId = UniversalShareModel.validateShareId(shareId);
            panel.request(body, () -> panel.api.deleteJson("/api/shares/" + HermesApi.segment(safeId)),
                    ok -> {
                        Diagnostics.event("sharing", "grant_revoked");
                        load();
                    }, error -> handleError(error, this::load));
        }

        private void handleError(Exception error, Runnable retry) {
            if (error instanceof HermesApi.HttpError http) {
                Diagnostics.warning("sharing", "http_error", "http_" + http.status);
                BrainUi.liveNote(panel, body, UniversalShareModel.describeHttpError(http.status, http.code));
                if (retry != null) panel.action(body, R.string.panel_retry, retry);
            } else {
                Diagnostics.warning("sharing", "request_failed", error);
                panel.failure(body, error, retry);
            }
        }

        private void handleErrorInto(LinearLayout target, Exception error) {
            if (error instanceof HermesApi.HttpError http) {
                Diagnostics.warning("sharing", "grant_http_error", "http_" + http.status);
                BrainUi.liveNote(panel, target, UniversalShareModel.describeHttpError(http.status, http.code));
            } else {
                Diagnostics.warning("sharing", "grant_failed", error);
                panel.failure(target, error, null);
            }
        }
    }

    private static final class IncomingController {
        private final SessionPanel panel;
        private final Consumer<String> openThread;
        private final Consumer<String> stageDraft;
        private final Runnable parent;
        private String kindFilter = "";
        private LinearLayout filterRow;
        private LinearLayout body;

        IncomingController(SessionPanel panel, Consumer<String> openThread,
                           Consumer<String> stageDraft, Runnable parent) {
            this.panel = panel;
            this.openThread = openThread;
            this.stageDraft = stageDraft;
            this.parent = parent;
        }

        void show() {
            LinearLayout page = panel.page("brain_shared_with_me", R.string.brain_shared_with_me_title, parent);
            panel.note(page, panel.t(R.string.brain_shared_with_me_subtitle));
            filterRow = Ui.row(panel.activity);
            page.addView(BrainUi.chipBar(panel.activity, filterRow));
            renderFilters();
            body = Ui.column(panel.activity);
            page.addView(body);
            load();
        }

        private void renderFilters() {
            filterRow.removeAllViews();
            filterRow.addView(filterChip("", panel.t(R.string.brain_skills_all_categories)));
            filterRow.addView(filterChip(UniversalShareModel.KIND_NOTE, "Notes"));
            filterRow.addView(filterChip(UniversalShareModel.KIND_SKILL, "Savoir-faire"));
            filterRow.addView(filterChip(UniversalShareModel.KIND_SESSION, "Discussions"));
            filterRow.addView(filterChip(UniversalShareModel.KIND_TOOL, "Outils"));
            filterRow.addView(filterChip(UniversalShareModel.KIND_SURFACE, "Écrans"));
            filterRow.addView(filterChip(UniversalShareModel.KIND_WIDGET, "Widgets"));
        }

        private View filterChip(String kind, String label) {
            return BrainUi.chip(panel.activity, label, kind.equals(kindFilter), () -> {
                kindFilter = kind;
                renderFilters();
                load();
            });
        }

        private void load() {
            body.removeAllViews();
            panel.request(body, () -> {
                BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
                if (!caps.universalSharing()) return null;
                String url = "/api/shares/incoming" + (kindFilter.isEmpty() ? ""
                        : "?resource_kind=" + HermesApi.queryValue(kindFilter));
                return BrainJson.parseShareList(panel.api.get(url));
            }, grants -> {
                if (grants == null) {
                    Diagnostics.event("sharing", "capability_absent");
                    BrainUi.showUnavailable(panel, body, R.string.brain_share_unavailable, this::load);
                    return;
                }
                render(grants);
            }, error -> {
                if (error instanceof HermesApi.HttpError http) {
                    Diagnostics.warning("sharing", "incoming_http_error", "http_" + http.status);
                    BrainUi.liveNote(panel, body, UniversalShareModel.describeHttpError(http.status, http.code));
                    panel.action(body, R.string.panel_retry, this::load);
                } else {
                    Diagnostics.warning("sharing", "incoming_failed", error);
                    panel.failure(body, error, this::load);
                }
            });
        }

        private void render(List<UniversalShareModel.ShareGrant> grants) {
            body.removeAllViews();
            panel.action(body, R.string.panel_refresh, this::load);
            if (grants.isEmpty()) {
                panel.note(body, panel.t(R.string.brain_shared_with_me_empty));
                return;
            }
            for (UniversalShareModel.ShareGrant grant : grants) {
                LinearLayout card = BrainUi.card(panel.activity);
                TextView title = Ui.text(panel.activity, grant.displayLabel(), 17);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                card.addView(title);

                LinearLayout badges = Ui.row(panel.activity);
                badges.addView(BrainUi.badge(panel.activity,
                        UniversalShareModel.kindLabel(grant.resourceKind()), true));
                badges.addView(BrainUi.badge(panel.activity,
                        UniversalShareModel.permissionLabel(grant.permission()), false));
                if (!grant.ownerProfile().isEmpty()) {
                    badges.addView(BrainUi.badge(panel.activity, "Par " + grant.ownerProfile(), false));
                }
                badges.setPaddingRelative(0, Ui.dp(panel.activity, 6), 0, 0);
                card.addView(badges);

                LinearLayout actions = Ui.row(panel.activity);
                actions.addView(Ui.button(panel.activity, panel.t(R.string.brain_share_open_item),
                        v -> openSharedContent(grant)));
                Button dismiss = Ui.button(panel.activity, panel.t(R.string.brain_share_dismiss),
                        v -> dismissShare(grant.shareId()));
                dismiss.setTextColor(panel.activity.getColor(R.color.danger));
                actions.addView(dismiss);
                card.addView(actions);
                body.addView(card);
            }
        }

        private void openSharedContent(UniversalShareModel.ShareGrant grant) {
            String safeId = UniversalShareModel.validateShareId(grant.shareId());
            panel.request(body, () -> panel.api.get("/api/shares/incoming/" + HermesApi.segment(safeId) + "/content"),
                    reply -> {
                        Diagnostics.event("sharing", "content_opened");
                        String text = extractReadableContent(reply);
                        AlertDialog.Builder reader = CodeReader.dialog(panel.activity,
                                grant.displayLabel(), "markdown", text);
                        if (stageDraft != null && !text.isEmpty()) {
                            reader.setPositiveButton(R.string.brain_skills_use_in_thread, (d, w) -> {
                                panel.close();
                                stageDraft.accept("Voici un élément partagé (« " + grant.displayLabel()
                                        + " ») à prendre en compte :\n" + text);
                            });
                        }
                        panel.present(reader.create());
                    }, error -> {
                        if (error instanceof HermesApi.HttpError http) {
                            Diagnostics.warning("sharing", "content_http_error", "http_" + http.status);
                            BrainUi.liveNote(panel, body, UniversalShareModel.describeHttpError(http.status, http.code));
                        } else {
                            Diagnostics.warning("sharing", "content_failed", error);
                            panel.failure(body, error, null);
                        }
                    });
        }

        private void dismissShare(String shareId) {
            String safeId = UniversalShareModel.validateShareId(shareId);
            panel.request(body, () -> panel.api.deleteJson("/api/shares/" + HermesApi.segment(safeId)),
                    ok -> {
                        Diagnostics.event("sharing", "incoming_dismissed");
                        load();
                    }, error -> panel.failure(body, error, this::load));
        }
    }
}
