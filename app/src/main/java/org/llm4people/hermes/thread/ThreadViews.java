package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.CodeReader;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.artifact.LiveUiPatchBar;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.artifact.ThreadInstruments.Pin;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.customization.InteractiveCardView;
import org.llm4people.hermes.profile.IncomingMediaShare;
import org.llm4people.hermes.share.ClipboardPastePill;
import org.llm4people.hermes.share.ShareDraftTray;
import org.llm4people.hermes.share.SmartIntake;
import org.llm4people.hermes.voice.VoiceDrafts;
import org.llm4people.hermes.voice.ReplyReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ThreadViews {
    private static final TranscriptCellCache CELL_CACHE = new TranscriptCellCache();

    /** Clear the bounded transcript Markdown/span LRU cache (memory trim or profile lock). */
    public static void clearCellCache() {
        CELL_CACHE.clear();
    }

    final LinearLayout root;
    final ListView list;
    final Activity activity;
    final PromptRows promptRows;
    private final TextView status;
    private final Button remedy;
    private final LinearLayout notice;
    private final LinearLayout noticeSlot;
    LiveUiPatchBar liveUiBar;
    private LinearLayout composerSlot;
    private LinearLayout quickActionSlot;
    private LinearLayout suggestedActionSlot;
    private LinearLayout interactiveCardsSlot;
    private Button nearbyStatus;
    private LinearLayout photoSlot;
    private ShareDraftTray attachmentTray;
    private LinearLayout smartIntakeSlot;
    private ClipboardPastePill clipboardPill;
    private Button clipboardPillButton;
    private boolean importingPhoto;
    private boolean runActive;
    private Button more;
    private EditText input;
    final VoiceDrafts voice;
    final ReplyReader reader;
    private VoiceDrafts.Controls composerVoice;
    private VoiceDrafts.DuplexHandle duplex;
    private Button send;
    private Button stop;
    private TextView progress;
    private TextView outgoing;
    private LinearLayout liveReasoningSlot;
    private LiveReasoningCard liveReasoningCard;
    private String lastLiveReasoning = "";
    private TextView liveText;
    private Button fullLiveText;
    private final LiveTranscript liveTranscript = new LiveTranscript();
    private JSONArray rows = new JSONArray();
    private BaseAdapter adapter;
    private AlertDialog dialog;
    private boolean transcript;
    private String selectedRoot;
    private Map<String, ActiveRunTracker.TaskBadge> activeBadges = Collections.emptyMap();
    private Consumer<String> onStopTask;
    private LinearLayout inboxFilterRow;
    private ThreadInboxFilter.Filter activeInboxFilter = ThreadInboxFilter.Filter.ALL;
    private Consumer<ThreadInboxFilter.Filter> onInboxFilterSelected;
    private InfiniteScrollController scrollController;
    private int tailFollowEpoch;
    private View.OnLayoutChangeListener pendingTailListener;
    ThreadInstrumentPanel instruments;
    private InstrumentViewport instrumentSlot;

    ThreadViews(Activity activity) { this(activity, new VoiceDrafts(activity)); }

    ThreadViews(Activity activity, VoiceDrafts voice) {
        this.voice = voice;
        reader = voice.reader();
        this.activity = activity;
        root = Ui.column(activity);
        list = new ListView(activity);
        promptRows = new PromptRows(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        notice = Ui.column(activity);
        notice.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface_container, 0, 0));
        Ui.pad(notice, 14, 10);
        noticeSlot = Ui.column(activity);
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(-1, -2);
        noticeLp.setMargins(Ui.dp(activity, 12), Ui.dp(activity, 6), Ui.dp(activity, 12), Ui.dp(activity, 4));
        noticeSlot.addView(notice, noticeLp);
        status = Ui.text(activity, "", 14);
        status.setTextColor(activity.getColor(R.color.ink));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        remedy = Ui.tonalButton(activity, "", v -> {});
        LinearLayout.LayoutParams remedyLp = new LinearLayout.LayoutParams(-2, -2);
        remedyLp.topMargin = Ui.dp(activity, 6);
        notice.addView(status);
        notice.addView(remedy, remedyLp);
    }

    void inbox(Runnable create, Runnable refresh, Consumer<JSONObject> choose, Runnable next) {
        inbox(create, refresh, choose, next, null);
    }

    void inbox(Runnable create, Runnable refresh, Consumer<JSONObject> choose, Runnable next,
               Consumer<ThreadInboxFilter.Filter> onFilter) {
        reset(false);
        this.onInboxFilterSelected = onFilter;
        Button newThread = Ui.button(activity, activity.getString(R.string.thread_new_conversation), v -> create.run());
        newThread.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        newThread.setTextSize(16);
        Ui.pad(newThread, 18, 10);
        newThread.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.thread_new, 0);
        newThread.setCompoundDrawableTintList(activity.getColorStateList(R.color.accent));
        LinearLayout actions = Ui.row(activity);
        actions.setBackgroundColor(activity.getColor(R.color.surface));
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPaddingRelative(0, Ui.dp(activity, 2), Ui.dp(activity, 6), Ui.dp(activity, 2));
        newThread.setSingleLine(false);
        actions.addView(newThread, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton reload = new ImageButton(activity);
        reload.setBackground(Ui.roundedRipple(activity, 24, null));
        reload.setImageResource(R.drawable.ic_skills_refresh);
        reload.setImageTintList(activity.getColorStateList(R.color.ink));
        reload.setContentDescription(activity.getString(R.string.thread_refresh_discussions));
        reload.setTooltipText(reload.getContentDescription());
        reload.setOnClickListener(v -> refresh.run());
        actions.addView(reload, Ui.dp(activity, 48), Ui.dp(activity, 48));
        root.addView(actions);
        root.addView(Ui.divider(activity));
        HorizontalScrollView filterScroll = new HorizontalScrollView(activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setBackgroundColor(activity.getColor(R.color.surface));
        inboxFilterRow = Ui.row(activity);
        inboxFilterRow.setPaddingRelative(Ui.dp(activity, 14), Ui.dp(activity, 8),
                Ui.dp(activity, 14), Ui.dp(activity, 8));
        filterScroll.addView(inboxFilterRow);
        root.addView(filterScroll);
        renderInboxFilters();
        root.addView(Ui.divider(activity));
        list.setId(R.id.discussion_list);
        list.setDivider(new ColorDrawable(activity.getColor(R.color.line)));
        list.setDividerHeight(Ui.dp(activity, 1));
        more = Ui.button(activity, activity.getString(R.string.thread_show_more), v -> next.run());
        list.addFooterView(pagingSlot(), null, false);
        bindList();
        list.setOnItemClickListener((parent, view, pos, id) -> {
            if (view.getTag() instanceof JSONObject session) choose.accept(session);
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}
            @Override public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                if (visibleCount > 0 && totalCount > 0 && more != null
                        && more.getVisibility() == View.VISIBLE && more.isEnabled()
                        && firstVisible + visibleCount >= totalCount - InfiniteScrollController.DEFAULT_BOTTOM_THRESHOLD) {
                    next.run();
                }
            }
        });
    }

    void chat(String draft, Consumer<String> onDraft, Runnable onSend, Runnable onStop,
              Runnable older) {
        reset(true);
        list.setId(R.id.discussion_transcript);
        more = Ui.button(activity, activity.getString(R.string.thread_previous_messages), v -> older.run());
        list.addHeaderView(pagingSlot(), null, false);
        LinearLayout live = Ui.column(activity);
        Ui.pad(live, 18, 3);
        progress = Ui.text(activity, "", 13);
        progress.setTextColor(activity.getColor(R.color.muted));
        liveText = Ui.text(activity, "", 17);
        liveText.setTextIsSelectable(true);
        fullLiveText = Ui.button(activity, activity.getString(R.string.transcript_read_full), v ->
                show(new AlertDialog.Builder(activity).setTitle(R.string.thread_reply_from_hermes)
                        .setView(MessageText.reader(activity, liveTranscript.source(), true, false,
                                liveTranscript.readerStart(), null))
                        .setPositiveButton(R.string.ws_close, null)));
        list.setItemsCanFocus(true);
        outgoing = Ui.text(activity, "", 17);
        outgoing.setTextIsSelectable(true);
        outgoing.setVisibility(View.GONE);
        live.addView(outgoing);
        live.addView(progress);
        liveReasoningSlot = Ui.column(activity);
        liveReasoningSlot.setVisibility(View.GONE);
        liveReasoningCard = null;
        lastLiveReasoning = "";
        live.addView(liveReasoningSlot);
        live.addView(liveText);
        live.addView(fullLiveText);
        suggestedActionSlot = Ui.column(activity);
        suggestedActionSlot.setVisibility(View.GONE);
        live.addView(suggestedActionSlot);
        interactiveCardsSlot = Ui.column(activity);
        interactiveCardsSlot.setVisibility(View.GONE);
        live.addView(interactiveCardsSlot);
        list.addFooterView(live, null, false);
        bindList();
        list.setOnItemClickListener(null);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) cancelPendingTailFollow();
            }
            @Override public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                promptRows.onScroll();
            }
        });
        LinearLayout composer = Ui.row(activity);
        composer.setGravity(Gravity.BOTTOM);
        composer.setBaselineAligned(false);
        Ui.pad(composer, 10, 6);
        input = new EditText(activity);
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setMinHeight(Ui.dp(activity, 48));
        input.setBackground(null);
        input.setTextColor(activity.getColor(R.color.ink));
        input.setHintTextColor(activity.getColor(R.color.muted));
        input.setTextSize(17);
        input.setLineSpacing(0f, 1.18f);
        input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        input.setPaddingRelative(Ui.dp(activity, 16), Ui.dp(activity, 8), Ui.dp(activity, 4), Ui.dp(activity, 8));
        input.setHint(R.string.thread_message_hint);
        input.setText(draft);
        input.setSelection(input.length());
        composerVoice = voice.bind(input);
        duplex = voice.bindDuplex(composerVoice, utterance -> {
            displayDraft(utterance);
            if (instruments != null) instruments.revealDiscussionIfCompact();
            onSend.run();
        });
        send = Ui.button(activity, activity.getString(R.string.thread_send), v -> {
            if (!send.isEnabled()) return;
            composerVoice.cancel();
            if (instruments != null) instruments.revealDiscussionIfCompact();
            onSend.run();
        });
        stop = Ui.button(activity, activity.getString(R.string.thread_stop), v -> onStop.run());
        LinearLayout writing = Ui.row(activity);
        writing.setGravity(Gravity.BOTTOM);
        writing.setBaselineAligned(false);
        writing.setAddStatesFromChildren(true);
        writing.setBackgroundResource(R.drawable.composer_capsule);
        writing.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        writing.addView(composerVoice.button);
        LinearLayout.LayoutParams writingSize = new LinearLayout.LayoutParams(0, -2, 1);
        writingSize.setMarginEnd(Ui.dp(activity, 8));
        composer.addView(writing, writingSize);
        for (Button action : new Button[]{send, stop}) {
            action.setMinWidth(Ui.dp(activity, 48));
            action.setMinimumWidth(Ui.dp(activity, 48));
            action.setTextSize(15);
            Ui.pad(action, 14, 4);
        }
        send.setBackgroundResource(R.drawable.thread_send);
        send.setBackgroundTintList(null);
        send.setTextColor(activity.getColorStateList(R.color.thread_send_ink));
        stop.setBackground(Ui.roundedRipple(activity, 24,
                Ui.shape(activity, 24, R.color.danger_surface, R.color.danger, 1)));
        stop.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.thread_stop, 0, 0, 0);
        ColorStateList dangerInk = Ui.dangerInk(activity);
        stop.setCompoundDrawableTintList(dangerInk);
        stop.setCompoundDrawablePadding(Ui.dp(activity, 6));
        stop.setTextColor(dangerInk);
        composer.addView(send);
        composer.addView(stop);
        composerSlot = Ui.column(activity);
        composerSlot.setBackgroundColor(activity.getColor(R.color.surface));
        composerSlot.addView(Ui.divider(activity));
        photoSlot = Ui.row(activity);
        photoSlot.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(photoSlot, 8, 0);
        photoSlot.setVisibility(View.GONE);
        composerSlot.addView(photoSlot);
        attachmentTray = new ShareDraftTray(activity, items -> {
            if (items.isEmpty()) clearSmartIntake();
            updateSend();
        });
        composerSlot.addView(attachmentTray.view());
        smartIntakeSlot = Ui.column(activity);
        smartIntakeSlot.setVisibility(View.GONE);
        composerSlot.addView(smartIntakeSlot);
        clipboardPill = new ClipboardPastePill(activity);
        clipboardPillButton = clipboardPill.createView(activity, input);
        clipboardPillButton.setVisibility(View.GONE);
        composerSlot.addView(clipboardPillButton);
        input.setOnFocusChangeListener((v, hasFocus) -> refreshClipboardPill());
        nearbyStatus = Ui.button(activity, "", null);
        nearbyStatus.setAllCaps(false);
        nearbyStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        nearbyStatus.setVisibility(View.GONE);
        composerSlot.addView(nearbyStatus);
        composerSlot.addView(duplex.bar());
        quickActionSlot = Ui.column(activity);
        quickActionSlot.setVisibility(View.GONE);
        composerSlot.addView(quickActionSlot);
        composerSlot.addView(composerVoice.feedback);
        composerSlot.addView(composer);
        root.addView(composerSlot);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onDraft.accept(s.toString());
                updateSend();
                refreshClipboardPill();
            }
        });
        busy(false, false);
        live("", "", false);
    }

    void attachInfiniteScroll(InfiniteScrollController.Callbacks callbacks, Runnable onJumpToLatest) {
        if (!transcript || composerSlot == null || callbacks == null) return;
        InfiniteScrollController controller = new InfiniteScrollController(
                callbacks,
                InfiniteScrollController.DEFAULT_TOP_THRESHOLD,
                InfiniteScrollController.DEFAULT_BOTTOM_THRESHOLD,
                list.getHeaderViewsCount(),
                list.getFooterViewsCount());
        Button pill = controller.createJumpPill(activity, () -> {
            if (onJumpToLatest != null) onJumpToLatest.run();
            else followTail();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.END;
        int margin = Ui.dp(activity, 8);
        lp.setMargins(margin, Ui.dp(activity, 4), margin, Ui.dp(activity, 4));
        composerSlot.addView(pill, 0, lp);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) cancelPendingTailFollow();
                controller.onScrollStateChanged(view, scrollState);
            }
            @Override public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                promptRows.onScroll();
                controller.onScroll(view, firstVisible, visibleCount, totalCount);
            }
        });
        scrollController = controller;
    }

    void onNewMessagesArrived(int count) {
        if (scrollController != null) scrollController.onNewMessagesArrived(count);
    }

    void scrollToTail() {
        followTail();
    }

    private void reset(boolean chat) {
        cancelPendingTailFollow();
        scrollController = null;
        transcript = chat;
        root.removeAllViews();
        rows = new JSONArray();
        root.addView(noticeSlot);
        notice("", "", null);
    }

    private FrameLayout pagingSlot() {
        // ListView measures a fixed header/footer even when the view itself is GONE.
        FrameLayout slot = new FrameLayout(activity);
        slot.addView(more);
        return slot;
    }

    private void bindList() {
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.length(); }
            @Override public Object getItem(int pos) { return rows.opt(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                Cell cell = convert instanceof Cell ? (Cell) convert : new Cell(activity);
                cell.bind(rows.optJSONObject(pos), transcript);
                return cell;
            }
        };
        list.setAdapter(adapter);
        more.setVisibility(View.GONE);
    }

    void rows(JSONArray data, boolean hasMore, boolean follow, int prepended) {
        rows(data, hasMore, follow, prepended, prepended);
    }

    void rows(JSONArray data, boolean hasMore, boolean follow, int prepended, int anchorShift) {
        int top = list.getFirstVisiblePosition();
        int y = list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
        int headers = list.getHeaderViewsCount();
        JSONObject topRow = top >= headers && (top - headers) < rows.length()
                ? rows.optJSONObject(top - headers) : null;
        ThreadData.RowId topAnchorId = topRow == null ? null : ThreadData.rowId(topRow);
        String topAnchorRaw = (topRow != null && topAnchorId == null) ? topRow.toString() : null;
        rows = transcript ? PromptHistory.display(data) : data;
        more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        more.setEnabled(true);
        adapter.notifyDataSetChanged();
        if (follow) {
            followTail();
        } else if (prepended > 0 || anchorShift != 0) {
            cancelPendingTailFollow();
            int target = -1;
            if (topAnchorId != null || topAnchorRaw != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    boolean match = topAnchorId != null
                            ? topAnchorId.equals(ThreadData.rowId(row))
                            : topAnchorRaw.equals(row.toString());
                    if (match) {
                        target = headers + i;
                        break;
                    }
                }
            }
            if (target < 0) {
                int delta = anchorShift != 0 ? anchorShift : prepended;
                target = Math.max(0, Math.min(Math.max(0, list.getCount() - 1), top + delta));
            }
            list.setSelectionFromTop(target, y);
        }
    }

    void setInboxFilter(ThreadInboxFilter.Filter filter) {
        activeInboxFilter = filter == null ? ThreadInboxFilter.Filter.ALL : filter;
        renderInboxFilters();
    }

    private void renderInboxFilters() {
        if (inboxFilterRow == null) return;
        inboxFilterRow.removeAllViews();
        int gap = Ui.dp(activity, 8);
        ThreadInboxFilter.Filter[] filters = ThreadInboxFilter.Filter.values();
        Typeface medium = Ui.mediumTypeface();
        for (int i = 0; i < filters.length; i++) {
            ThreadInboxFilter.Filter filter = filters[i];
            boolean selected = filter == activeInboxFilter;
            String label = activity.getString(filter.labelRes);
            Button pill = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            pill.setTag("thread_inbox:filter:" + filter.name().toLowerCase(java.util.Locale.ROOT));
            pill.setText(label);
            pill.setAllCaps(false);
            pill.setTextSize(14);
            pill.setTypeface(medium);
            pill.setStateListAnimator(null);
            pill.setMinHeight(Ui.dp(activity, 48));
            pill.setMinimumHeight(Ui.dp(activity, 48));
            pill.setPaddingRelative(Ui.dp(activity, 16), Ui.dp(activity, 8),
                    Ui.dp(activity, 16), Ui.dp(activity, 8));
            pill.setSelected(selected);
            pill.setContentDescription(label);
            pill.setTextColor(activity.getColor(selected ? R.color.on_accent : R.color.ink));
            pill.setBackground(Ui.roundedRipple(activity, Ui.BUTTON_CORNER_DP,
                    selected
                            ? Ui.shape(activity, Ui.BUTTON_CORNER_DP, R.color.accent, 0, 0)
                            : Ui.shape(activity, Ui.BUTTON_CORNER_DP, R.color.surface_container, R.color.line, 1)));
            pill.setOnClickListener(v -> {
                if (activeInboxFilter == filter) return;
                activeInboxFilter = filter;
                renderInboxFilters();
                if (onInboxFilterSelected != null) onInboxFilterSelected.accept(filter);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i < filters.length - 1) lp.setMarginEnd(gap);
            inboxFilterRow.addView(pill, lp);
        }
    }

    void indexRows(JSONArray data, String selected, boolean hasMore) {
        indexRows(data, selected, hasMore, Collections.emptyMap(), null);
    }

    void indexRows(JSONArray data, String selected, boolean hasMore,
                   Map<String, ActiveRunTracker.TaskBadge> badges, Consumer<String> onStop) {
        selectedRoot = selected;
        activeBadges = badges == null ? Collections.emptyMap() : badges;
        onStopTask = onStop;
        rows(data, hasMore, false, 0);
    }

    void paging() { more.setEnabled(false); }

    String draft() { return input.getText().toString(); }

    void displayDraft(String draft) {
        input.setText(draft);
        input.setSelection(input.length());
    }

    InstrumentViewport attachInstrumentViewport() {
        int index = root.indexOfChild(list);
        ViewGroup.LayoutParams size = list.getLayoutParams();
        root.removeView(list);
        instrumentSlot = new InstrumentViewport(list);
        root.addView(instrumentSlot, index, size);
        return instrumentSlot;
    }

    void refreshInstrumentRows() { if (adapter != null) adapter.notifyDataSetChanged(); }

    void revealProposal(Pin pin) {
        for (int i = 0; i < rows.length(); i++) {
            if (pin.fromMessage(rows.optJSONObject(i))) {
                int position = list.getHeaderViewsCount() + i;
                list.post(() -> list.setSelectionFromTop(position, Ui.dp(activity, 8)));
                return;
            }
        }
    }

    private View slot(String name) {
        return switch (name) {
            case "notice" -> noticeSlot;
            case "transcript" -> instrumentSlot == null ? list : instrumentSlot;
            case "composer" -> composerSlot;
            default -> throw new IllegalArgumentException("Unknown thread slot");
        };
    }

    /** Move attached children in layout order so the editor and message list keep their identity. */
    void slots(List<String> requested) {
        if (!transcript || composerSlot == null) return;
        List<String> all = Arrays.asList("notice", "transcript", "composer");
        List<String> order = requested.isEmpty() ? all : requested;
        for (String name : all) slot(name).setVisibility(order.contains(name) ? View.VISIBLE : View.GONE);
        if (!order.contains("composer")) composerVoice.cancel();
        List<View> visible = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) visible.add(child);
        }
        for (int i = 0; i < order.size(); i++) {
            if (visible.get(i) != slot(order.get(i))) {
                for (String name : order) root.bringChildToFront(slot(name));
                break;
            }
        }
    }

    void focusComposer() { if (input != null) input.requestFocus(); }
    boolean composerVisible() { return input != null && input.isShown(); }
    boolean editing() { return composerVisible() && input.hasFocus(); }

    void triggerDictation() {
        if (composerVoice != null && composerVoice.button != null && composerVoice.button.isShown()) {
            composerVoice.button.performClick();
        }
    }

    void clearDraft() { input.setText(""); }

    void importingPhoto(boolean importing) {
        importingPhoto = importing;
        updateSend();
    }

    void nearbyStatus(String label, Runnable open) {
        if (nearbyStatus == null) return;
        nearbyStatus.setText(label);
        nearbyStatus.setOnClickListener(v -> open.run());
        nearbyStatus.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void attachLiveUiBar(LiveUiPatchBar.Listener listener) {
        if (liveUiBar != null) noticeSlot.removeView(liveUiBar);
        liveUiBar = new LiveUiPatchBar(activity, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        int margin = Ui.dp(activity, 8);
        lp.setMargins(margin, Ui.dp(activity, 4), margin, Ui.dp(activity, 4));
        noticeSlot.addView(liveUiBar, lp);
    }

    void quickActions(List<LiveUiPatch.QuickAction> actions, ThemePalette palette,
                      Consumer<String> stageDraft, Consumer<String> openSurface,
                      Consumer<String> openAction) {
        if (quickActionSlot == null) return;
        quickActionSlot.removeAllViews();
        if (actions == null || actions.isEmpty()) {
            quickActionSlot.setVisibility(View.GONE);
            return;
        }
        quickActionSlot.addView(LiveUiPatchBar.quickActionStrip(
                activity, actions, palette, stageDraft, openSurface, openAction));
        quickActionSlot.setVisibility(View.VISIBLE);
    }

    void suggestedActions(List<LiveUiPatch.SuggestedAction> suggestions, ThemePalette palette,
                          Consumer<String> stageDraft, Consumer<String> openSurface,
                          Consumer<String> openAction, Consumer<String> pinWidget) {
        if (suggestedActionSlot == null) return;
        suggestedActionSlot.removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) {
            suggestedActionSlot.setVisibility(View.GONE);
            return;
        }
        suggestedActionSlot.addView(LiveUiPatchBar.suggestedActionsStrip(
                activity, suggestions, palette, stageDraft, openSurface, openAction, pinWidget));
        suggestedActionSlot.setVisibility(View.VISIBLE);
    }

    void interactiveCards(List<InteractiveCardView.Spec> cards, InteractiveCardView.Callbacks callbacks) {
        if (interactiveCardsSlot == null) return;
        interactiveCardsSlot.removeAllViews();
        if (cards == null || cards.isEmpty()) {
            interactiveCardsSlot.setVisibility(View.GONE);
            return;
        }
        int pad = Ui.dp(activity, 8);
        for (InteractiveCardView.Spec spec : cards) {
            if (spec == null) continue;
            InteractiveCardView card = new InteractiveCardView(activity, spec, callbacks);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, pad, 0, pad);
            interactiveCardsSlot.addView(card, lp);
        }
        interactiveCardsSlot.setVisibility(interactiveCardsSlot.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    void photo(JSONObject info, Runnable remove) {
        photoSlot.removeAllViews();
        photoSlot.setVisibility(info == null ? View.GONE : View.VISIBLE);
        input.setHint(info == null ? R.string.thread_message_hint : R.string.thread_image_question_hint);
        if (info == null) return;
        ImageView preview = new ImageView(activity);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        preview.setImageBitmap(DiscussionPhoto.thumbnail(info));
        photoSlot.addView(preview, Ui.dp(activity, 44), Ui.dp(activity, 44));
        String name = info.optString("name");
        TextView label = Ui.text(activity, name, 14);
        Ui.pad(label, 8, 0);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setContentDescription(activity.getString(R.string.thread_photo_attached_cd, name));
        photoSlot.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Button removeButton = Ui.button(activity, activity.getString(R.string.thread_remove), v -> remove.run());
        removeButton.setContentDescription(activity.getString(R.string.thread_photo_remove_cd, name));
        photoSlot.addView(removeButton);
    }

    void attachMedia(List<CapturedMedia.Item> items, Consumer<SmartIntake.Suggestion> onPick) {
        if (attachmentTray == null || items == null || items.isEmpty()) return;
        for (CapturedMedia.Item item : items) {
            attachmentTray.add(item, IncomingMediaShare.MAX_ITEMS);
        }
        List<CapturedMedia.Item> current = attachmentTray.items();
        if (!current.isEmpty() && smartIntakeSlot != null) {
            smartIntakeSlot.removeAllViews();
            List<SmartIntake.Suggestion> suggestions = SmartIntake.suggest(draft(), current, null);
            smartIntakeSlot.addView(SmartIntake.createChipRow(activity, suggestions, suggestion -> {
                if (onPick != null) onPick.accept(suggestion);
                clearSmartIntake();
            }));
            smartIntakeSlot.setVisibility(suggestions.isEmpty() ? View.GONE : View.VISIBLE);
        }
        updateSend();
    }

    List<CapturedMedia.Item> stagedAttachments() {
        return attachmentTray == null ? Collections.emptyList() : attachmentTray.items();
    }

    void clearAttachments() {
        if (attachmentTray != null) attachmentTray.clear();
        clearSmartIntake();
    }

    private void clearSmartIntake() {
        if (smartIntakeSlot != null) {
            smartIntakeSlot.removeAllViews();
            smartIntakeSlot.setVisibility(View.GONE);
        }
    }

    void refreshClipboardPill() {
        if (clipboardPill != null && clipboardPillButton != null && input != null) {
            boolean offer = input.hasFocus() && input.length() == 0 && clipboardPill.shouldOffer();
            clipboardPillButton.setVisibility(offer ? View.VISIBLE : View.GONE);
        }
    }

    private void updateSend() {
        send.setEnabled(!runActive && !importingPhoto && !VisibleText.isBlank(draft()));
    }

    void busy(boolean active, boolean canStop) {
        runActive = active;
        send.setVisibility(active ? View.GONE : View.VISIBLE);
        updateSend();
        stop.setVisibility(active && canStop ? View.VISIBLE : View.GONE);
        stop.setEnabled(canStop);
    }

    void outgoing(String text) {
        if (text.isEmpty()) {
            outgoing.setText("");
            outgoing.setVisibility(View.GONE);
            return;
        }
        String speaker = activity.getString(R.string.thread_you);
        android.text.SpannableString styled = new android.text.SpannableString(speaker + "\n" + text);
        styled.setSpan(new android.text.style.AbsoluteSizeSpan(13, true),
                0, speaker.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                0, speaker.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        outgoing.setLineSpacing(0f, 1.18f);
        outgoing.setText(styled);
        outgoing.setVisibility(View.VISIBLE);
    }

    void live(String detail, String text, boolean hasPrompts) {
        live(detail, text, hasPrompts, RunPreview.Origin.FIRST, "");
    }

    void live(String detail, String text, boolean hasPrompts, RunPreview.Origin origin) {
        live(detail, text, hasPrompts, origin, "");
    }

    void live(String detail, String text, boolean hasPrompts, RunPreview.Origin origin, String sseReasoning) {
        boolean atEnd = list.getLastVisiblePosition() >= list.getCount() - 1;
        progress.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setText(detail);
        TranscriptPage.ReasoningSplit split = TranscriptPage.splitReasoning(text, sseReasoning);
        String visibleText = split.visible();
        if (liveReasoningSlot != null) {
            if (split.reasoning().isEmpty()) {
                if (!lastLiveReasoning.isEmpty() || liveReasoningCard != null) {
                    lastLiveReasoning = "";
                    liveReasoningCard = null;
                    liveReasoningSlot.removeAllViews();
                    liveReasoningSlot.setVisibility(View.GONE);
                }
            } else {
                boolean streamingReasoning = split.open() || visibleText.isEmpty();
                if (liveReasoningCard == null) {
                    liveReasoningCard = new LiveReasoningCard(activity, this::show);
                    liveReasoningSlot.removeAllViews();
                    liveReasoningSlot.addView(liveReasoningCard.root);
                }
                if (!split.reasoning().equals(lastLiveReasoning) || liveReasoningCard.streaming != streamingReasoning) {
                    lastLiveReasoning = split.reasoning();
                    liveReasoningCard.update(lastLiveReasoning, streamingReasoning);
                }
                liveReasoningSlot.setVisibility(View.VISIBLE);
            }
        }
        liveText.setVisibility(visibleText.isEmpty() ? View.GONE : View.VISIBLE);
        boolean wasExcerpt = liveTranscript.excerpt();
        MessageText.updateLive(liveText, liveTranscript, visibleText, origin);
        if (wasExcerpt != liveTranscript.excerpt())
            fullLiveText.setText(liveTranscript.excerpt() ? R.string.transcript_read_excerpt : R.string.transcript_read_full);
        boolean readableSource = liveTranscript.earlier || liveTranscript.later || liveTranscript.foldedWorkspace;
        fullLiveText.setVisibility(!visibleText.isEmpty() && readableSource ? View.VISIBLE : View.GONE);
        if (duplex != null && duplex.active() && !visibleText.isEmpty()) {
            duplex.onReplyDelta(visibleText);
        }
        if (atEnd && !hasPrompts && !visibleText.isEmpty()) followTail();
    }

    void duplexReplyComplete(String output) {
        if (duplex != null && duplex.active()) {
            TranscriptPage.ReasoningSplit split = TranscriptPage.splitReasoning(output, "");
            duplex.onReplyComplete(split.visible());
        }
    }

    void stopDuplex() {
        if (duplex != null && duplex.active()) duplex.stop();
    }

    private void cancelPendingTailFollow() {
        tailFollowEpoch++;
        if (pendingTailListener != null) {
            list.removeOnLayoutChangeListener(pendingTailListener);
            pendingTailListener = null;
        }
    }

    private void followTail() {
        // A prompt can arrive between a transcript refresh and its queued scroll.
        if (scrollController != null) scrollController.onSnappedToLatest();
        if (promptRows.visible()) return;
        cancelPendingTailFollow();
        int epoch = tailFollowEpoch;
        list.post(() -> scrollToTailAfterLayout(epoch, 2));
    }

    private void scrollToTailAfterLayout(int epoch, int retriesRemaining) {
        if (epoch != tailFollowEpoch || promptRows.visible()) return;
        int count = list.getCount();
        if (count <= 0) return;
        int target = count - 1;
        list.setSelection(target);
        if (scrollController != null) scrollController.onSnappedToLatest();
        if (retriesRemaining <= 0) return;
        if (!list.isLaidOut() || list.isLayoutRequested() || list.getLastVisiblePosition() < target) {
            if (pendingTailListener != null) {
                list.removeOnLayoutChangeListener(pendingTailListener);
                pendingTailListener = null;
            }
            View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                     int ol, int ot, int or, int ob) {
                    list.removeOnLayoutChangeListener(this);
                    if (pendingTailListener == this) pendingTailListener = null;
                    if (epoch != tailFollowEpoch || promptRows.visible()) return;
                    if (list.getLastVisiblePosition() < list.getCount() - 1) {
                        list.post(() -> scrollToTailAfterLayout(epoch, retriesRemaining - 1));
                    } else if (scrollController != null) {
                        scrollController.onSnappedToLatest();
                    }
                }
            };
            pendingTailListener = listener;
            list.addOnLayoutChangeListener(listener);
        }
    }

    /** In-place live reasoning disclosure card preserving user expanded/collapsed state across token updates. */
    private static final class LiveReasoningCard {
        final LinearLayout root;
        private final Context context;
        private final Consumer<AlertDialog.Builder> openDialog;
        private final Button toggle;
        private final LinearLayout contentBox;
        private final TextView detail;
        private final Button fullButton;
        private boolean expanded;
        boolean streaming;
        private String currentReasoning = "";

        LiveReasoningCard(Context context, Consumer<AlertDialog.Builder> openDialog) {
            this.context = context;
            this.openDialog = openDialog;
            root = Ui.tonalCard(context);
            Ui.pad(root, 12, 8);

            contentBox = Ui.column(context);
            contentBox.setVisibility(View.GONE);

            detail = Ui.text(context, "", 14);
            detail.setTextColor(context.getColor(R.color.muted));
            detail.setTextIsSelectable(true);
            Ui.pad(detail, 4, 4);
            contentBox.addView(detail);

            fullButton = Ui.button(context, context.getString(R.string.reasoning_read_full), b -> {
                if (this.openDialog != null) {
                    this.openDialog.accept(new AlertDialog.Builder(context)
                            .setTitle(R.string.reasoning_title)
                            .setView(MessageText.reader(context, currentReasoning, false, false))
                            .setPositiveButton(R.string.ws_close, null));
                }
            });
            fullButton.setVisibility(View.GONE);
            contentBox.addView(fullButton);

            toggle = Ui.button(context, context.getString(R.string.reasoning_streaming), null);
            toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            toggle.setTextSize(14);
            toggle.setContentDescription(context.getString(R.string.reasoning_streaming_show_label));
            PromptButtons.icon(toggle, R.drawable.ic_prompt_disclosure);
            toggle.setOnClickListener(v -> {
                expanded = !expanded;
                if (expanded) bindDetail();
                contentBox.setVisibility(expanded ? View.VISIBLE : View.GONE);
                syncToggleLabel();
            });

            root.addView(toggle);
            root.addView(contentBox);
        }

        void update(String reasoning, boolean isStreaming) {
            this.currentReasoning = reasoning == null ? "" : reasoning;
            this.streaming = isStreaming;
            syncToggleLabel();
            if (expanded) bindDetail();
        }

        private void syncToggleLabel() {
            int labelRes = expanded
                    ? R.string.reasoning_hide
                    : (streaming ? R.string.reasoning_streaming : R.string.reasoning_title);
            toggle.setActivated(expanded);
            toggle.setText(context.getString(labelRes));
            toggle.setContentDescription(context.getString(
                    expanded ? R.string.reasoning_hide
                            : (streaming ? R.string.reasoning_streaming_show_label : R.string.reasoning_show_label)));
        }

        private void bindDetail() {
            boolean over = currentReasoning.length() > TranscriptPage.PAGE_CHARS;
            detail.setText(over
                    ? currentReasoning.substring(0, TranscriptPage.PAGE_CHARS) + "…"
                    : currentReasoning);
            fullButton.setVisibility(over && openDialog != null ? View.VISIBLE : View.GONE);
        }
    }

    void notice(String message, String label, Runnable action) {
        notice.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        notice.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP,
                action != null ? R.color.warning_surface : R.color.surface_container, 0, 0));
        status.setText(message);
        remedy.setVisibility(action == null ? View.GONE : View.VISIBLE);
        remedy.setText(label);
        remedy.setOnClickListener(action == null ? null : v -> action.run());
    }

    AlertDialog show(AlertDialog.Builder builder) {
        dismiss();
        dialog = Ui.secureDialog(builder);
        return dialog;
    }

    void dismiss(AlertDialog expected) {
        if (expected != null && dialog == expected) {
            dialog.dismiss();
            dialog = null;
        }
    }

    void dismiss() {
        voice.cancel();
        dismiss(dialog);
    }

    private final class Cell extends LinearLayout {
        private final TextView heading;
        private final TextView body;
        Cell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            Ui.pad(this, 18, transcript ? 11 : 13);
            setMinimumHeight(Ui.dp(context, 48));
            heading = Ui.text(context, "", 13);
            body = Ui.text(context, "", 17);
            if (transcript) {
                heading.setLetterSpacing(0.015f);
                body.setLineSpacing(0f, 1.18f);
                body.setPaddingRelative(0, Ui.dp(context, 3), 0, 0);
            } else {
                body.setPaddingRelative(0, Ui.dp(context, 3), 0, 0);
                body.setLineSpacing(0f, 1.15f);
                setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        if (host.isActivated()) info.setSelected(true);
                    }
                });
                heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
                body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            addView(heading);
            addView(body);
        }

        void bind(JSONObject row, boolean chat) {
            if (getChildCount() > 2) removeViews(2, getChildCount() - 2);
            if (!chat) {
                setTag(row);
                boolean selected = selectedRoot != null && selectedRoot.equals(ThreadIdentity.rootId(row));
                setActivated(selected);
                setBackgroundColor(selected ? getContext().getColor(R.color.accent_surface) : android.graphics.Color.TRANSPARENT);
            }
            String role = ThreadData.string(row, "role");
            if (chat && role.equals("tool")) {
                bindTool(row);
                return;
            }
            String name = switch (role) {
                case "user" -> getContext().getString(R.string.thread_you);
                case "assistant" -> getContext().getString(R.string.thread_speaker_hermes);
                case "prompt_status" -> getContext().getString(R.string.thread_role_prompt_status);
                case "approval_result" -> getContext().getString(R.string.thread_role_approval_result);
                default -> role;
            };
            List<Pin> proposals =
                    chat && instruments != null ? instruments.forRow(row) : java.util.Collections.emptyList();
            String content;
            if (!chat) content = ThreadTitles.previewForRow(row);
            else content = proposals.isEmpty() ? ThreadData.content(getContext(), row) : instruments.prose(row, proposals);
            if (chat && content.trim().isEmpty() && row.optJSONArray("tool_calls") != null) {
                StringBuilder names = new StringBuilder();
                JSONArray calls = row.optJSONArray("tool_calls");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.optJSONObject(i);
                    if (call == null) continue;
                    String tool = ThreadData.string(call.optJSONObject("function"), "name");
                    if (tool.isEmpty()) continue;
                    if (names.length() > 0) names.append(", ");
                    names.append(tool);
                }
                content = names.length() == 0 ? "" : getContext().getString(R.string.thread_tools_prefix, names.toString());
            }
            String label = chat ? name : ThreadData.title(row);
            if (!chat && label.equals(ThreadTitles.snippet(content))) content = "";
            heading.setText(label);
            heading.setTextSize(chat ? 13 : 16);
            heading.setMaxLines(chat ? 2 : 3);
            heading.setTypeface(Ui.mediumTypeface(), chat ? Typeface.BOLD : Typeface.NORMAL);
            heading.setTextColor(getContext().getColor(chat && role.equals("assistant") ? R.color.accent : R.color.ink));
            body.setTextColor(getContext().getColor(chat ? R.color.ink : R.color.muted));
            body.setMaxLines(chat ? Integer.MAX_VALUE : 2);
            body.setTextSize(chat ? 17 : 14);
            body.setTypeface(Typeface.DEFAULT);
            body.setTextIsSelectable(chat);
            boolean markdown = chat && "assistant".equals(role);
            String rawReasoning = markdown ? ThreadData.string(row, "reasoning") : "";
            int pageLimit = chat ? TranscriptPage.PAGE_CHARS : 300;
            boolean night = ThemePalette.isNight(getContext());
            ThreadData.RowId rid = ThreadData.rowId(row);
            String rowKey = rid == null ? (role + "#" + (chat ? "c" : "i")) : (rid.segment() + ":" + rid.id());
            final String rowContent = content;
            TranscriptCellCache.Key cacheKey = TranscriptCellCache.keyFor(
                    rowKey, rowContent, rawReasoning, markdown, night, pageLimit);
            TranscriptCellCache.Rendered cached = CELL_CACHE.getOrCompute(cacheKey, () -> {
                String vis = rowContent;
                String reas = "";
                if (markdown) {
                    TranscriptPage.ReasoningSplit split = TranscriptPage.splitReasoning(rowContent, rawReasoning);
                    vis = split.visible();
                    reas = split.reasoning();
                }
                TranscriptPage pg = new TranscriptPage(vis, new TranscriptPage.Cursor(), markdown, pageLimit);
                CharSequence styled = markdown && (!pg.blocks.isEmpty() || !pg.tables.isEmpty())
                        ? MessageText.excerpt(getContext(), pg)
                        : MessageText.styled(getContext(), pg);
                CharSequence finalText = pg.hasMore() ? android.text.TextUtils.concat(styled, "…") : styled;
                return new TranscriptCellCache.Rendered(pg, finalText, vis, reas);
            });
            String visibleContent = cached.visibleContent();
            String reasoningContent = cached.reasoningContent();
            TranscriptPage preview = cached.page();
            body.setVisibility(visibleContent.isEmpty() ? View.GONE : View.VISIBLE);
            body.setText(cached.text());
            if (markdown && !reasoningContent.isEmpty()) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = Ui.dp(getContext(), 6);
                addView(MessageText.reasoningCard(getContext(), reasoningContent, false, ThreadViews.this::show), params);
            }
            if (markdown) {
                MessageText.addTables(this, preview);
                MessageText.addLinkActions(this, preview, ThreadViews.this::show);
                MessageText.addCodeAction(this, preview, visibleContent, ThreadViews.this::show);
            }
            if (chat && "approval_result".equals(role)) {
                String details = ThreadData.string(row, "receipt_details");
                addView(Ui.button(getContext(), getContext().getString(R.string.thread_see_details), v ->
                        show(new AlertDialog.Builder(getContext())
                                .setTitle(R.string.thread_action_details)
                                .setView(MessageText.reader(getContext(), details, false, true))
                                .setPositiveButton(R.string.ws_close, null))));
            }
            if (chat && preview.hasMore()) {
                String full = content;
                addView(Ui.button(getContext(), getContext().getString(R.string.thread_read_full_message), v ->
                        show(new AlertDialog.Builder(getContext()).setTitle(name)
                                .setView(MessageText.reader(getContext(), full, markdown, false,
                                        new TranscriptPage.Cursor(), ThreadViews.this::show))
                                .setPositiveButton(R.string.ws_close, null))));
            }
            for (Pin proposal : proposals)
                instruments.proposal(this, proposal);
            if (!chat) {
                if (ThreadInboxFilter.isSharedRow(row)) {
                    HorizontalScrollView badgeScroll = new HorizontalScrollView(getContext());
                    badgeScroll.setHorizontalScrollBarEnabled(false);
                    LinearLayout badges = Ui.row(getContext());
                    badges.setPaddingRelative(0, Ui.dp(getContext(), 6), 0, 0);
                    String perm = ThreadInboxFilter.permission(row);
                    TextView permPill = Ui.pill(getContext(),
                            org.llm4people.hermes.panel.UniversalShareModel.permissionLabel(getContext(), perm),
                            R.color.accent_surface, R.color.accent);
                    LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(-2, -2);
                    permLp.setMarginEnd(Ui.dp(getContext(), 8));
                    badges.addView(permPill, permLp);
                    String owner = ThreadInboxFilter.ownerProfile(row);
                    if (!owner.isEmpty()) {
                        badges.addView(Ui.pill(getContext(),
                                getContext().getString(R.string.ws_shared_by, owner),
                                R.color.surface_container, R.color.muted));
                    }
                    badgeScroll.addView(badges);
                    addView(badgeScroll);
                } else if (ThreadInboxFilter.isPinned(row)) {
                    LinearLayout pinRow = Ui.row(getContext());
                    pinRow.setPaddingRelative(0, Ui.dp(getContext(), 6), 0, 0);
                    pinRow.addView(Ui.pill(getContext(),
                            getContext().getString(R.string.brain_memory_pinned),
                            R.color.accent_surface, R.color.accent));
                    addView(pinRow);
                }
                String rootId = ThreadIdentity.rootId(row);
                ActiveRunTracker.TaskBadge badge = activeBadges.get(rootId);
                if (badge != null && badge.active()) {
                    addView(activeTaskRow(getContext(), rootId, label, badge));
                }
            }
        }

        private View activeTaskRow(Context context, String rootId, String threadTitle, ActiveRunTracker.TaskBadge badge) {
            LinearLayout strip = Ui.row(context);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setPadding(0, Ui.dp(context, 4), 0, 0);
            int labelRes = badge.awaitingDecision()
                    ? R.string.thread_badge_action_needed
                    : R.string.thread_badge_running;
            TextView pill = Ui.pill(context, context.getString(labelRes),
                    badge.awaitingDecision() ? R.color.danger_surface : R.color.accent_surface,
                    badge.awaitingDecision() ? R.color.danger : R.color.accent);
            strip.addView(pill, new LinearLayout.LayoutParams(-2, -2));
            View spacer = new View(context);
            strip.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            if (badge.canStop() && onStopTask != null) {
                String stopLabel = context.getString(R.string.thread_badge_stop);
                Button stopBtn = Ui.button(context, stopLabel, v -> onStopTask.accept(rootId));
                stopBtn.setTextColor(Ui.dangerInk(context));
                stopBtn.setContentDescription(threadTitle == null || threadTitle.isEmpty()
                        ? stopLabel : stopLabel + " · " + threadTitle);
                stopBtn.setFocusable(false);
                stopBtn.setFocusableInTouchMode(false);
                stopBtn.setMinHeight(Ui.dp(context, 48));
                stopBtn.setMinimumHeight(Ui.dp(context, 48));
                strip.addView(stopBtn);
            }
            return strip;
        }

        private void bindTool(JSONObject row) {
            Context context = getContext();
            heading.setText(ToolResult.label(context, row));
            heading.setTextSize(14);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setMaxLines(2);
            heading.setTextColor(context.getColor(R.color.muted));
            body.setText(null);
            body.setVisibility(View.GONE);
            if (row.isNull("content") || "".equals(row.opt("content"))) return;
            String name = ToolResult.name(row);
            String seeResult = context.getString(R.string.thread_see_result);
            String dialogTitle = name.isEmpty()
                    ? context.getString(R.string.thread_tool_result)
                    : context.getString(R.string.thread_tool_result_dialog_named, name);
            Button details = Ui.button(context, seeResult, v -> show(CodeReader.dialog(context,
                    dialogTitle, "", ToolResult.raw(row))));
            details.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            details.setContentDescription(seeResult + " · " + heading.getText());
            addView(details);
        }
    }
}
