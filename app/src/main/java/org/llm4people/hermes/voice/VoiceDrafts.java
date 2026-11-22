package org.llm4people.hermes.voice;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.concurrent.atomic.AtomicInteger;

/** Main-thread owner of one microphone and its editable destination across the unlocked workspace. */
public final class VoiceDrafts {
    private static final int SERVICE_TIMEOUT_MS = 15000;
    private static final AtomicInteger permissionIds = new AtomicInteger(20000);
    private final Activity activity;
    private final VoiceInput input;
    private final ReplyReader reader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Attempt active;
    private boolean paused;

    public VoiceDrafts(Activity activity) { this(activity, new AndroidVoiceInput(activity)); }
    VoiceDrafts(Activity activity, VoiceInput input) {
        this.activity = activity;
        this.input = input;
        reader = new ReplyReader(activity, this::cancel);
    }

    public ReplyReader reader() { return reader; }

    public Controls bind(EditText editor) { return new Controls(editor); }

    public final class Controls {
        public final ImageButton button;
        public final LinearLayout feedback;
        private final EditText editor;
        private final TextView status;
        private final Button action;

        private Controls(EditText editor) {
            this.editor = editor;
            button = new ImageButton(activity);
            TypedValue background = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
            button.setBackgroundResource(background.resourceId);
            int size = Ui.dp(activity, 48);
            button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            button.setOnClickListener(v -> pressed());
            feedback = Ui.row(activity);
            Ui.pad(feedback, 8, 0);
            status = Ui.text(activity, "", 14);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            feedback.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
            action = Ui.button(activity, "", null);
            feedback.addView(action);
            editor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) { cancel(); }
            });
            reset();
        }

        private boolean visible() {
            return editor.isAttachedToWindow() && editor.isShown() && editor.isEnabled();
        }

        private void pressed() {
            if (!visible() || paused) return;
            if (active != null && active.target == this) {
                if (active.phase == Phase.LISTENING || active.phase == Phase.STARTING) active.stop();
                else cancel();
            } else {
                try { begin(this, input.preferred()); }
                catch (RuntimeException unavailable) { cancel(); report(R.string.voice_unavailable, 0, null); }
            }
        }

        private void icon(boolean recording, int label) {
            button.setImageResource(recording ? R.drawable.ic_voice_stop : R.drawable.ic_voice);
            button.setContentDescription(activity.getString(label));
            button.setTooltipText(activity.getString(label));
        }

        private void report(int message, int actionLabel, Runnable click) {
            boolean stacked = click != null && actionLabel != R.string.voice_cancel_short;
            feedback.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            feedback.setGravity(stacked ? Gravity.START : Gravity.CENTER_VERTICAL);
            status.setLayoutParams(stacked ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1));
            feedback.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
            status.setText(message == 0 ? "" : activity.getString(message));
            action.setVisibility(click == null ? View.GONE : View.VISIBLE);
            action.setText(click == null ? "" : activity.getString(actionLabel));
            action.setOnClickListener(click == null ? null : v -> click.run());
        }

        private void reset() { icon(false, R.string.voice_dictate); report(0, 0, null); }

        public void cancel() {
            if (active != null && active.target == this) VoiceDrafts.this.cancel();
            else reset();
        }
    }

    private void begin(Controls target, VoiceInput.Source source) {
        cancel();
        if (!target.visible() || paused) return;
        reader.stop();
        if (source == VoiceInput.Source.NONE) {
            target.report(R.string.voice_unavailable, 0, null);
            return;
        }
        Attempt attempt = new Attempt(target, source);
        active = attempt;
        target.icon(true, R.string.voice_cancel);
        if (source == VoiceInput.Source.SYSTEM) {
            target.report(R.string.voice_system_notice, R.string.voice_use_system, attempt::permission);
        } else attempt.permission();
    }

    public void pause() {
        reader.stop();
        paused = true;
        // The system permission sheet pauses the activity. Only its pending grant can survive that pause.
        windowFocusChanged(false);
    }

    public void windowFocusChanged(boolean focused) {
        if (focused) startApprovedRecording();
        else if (active != null && active.phase != Phase.PERMISSION) cancel();
    }
    public void resume() {
        paused = false;
        if (activity.hasWindowFocus()) startApprovedRecording();
    }

    private void startApprovedRecording() {
        if (!paused && active != null && active.phase == Phase.PERMISSION && active.permissionGranted) active.start();
    }

    public boolean permissionResult(int code, String[] permissions, int[] results) {
        Attempt attempt = active;
        if (attempt == null || attempt.phase != Phase.PERMISSION || code != attempt.permissionCode) return false;
        attempt.permissionGranted = permissions.length == 1 && results.length == 1
                && Manifest.permission.RECORD_AUDIO.equals(permissions[0])
                && results[0] == PackageManager.PERMISSION_GRANTED && input.permitted();
        if (!attempt.permissionGranted) attempt.finish(R.string.voice_permission_denied);
        else if (activity.hasWindowFocus()) startApprovedRecording();
        return true;
    }

    public void cancel() {
        Attempt attempt = active;
        if (attempt == null) return;
        active = null;
        main.removeCallbacks(attempt.timeout);
        attempt.target.reset();
        if (attempt.recording != null) attempt.recording.close();
    }

    private enum Phase { CONSENT, PERMISSION, STARTING, LISTENING, PROCESSING }

    private final class Attempt implements VoiceInput.Listener {
        private final Controls target;
        private final VoiceInput.Source source;
        private final Runnable timeout = () -> finish(R.string.voice_failed);
        private Phase phase = Phase.CONSENT;
        private int permissionCode;
        private boolean permissionGranted;
        private VoiceInput.Recording recording;

        Attempt(Controls target, VoiceInput.Source source) {
            this.target = target;
            this.source = source;
        }

        void permission() {
            if (active != this || paused || !target.visible()) return;
            try {
                if (input.permitted()) { start(); return; }
                phase = Phase.PERMISSION;
                permissionCode = permissionIds.getAndUpdate(previous -> previous >= 60000 ? 20000 : previous + 1);
                target.report(R.string.voice_permission_wait, R.string.voice_cancel_short, target::cancel);
                input.requestPermission(permissionCode);
            } catch (RuntimeException unavailable) { finish(R.string.voice_permission_denied); }
        }

        void start() {
            if (active != this || paused || !target.visible()) { finish(0); return; }
            try {
                phase = Phase.STARTING;
                target.icon(true, R.string.voice_finish);
                target.report(R.string.voice_starting, R.string.voice_cancel_short, target::cancel);
                main.postDelayed(timeout, SERVICE_TIMEOUT_MS);
                recording = input.open(source, this);
                recording.start();
            } catch (SecurityException denied) { finish(R.string.voice_permission_denied); }
            catch (RuntimeException unavailable) { finish(R.string.voice_unavailable); }
        }

        void stop() {
            if (active != this || recording == null) return;
            ended();
            try { recording.stop(); }
            catch (RuntimeException unavailable) { finish(R.string.voice_failed); }
        }

        @Override public void ready() {
            if (active != this || phase != Phase.STARTING) return;
            phase = Phase.LISTENING;
            main.removeCallbacks(timeout);
            target.report(R.string.voice_listening, R.string.voice_cancel_short, target::cancel);
        }

        @Override public void ended() {
            if (active != this || phase == Phase.PROCESSING) return;
            phase = Phase.PROCESSING;
            target.icon(true, R.string.voice_cancel);
            target.report(R.string.voice_processing, R.string.voice_cancel_short, target::cancel);
            main.removeCallbacks(timeout);
            main.postDelayed(timeout, SERVICE_TIMEOUT_MS);
        }

        @Override public void result(String transcript) {
            if (active != this) return;
            String phrase = transcript == null ? "" : transcript.trim();
            if (paused || !target.visible()) { finish(0); return; }
            if (phrase.isEmpty()) { finish(R.string.voice_empty); return; }
            // Writing through the field preserves its normal encrypted-draft and validation path.
            finish(R.string.voice_added);
            insert(target.editor, phrase);
        }

        @Override public void error(int reason) {
            if (active != this) return;
            if ((reason == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || reason == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                    && source == VoiceInput.Source.DEVICE && input.systemAvailable()) {
                finish(0);
                target.report(R.string.voice_local_language, R.string.voice_other_service,
                        () -> begin(target, VoiceInput.Source.SYSTEM));
                return;
            }
            finish(switch (reason) {
                case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_empty;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied;
                case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_network;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_language;
                default -> R.string.voice_failed;
            });
        }

        private void finish(int status) {
            if (active != this) return;
            cancel();
            target.report(status, 0, null);
        }
    }

    private static void insert(EditText editor, String phrase) {
        Editable draft = editor.getText();
        int anchor = editor.getSelectionStart(), cursor = editor.getSelectionEnd();
        int start = anchor < 0 || cursor < 0 ? draft.length() : Math.min(anchor, cursor);
        int end = anchor < 0 || cursor < 0 ? draft.length() : Math.max(anchor, cursor);
        String before = start > 0 && wordBoundary(draft.charAt(start - 1), phrase.charAt(0)) ? " " : "";
        String after = end < draft.length() && wordBoundary(phrase.charAt(phrase.length() - 1), draft.charAt(end)) ? " " : "";
        draft.replace(start, end, before + phrase + after);
        editor.setSelection(Math.min(start + before.length() + phrase.length(), draft.length()));
    }

    private static boolean wordBoundary(char left, char right) {
        return (Character.isLetterOrDigit(left) || ".,!?;:)»".indexOf(left) >= 0) && Character.isLetterOrDigit(right);
    }
}
