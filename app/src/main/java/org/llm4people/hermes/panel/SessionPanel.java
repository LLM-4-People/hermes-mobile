package org.llm4people.hermes.panel;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Window owned by the unlocked profile's activity, including its in-flight UI callbacks. */
public final class SessionPanel implements AutoCloseable {
    final Activity activity;
    final HermesApi api;
    final Account account;
    private Dialog dialog;
    private Dialog child;
    private ExecutorService worker;
    private int generation;
    private Runnable back;
    private ArtifactDesk.Host layoutHost;

    public SessionPanel(Activity activity, HermesApi api, Account account) {
        this.activity = activity;
        this.api = api;
        this.account = account;
    }

    public void setLayoutHost(ArtifactDesk.Host host) { layoutHost = host; }
    public void show(String sessionId) { new SessionSettings(this, sessionId).show(); }
    public void showStats(String sessionId) { new UsagePanel(this, sessionId).show(); }
    public void showReminders() { new ReminderPanel(this).show(); }

    public static JSONObject runOptions(Context context, Account account, String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return new JSONObject();
        return PanelData.restoreSelection(account.read("panel/options/" + sessionId));
    }

    public static void recordTiming(Context context, Account account, String sessionId, long firstTextMillis,
                                   long elapsedMillis, long outputTokens) {
        if (sessionId == null || sessionId.isEmpty() || elapsedMillis <= 0) return;
        JSONObject timing = PanelData.put(new JSONObject(), "elapsed_ms", elapsedMillis);
        if (firstTextMillis >= 0 && firstTextMillis <= elapsedMillis) PanelData.put(timing, "first_ms", firstTextMillis);
        if (outputTokens >= 0) PanelData.put(timing, "output_tokens", outputTokens);
        account.write("panel/timing/" + sessionId, timing.toString());
    }

    void saveOptions(String sessionId, JSONObject options) {
        account.write("panel/options/" + sessionId, options.toString());
    }

    String t(int resource, Object... args) { return activity.getString(resource, args); }

    LinearLayout page(String surface, int title, Runnable parent) {
        return page(surface, title, parent, true);
    }

    LinearLayout page(String surface, int title, Runnable parent, boolean scrollable) {
        if (dialog != null && !dialog.isShowing()) close();
        if (dialog == null) {
            worker = Executors.newSingleThreadExecutor();
            dialog = new Dialog(activity, R.style.Theme_Hermes) {
                @Override public void onBackPressed() {
                    if (back != null) back.run(); else dismiss();
                }
            };
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setOnDismissListener(closed -> { if (closed == dialog) teardown(); });
            Window window = dialog.getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        generation++;
        back = parent;
        LinearLayout container = Ui.column(activity);
        container.setFitsSystemWindows(true);
        container.setBackgroundColor(activity.getColor(R.color.paper));
        LinearLayout nav = Ui.row(activity);
        Ui.pad(nav, 12, 0);
        nav.addView(Ui.button(activity, t(parent == null ? R.string.panel_done : R.string.panel_back),
                view -> { if (parent == null) dialog.dismiss(); else parent.run(); }));
        container.addView(nav);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 20, 12);
        TextView heading = Ui.text(activity, t(title), 29);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        content.addView(heading);
        if (scrollable) scroll.addView(content);
        container.addView(scrollable ? scroll : content, new LinearLayout.LayoutParams(-1, 0, 1));
        View decorated = layoutHost == null ? container :
                ArtifactDesk.decorate(activity, account, surface, container, layoutHost);
        dialog.setContentView(decorated);
        dialog.setTitle(t(title));
        if (!dialog.isShowing()) dialog.show();
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return content;
    }

    TextView note(LinearLayout box, String value) {
        TextView text = Ui.text(activity, value, 16);
        text.setTextColor(activity.getColor(R.color.muted));
        Ui.pad(text, 0, 5);
        box.addView(text);
        return text;
    }

    void section(LinearLayout box, int resource) {
        TextView view = Ui.text(activity, t(resource), 20);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPaddingRelative(0, Ui.dp(activity, 18), 0, Ui.dp(activity, 4));
        view.setAccessibilityHeading(true);
        box.addView(view);
    }

    Button action(LinearLayout box, int label, Runnable listener) {
        Button button = Ui.button(activity, t(label), v -> listener.run());
        button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        box.addView(button);
        return button;
    }

    <T> void load(LinearLayout box, Callable<T> call, Consumer<T> success, Runnable retry) {
        request(box, call, success, error -> failure(box, error, retry));
    }

    <T> void request(LinearLayout box, Callable<T> call, Consumer<T> success, Consumer<Exception> rejected) {
        int page = generation;
        TextView progress = note(box, t(R.string.panel_loading));
        progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        worker.execute(() -> {
            try {
                T value = call.call();
                activity.runOnUiThread(() -> {
                    if (page != generation) return;
                    box.removeView(progress);
                    try { success.accept(value); }
                    catch (IllegalStateException error) { rejected.accept(error); }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (page != generation) return;
                    box.removeView(progress);
                    rejected.accept(error);
                });
            }
        });
    }

    void failure(LinearLayout box, Exception error, Runnable retry) {
        org.llm4people.hermes.Diagnostics.warning("panel", "operation_failed");
        String message = error instanceof HermesApi.HttpError
                ? t(R.string.panel_http_error, ((HermesApi.HttpError) error).status)
                : t(error instanceof IOException ? R.string.panel_network_error :
                    error instanceof JSONException ? R.string.panel_invalid : R.string.panel_storage_error);
        TextView status = note(box, message);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (retry != null) action(box, R.string.panel_retry, retry);
    }

    void present(Dialog childDialog) {
        if (child != null) child.dismiss();
        child = childDialog;
        child.setOnDismissListener(d -> { if (child == d) child = null; });
        child.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        child.show();
    }

    private void teardown() {
        generation++;
        if (child != null) { child.dismiss(); child = null; }
        if (worker != null) worker.shutdownNow();
        worker = null;
        dialog = null;
        back = null;
    }

    @Override public void close() {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
            teardown();
        }
    }
}
