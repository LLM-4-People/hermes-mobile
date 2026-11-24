package org.llm4people.hermes.panel;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.KeyEvent;
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
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Window owned by the unlocked profile's activity, including its in-flight UI callbacks. */
public final class SessionPanel implements AutoCloseable {
    final Activity activity;
    final HermesApi api;
    final Account account;
    private Dialog dialog;
    private Dialog child;
    private ExecutorService worker;
    private volatile int generation;
    private Runnable back;
    private String surface;
    private LinearLayout nativeContent;
    private PanelFrame frame;
    private LinearLayout navigation;
    private ArtifactDesk.Host layoutHost;

    public SessionPanel(Activity activity, HermesApi api, Account account) {
        this.activity = activity;
        this.api = api;
        this.account = account;
    }

    public void setLayoutHost(ArtifactDesk.Host host) { layoutHost = host; }
    public void show(String stableId, String remoteId) { show(stableId, remoteId, () -> false, null); }
    public void show(String stableId, String remoteId, BooleanSupplier activeThread, Consumer<String> stageDraft) {
        new SessionSettings(this, stableId, remoteId, activeThread, stageDraft,
                () -> activity.getResources().getConfiguration()).show();
    }
    public void showStats(String stableId, String remoteId) { new UsagePanel(this, stableId, remoteId, null).show(); }
    public void showSkills(BooleanSupplier activeOwner, Consumer<String> stageDraft) {
        new SkillBrowser(this, activeOwner, stageDraft, null).show();
    }
    public void showReminders() { new ReminderPanel(this).show(); }
    public void showReminder(String jobId) { new ReminderPanel(this).open(jobId); }
    public void showBrainHub(RouteRegistry registry) { new BrainHubPanel(this, registry).show(); }
    public void showMemory(Runnable parent) { new MemoryPanel(this, parent).show(); }
    public void showNotes(BooleanSupplier activeThread, Consumer<String> stageDraft, Runnable parent) {
        new NotesPanel(this, activeThread, stageDraft, parent).show();
    }
    public void showSharedNotes(BooleanSupplier activeThread, Consumer<String> stageDraft, Runnable parent) {
        new NotesPanel(this, activeThread, stageDraft, parent).showShared();
    }
    public void showCreateNote(String title, String body, BooleanSupplier activeThread,
                               Consumer<String> stageDraft, Runnable parent) {
        new NotesPanel(this, activeThread, stageDraft, parent).showWithDraft(title, body);
    }
    public void showSharedWithMe(Consumer<String> stageDraft, Runnable parent) {
        ShareWithUserSheet.showSharedWithMe(this, stageDraft, parent);
    }
    public void showGlobalSearch(Consumer<String> openThread, Runnable parent) {
        new GlobalSearchPanel(this, openThread, parent).show();
    }
    public void showSkillStudio(BooleanSupplier activeThread, Consumer<String> stageDraft, Runnable parent) {
        new SkillStudio(this, activeThread, stageDraft, parent).show();
    }
    public void showWorkspaceFiles(String sessionId, BooleanSupplier activeThread,
                                   Consumer<String> stageDraft, Runnable parent) {
        new WorkspaceFilesPanel(this, sessionId, activeThread, stageDraft, parent).show();
    }
    public void showProfileDefaults(Runnable parent) { new ProfileDefaultsPanel(this, parent).show(); }

    /** Rebuild only the customized frame, retaining the native form and its in-flight requests. */
    public boolean refreshLayout() {
        if (dialog == null || !dialog.isShowing() || nativeContent == null) return false;
        mountLayout();
        return true;
    }

    private void mountLayout() {
        if (frame.getParent() instanceof ViewGroup parent) parent.removeView(frame);
        View decorated = layoutHost == null || !ArtifactDesk.supportsSurface(surface) ? frame :
                ArtifactDesk.decorate(activity, account, surface, frame, layoutHost);
        dialog.setContentView(decorated);
    }

    private void goBack() {
        if (back != null) back.run();
        else if (dialog != null) dialog.dismiss();
    }

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

    void resetOptions(String sessionId) { account.remove("panel/options/" + sessionId); }

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
            dialog = new Dialog(activity, R.style.Theme_Hermes);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setOnDismissListener(closed -> { if (closed == dialog) teardown(); });
            Window window = dialog.getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        generation++;
        back = parent;
        LinearLayout container = Ui.column(activity);
        frame = new PanelFrame(activity, container);
        navigation = Ui.row(activity);
        Ui.pad(navigation, 12, 0);
        navigation.addView(Ui.button(activity, t(parent == null ? R.string.panel_done : R.string.panel_back),
                view -> { if (parent == null) dialog.dismiss(); else parent.run(); }));
        container.addView(navigation);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, PanelFrame.PAGE_PADDING_DP, 12);
        TextView heading = Ui.text(activity, t(title), 29);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        content.addView(heading);
        if (scrollable) scroll.addView(content);
        container.addView(scrollable ? scroll : content, new LinearLayout.LayoutParams(-1, 0, 1));
        this.surface = surface;
        nativeContent = container;
        mountLayout();
        dialog.setTitle(t(title));
        if (!dialog.isShowing()) {
            dialog.show();
            if (Build.VERSION.SDK_INT >= 33) {
                dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
            } else {
                dialog.setOnKeyListener((window, key, event) -> {
                    if (key != KeyEvent.KEYCODE_BACK) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) goBack();
                    return true;
                });
            }
        }
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return content;
    }

    PanelFrame.Sections sections(LinearLayout page) { return frame.sections(page); }

    /** Re-arranges picker controls beside or above results as available height and width change. */
    void bindPicker(LinearLayout results, View heading, View search, LinearLayout.LayoutParams fieldParams,
                    boolean[] splitFlag, boolean initialSplit) {
        if (frame != null) frame.bindPicker(navigation, results, heading, search, fieldParams, splitFlag, initialSplit);
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

    BooleanSupplier currentPage() {
        int page = generation;
        return () -> generation == page && !Thread.currentThread().isInterrupted();
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

    TextView failure(LinearLayout box, Exception error, Runnable retry) {
        org.llm4people.hermes.Diagnostics.warning("panel", "operation_failed");
        String message = error instanceof HermesApi.HttpError
                ? t(R.string.panel_http_error, ((HermesApi.HttpError) error).status)
                : t(error instanceof IOException ? R.string.panel_network_error :
                    error instanceof JSONException ? R.string.panel_invalid : R.string.panel_storage_error);
        TextView status = note(box, message);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (retry != null) action(box, R.string.panel_retry, retry);
        return status;
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
        surface = null;
        nativeContent = null;
        frame = null;
        navigation = null;
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
