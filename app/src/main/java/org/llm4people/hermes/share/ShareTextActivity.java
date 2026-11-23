package org.llm4people.hermes.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.profile.IncomingShare;

/** The exported boundary accepts text only. No caller Intent, URI grant, span or return data is forwarded. */
public final class ShareTextActivity extends Activity {
    static final String REVIEW = "org.llm4people.hermes.REVIEW_SHARED_TEXT";
    static final String RECEIPT = "org.llm4people.hermes.SHARE_RECEIPT";

    private String waiting;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrapContext(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        String message;
        try {
            if (state != null) {
                String previous = state.getString(RECEIPT);
                if (previous != null && previous.equals(IncomingShare.pending(this))) busy(previous);
                else unavailable(getString(R.string.share_retry_origin));
                return;
            }
            String text = incoming(getIntent());
            setIntent(new Intent());
            String receipt = IncomingShare.create(this, text);
            if (receipt != null) { review(receipt); return; }
            busy(IncomingShare.pending(this));
            return;
        } catch (IllegalArgumentException rejected) {
            Diagnostics.warning("share", "incoming_rejected", rejected);
            message = getString(R.string.share_text_limit);
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "incoming_unavailable", unavailable);
            message = getString(R.string.share_open_failed);
        }
        unavailable(message);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.clear();
        if (waiting != null) state.putString(RECEIPT, waiting);
    }

    private void busy(String previous) {
        waiting = previous;
        Ui.secureDialog(new AlertDialog.Builder(this).setTitle(R.string.share_pending_title)
                .setMessage(R.string.share_pending_text_body)
                .setPositiveButton(R.string.share_pending_resume, (d,w) -> review(previous))
                .setNegativeButton(R.string.ws_close, (d,w) -> finish()).setOnCancelListener(d -> finish()));
    }

    private void unavailable(String message) {
        setIntent(new Intent());
        Ui.secureDialog(new AlertDialog.Builder(this).setTitle(R.string.share_unavailable_title).setMessage(message)
                .setPositiveButton(R.string.ws_close, (d,w) -> finish()).setOnCancelListener(d -> finish()));
    }

    static String incoming(Intent intent) {
        if (intent == null || !"text/plain".equals(Intent.normalizeMimeType(intent.getType())))
            throw new IllegalArgumentException();
        String extra;
        if (Intent.ACTION_SEND.equals(intent.getAction())) extra = Intent.EXTRA_TEXT;
        else if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) extra = Intent.EXTRA_PROCESS_TEXT;
        else throw new IllegalArgumentException();
        // READONLY governs replacement in the caller. This action never returns replacement text.
        intent.setExtrasClassLoader(null);
        CharSequence input = intent.getCharSequenceExtra(extra);
        if (input == null || input.length() == 0 || input.length() > IncomingShare.MAX_TEXT)
            throw new IllegalArgumentException();
        String text = input.toString();
        if (text.length() > IncomingShare.MAX_TEXT || VisibleText.isBlank(text)) throw new IllegalArgumentException();
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t'
                    || Character.getType(cp) == Character.SURROGATE) throw new IllegalArgumentException();
            i += Character.charCount(cp);
        }
        return text;
    }

    private void review(String id) {
        if (id == null || !id.equals(IncomingShare.pending(this))) {
            unavailable(getString(R.string.share_text_expired));
            return;
        }
        startActivity(new Intent(this, MainActivity.class).setAction(REVIEW).putExtra(RECEIPT, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
