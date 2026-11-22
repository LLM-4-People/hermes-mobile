package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.function.Consumer;

final class ThreadViews {
    final LinearLayout root;
    final ListView list;
    private final Activity activity;
    private final TextView status;
    private final Button remedy;
    private final LinearLayout notice;
    private Button more;
    private EditText input;
    private Button send;
    private Button stop;
    private TextView progress;
    private TextView outgoing;
    private TextView liveText;
    private Button approval;
    private JSONArray rows = new JSONArray();
    private BaseAdapter adapter;
    private AlertDialog dialog;
    private boolean transcript;

    ThreadViews(Activity activity) {
        this.activity = activity;
        root = Ui.column(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setClipToPadding(false);
        notice = Ui.column(activity);
        Ui.pad(notice, 16, 2);
        status = Ui.text(activity, "", 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        remedy = Ui.button(activity, "", v -> {});
        notice.addView(status);
        notice.addView(remedy);
    }

    void inbox(Runnable create, Consumer<JSONObject> choose, Runnable next) {
        reset(false);
        Button newThread = Ui.button(activity, "Nouvelle conversation", v -> create.run());
        root.addView(newThread);
        more = Ui.button(activity, "Afficher la suite", v -> next.run());
        list.addFooterView(more, null, false);
        bindList();
        list.setOnItemClickListener((parent, view, pos, id) -> {
            JSONObject session = rows.optJSONObject(pos);
            if (session != null) choose.accept(session);
        });
    }

    void chat(String draft, Consumer<String> onDraft, Runnable onSend, Runnable onStop,
              Runnable approve, Runnable older) {
        reset(true);
        more = Ui.button(activity, "Messages précédents", v -> older.run());
        list.addHeaderView(more, null, false);
        LinearLayout live = Ui.column(activity);
        Ui.pad(live, 18, 3);
        progress = Ui.text(activity, "", 13);
        progress.setTextColor(activity.getColor(R.color.muted));
        liveText = Ui.text(activity, "", 17);
        liveText.setTextIsSelectable(true);
        approval = Ui.button(activity, "Examiner la demande", v -> approve.run());
        outgoing = Ui.text(activity, "", 17);
        outgoing.setTextIsSelectable(true);
        outgoing.setVisibility(View.GONE);
        live.addView(outgoing);
        live.addView(progress);
        live.addView(liveText);
        live.addView(approval);
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
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setMinHeight(Ui.dp(activity, 48));
        input.setHint("Message à Hermes");
        input.setText(draft);
        input.setSelection(input.length());
        send = Ui.button(activity, "Envoyer", v -> onSend.run());
        stop = Ui.button(activity, "Arrêter", v -> onStop.run());
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        composer.addView(send);
        composer.addView(stop);
        root.addView(Ui.divider(activity));
        root.addView(composer);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onDraft.accept(s.toString());
                send.setEnabled(!s.toString().trim().isEmpty());
            }
        });
        busy(false, false);
        live("", "", false);
    }

    private void reset(boolean chat) {
        transcript = chat;
        root.removeAllViews();
        rows = new JSONArray();
        root.addView(notice);
        notice("", "", null);
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
        rows = data;
        more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        more.setEnabled(true);
        adapter.notifyDataSetChanged();
        if (follow) list.post(() -> list.setSelection(list.getCount() - 1));
        else if (prepended > 0) list.setSelectionFromTop(top + prepended, y);
    }

    void paging() { more.setEnabled(false); }

    String draft() { return input.getText().toString(); }

    void displayDraft(String draft) {
        input.setText(draft);
        input.setSelection(input.length());
    }

    void clearDraft() { input.setText(""); }

    void busy(boolean active, boolean canStop) {
        send.setVisibility(active ? View.GONE : View.VISIBLE);
        send.setEnabled(!active && !draft().trim().isEmpty());
        stop.setVisibility(active && canStop ? View.VISIBLE : View.GONE);
        stop.setEnabled(canStop);
    }

    void outgoing(String text) {
        outgoing.setText(text.isEmpty() ? "" : "Vous\n" + text);
        outgoing.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void live(String detail, String text, boolean needsApproval) {
        boolean atEnd = list.getLastVisiblePosition() >= list.getCount() - 1;
        progress.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setText(detail);
        liveText.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (!text.contentEquals(liveText.getText())) liveText.setText(text);
        approval.setVisibility(needsApproval ? View.VISIBLE : View.GONE);
        if (atEnd && !text.isEmpty()) list.post(() -> list.setSelection(list.getCount() - 1));
    }

    void notice(String message, String label, Runnable action) {
        notice.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        status.setText(message);
        remedy.setVisibility(action == null ? View.GONE : View.VISIBLE);
        remedy.setText(label);
        remedy.setOnClickListener(action == null ? null : v -> action.run());
    }

    void show(AlertDialog.Builder builder) {
        dismiss();
        dialog = Ui.secureDialog(builder);
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private final class Cell extends LinearLayout {
        private final TextView heading;
        private final TextView body;
        Cell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            Ui.pad(this, 18, 9);
            setMinimumHeight(Ui.dp(context, 48));
            heading = Ui.text(context, "", 13);
            body = Ui.text(context, "", 17);
            addView(heading);
            addView(body);
        }

        void bind(JSONObject row, boolean chat) {
            String role = ThreadData.string(row, "role");
            String name = switch (role) {
                case "user" -> "Vous";
                case "assistant" -> "Hermes";
                case "tool" -> "Outil · " + ThreadData.string(row, "tool_name");
                default -> role;
            };
            String content = chat ? ThreadData.content(row) : ThreadData.string(row, "preview");
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
            heading.setText(chat ? name : ThreadData.title(row));
            heading.setTextSize(chat ? 13 : 17);
            heading.setMaxLines(chat ? 2 : 3);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setTextColor(getContext().getColor(chat && role.equals("assistant") ? R.color.accent : R.color.ink));
            body.setTextColor(getContext().getColor(chat ? R.color.ink : R.color.muted));
            body.setMaxLines(chat ? Integer.MAX_VALUE : 2);
            body.setTextSize(chat ? 17 : 14);
            body.setTypeface(role.equals("tool") || content.startsWith("```") ? Typeface.MONOSPACE : Typeface.DEFAULT);
            body.setTextIsSelectable(chat);
            body.setVisibility(content.isEmpty() ? View.GONE : View.VISIBLE);
            // Large output remains available in full without forcing ListView to lay out a whole log.
            boolean clipped = chat && content.length() > 6000;
            body.setText(clipped ? content.substring(0, 6000) + "…" : content);
            if (getChildCount() > 2) removeViewAt(2);
            if (clipped) {
                String full = content;
                addView(Ui.button(getContext(), "Lire le message entier", v -> {
                    TextView text = Ui.text(getContext(), full, 16);
                    text.setTextIsSelectable(true);
                    Ui.pad(text, 20, 12);
                    ScrollView scroll = new ScrollView(getContext());
                    scroll.addView(text);
                    show(new AlertDialog.Builder(getContext()).setTitle(name).setView(scroll)
                            .setPositiveButton("Fermer", null));
                }));
            }
        }
    }
}
