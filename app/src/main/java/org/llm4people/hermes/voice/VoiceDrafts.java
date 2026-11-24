package org.llm4people.hermes.voice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.os.PermissionCenter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Main-thread owner of one microphone and its editable destination across the unlocked workspace. */
public final class VoiceDrafts {
    private static final String PREFS = "hermes_voice_prefs";
    private static final String KEY_LOCALE = "voice_locale";
    private static final AtomicInteger permissionIds = new AtomicInteger(20000);
    private final Activity activity;
    private final VoiceInput input;
    private final ReplyReader reader;
    private final int serviceTimeoutMs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private VoiceLocale selectedLocale;
    private Attempt active;
    private ModelRecovery recovery;
    private DuplexBridge activeDuplex;
    private AlertDialog optionsDialog;
    private Controls deniedControls;
    private boolean paused;

    public VoiceDrafts(Activity activity) { this(activity, new AndroidVoiceInput(activity)); }

    VoiceDrafts(Activity activity, VoiceInput input) {
        this.activity = activity;
        this.input = input;
        this.serviceTimeoutMs = HermesConfig.load(activity).voiceServiceTimeoutMs;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        selectedLocale = VoiceLocale.fromStorageKey(prefs.getString(KEY_LOCALE, null));
        input.setLocale(selectedLocale);
        reader = new ReplyReader(activity, this::cancel, this::locale);
    }

    public ReplyReader reader() { return reader; }

    public VoiceLocale locale() { return selectedLocale; }

    public void setLocale(VoiceLocale locale) {
        VoiceLocale next = locale == null ? VoiceLocale.SYSTEM : locale;
        if (next == selectedLocale) return;
        selectedLocale = next;
        input.setLocale(next);
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LOCALE, next.storageKey()).apply();
        Diagnostics.event("voice", "locale_changed");
    }

    public Controls bind(EditText editor) { return new Controls(editor); }

    /** Bind an opt-in hands-free duplex voice controller to a composer's Controls. */
    public DuplexHandle bindDuplex(Controls controls, Consumer<String> onSubmitUtterance) {
        return new DuplexBridge(controls, onSubmitUtterance);
    }

    /** Public handle for the opt-in hands-free duplex voice session mounted on the discussion screen. */
    public interface DuplexHandle {
        View bar();
        boolean active();
        DuplexVoiceSession.State state();
        boolean canBargeIn();
        void setBargeInAllowed(boolean allowed);
        void start();
        void stop();
        void bargeIn();
        void onReplyDelta(String cumulativeVisibleText);
        void onReplyComplete(String finalVisibleText);
        void openOptions();
    }

    public final class Controls {
        public final ImageButton button;
        public final LinearLayout feedback;
        public final AudioLevelMeter meter;
        private final EditText editor;
        private final TextView status;
        private final Button action;
        private Runnable longPressOptions;

        private Controls(EditText editor) {
            this.editor = editor;
            button = new ImageButton(activity);
            TypedValue background = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
            button.setBackgroundResource(background.resourceId);
            button.setImageTintList(Ui.enabledDisabledInk(activity, R.color.accent));
            int size = Ui.dp(activity, 48);
            button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            button.setOnClickListener(v -> pressed());
            button.setOnLongClickListener(v -> {
                if (!visible() || paused) return false;
                if (longPressOptions != null) longPressOptions.run();
                else openOptionsDialog(null, false);
                return true;
            });
            feedback = Ui.row(activity);
            Ui.pad(feedback, 8, 0);
            status = Ui.text(activity, "", 14);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            feedback.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
            action = Ui.button(activity, "", null);
            feedback.addView(action);
            meter = new AudioLevelMeter(activity);
            meter.setVisibility(View.GONE);
            LinearLayout.LayoutParams meterParams = new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 22));
            meterParams.setMarginStart(Ui.dp(activity, 6));
            feedback.addView(meter, meterParams);
            editor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) { cancel(); }
            });
            reset();
        }

        void setLongPressOptions(Runnable options) { this.longPressOptions = options; }

        private boolean visible() {
            return editor.isAttachedToWindow() && editor.isShown() && editor.isEnabled();
        }

        private void pressed() {
            if (!visible() || paused) return;
            if (activeDuplex != null && activeDuplex.controls == this && activeDuplex.active()) {
                if (activeDuplex.canBargeIn()) {
                    activeDuplex.bargeIn();
                } else {
                    activeDuplex.stop();
                }
                return;
            }
            if (active != null && active.target == this) {
                if (active.phase == Phase.LISTENING || active.phase == Phase.STARTING) active.stop();
                else cancel();
            } else {
                try { begin(this, input.preferred(), null, -1); }
                catch (RuntimeException unavailable) {
                    Diagnostics.warning("voice", "dictation_begin_failed", unavailable);
                    cancel();
                    report(R.string.voice_unavailable, 0, null);
                }
            }
        }

        private void icon(boolean recording, int label) {
            button.setImageResource(recording ? R.drawable.ic_voice_stop : R.drawable.ic_voice);
            button.setContentDescription(activity.getString(label));
            button.setTooltipText(activity.getString(label));
        }

        private void report(int message, int actionLabel, Runnable click) {
            status.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
            boolean stacked = click != null && actionLabel != R.string.voice_cancel_short;
            feedback.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            feedback.setGravity(stacked ? Gravity.START : Gravity.CENTER_VERTICAL);
            status.setLayoutParams(stacked ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1));
            feedback.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
            status.setText(message == 0 ? "" : activity.getString(message));
            action.setAccessibilityLiveRegion(message == 0 && click != null
                    ? View.ACCESSIBILITY_LIVE_REGION_POLITE : View.ACCESSIBILITY_LIVE_REGION_NONE);
            action.setVisibility(click == null ? View.GONE : View.VISIBLE);
            action.setText(click == null ? "" : activity.getString(actionLabel));
            action.setOnClickListener(click == null ? null : v -> click.run());
        }

        private void showMeter(boolean listening) {
            meter.setActive(listening);
            meter.setVisibility(listening ? View.VISIBLE : View.GONE);
        }

        private void modelAction(int label, Runnable click) {
            report(0, label, click);
            showMeter(false);
            feedback.setVisibility(View.VISIBLE);
        }

        private void reset() {
            if (deniedControls == this) deniedControls = null;
            icon(false, R.string.voice_dictate);
            showMeter(false);
            report(0, 0, null);
        }

        public void cancel() {
            if (activeDuplex != null && activeDuplex.controls == this) activeDuplex.stop();
            if ((active != null && active.target == this) || (recovery != null && recovery.target == this))
                VoiceDrafts.this.cancel();
            else reset();
        }
    }

    /** Plain-French dialog exposing voice locale selection and explicit opt-in hands-free duplex toggle. */
    public void openOptionsDialog(Runnable toggleDuplex, boolean duplexActive) {
        if (optionsDialog != null) optionsDialog.dismiss();
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 20, 10);

        TextView localeHeading = Ui.text(activity, activity.getString(R.string.voice_locale_section), 15);
        localeHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(localeHeading);

        VoiceLocale[] locales = VoiceLocale.values();
        for (int i = 0; i < locales.length; i++) {
            VoiceLocale option = locales[i];
            boolean current = option == selectedLocale;
            String label = activity.getString(option.labelRes);
            Button choice = Ui.button(activity, label, v -> {
                setLocale(option);
                if (optionsDialog != null) optionsDialog.dismiss();
            });
            choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            choice.setSelected(current);
            choice.setTextColor(activity.getColor(current ? R.color.accent : R.color.ink));
            choice.setBackground(Ui.roundedRipple(activity, Ui.BUTTON_CORNER_DP,
                    current ? Ui.shape(activity, Ui.BUTTON_CORNER_DP, R.color.accent_surface, R.color.accent, 1)
                            : Ui.shape(activity, Ui.BUTTON_CORNER_DP, R.color.surface, R.color.line, 1)));
            Ui.pad(choice, 14, 10);
            if (current) choice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(-1, -2);
            choiceLp.topMargin = Ui.dp(activity, i == 0 ? 8 : 6);
            content.addView(choice, choiceLp);
        }

        if (toggleDuplex != null) {
            View divider = Ui.divider(activity);
            LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
            divLp.topMargin = Ui.dp(activity, 12);
            divLp.bottomMargin = Ui.dp(activity, 8);
            content.addView(divider, divLp);
            TextView hint = Ui.text(activity, activity.getString(R.string.voice_duplex_hint), 14);
            hint.setTextColor(activity.getColor(R.color.muted));
            Ui.pad(hint, 0, 4);
            content.addView(hint);
            Button duplexBtn = Ui.tonalButton(activity,
                    activity.getString(duplexActive ? R.string.voice_duplex_stop : R.string.voice_duplex_start),
                    v -> {
                        if (optionsDialog != null) optionsDialog.dismiss();
                        toggleDuplex.run();
                    });
            LinearLayout.LayoutParams dupLp = new LinearLayout.LayoutParams(-1, -2);
            dupLp.topMargin = Ui.dp(activity, 8);
            content.addView(duplexBtn, dupLp);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        optionsDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.voice_options_title)
                .setView(scroll)
                .setNegativeButton(R.string.ws_close, null));
    }

    private void begin(Controls target, VoiceInput.Source source, DuplexBridge duplex, int duplexGen) {
        if (activeDuplex != null && (duplex == null || activeDuplex != duplex)) {
            activeDuplex.stop();
        }
        cancelAttemptAndRecovery();
        if (!target.visible() || paused) {
            if (duplex != null) duplex.session.onListenError(duplexGen, false);
            return;
        }
        reader.stop();
        if (source == VoiceInput.Source.NONE) {
            target.report(R.string.voice_unavailable, 0, null);
            if (duplex != null) duplex.session.onListenError(duplexGen, false);
            return;
        }
        Attempt attempt = new Attempt(target, source, duplex, duplexGen);
        active = attempt;
        target.icon(true, R.string.voice_cancel);
        if (source == VoiceInput.Source.SYSTEM) {
            target.report(R.string.voice_system_notice, R.string.voice_use_system, attempt::permission);
        } else attempt.permission();
    }

    public void pause() {
        paused = true;
        boolean awaitingDuplexPermission = active != null
                && active.phase == Phase.PERMISSION
                && active.duplex != null
                && active.duplex == activeDuplex;
        if (activeDuplex != null && !awaitingDuplexPermission) activeDuplex.stop();
        reader.stop();
        if (optionsDialog != null) { optionsDialog.dismiss(); optionsDialog = null; }
        if (recovery != null) recovery.dismiss();
        // The system permission sheet pauses the activity. Only its pending grant can survive that pause.
        windowFocusChanged(false);
    }

    public void windowFocusChanged(boolean focused) {
        if (focused) startApprovedRecording();
        else if (active != null && active.phase != Phase.PERMISSION) cancel();
    }

    public void resume() {
        paused = false;
        if (deniedControls != null && input.permitted()) {
            Controls stale = deniedControls;
            deniedControls = null;
            stale.reset();
        }
        if (activity.hasWindowFocus()) startApprovedRecording();
    }

    private void startApprovedRecording() {
        if (!paused && active != null && active.phase == Phase.PERMISSION && active.permissionGranted) active.start();
    }

    public boolean permissionResult(int code, String[] permissions, int[] results) {
        Attempt attempt = active;
        if (attempt == null || attempt.phase != Phase.PERMISSION || code != attempt.permissionCode) return false;
        PermissionCenter.recordCompletedPrompt(activity, PermissionCenter.Key.MICROPHONE, results);
        attempt.permissionGranted = permissions != null && results != null
                && permissions.length == 1 && results.length == 1
                && Manifest.permission.RECORD_AUDIO.equals(permissions[0])
                && results[0] == PackageManager.PERMISSION_GRANTED && input.permitted();
        if (!attempt.permissionGranted) {
            DuplexBridge d = attempt.duplex;
            int g = attempt.duplexGen;
            attempt.finishPermissionDenied();
            if (d != null) d.session.onListenError(g, false);
        } else if (activity.hasWindowFocus()) startApprovedRecording();
        return true;
    }

    private void reportPermissionDenied(Controls target, VoiceInput.Source source) {
        deniedControls = target;
        PermissionCenter.State state = PermissionCenter.state(activity, PermissionCenter.Key.MICROPHONE);
        if (state == PermissionCenter.State.PERMANENTLY_DENIED) {
            target.report(R.string.voice_permission_denied, R.string.perm_action_open_settings,
                    () -> PermissionCenter.openAppSettings(activity));
        } else {
            target.report(R.string.voice_permission_denied, R.string.perm_action_allow_microphone,
                    () -> begin(target, source, null, -1));
        }
    }

    public void cancel() {
        if (activeDuplex != null) activeDuplex.stop();
        if (optionsDialog != null) { optionsDialog.dismiss(); optionsDialog = null; }
        if (deniedControls != null) {
            Controls stale = deniedControls;
            deniedControls = null;
            stale.reset();
        }
        cancelAttemptAndRecovery();
    }

    private void cancelAttemptAndRecovery() {
        if (recovery != null) {
            ModelRecovery previous = recovery;
            recovery = null;
            main.removeCallbacks(previous.timeout);
            previous.dismiss();
            previous.model.close();
            previous.target.reset();
        }
        Attempt attempt = active;
        if (attempt == null) return;
        active = null;
        main.removeCallbacks(attempt.timeout);
        attempt.clearPartial();
        attempt.target.reset();
        if (attempt.recording != null) attempt.recording.close();
    }

    private void recoverLanguage(Controls target) {
        cancel();
        if (!target.visible() || paused) return;
        try {
            VoiceModel model = input.localModel();
            if (model != null) {
                recovery = new ModelRecovery(target, model);
                target.report(R.string.voice_model_checking, 0, null);
                main.postDelayed(recovery.timeout, serviceTimeoutMs);
                model.check(recovery::update);
                return;
            }
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("voice", "model_recovery_failed", unavailable);
            cancel();
        }
        languageFallback(target);
    }

    private void languageFallback(Controls target) {
        if (input.systemAvailable()) {
            target.report(R.string.voice_local_language, R.string.voice_other_service,
                    () -> begin(target, VoiceInput.Source.SYSTEM, null, -1));
        } else target.report(R.string.voice_language, 0, null);
    }

    private final class DuplexBridge implements DuplexHandle, DuplexVoiceSession.Callbacks {
        final Controls controls;
        final Consumer<String> onSubmit;
        final DuplexVoiceSession session;
        final LinearLayout bar;
        final AudioLevelMeter meter;
        final TextView stateLabel;
        final Button bargeInButton;
        final Button quitButton;

        DuplexBridge(Controls controls, Consumer<String> onSubmit) {
            this.controls = controls;
            this.onSubmit = onSubmit;
            this.session = new DuplexVoiceSession(this);
            controls.setLongPressOptions(this::openOptions);

            bar = Ui.row(activity);
            Ui.pad(bar, 10, 4);
            bar.setBackgroundColor(activity.getColor(R.color.accent_surface));
            bar.setVisibility(View.GONE);

            meter = new AudioLevelMeter(activity);
            LinearLayout.LayoutParams meterParams = new LinearLayout.LayoutParams(Ui.dp(activity, 44), Ui.dp(activity, 20));
            meterParams.setMarginEnd(Ui.dp(activity, 8));
            bar.addView(meter, meterParams);

            stateLabel = Ui.text(activity, "", 14);
            stateLabel.setTextColor(activity.getColor(R.color.ink));
            stateLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            bar.addView(stateLabel, new LinearLayout.LayoutParams(0, -2, 1));

            bargeInButton = Ui.button(activity, activity.getString(R.string.voice_duplex_barge_in), v -> bargeIn());
            bargeInButton.setContentDescription(activity.getString(R.string.voice_duplex_barge_in_label));
            LinearLayout.LayoutParams bargeLp = new LinearLayout.LayoutParams(-2, -2);
            bargeLp.setMarginStart(Ui.dp(activity, 6));
            bar.addView(bargeInButton, bargeLp);

            quitButton = Ui.button(activity, activity.getString(R.string.voice_duplex_quit), v -> stop());
            LinearLayout.LayoutParams quitLp = new LinearLayout.LayoutParams(-2, -2);
            quitLp.setMarginStart(Ui.dp(activity, 6));
            bar.addView(quitButton, quitLp);
        }

        @Override public View bar() { return bar; }
        @Override public boolean active() { return session.active(); }
        @Override public DuplexVoiceSession.State state() { return session.state(); }
        @Override public boolean canBargeIn() { return session.canBargeIn(); }

        @Override public void setBargeInAllowed(boolean allowed) {
            session.setBargeInAllowed(allowed);
            updateBargeInVisibility();
        }

        private void updateBargeInVisibility() {
            bargeInButton.setVisibility(session.canBargeIn() ? View.VISIBLE : View.GONE);
        }

        @Override public void start() {
            if (paused || !controls.visible()) return;
            if (activeDuplex != null && activeDuplex != this) activeDuplex.stop();
            activeDuplex = this;
            Diagnostics.event("voice", "duplex_started");
            session.start();
        }

        @Override public void stop() {
            if (activeDuplex == this) activeDuplex = null;
            session.stop();
            Diagnostics.event("voice", "duplex_stopped");
        }

        @Override public void bargeIn() {
            if (!session.canBargeIn()) return;
            Diagnostics.event("voice", "duplex_barge_in");
            session.bargeIn();
        }

        @Override public void onReplyDelta(String cumulativeVisibleText) {
            session.onReplyDelta(cumulativeVisibleText);
        }

        @Override public void onReplyComplete(String finalVisibleText) {
            session.onReplyComplete(finalVisibleText);
        }

        @Override public void openOptions() {
            openOptionsDialog(() -> {
                if (active()) stop();
                else start();
            }, active());
        }

        @Override public void startListening(int gen) {
            begin(controls, input.preferred(), this, gen);
        }

        @Override public void stopListening() {
            cancelAttemptAndRecovery();
        }

        @Override public void submitUtterance(String utterance) {
            if (paused || !controls.visible()) { stop(); return; }
            onSubmit.accept(utterance);
        }

        @Override public void speakSentence(String sentence, int gen) {
            reader.speakSentence(sentence, ok -> session.onSentenceDone(gen, ok));
        }

        @Override public void stopSpeaking() {
            reader.stop();
        }

        @Override public void stateChanged(DuplexVoiceSession.State next) {
            boolean show = next != DuplexVoiceSession.State.OFF;
            bar.setVisibility(show ? View.VISIBLE : View.GONE);
            meter.setActive(next == DuplexVoiceSession.State.LISTENING);
            updateBargeInVisibility();
            int labelRes = switch (next) {
                case LISTENING -> R.string.voice_duplex_listening;
                case THINKING -> R.string.voice_duplex_thinking;
                case SPEAKING -> R.string.voice_duplex_speaking;
                case OFF -> 0;
            };
            stateLabel.setText(labelRes == 0 ? "" : activity.getString(labelRes));
            if (next == DuplexVoiceSession.State.OFF && activeDuplex == this) activeDuplex = null;
        }
    }

    private final class ModelRecovery {
        final Controls target;
        final VoiceModel model;
        final Runnable timeout = () -> update(VoiceModel.State.UNKNOWN);
        VoiceModel.State displayed;
        AlertDialog dialog;

        ModelRecovery(Controls target, VoiceModel model) { this.target = target; this.model = model; }

        void update(VoiceModel.State state) {
            if (recovery != this) return;
            main.removeCallbacks(timeout);
            if (!target.visible()) { cancel(); return; }
            if (displayed == state) return;
            displayed = state;
            dismiss();
            switch (state) {
                case OFFERED:
                    options(R.string.voice_model_options, R.string.voice_model_offer, R.string.voice_model_download, this::download);
                    break;
                case PENDING:
                    options(R.string.voice_model_progress, R.string.voice_model_pending, R.string.voice_model_check, () -> recoverLanguage(target));
                    break;
                case REQUESTED:
                    options(R.string.voice_model_follow, R.string.voice_model_requested, R.string.voice_model_check, () -> recoverLanguage(target));
                    break;
                case READY:
                    cancel();
                    target.report(R.string.voice_model_ready, 0, null);
                    break;
                case FAILED:
                    options(R.string.voice_model_retry, R.string.voice_model_failed, R.string.voice_model_check, () -> recoverLanguage(target));
                    break;
                case UNKNOWN:
                    cancel();
                    languageFallback(target);
                    break;
            }
        }

        void options(int compactLabel, int title, int action, Runnable select) {
            target.modelAction(compactLabel, () -> {
                if (recovery != this || paused || !target.visible()) return;
                boolean system = input.systemAvailable();
                String first = activity.getString(action);
                String[] choices = system ? new String[]{first, activity.getString(R.string.voice_other_service)}
                        : new String[]{first};
                dismiss();
                dialog = Ui.secureDialog(new AlertDialog.Builder(activity).setTitle(title)
                        .setItems(choices, (view, which) -> {
                            if (recovery != this || paused || !target.visible()) return;
                            if (which == 0) select.run();
                            else begin(target, VoiceInput.Source.SYSTEM, null, -1);
                        }).setNegativeButton(R.string.voice_cancel_short, null));
            });
        }

        void dismiss() { if (dialog != null) dialog.dismiss(); dialog = null; }

        void download() {
            if (recovery != this || paused || !target.visible()) return;
            update(VoiceModel.State.REQUESTED);
            try { model.download(this::update); }
            catch (RuntimeException unavailable) {
                Diagnostics.warning("voice", "model_download_failed", unavailable);
                update(VoiceModel.State.FAILED);
            }
        }
    }

    private enum Phase { CONSENT, PERMISSION, STARTING, LISTENING, PROCESSING }

    private final class Attempt implements VoiceInput.Listener {
        private final Controls target;
        private final VoiceInput.Source source;
        private final DuplexBridge duplex;
        private final int duplexGen;
        private final Runnable timeout;
        private Phase phase = Phase.CONSENT;
        private int permissionCode;
        private boolean permissionGranted;
        private VoiceInput.Recording recording;
        private int partialStart = -1;
        private int partialEnd = -1;
        private String lastPartial = "";
        private UnderlineSpan partialUnderline;
        private ForegroundColorSpan partialInk;

        Attempt(Controls target, VoiceInput.Source source, DuplexBridge duplex, int duplexGen) {
            this.target = target;
            this.source = source;
            this.duplex = duplex;
            this.duplexGen = duplexGen;
            this.timeout = () -> {
                finish(R.string.voice_failed);
                if (duplex != null) duplex.session.onListenError(duplexGen, true);
            };
        }

        void permission() {
            if (active != this) return;
            if (paused || !target.visible()) {
                abortForUnavailableTarget();
                return;
            }
            try {
                if (input.permitted()) { start(); return; }
                phase = Phase.PERMISSION;
                permissionCode = permissionIds.getAndUpdate(previous -> previous >= 60000 ? 20000 : previous + 1);
                target.report(R.string.voice_permission_wait, R.string.voice_cancel_short, target::cancel);
                input.requestPermission(permissionCode);
            } catch (RuntimeException unavailable) {
                Diagnostics.warning("voice", "permission_request_failed", unavailable);
                finishPermissionDenied();
                if (duplex != null) duplex.session.onListenError(duplexGen, false);
            }
        }

        void start() {
            if (active != this) return;
            if (paused || !target.visible()) {
                abortForUnavailableTarget();
                return;
            }
            try {
                phase = Phase.STARTING;
                target.icon(true, R.string.voice_finish);
                target.report(R.string.voice_starting, R.string.voice_cancel_short, target::cancel);
                main.postDelayed(timeout, serviceTimeoutMs);
                recording = input.open(source, this);
                recording.start();
            } catch (SecurityException denied) {
                Diagnostics.warning("voice", "recording_permission_denied", denied);
                finishPermissionDenied();
                if (duplex != null) duplex.session.onListenError(duplexGen, false);
            } catch (RuntimeException unavailable) {
                Diagnostics.warning("voice", "recording_start_failed", unavailable);
                finish(R.string.voice_unavailable);
                if (duplex != null) duplex.session.onListenError(duplexGen, false);
            }
        }

        void stop() {
            if (active != this || recording == null) return;
            ended();
            try { recording.stop(); }
            catch (RuntimeException unavailable) {
                Diagnostics.warning("voice", "recording_stop_failed", unavailable);
                finish(R.string.voice_failed);
            }
        }

        @Override public void ready() {
            if (active != this || phase != Phase.STARTING) return;
            phase = Phase.LISTENING;
            main.removeCallbacks(timeout);
            target.report(R.string.voice_listening, R.string.voice_cancel_short, target::cancel);
            target.showMeter(true);
        }

        @Override public void rms(float rmsdB) {
            if (active != this || phase != Phase.LISTENING) return;
            target.meter.setRms(rmsdB);
            if (duplex != null) duplex.meter.setRms(rmsdB);
        }

        @Override public void partial(String transcript) {
            if (active != this || phase != Phase.LISTENING || paused || !target.visible()) return;
            String cleaned = transcript == null ? "" : transcript.trim();
            if (cleaned.isEmpty()) return;
            Editable draft = target.editor.getText();
            if (partialValid(draft)) {
                removePartialSpans(draft);
                String formatted = formatInsertion(draft, partialStart, partialEnd, cleaned);
                draft.replace(partialStart, partialEnd, formatted);
                partialEnd = partialStart + formatted.length();
                lastPartial = formatted;
            } else {
                int anchor = target.editor.getSelectionStart(), cursor = target.editor.getSelectionEnd();
                int start = anchor < 0 || cursor < 0 ? draft.length() : Math.min(anchor, cursor);
                int end = anchor < 0 || cursor < 0 ? draft.length() : Math.max(anchor, cursor);
                String formatted = formatInsertion(draft, start, end, cleaned);
                draft.replace(start, end, formatted);
                partialStart = start;
                partialEnd = start + formatted.length();
                lastPartial = formatted;
            }
            partialUnderline = new UnderlineSpan();
            partialInk = new ForegroundColorSpan(activity.getColor(R.color.muted));
            draft.setSpan(partialUnderline, partialStart, partialEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            draft.setSpan(partialInk, partialStart, partialEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            target.editor.setSelection(Math.min(partialEnd, draft.length()));
        }

        @Override public void ended() {
            if (active != this || phase == Phase.PROCESSING) return;
            phase = Phase.PROCESSING;
            target.showMeter(false);
            target.icon(true, R.string.voice_cancel);
            target.report(R.string.voice_processing, R.string.voice_cancel_short, target::cancel);
            main.removeCallbacks(timeout);
            main.postDelayed(timeout, serviceTimeoutMs);
        }

        @Override public void result(String transcript) {
            if (active != this) return;
            String phrase = transcript == null ? "" : transcript.trim();
            if (paused || !target.visible()) {
                abortForUnavailableTarget();
                return;
            }
            if (phrase.isEmpty()) {
                DuplexBridge d = duplex;
                int g = duplexGen;
                finish(R.string.voice_empty);
                if (d != null) d.session.onTranscriptFinal(g, "");
                return;
            }
            if (duplex != null && duplex.active()) {
                DuplexBridge d = duplex;
                int g = duplexGen;
                finish(0);
                d.session.onTranscriptFinal(g, phrase);
                return;
            }
            // Replace provisional partial cleanly if still intact; otherwise insert at current selection.
            Editable draft = target.editor.getText();
            boolean hadPartial = partialValid(draft);
            int replaceStart = partialStart;
            int replaceEnd = partialEnd;
            clearPartialStateOnly(draft);
            finish(R.string.voice_added);
            if (hadPartial) {
                replaceAt(target.editor, replaceStart, replaceEnd, phrase);
            } else {
                insert(target.editor, phrase);
            }
        }

        @Override public void error(int reason) {
            if (active != this) return;
            if ((reason == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || reason == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                    && source == VoiceInput.Source.DEVICE) {
                finish(0);
                if (duplex != null) duplex.session.onListenError(duplexGen, false);
                recoverLanguage(target);
                return;
            }
            boolean recoverableSilence = reason == SpeechRecognizer.ERROR_NO_MATCH
                    || reason == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            DuplexBridge d = duplex;
            int g = duplexGen;
            if (reason == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                finishPermissionDenied();
            } else {
                finish(switch (reason) {
                    case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_empty;
                    case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_network;
                    case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_language;
                    default -> R.string.voice_failed;
                });
            }
            if (d != null) d.session.onListenError(g, recoverableSilence);
        }

        private boolean partialValid(Editable draft) {
            if (partialStart < 0 || partialEnd < partialStart || partialEnd > draft.length()) return false;
            return lastPartial.equals(draft.subSequence(partialStart, partialEnd).toString());
        }

        private void removePartialSpans(Editable draft) {
            if (partialUnderline != null) draft.removeSpan(partialUnderline);
            if (partialInk != null) draft.removeSpan(partialInk);
            partialUnderline = null;
            partialInk = null;
        }

        private void clearPartialStateOnly(Editable draft) {
            removePartialSpans(draft);
            partialStart = -1;
            partialEnd = -1;
            lastPartial = "";
        }

        void clearPartial() {
            Editable draft = target.editor.getText();
            if (partialValid(draft)) {
                removePartialSpans(draft);
                draft.delete(partialStart, partialEnd);
            } else {
                removePartialSpans(draft);
            }
            partialStart = -1;
            partialEnd = -1;
            lastPartial = "";
        }

        private void abortForUnavailableTarget() {
            DuplexBridge d = duplex;
            int g = duplexGen;
            finish(0);
            if (d != null) d.session.onListenError(g, false);
        }

        private void finish(int status) {
            if (active != this) return;
            cancelAttemptAndRecovery();
            target.report(status, 0, null);
        }

        private void finishPermissionDenied() {
            if (active != this) return;
            cancelAttemptAndRecovery();
            reportPermissionDenied(target, source);
        }
    }

    private static String formatInsertion(Editable draft, int start, int end, String phrase) {
        String before = start > 0 && wordBoundary(draft.charAt(start - 1), phrase.charAt(0)) ? " " : "";
        String after = end < draft.length() && wordBoundary(phrase.charAt(phrase.length() - 1), draft.charAt(end)) ? " " : "";
        return before + phrase + after;
    }

    private static void replaceAt(EditText editor, int start, int end, String phrase) {
        Editable draft = editor.getText();
        String before = start > 0 && wordBoundary(draft.charAt(start - 1), phrase.charAt(0)) ? " " : "";
        String after = end < draft.length() && wordBoundary(phrase.charAt(phrase.length() - 1), draft.charAt(end)) ? " " : "";
        draft.replace(start, end, before + phrase + after);
        editor.setSelection(Math.min(start + before.length() + phrase.length(), draft.length()));
    }

    private static void insert(EditText editor, String phrase) {
        Editable draft = editor.getText();
        int anchor = editor.getSelectionStart(), cursor = editor.getSelectionEnd();
        int start = anchor < 0 || cursor < 0 ? draft.length() : Math.min(anchor, cursor);
        int end = anchor < 0 || cursor < 0 ? draft.length() : Math.max(anchor, cursor);
        replaceAt(editor, start, end, phrase);
    }

    private static boolean wordBoundary(char left, char right) {
        return (Character.isLetterOrDigit(left) || ".,!?;:)»".indexOf(left) >= 0) && Character.isLetterOrDigit(right);
    }
}
