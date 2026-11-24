package org.llm4people.hermes.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.view.WindowManager;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.profile.IncomingMediaShare;
import org.llm4people.hermes.settings.AppThemeMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exported FLAG_SECURE intake boundary for inbound images, PDFs, code/text files, and multi-file shares.
 * Clears untrusted extras ClassLoader, strips EXIF & seals off the UI thread, and forwards only an opaque UUID.
 */
public final class ShareMediaActivity extends Activity {
    public static final String REVIEW_MEDIA = "org.llm4people.hermes.REVIEW_SHARED_MEDIA";
    public static final String MEDIA_RECEIPT = "org.llm4people.hermes.MEDIA_SHARE_RECEIPT";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog activeDialog;
    private String waiting;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeMode.wrapContext(AppLanguage.wrapContext(newBase)));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        try {
            if (state != null) {
                String previous = state.getString(MEDIA_RECEIPT);
                if (previous != null && previous.equals(IncomingMediaShare.pending(this))) {
                    busy(previous);
                } else {
                    unavailable(getString(R.string.share_retry_origin));
                }
                return;
            }
            Intent incoming = getIntent();
            List<Uri> uris = extractUris(this, incoming);
            String declaredMime = incoming == null ? null : incoming.getType();
            if (uris.isEmpty() || uris.size() > IncomingMediaShare.MAX_ITEMS) {
                setIntent(new Intent());
                Diagnostics.warning("share", "media_count_rejected");
                unavailable(getString(R.string.share_media_limit, IncomingMediaShare.MAX_ITEMS));
                return;
            }
            long maxBytes = HermesConfig.load(this).artifactDefaultMaxBytes;
            worker.execute(() -> processOffUiThread(uris, declaredMime, maxBytes));
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "media_unavailable", unavailable);
            unavailable(getString(R.string.share_open_failed));
        }
    }

    private void processOffUiThread(List<Uri> uris, String declaredMime, long maxBytes) {
        try {
            List<CapturedMedia.Item> sanitized = new ArrayList<>(uris.size());
            for (Uri uri : uris) {
                if (Thread.currentThread().isInterrupted()) return;
                sanitized.add(CapturedMedia.readFromUri(getContentResolver(), uri, declaredMime, maxBytes));
            }
            String receipt = IncomingMediaShare.create(this, sanitized);
            if (receipt != null && (Thread.currentThread().isInterrupted() || isFinishing() || isDestroyed())) {
                IncomingMediaShare.discard(this, receipt);
                return;
            }
            String existing = receipt == null ? IncomingMediaShare.pending(this) : null;
            runOnUiThread(() -> {
                setIntent(new Intent());
                if (isFinishing() || isDestroyed()) {
                    if (receipt != null) IncomingMediaShare.discard(this, receipt);
                    return;
                }
                if (receipt != null) review(receipt);
                else busy(existing);
            });
        } catch (IOException | IllegalArgumentException rejected) {
            Diagnostics.warning("share", "media_rejected", rejected);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    unavailable(getString(R.string.share_media_unsupported));
                }
            });
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "media_unavailable", unavailable);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    unavailable(getString(R.string.share_open_failed));
                }
            });
        }
    }

    @Override protected void onDestroy() {
        dismissDialog();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.clear();
        if (waiting != null) state.putString(MEDIA_RECEIPT, waiting);
    }

    static boolean isSelfOwnedAuthority(String packageName, String authority) {
        if (packageName == null || packageName.isEmpty() || authority == null || authority.isEmpty()) {
            return false;
        }
        String host = stripUserAndPort(authority).trim().toLowerCase(Locale.ROOT);
        String pkg = packageName.trim().toLowerCase(Locale.ROOT);
        return !pkg.isEmpty() && (host.equals(pkg) || host.startsWith(pkg + "."));
    }

    private static String stripUserAndPort(String authority) {
        String host = authority;
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host;
    }

    private static boolean isAllowedInboundUri(Context context, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return false;
        String authority = uri.getAuthority();
        if (authority == null || authority.trim().isEmpty()) return false;
        if (context == null) return true;
        if (isSelfOwnedAuthority(context.getPackageName(), authority)) return false;
        PackageManager pm = context.getPackageManager();
        if (pm != null) {
            ProviderInfo info = pm.resolveContentProvider(stripUserAndPort(authority), 0);
            if (info != null && info.applicationInfo != null && info.applicationInfo.uid == Process.myUid()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    static List<Uri> extractUris(Context context, Intent intent) {
        if (intent == null) return Collections.emptyList();
        // Prevent untrusted Parcelable deserialization from third-party callers.
        intent.setExtrasClassLoader(null);
        String action = intent.getAction();
        List<Uri> out = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (isAllowedInboundUri(context, single)) out.add(single);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                for (Uri u : many) {
                    if (isAllowedInboundUri(context, u)) out.add(u);
                }
            }
        }
        return out;
    }

    private void showDialog(AlertDialog.Builder builder) {
        dismissDialog();
        if (isFinishing() || isDestroyed()) return;
        activeDialog = Ui.secureDialog(builder);
    }

    private void dismissDialog() {
        AlertDialog current = activeDialog;
        activeDialog = null;
        if (current != null) {
            try { current.dismiss(); } catch (RuntimeException ignored) {}
        }
    }

    private void busy(String previous) {
        waiting = previous;
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.share_pending_title)
                .setMessage(R.string.share_pending_media_body)
                .setPositiveButton(R.string.share_pending_resume, (d, w) -> review(previous))
                .setNegativeButton(R.string.ws_close, (d, w) -> finish())
                .setOnCancelListener(d -> finish()));
    }

    private void unavailable(String message) {
        setIntent(new Intent());
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.share_unavailable_title)
                .setMessage(message)
                .setPositiveButton(R.string.ws_close, (d, w) -> finish())
                .setOnCancelListener(d -> finish()));
    }

    private void review(String id) {
        if (id == null || !id.equals(IncomingMediaShare.pending(this))) {
            unavailable(getString(R.string.share_media_expired));
            return;
        }
        startActivity(new Intent(this, MainActivity.class)
                .setAction(REVIEW_MEDIA)
                .putExtra(MEDIA_RECEIPT, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
