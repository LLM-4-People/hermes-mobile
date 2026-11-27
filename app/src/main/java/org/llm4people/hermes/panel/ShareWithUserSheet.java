package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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
 * Works uniformly across Notes, Savoir-faire (Skills), Custom Screens/Tools (Apps), Widgets, Artefacts, and Discussions.
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
        String safeKind;
        String safeId;
        try {
            safeKind = UniversalShareModel.validateKind(resourceKind);
            safeId = UniversalShareModel.validateResourceId(resourceId);
        } catch (LocalizedInputException invalid) {
            Diagnostics.warning("sharing", "invalid_share_target");
            Toast.makeText(panel.activity, invalid.resolve(panel.activity), Toast.LENGTH_LONG).show();
            return;
        }
        String displayLabel = label == null || label.trim().isEmpty() ? safeId : label.trim();
        new OutgoingController(panel, safeKind, safeId, displayLabel, parent).show();
    }

    /** Open the "Partagé avec moi" section under Notes &amp; Tasks listing all incoming same-server shares. */
    public static void showSharedWithMe(SessionPanel panel, Consumer<String> stageDraft, Runnable parent) {
        new IncomingController(panel, stageDraft, parent).show();
    }

    static String extractReadableContent(Context context, JSONObject reply) {
        if (reply == null) return "";
        for (String key : new String[]{"raw", "body", "content", "text", "text_preview", "markdown"}) {
            Object val = reply.opt(key);
            if (val instanceof String s && !s.trim().isEmpty()) {
                return WorkspacePathGuard.boundContent(s);
            }
        }
        if (reply.optBoolean("is_binary", false)) {
            return formatBinarySummary(context, reply);
        }
        Object spec = reply.opt("spec");
        if (spec instanceof JSONObject || spec instanceof org.json.JSONArray) {
            String pretty = prettyPrintSpec(spec);
            String title = PanelData.text(reply, "title");
            String combined = title.isEmpty() ? pretty : title + "\n\n" + pretty;
            return WorkspacePathGuard.boundContent(combined);
        }
        JSONObject nestedContent = reply.optJSONObject("content");
        if (nestedContent != null) {
            String extracted = extractReadableContent(context, nestedContent);
            if (!extracted.isEmpty()) return extracted;
        }
        JSONObject nestedData = reply.optJSONObject("data");
        if (nestedData != null) {
            String extracted = extractReadableContent(context, nestedData);
            if (!extracted.isEmpty()) return extracted;
        }
        org.json.JSONArray messages = reply.optJSONArray("messages");
        if (messages != null && messages.length() > 0) {
            StringBuilder transcript = new StringBuilder();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row == null) continue;
                String role = SearchResultModel.roleLabel(context, PanelData.text(row, "role"));
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

    private static String formatBinarySummary(Context context, JSONObject reply) {
        String filename = PanelData.text(reply, "filename");
        if (filename.isEmpty()) filename = PanelData.text(reply, "name");
        if (filename.isEmpty()) filename = PanelData.text(reply, "title");
        String mime = PanelData.text(reply, "mime_type");
        long size = reply.has("size") ? reply.optLong("size", -1L) : reply.optLong("size_bytes", -1L);
        StringBuilder out = new StringBuilder();
        if (!filename.isEmpty()) out.append(filename);
        if (!mime.isEmpty()) {
            if (out.length() > 0) out.append(" · ");
            out.append(mime);
        }
        if (size >= 0) {
            if (out.length() > 0) out.append(" · ");
            out.append(context.getString(R.string.ws_share_bytes, size));
        }
        return out.length() > 0 ? out.toString() : context.getString(R.string.ws_share_binary_file);
    }

    private static String prettyPrintSpec(Object spec) {
        try {
            if (spec instanceof JSONObject obj) return obj.toString(2);
            if (spec instanceof org.json.JSONArray arr) return arr.toString(2);
        } catch (org.json.JSONException ex) {
            Diagnostics.warning("share", "spec_format_failed", ex);
        }
        return spec.toString();
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
            panel.note(page, UniversalShareModel.kindLabel(panel.activity, kind) + " · " + label);
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

            Button[] sendBtn = new Button[1];
            sendBtn[0] = panel.action(body, R.string.brain_share_send, () -> {
                if (!sendBtn[0].isEnabled()) return;
                feedback.removeAllViews();
                String targetProfile;
                try {
                    targetProfile = UniversalShareModel.validateTargetProfile(profileField.getText().toString());
                } catch (LocalizedInputException invalid) {
                    TextView err = BrainUi.liveNote(panel, feedback, invalid.resolve(panel.activity));
                    err.setTextColor(panel.activity.getColor(R.color.danger));
                    return;
                }
                sendBtn[0].setEnabled(false);
                JSONObject payload = new JSONObject();
                PanelData.put(payload, "resource_kind", kind);
                PanelData.put(payload, "resource_id", resourceId);
                PanelData.put(payload, "target_profile", targetProfile);
                PanelData.put(payload, "permission", selectedPermission);
                PanelData.put(payload, "label", label);
                if (!panel.request(feedback, () -> panel.api.post("/api/shares", payload),
                        ok -> {
                            sendBtn[0].setEnabled(true);
                            Diagnostics.event("sharing", "grant_created");
                            profileField.setText("");
                            load();
                        }, error -> {
                            sendBtn[0].setEnabled(true);
                            handleErrorInto(feedback, error);
                        })) {
                    sendBtn[0].setEnabled(true);
                }
            });

            body.addView(Ui.divider(panel.activity));
            panel.section(body, R.string.brain_share_active_section);
            if (grants.isEmpty()) {
                BrainUi.liveNote(panel, body, panel.t(R.string.brain_share_none_outgoing));
                return;
            }
            for (UniversalShareModel.ShareGrant grant : grants) {
                LinearLayout card = BrainUi.card(panel.activity);
                LinearLayout top = Ui.row(panel.activity);
                TextView who = Ui.text(panel.activity, grant.targetProfile(), 16);
                who.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                top.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
                top.addView(BrainUi.badge(panel.activity,
                        UniversalShareModel.permissionLabel(panel.activity, grant.permission()), true));
                card.addView(top);

                Button revoke = Ui.button(panel.activity, panel.t(R.string.brain_share_revoke),
                        v -> revokeShare(grant.shareId(), (Button) v));
                revoke.setTextColor(Ui.dangerInk(panel.activity));
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
            return BrainUi.chip(panel.activity, UniversalShareModel.permissionLabel(panel.activity, perm),
                    perm.equals(selectedPermission), () -> {
                        selectedPermission = perm;
                        renderPermissionChips();
                    });
        }

        private void revokeShare(String shareId, Button trigger) {
            if (trigger != null && !trigger.isEnabled()) return;
            String safeId;
            try {
                safeId = UniversalShareModel.validateShareId(shareId);
            } catch (LocalizedInputException invalid) {
                Diagnostics.warning("sharing", "invalid_share_id");
                BrainUi.liveNote(panel, body, invalid.resolve(panel.activity));
                return;
            }
            if (trigger != null) trigger.setEnabled(false);
            if (!panel.request(body, () -> panel.api.deleteJson("/api/shares/" + HermesApi.segment(safeId)),
                    ok -> {
                        if (trigger != null) trigger.setEnabled(true);
                        Diagnostics.event("sharing", "grant_revoked");
                        load();
                    }, error -> {
                        if (trigger != null) trigger.setEnabled(true);
                        handleError(error, this::load);
                    })) {
                if (trigger != null) trigger.setEnabled(true);
            }
        }

        private void handleError(Exception error, Runnable retry) {
            if (error instanceof HermesApi.HttpError http) {
                Diagnostics.warning("sharing", "http_error", "http_" + http.status);
                BrainUi.liveNote(panel, body,
                        UniversalShareModel.describeHttpError(panel.activity, http.status, http.code));
                if (retry != null) panel.action(body, R.string.panel_retry, retry);
            } else {
                Diagnostics.warning("sharing", "request_failed", error);
                panel.failure(body, error, retry);
            }
        }

        private void handleErrorInto(LinearLayout target, Exception error) {
            if (error instanceof HermesApi.HttpError http) {
                Diagnostics.warning("sharing", "grant_http_error", "http_" + http.status);
                BrainUi.liveNote(panel, target,
                        UniversalShareModel.describeHttpError(panel.activity, http.status, http.code));
            } else {
                Diagnostics.warning("sharing", "grant_failed", error);
                panel.failure(target, error, null);
            }
        }
    }

    private static final class IncomingController {
        private final SessionPanel panel;
        private final Consumer<String> stageDraft;
        private final Runnable parent;
        private String kindFilter = "";
        private int loadSeq;
        private LinearLayout filterRow;
        private LinearLayout body;

        IncomingController(SessionPanel panel, Consumer<String> stageDraft, Runnable parent) {
            this.panel = panel;
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
            filterRow.addView(filterChip(UniversalShareModel.KIND_NOTE, panel.t(R.string.ws_share_filter_notes)));
            filterRow.addView(filterChip(UniversalShareModel.KIND_SKILL, panel.t(R.string.ws_share_filter_skills)));
            filterRow.addView(filterChip(UniversalShareModel.KIND_SESSION, panel.t(R.string.ws_share_filter_sessions)));
            filterRow.addView(filterChip(UniversalShareModel.KIND_TOOL, panel.t(R.string.ws_share_filter_tools)));
            filterRow.addView(filterChip(UniversalShareModel.KIND_SURFACE, panel.t(R.string.ws_share_filter_surfaces)));
            filterRow.addView(filterChip(UniversalShareModel.KIND_WIDGET, panel.t(R.string.ws_share_filter_widgets)));
            filterRow.addView(filterChip(UniversalShareModel.KIND_ARTIFACT, panel.t(R.string.ws_share_filter_artifacts)));
        }

        private View filterChip(String kind, String label) {
            return BrainUi.chip(panel.activity, label, kind.equals(kindFilter), () -> {
                if (kind.equals(kindFilter)) return;
                kindFilter = kind;
                renderFilters();
                load();
            });
        }

        private void load() {
            final String filter = kindFilter;
            int seq = ++loadSeq;
            body.removeAllViews();
            panel.request(body, () -> {
                BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
                if (!caps.universalSharing()) return null;
                String url = "/api/shares/incoming" + (filter.isEmpty() ? ""
                        : "?resource_kind=" + HermesApi.queryValue(filter));
                return BrainJson.parseShareList(panel.api.get(url));
            }, grants -> {
                if (seq != loadSeq) return;
                if (grants == null) {
                    Diagnostics.event("sharing", "capability_absent");
                    BrainUi.showUnavailable(panel, body, R.string.brain_share_unavailable, this::load);
                    return;
                }
                render(grants);
            }, error -> {
                if (seq != loadSeq) return;
                if (error instanceof HermesApi.HttpError http) {
                    Diagnostics.warning("sharing", "incoming_http_error", "http_" + http.status);
                    BrainUi.liveNote(panel, body,
                            UniversalShareModel.describeHttpError(panel.activity, http.status, http.code));
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
                BrainUi.liveNote(panel, body, panel.t(R.string.brain_shared_with_me_empty));
                return;
            }
            for (UniversalShareModel.ShareGrant grant : grants) {
                LinearLayout card = BrainUi.card(panel.activity);
                TextView title = Ui.text(panel.activity, grant.displayLabel(), 17);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                card.addView(title);

                LinearLayout badges = Ui.row(panel.activity);
                badges.addView(BrainUi.badge(panel.activity,
                        UniversalShareModel.kindLabel(panel.activity, grant.resourceKind()), true));
                badges.addView(BrainUi.badge(panel.activity,
                        UniversalShareModel.permissionLabel(panel.activity, grant.permission()), false));
                if (!grant.ownerProfile().isEmpty()) {
                    badges.addView(BrainUi.badge(panel.activity,
                            panel.t(R.string.ws_shared_by, grant.ownerProfile()), false));
                }
                card.addView(BrainUi.chipBar(panel.activity, badges));

                LinearLayout actions = Ui.row(panel.activity);
                Button openBtn = Ui.button(panel.activity, panel.t(R.string.brain_share_open_item),
                        v -> openSharedContent(grant));
                LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-2, -2);
                openLp.setMarginEnd(Ui.dp(panel.activity, 8));
                actions.addView(openBtn, openLp);
                Button dismiss = Ui.button(panel.activity, panel.t(R.string.brain_share_dismiss),
                        v -> dismissShare(grant.shareId(), (Button) v));
                dismiss.setTextColor(Ui.dangerInk(panel.activity));
                actions.addView(dismiss);
                card.addView(BrainUi.chipBar(panel.activity, actions));
                body.addView(card);
            }
        }

        private void openSharedContent(UniversalShareModel.ShareGrant grant) {
            String safeId;
            try {
                safeId = UniversalShareModel.validateShareId(grant.shareId());
            } catch (LocalizedInputException invalid) {
                Diagnostics.warning("sharing", "invalid_share_id");
                BrainUi.liveNote(panel, body, invalid.resolve(panel.activity));
                return;
            }
            panel.request(body, () -> panel.api.get("/api/shares/incoming/" + HermesApi.segment(safeId) + "/content"),
                    reply -> {
                        Diagnostics.event("sharing", "content_opened");
                        String text = extractReadableContent(panel.activity, reply);
                        AlertDialog.Builder reader = CodeReader.dialog(panel.activity,
                                grant.displayLabel(), "markdown", text);
                        if (stageDraft != null && !text.isEmpty()) {
                            reader.setPositiveButton(R.string.brain_skills_use_in_thread, (d, w) -> {
                                panel.close();
                                stageDraft.accept(panel.t(R.string.ws_share_draft_prompt,
                                        grant.displayLabel(), text));
                            });
                        }
                        panel.present(reader.create());
                    }, error -> {
                        if (error instanceof HermesApi.HttpError http) {
                            Diagnostics.warning("sharing", "content_http_error", "http_" + http.status);
                            BrainUi.liveNote(panel, body,
                                    UniversalShareModel.describeHttpError(panel.activity, http.status, http.code));
                        } else {
                            Diagnostics.warning("sharing", "content_failed", error);
                            panel.failure(body, error, null);
                        }
                    });
        }

        private void dismissShare(String shareId, Button trigger) {
            if (trigger != null && !trigger.isEnabled()) return;
            String safeId;
            try {
                safeId = UniversalShareModel.validateShareId(shareId);
            } catch (LocalizedInputException invalid) {
                Diagnostics.warning("sharing", "invalid_share_id");
                BrainUi.liveNote(panel, body, invalid.resolve(panel.activity));
                return;
            }
            if (trigger != null) trigger.setEnabled(false);
            if (!panel.request(body, () -> panel.api.deleteJson("/api/shares/" + HermesApi.segment(safeId)),
                    ok -> {
                        if (trigger != null) trigger.setEnabled(true);
                        Diagnostics.event("sharing", "incoming_dismissed");
                        load();
                    }, error -> {
                        if (trigger != null) trigger.setEnabled(true);
                        panel.failure(body, error, this::load);
                    })) {
                if (trigger != null) trigger.setEnabled(true);
            }
        }
    }
}
