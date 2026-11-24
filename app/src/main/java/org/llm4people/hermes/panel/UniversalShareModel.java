package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.R;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure SSoT model and ACL validation for Universal Same-Server Sharing (/api/shares). */
public final class UniversalShareModel {
    public static final String KIND_NOTE = "note";
    public static final String KIND_SURFACE = "surface";
    public static final String KIND_WIDGET = "widget";
    public static final String KIND_TOOL = "tool";
    public static final String KIND_SKILL = "skill";
    public static final String KIND_ARTIFACT = "artifact";
    public static final String KIND_SESSION = "session";

    public static final String PERM_READ = "read";
    public static final String PERM_EDIT = "edit";
    public static final String PERM_CLONE = "clone";

    private static final Set<String> ALLOWED_KINDS = Set.of(
            KIND_NOTE, KIND_SURFACE, KIND_WIDGET, KIND_TOOL, KIND_SKILL, KIND_ARTIFACT, KIND_SESSION);
    private static final Pattern VALID_PROFILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public record ShareGrant(
            String shareId,
            String resourceKind,
            String resourceId,
            String label,
            String ownerProfile,
            String targetProfile,
            String permission,
            String createdAt) {
        public ShareGrant {
            shareId = safe(shareId);
            resourceKind = safe(resourceKind).toLowerCase(Locale.ROOT);
            resourceId = safe(resourceId);
            label = safe(label);
            ownerProfile = safe(ownerProfile);
            targetProfile = safe(targetProfile);
            permission = safe(permission).toLowerCase(Locale.ROOT);
            createdAt = safe(createdAt);
        }

        public String displayLabel() {
            return label.isEmpty() ? resourceId : label;
        }

        public boolean canEdit() {
            return PERM_EDIT.equals(permission);
        }
    }

    private UniversalShareModel() {}

    public static String validateKind(String rawKind) {
        if (rawKind == null) throw new LocalizedInputException("share_kind_missing", R.string.ws_share_err_kind_missing);
        String normalized = rawKind.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_KINDS.contains(normalized)) {
            throw new LocalizedInputException("share_kind_unsupported", R.string.ws_share_err_kind_unsupported);
        }
        return normalized;
    }

    public static String validateTargetProfile(String rawProfile) {
        if (rawProfile == null || rawProfile.trim().isEmpty()) {
            throw new LocalizedInputException("share_profile_missing", R.string.ws_share_err_profile_missing);
        }
        String trimmed = rawProfile.trim();
        if (!VALID_PROFILE.matcher(trimmed).matches() || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new LocalizedInputException("share_profile_invalid", R.string.ws_share_err_profile_invalid);
        }
        return trimmed;
    }

    public static String validateResourceId(String rawId) {
        if (rawId == null || rawId.trim().isEmpty()) {
            throw new LocalizedInputException("share_resource_id_missing", R.string.ws_share_err_id_missing);
        }
        String trimmed = rawId.trim();
        if (trimmed.contains("..") || trimmed.indexOf('\\') >= 0
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new LocalizedInputException("share_resource_id_invalid", R.string.ws_share_err_id_invalid);
        }
        return trimmed;
    }

    public static String validateShareId(String rawShareId) {
        if (rawShareId == null || rawShareId.trim().isEmpty()) {
            throw new LocalizedInputException("share_id_missing", R.string.ws_share_err_share_id_missing);
        }
        String trimmed = rawShareId.trim();
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0
                || ".".equals(trimmed) || "..".equals(trimmed)
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new LocalizedInputException("share_id_invalid", R.string.ws_share_err_share_id_invalid);
        }
        return trimmed;
    }

    public static int kindLabelRes(String kind) {
        if (kind == null) return R.string.ws_share_kind_item;
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case KIND_NOTE -> R.string.ws_share_kind_note;
            case KIND_SKILL -> R.string.ws_share_kind_skill;
            case KIND_SURFACE -> R.string.ws_share_kind_surface;
            case KIND_WIDGET -> R.string.ws_share_kind_widget;
            case KIND_TOOL -> R.string.ws_share_kind_tool;
            case KIND_ARTIFACT -> R.string.ws_share_kind_artifact;
            case KIND_SESSION -> R.string.ws_share_kind_session;
            default -> R.string.ws_share_kind_item;
        };
    }

    public static String kindLabel(Context context, String kind) {
        return context.getString(kindLabelRes(kind));
    }

    public static int permissionLabelRes(String permission) {
        if (permission == null) return R.string.ws_share_perm_read;
        return switch (permission.trim().toLowerCase(Locale.ROOT)) {
            case PERM_EDIT -> R.string.ws_share_perm_edit;
            case PERM_CLONE -> R.string.ws_share_perm_clone;
            default -> R.string.ws_share_perm_read;
        };
    }

    public static String permissionLabel(Context context, String permission) {
        return context.getString(permissionLabelRes(permission));
    }

    public static int describeHttpErrorRes(int status, String code) {
        if (status == 404) {
            if (code != null && code.contains("profile")) {
                return R.string.ws_share_err_profile_not_found;
            }
            return R.string.ws_share_err_not_found;
        }
        if (status == 403) return R.string.ws_share_err_denied;
        if (status == 409) return R.string.ws_share_err_conflict;
        if (status == 400 || status == 422) return R.string.ws_share_err_bad_request;
        if (status == 405 || status == 501) return R.string.ws_share_err_upgrade;
        return R.string.ws_http_rejected;
    }

    public static String describeHttpError(Context context, int status, String code) {
        int resId = describeHttpErrorRes(status, code);
        return resId == R.string.ws_http_rejected ? context.getString(resId, status) : context.getString(resId);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
