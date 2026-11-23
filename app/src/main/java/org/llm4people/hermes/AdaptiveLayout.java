package org.llm4people.hermes;

/** Single source of truth for phone, tablet and multi-window layout geometry in density-independent pixels. */
public final class AdaptiveLayout {
    public static final int SPLIT_TOOL_DP = 640;
    public static final int NEARBY_SPLIT_DP = 620;
    public static final int CONVERSATION_DUAL_MIN_WIDTH_DP = 720;
    public static final int CONVERSATION_DUAL_MIN_HEIGHT_DP = 400;
    public static final int SIDEBAR_MIN_DP = 232;
    public static final int SIDEBAR_TARGET_MAX_DP = 320;
    public static final int SIDEBAR_MAX_DP = 360;
    public static final int DETAIL_MIN_DP = 440;
    public static final int PANEL_SINGLE_MAX_DP = 680;
    public static final int PANEL_PAIRED_MAX_DP = 1040;
    public static final int PANEL_PAGE_PADDING_DP = 20;
    public static final int PANEL_GAP_DP = 24;
    public static final int PANEL_MIN_COLUMN_DP = 340;
    public static final int PANEL_SCALED_COLUMN_DP = 320;
    public static final int PICKER_SPLIT_MIN_WIDTH_DP = 480;
    public static final int PICKER_SPLIT_MAX_HEIGHT_DP = 480;
    public static final int PROFILE_COLUMN_MAX_DP = 520;
    public static final int READING_COLUMN_MAX_DP = 680;
    public static final int NAV_RAIL_MIN_WIDTH_DP = 720;
    public static final int NAV_RAIL_WIDTH_DP = 92;
    public static final int NAV_BAR_MIN_HEIGHT_DP = 60;

    private AdaptiveLayout() {}

    public enum NavChrome { BOTTOM_BAR, RAIL, HIDDEN }

    public record ConversationGeometry(int railDp, boolean dual, boolean sidebarVisible, boolean detailVisible) {}

    public record PanelGeometry(int sheetWidthDp, boolean paired) {}

    /** Choose phone bottom bar (auto-hidden when IME is open) or 92dp tablet navigation rail. */
    public static NavChrome navigationChrome(float widthDp, float heightDp, boolean imeVisible) {
        float w = safeDimension(widthDp);
        safeDimension(heightDp);
        if (w >= NAV_RAIL_MIN_WIDTH_DP) return NavChrome.RAIL;
        return imeVisible ? NavChrome.HIDDEN : NavChrome.BOTTOM_BAR;
    }

    public static float safeDimension(float dp) {
        return Float.isFinite(dp) && dp > 0 ? dp : 0;
    }

    public static float safeFontScale(float fontScale) {
        return Float.isFinite(fontScale) && fontScale > 1 ? fontScale : 1;
    }

    public static boolean splitInstrument(float paneWidthDp) {
        return safeDimension(paneWidthDp) >= SPLIT_TOOL_DP;
    }

    public static boolean splitNearbyGuide(float paneWidthDp) {
        return safeDimension(paneWidthDp) >= NEARBY_SPLIT_DP;
    }

    /** Short landscape viewports (such as above a phone IME) place picker controls beside results. */
    public static boolean splitPicker(float widthDp, float heightDp) {
        float w = safeDimension(widthDp);
        float h = safeDimension(heightDp);
        return w >= PICKER_SPLIT_MIN_WIDTH_DP && h > 0 && h <= PICKER_SPLIT_MAX_HEIGHT_DP && w > h;
    }

    /** Size the discussion rail and decide whether inbox and thread sit side by side. */
    public static ConversationGeometry conversationPanes(float widthDp, float heightDp, float fontScale,
                                                         boolean selected, boolean inspectorOpen,
                                                         Boolean preferredSidebar) {
        float w = safeDimension(widthDp);
        float h = safeDimension(heightDp);
        float scale = safeFontScale(fontScale);
        int railDp = Math.round(Math.min(SIDEBAR_MAX_DP, Math.max(SIDEBAR_MIN_DP * Math.min(scale, 1.55f),
                Math.min(SIDEBAR_TARGET_MAX_DP, w / 3f))));
        boolean dual = w >= Math.max(CONVERSATION_DUAL_MIN_WIDTH_DP,
                railDp + 1 + DETAIL_MIN_DP * Math.min(scale, 1.35f))
                && h >= CONVERSATION_DUAL_MIN_HEIGHT_DP;
        boolean autoSidebar = !inspectorOpen || w - railDp - 1 >= SPLIT_TOOL_DP;
        boolean sidebarVisible = !selected || (dual && (preferredSidebar == null ? autoSidebar : preferredSidebar));
        boolean detailVisible = selected || dual;
        return new ConversationGeometry(railDp, dual, sidebarVisible, detailVisible);
    }

    /** Bound panel sheets on wide windows and pair two sections only when both stay legible. */
    public static PanelGeometry panel(float availableDp, float fontScale, boolean hasSections, boolean allowWideSingle) {
        float available = safeDimension(availableDp);
        float wide = Math.min(available, PANEL_PAIRED_MAX_DP);
        float minColumn = Math.max(PANEL_MIN_COLUMN_DP, PANEL_SCALED_COLUMN_DP * safeFontScale(fontScale));
        boolean paired = hasSections
                && wide >= 2 * minColumn + PANEL_GAP_DP + 2 * PANEL_PAGE_PADDING_DP;
        float target = paired || allowWideSingle ? wide : Math.min(available, PANEL_SINGLE_MAX_DP);
        return new PanelGeometry(Math.round(target), paired);
    }

    /** Keep centered reading and form columns within comfortable line widths without shrinking side gutters. */
    public static int centeredGutterDp(float widthDp, int maxColumnDp, int minGutterDp) {
        float w = safeDimension(widthDp);
        int gutter = Math.max(0, minGutterDp);
        int maxCol = Math.max(0, maxColumnDp);
        return Math.max(gutter, Math.round((w - maxCol) / 2f));
    }
}
