package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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

final class ThreadViews {
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
        noticeSlot = Ui.column(activity);
        noticeSlot.addView(notice);
        Ui.pad(notice, 16, 2);
        status = Ui.text(activity, "", 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        remedy = Ui.button(activity, "", v -> {});
        notice.addView(status);
        notice.addView(remedy);
    }

    void inbox(Runnable create, Runnable refresh, Consumer<JSONObject> choose, Runnable next) {
        reset(false);
        Button newThread = Ui.button(activity, activity.getString(R.string.thread_new_conversation), v -> create.run());
        newThread.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        newThread.setTextSize(16);
        Ui.pad(newThread, 18, 4);
        newThread.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.thread_new, 0);
        LinearLayout actions = Ui.row(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        newThread.setSingleLine(false);
        actions.addView(newThread, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton reload = new ImageButton(activity, null, android.R.attr.borderlessButtonStyle);
        reload.setImageResource(R.drawable.ic_skills_refresh);
        reload.setContentDescription(activity.getString(R.string.thread_refresh_discussions));
        reload.setTooltipText(reload.getContentDescription());
        reload.setOnClickListener(v -> refresh.run());
        actions.addView(reload, Ui.dp(activity, 48), Ui.dp(activity, 48));
        root.addView(actions);
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
        live.addView(liveReasoningSlot);
        live.addView(liveText);
        live.addView(fullLiveText);
        suggestedActionSlot = Ui.column(activity);
        suggestedActionSlot.setVisibility(View.GONE);
        live.addView(suggestedActionSlot);
        list.addFooterView(live, null, false);
        bindList();
        list.setOnItemClickListener(null);
        LinearLayout composer = Ui.row(activity);
        composer.setGravity(Gravity.BOTTOM);
        composer.setBaselineAligned(false);
        Ui.pad(composer, 8, 4);
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
        input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        input.setPaddingRelative(Ui.dp(activity, 10), Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
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
        writing.setBackgroundResource(R.drawable.profile_input);
        writing.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        writing.addView(composerVoice.button);
        LinearLayout.LayoutParams writingSize = new LinearLayout.LayoutParams(0, -2, 1);
        writingSize.setMarginEnd(Ui.dp(activity, 6));
        composer.addView(writing, writingSize);
        for (Button action : new Button[]{send, stop}) {
            action.setMinWidth(Ui.dp(activity, 48));
            action.setMinimumWidth(Ui.dp(activity, 48));
            Ui.pad(action, 10, 4);
        }
        send.setBackgroundResource(R.drawable.thread_send);
        send.setBackgroundTintList(null);
        send.setTextColor(activity.getColorStateList(R.color.thread_send_ink));
        stop.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.thread_stop, 0, 0, 0);
        stop.setCompoundDrawablePadding(Ui.dp(activity, 4));
        stop.setTextColor(activity.getColor(R.color.ink));
        composer.addView(send);
        composer.addView(stop);
        composerSlot = Ui.column(activity);
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

    private void reset(boolean chat) {
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
        int top = list.getFirstVisiblePosition();
        int y = list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
        int previous = rows.length();
        rows = transcript ? PromptHistory.display(data) : data;
        more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        more.setEnabled(true);
        adapter.notifyDataSetChanged();
        if (follow) followTail();
        else if (prepended > 0) list.setSelectionFromTop(top + rows.length() - previous, y);
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
        label.setContentDescription("Image jointe : " + name);
        photoSlot.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Button removeButton = Ui.button(activity, activity.getString(R.string.thread_remove), v -> remove.run());
        removeButton.setContentDescription("Retirer l’image " + name);
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
        outgoing.setText(text.isEmpty() ? "" : activity.getString(R.string.thread_you) + "\n" + text);
        outgoing.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
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
                if (!lastLiveReasoning.isEmpty()) {
                    lastLiveReasoning = "";
                    liveReasoningSlot.removeAllViews();
                    liveReasoningSlot.setVisibility(View.GONE);
                }
            } else if (!split.reasoning().equals(lastLiveReasoning)) {
                lastLiveReasoning = split.reasoning();
                liveReasoningSlot.removeAllViews();
                liveReasoningSlot.addView(MessageText.reasoningCard(activity, lastLiveReasoning,
                        split.open() || visibleText.isEmpty(), this::show));
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

    private void followTail() {
        // A prompt can arrive between a transcript refresh and its queued scroll.
        if (!promptRows.visible()) list.post(() -> {
            if (!promptRows.visible()) list.setSelection(list.getCount() - 1);
        });
    }

    void notice(String message, String label, Runnable action) {
        notice.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
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
            Ui.pad(this, 18, transcript ? 9 : 12);
            setMinimumHeight(Ui.dp(context, 48));
            heading = Ui.text(context, "", 13);
            body = Ui.text(context, "", 17);
            if (!transcript) {
                body.setPadding(0, Ui.dp(context, 2), 0, 0);
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
                case "assistant" -> "Hermes";
                case "prompt_status" -> "Suivi de la question";
                case "approval_result" -> "Autorisation";
                default -> role;
            };
            List<Pin> proposals =
                    chat && instruments != null ? instruments.forRow(row) : java.util.Collections.emptyList();
            String content;
            if (!chat) content = ThreadTitles.previewForRow(row);
            else content = proposals.isEmpty() ? ThreadData.content(row) : instruments.prose(row, proposals);
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
                content = names.length() == 0 ? "" : "Outils · " + names;
            }
            String label = chat ? name : ThreadData.title(row);
            if (!chat && label.equals(ThreadTitles.snippet(content))) content = "";
            heading.setText(label);
            heading.setTextSize(chat ? 13 : 17);
            heading.setMaxLines(chat ? 2 : 3);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setTextColor(getContext().getColor(chat && role.equals("assistant") ? R.color.accent : R.color.ink));
            body.setTextColor(getContext().getColor(chat ? R.color.ink : R.color.muted));
            body.setMaxLines(chat ? Integer.MAX_VALUE : 2);
            body.setTextSize(chat ? 17 : 14);
            body.setTypeface(Typeface.DEFAULT);
            body.setTextIsSelectable(chat);
            boolean markdown = chat && "assistant".equals(role);
            String visibleContent = content;
            String reasoningContent = "";
            if (markdown) {
                TranscriptPage.ReasoningSplit split = TranscriptPage.splitReasoning(content, ThreadData.string(row, "reasoning"));
                visibleContent = split.visible();
                reasoningContent = split.reasoning();
            }
            body.setVisibility(visibleContent.isEmpty() ? View.GONE : View.VISIBLE);
            TranscriptPage preview = new TranscriptPage(visibleContent, new TranscriptPage.Cursor(), markdown,
                    chat ? TranscriptPage.PAGE_CHARS : 300);
            CharSequence rendered = markdown && (!preview.blocks.isEmpty() || !preview.tables.isEmpty())
                    ? MessageText.excerpt(getContext(), preview)
                    : MessageText.styled(getContext(), preview);
            body.setText(preview.hasMore() ? android.text.TextUtils.concat(rendered, "…") : rendered);
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
                addView(Ui.button(getContext(), "Voir les détails", v ->
                        show(new AlertDialog.Builder(getContext()).setTitle("Détails de l’action")
                                .setView(MessageText.reader(getContext(), details, false, true))
                                .setPositiveButton("Fermer", null))));
            }
            if (chat && preview.hasMore()) {
                String full = content;
                addView(Ui.button(getContext(), "Lire le message entier", v ->
                        show(new AlertDialog.Builder(getContext()).setTitle(name)
                                .setView(MessageText.reader(getContext(), full, markdown, false,
                                        new TranscriptPage.Cursor(), ThreadViews.this::show))
                                .setPositiveButton("Fermer", null))));
            }
            for (Pin proposal : proposals)
                instruments.proposal(this, proposal);
            if (!chat) {
                String rootId = ThreadIdentity.rootId(row);
                ActiveRunTracker.TaskBadge badge = activeBadges.get(rootId);
                if (badge != null && badge.active()) {
                    addView(activeTaskRow(getContext(), rootId, badge));
                }
            }
        }

        private View activeTaskRow(Context context, String rootId, ActiveRunTracker.TaskBadge badge) {
            LinearLayout strip = Ui.row(context);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setPadding(0, Ui.dp(context, 4), 0, 0);
            int labelRes = badge.awaitingDecision()
                    ? R.string.thread_badge_action_needed
                    : R.string.thread_badge_running;
            TextView pill = Ui.text(context, "● " + context.getString(labelRes), 13);
            pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            pill.setTextColor(context.getColor(badge.awaitingDecision() ? R.color.danger : R.color.accent));
            strip.addView(pill, new LinearLayout.LayoutParams(0, -2, 1));
            if (badge.canStop() && onStopTask != null) {
                Button stopBtn = Ui.button(context, context.getString(R.string.thread_badge_stop),
                        v -> onStopTask.accept(rootId));
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
