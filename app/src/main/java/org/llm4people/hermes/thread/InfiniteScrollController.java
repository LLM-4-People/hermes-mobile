package org.llm4people.hermes.thread;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.ListView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.Objects;

/**
 * Reusable auto-infinite scroll controller for both transcript and inbox {@link ListView}s,
 * paired with an accessible (&gt;= 48dp, Impeccable Day/Night themed) floating "↓ Latest" pill.
 */
public final class InfiniteScrollController implements AbsListView.OnScrollListener {
    public static final int DEFAULT_TOP_THRESHOLD = 6;
    public static final int DEFAULT_BOTTOM_THRESHOLD = 6;

    public interface Callbacks {
        boolean hasOlder();
        boolean hasNewer();
        boolean isLoadingOlder();
        boolean isLoadingNewer();
        void loadOlder();
        void loadNewer();
    }

    public record ScrollState(
            boolean nearTop,
            boolean nearBottom,
            boolean atTail,
            boolean triggerOlder,
            boolean triggerNewer,
            boolean showJumpPill) {}

    private final Callbacks callbacks;
    private final int topThreshold;
    private final int bottomThreshold;
    private final int headerCount;
    private final int footerCount;

    private Button jumpPill;
    private boolean atTail = true;
    private int unreadCount;
    private boolean enabled = true;
    private boolean desiredPillVisible;
    private final Runnable applyPendingPill = this::applyPillUiNow;

    public InfiniteScrollController(Callbacks callbacks) {
        this(callbacks, DEFAULT_TOP_THRESHOLD, DEFAULT_BOTTOM_THRESHOLD, 0, 0);
    }

    public InfiniteScrollController(Callbacks callbacks, int topThreshold, int bottomThreshold,
                                    int headerCount, int footerCount) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.topThreshold = Math.max(1, topThreshold);
        this.bottomThreshold = Math.max(1, bottomThreshold);
        this.headerCount = Math.max(0, headerCount);
        this.footerCount = Math.max(0, footerCount);
    }

    /** Pure-Java scroll evaluation for deterministic JVM tests and live Android scroll events. */
    public static ScrollState evaluate(int firstVisibleItem, int visibleItemCount, int totalItemCount,
                                       int headerCount, int footerCount, int topThreshold, int bottomThreshold,
                                       boolean hasOlder, boolean hasNewer,
                                       boolean loadingOlder, boolean loadingNewer) {
        int dataCount = Math.max(0, totalItemCount - headerCount - footerCount);
        if (dataCount == 0 || visibleItemCount <= 0) {
            return new ScrollState(false, true, !hasNewer, false, false, hasNewer);
        }
        int firstData = Math.max(0, firstVisibleItem - headerCount);
        int lastData = Math.min(dataCount - 1, firstVisibleItem + visibleItemCount - 1 - headerCount);
        boolean nearTop = firstData <= topThreshold;
        boolean nearBottom = (dataCount - 1 - lastData) <= bottomThreshold;
        boolean atTail = !hasNewer && lastData >= dataCount - 2;
        boolean triggerOlder = hasOlder && !loadingOlder && nearTop;
        boolean triggerNewer = hasNewer && !loadingNewer && nearBottom;
        boolean showJumpPill = hasNewer || !atTail;
        return new ScrollState(nearTop, nearBottom, atTail, triggerOlder, triggerNewer, showJumpPill);
    }

    /** Builds the accessible floating "↓ Latest" / "↓ Récents" pill following .impeccable/design.json. */
    public Button createJumpPill(Context context, Runnable onJumpToLatest) {
        Objects.requireNonNull(context, "context");
        Button pill = Ui.button(context, context.getString(R.string.infinite_jump_latest), v -> {
            logEvent("jump_to_latest");
            onSnappedToLatest();
            if (onJumpToLatest != null) onJumpToLatest.run();
        });
        pill.setTextColor(context.getColor(R.color.on_accent));
        pill.setTypeface(Ui.mediumTypeface());
        pill.setTextSize(14);
        pill.setGravity(Gravity.CENTER);
        pill.setMinimumHeight(Ui.dp(context, 48));
        pill.setMinimumWidth(Ui.dp(context, 48));
        Ui.pad(pill, 16, 10);
        pill.setBackground(Ui.roundedRipple(context, 24,
                Ui.shape(context, 24, R.color.accent, R.color.line, 1)));
        pill.setContentDescription(context.getString(R.string.infinite_jump_latest_cd));
        pill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pill.setVisibility(View.GONE);
        this.jumpPill = pill;
        updatePillUi();
        return pill;
    }

    public void attachJumpPill(Button existing) {
        this.jumpPill = existing;
        updatePillUi();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Records newly arrived live messages while the user is scrolled up, incrementing the pill badge. */
    public void onNewMessagesArrived(int count) {
        if (count <= 0) return;
        if (!atTail || callbacks.hasNewer()) {
            unreadCount = Math.min(999, unreadCount + count);
            updatePillUi();
        }
    }

    /** Resets unread badge and hides pill after snapping to the latest live messages. */
    public void onSnappedToLatest() {
        unreadCount = 0;
        atTail = true;
        updatePillUi();
    }

    public boolean isAtTail() { return atTail; }
    public int unreadCount() { return unreadCount; }

    /**
     * Preserves the exact top visible row and pixel offset when {@code prependedCount} older rows
     * are inserted above the current viewport — eliminating scroll jumps during upward infinite scroll.
     */
    public static void preserveAnchorAfterPrepend(ListView list, int prependedCount) {
        if (list == null || prependedCount <= 0) return;
        int firstPos = list.getFirstVisiblePosition();
        View firstChild = list.getChildCount() > 0 ? list.getChildAt(0) : null;
        int topOffset = firstChild == null ? 0 : firstChild.getTop();
        list.setSelectionFromTop(firstPos + prependedCount, topOffset);
    }

    @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}

    @Override public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (!enabled) return;
        int headers = view instanceof ListView lv ? lv.getHeaderViewsCount() : headerCount;
        int footers = view instanceof ListView lv ? lv.getFooterViewsCount() : footerCount;
        ScrollState state = evaluate(
                firstVisibleItem, visibleItemCount, totalItemCount,
                headers, footers, topThreshold, bottomThreshold,
                callbacks.hasOlder(), callbacks.hasNewer(),
                callbacks.isLoadingOlder(), callbacks.isLoadingNewer());
        atTail = state.atTail();
        if (atTail) unreadCount = 0;
        updatePillUi(state.showJumpPill(), isInLayoutPass(view, jumpPill));
        if (state.triggerOlder()) {
            logEvent("prefetch_older");
            callbacks.loadOlder();
        } else if (state.triggerNewer()) {
            logEvent("prefetch_newer");
            callbacks.loadNewer();
        }
    }

    /** Pure-Java deferral decision so layout-pass scroll callbacks never mutate sibling visibility mid-layout. */
    static boolean shouldDeferPillUpdate(boolean scrollInLayout, boolean pillOrAncestorInLayout) {
        return scrollInLayout || pillOrAncestorInLayout;
    }

    private static boolean isInLayoutPass(View scrollView, View pill) {
        boolean scrollInLayout = scrollView != null && scrollView.isInLayout();
        boolean pillOrAncestorInLayout = false;
        if (pill != null) {
            if (pill.isInLayout()) {
                pillOrAncestorInLayout = true;
            } else {
                for (ViewParent parent = pill.getParent(); parent instanceof View pv; parent = pv.getParent()) {
                    if (pv.isInLayout()) {
                        pillOrAncestorInLayout = true;
                        break;
                    }
                }
            }
        }
        return shouldDeferPillUpdate(scrollInLayout, pillOrAncestorInLayout);
    }

    private void updatePillUi() {
        updatePillUi(!atTail || callbacks.hasNewer() || unreadCount > 0, isInLayoutPass(null, jumpPill));
    }

    private void updatePillUi(boolean visible, boolean defer) {
        desiredPillVisible = visible;
        if (jumpPill == null) return;
        if (defer) {
            jumpPill.removeCallbacks(applyPendingPill);
            jumpPill.post(applyPendingPill);
            return;
        }
        jumpPill.removeCallbacks(applyPendingPill);
        applyPillUiNow();
    }

    static int jumpPillContentDescRes(int unreadCount) {
        if (unreadCount == 1) return R.string.infinite_jump_latest_one_cd;
        if (unreadCount > 1) return R.string.infinite_jump_latest_unread_cd;
        return R.string.infinite_jump_latest_cd;
    }

    private void applyPillUiNow() {
        if (jumpPill == null) return;
        Context ctx = jumpPill.getContext();
        int cdRes = jumpPillContentDescRes(unreadCount);
        if (unreadCount == 1) {
            jumpPill.setText(R.string.infinite_jump_latest_one);
            jumpPill.setContentDescription(ctx.getString(cdRes));
        } else if (unreadCount > 1) {
            jumpPill.setText(ctx.getString(R.string.infinite_jump_latest_unread, unreadCount));
            jumpPill.setContentDescription(ctx.getString(cdRes, unreadCount));
        } else {
            jumpPill.setText(R.string.infinite_jump_latest);
            jumpPill.setContentDescription(ctx.getString(cdRes));
        }
        int targetVisibility = desiredPillVisible ? View.VISIBLE : View.GONE;
        if (jumpPill.getVisibility() != targetVisibility) {
            jumpPill.setVisibility(targetVisibility);
        }
    }

    private static void logEvent(String action) {
        Diagnostics.event("infinite_scroll", action);
    }
}
