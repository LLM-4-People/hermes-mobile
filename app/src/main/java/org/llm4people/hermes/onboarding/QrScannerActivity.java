package org.llm4people.hermes.onboarding;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.os.PermissionCenter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-dependency native Camera2 QR scanner for Hermes pairing links.
 * Features:
 * <ul>
 *   <li>{@code FLAG_SECURE} window and {@code android:exported="false"} in the manifest.</li>
 *   <li>Fail-closed {@code CAMERA} permission UX: requested only after the user taps "Scan QR code",
 *       with a 1-tap "Choose QR image" ({@code ACTION_OPEN_DOCUMENT}) fallback when camera access is
 *       denied or unavailable.</li>
 *   <li>Zero frames written to disk and zero secrets logged; only validated Hermes pairing payloads
 *       are returned via {@link #EXTRA_SCANNED_LINK}.</li>
 * </ul>
 */
public final class QrScannerActivity extends Activity {
    public static final int REQUEST_CODE = 4210;
    public static final String EXTRA_SCANNED_LINK = "org.llm4people.hermes.extra.SCANNED_LINK";

    private static final int REQ_CAMERA_PERMISSION = 4211;
    private static final int REQ_PICK_IMAGE = 4212;
    private static final int MAX_PREVIEW_EDGE = 1920;
    private static final int MAX_ANALYSIS_EDGE = 960;
    private static final int MAX_PICKED_IMAGE_EDGE = 1280;
    private static final long MIN_FRAME_INTERVAL_MS = 120L;
    private static final long HINT_THROTTLE_MS = 2000L;

    public enum ScanVerdict {
        VALID_HERMES,
        NOT_HERMES,
        NO_QR
    }

    /** Pure-Java evaluation result for a decoded QR string (host-JVM testable). */
    public record ScanEvaluation(
            ScanVerdict verdict,
            String rawText,
            PairingLinkParser.ParsedInput parsed) {}

    /** Pure-Java aspect-preserving center-crop surface dimensions (host-JVM testable). */
    public record PreviewLayout(int width, int height) {}

    /** Returns true when camera buffer axes must be swapped relative to display orientation. */
    public static boolean shouldSwapAxes(int sensorOrientationDeg, int displayRotationDeg) {
        int relative = Math.floorMod(sensorOrientationDeg - displayRotationDeg, 360);
        return relative == 90 || relative == 270;
    }

    /**
     * Pure-Java center-crop layout solver guaranteeing zero preview distortion and full viewport coverage:
     * {@code width >= containerW && height >= containerH} with aspect ratio matching the oriented buffer.
     */
    public static PreviewLayout computeCenterCropLayout(
            int containerW, int containerH, int bufferW, int bufferH, boolean swapAxes) {
        if (containerW <= 0 || containerH <= 0 || bufferW <= 0 || bufferH <= 0) {
            return new PreviewLayout(Math.max(1, containerW), Math.max(1, containerH));
        }
        double orientedW = swapAxes ? bufferH : bufferW;
        double orientedH = swapAxes ? bufferW : bufferH;
        double scale = Math.max((double) containerW / orientedW, (double) containerH / orientedH);
        int layoutW = Math.max(containerW, (int) Math.round(orientedW * scale));
        int layoutH = Math.max(containerH, (int) Math.round(orientedH * scale));
        return new PreviewLayout(layoutW, layoutH);
    }

    /**
     * Pure-Java camera stream size selector strictly preferring valid candidates within
     * {@code maxLongEdge} before falling back to oversized candidates, optimizing closest oriented
     * aspect ratio to {@code targetOrientedAspect} and then sharper resolution. Returns {@code null}
     * when no positive valid size entry exists.
     */
    public static int[] chooseBestSize(
            int[][] candidates, int maxLongEdge, double targetOrientedAspect, boolean swapAxes) {
        if (candidates == null || candidates.length == 0) return null;
        double safeAspect = (Double.isFinite(targetOrientedAspect) && targetOrientedAspect > 0.0)
                ? targetOrientedAspect : (9.0 / 16.0);
        int safeMaxEdge = Math.max(240, maxLongEdge);
        int[] bestWithin = null;
        double bestWithinScore = Double.POSITIVE_INFINITY;
        int[] bestOversized = null;
        double bestOversizedScore = Double.POSITIVE_INFINITY;
        int[] firstValid = null;
        for (int[] c : candidates) {
            if (c == null || c.length < 2 || c[0] <= 0 || c[1] <= 0) continue;
            if (firstValid == null) firstValid = c;
            int minDim = Math.min(c[0], c[1]);
            int maxDim = Math.max(c[0], c[1]);
            if (minDim < 240) continue;
            double orientedW = swapAxes ? c[1] : c[0];
            double orientedH = swapAxes ? c[0] : c[1];
            double aspectDiff = Math.abs((orientedW / orientedH) - safeAspect);
            if (maxDim <= safeMaxEdge) {
                double edgePenalty = (double) (safeMaxEdge - maxDim) / (safeMaxEdge * 20.0);
                double score = aspectDiff * 100.0 + edgePenalty;
                if (score < bestWithinScore) {
                    bestWithinScore = score;
                    bestWithin = c;
                }
            } else {
                double edgePenalty = (double) (maxDim - safeMaxEdge) / 1000.0;
                double score = aspectDiff * 100.0 + edgePenalty;
                if (score < bestOversizedScore) {
                    bestOversizedScore = score;
                    bestOversized = c;
                }
            }
        }
        int[] chosen = bestWithin != null ? bestWithin : (bestOversized != null ? bestOversized : firstValid);
        return chosen != null ? new int[]{chosen[0], chosen[1]} : null;
    }

    /** Launch the native QR scanner for result from {@code activity}. */
    public static void launch(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.startActivityForResult(new Intent(activity, QrScannerActivity.class), REQUEST_CODE);
    }

    /** Pure-Java fail-closed filter ensuring only recognized Hermes pairing payloads are accepted. */
    public static ScanEvaluation evaluateDecodedText(String decoded) {
        if (decoded == null || decoded.trim().isEmpty()) {
            return new ScanEvaluation(ScanVerdict.NO_QR, "", null);
        }
        String trimmed = decoded.trim();
        PairingLinkParser.ParsedInput parsed = PairingLinkParser.parseOrNull(trimmed);
        if (parsed == null) {
            return new ScanEvaluation(ScanVerdict.NOT_HERMES, "", null);
        }
        return new ScanEvaluation(ScanVerdict.VALID_HERMES, trimmed, parsed);
    }

    /** Fail-closed helper extracting a validated {@link PairingLinkParser.ParsedInput} from activity result. */
    public static PairingLinkParser.ParsedInput extractScannedPairing(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return null;
        String raw = data.getStringExtra(EXTRA_SCANNED_LINK);
        return raw == null ? null : PairingLinkParser.parseOrNull(raw);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object cameraLock = new Object();
    private final AtomicBoolean decodingFrame = new AtomicBoolean(false);
    private final AtomicBoolean resultDelivered = new AtomicBoolean(false);
    private final ExecutorService imageIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-qr-image");
        t.setDaemon(true);
        return t;
    });

    private FrameLayout previewContainer;
    private SurfaceView previewView;
    private ViewfinderOverlay overlayView;
    private LinearLayout fallbackCard;
    private TextView fallbackTitle;
    private TextView fallbackDetail;
    private Button fallbackPermBtn;
    private TextView statusText;
    private Button torchButton;
    private LinearLayout topBar;
    private LinearLayout bottomBar;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private CaptureRequest.Builder previewRequestBuilder;

    private Size previewSize;
    private Size analysisSize;
    private int sensorOrientationDeg = 90;
    private int cameraEpoch;
    private boolean surfaceReady;
    private boolean cameraOpening;
    private boolean permissionRequested;
    private boolean flashAvailable;
    private boolean torchOn;
    private long lastAnalyzeElapsedMs;
    private long lastHintElapsedMs;

    private static final String STATE_PERMISSION_REQUESTED = "perm_req";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            permissionRequested = savedInstanceState.getBoolean(STATE_PERMISSION_REQUESTED, false);
        }
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        Diagnostics.event("qr_scanner", "opened");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permissionRequested);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1112);

        previewContainer = new FrameLayout(this);
        previewContainer.setClipChildren(true);
        previewContainer.setClipToPadding(true);
        previewContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                previewView.post(this::applyPreviewLayout);
            }
        });

        previewView = new SurfaceView(this);
        previewView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                maybeStartCamera();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = true;
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                closeCamera();
            }
        });
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(previewContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlayView = new ViewfinderOverlay(this, getColor(R.color.accent));
        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Top bar: Close + Title + Torch (visible only when flash hardware exists)
        topBar = Ui.row(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(topBar, 12, 12);

        Button closeBtn = overlayButton(getString(R.string.qr_scanner_close), v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        topBar.addView(closeBtn);

        TextView title = Ui.text(this, getString(R.string.qr_scanner_title), 17);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(Ui.dp(this, 8));
        titleLp.setMarginEnd(Ui.dp(this, 8));
        topBar.addView(title, titleLp);

        torchButton = overlayButton(getString(R.string.qr_scanner_torch_on), v -> toggleTorch());
        torchButton.setVisibility(View.GONE);
        topBar.addView(torchButton);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(topBar, topLp);

        // Centered fail-closed fallback card (shown when camera permission denied or hardware absent)
        fallbackCard = Ui.column(this);
        fallbackCard.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(fallbackCard, 20, 20);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(Ui.dp(this, 16));
        cardBg.setColor(getColor(R.color.surface));
        cardBg.setStroke(Math.max(1, Ui.dp(this, 1)), getColor(R.color.line));
        fallbackCard.setBackground(cardBg);
        fallbackCard.setVisibility(View.GONE);

        fallbackTitle = Ui.text(this, getString(R.string.qr_scanner_permission_title), 17);
        fallbackTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fallbackTitle.setTextColor(getColor(R.color.ink));
        fallbackTitle.setGravity(Gravity.CENTER);
        fallbackCard.addView(fallbackTitle);

        fallbackDetail = Ui.text(this, getString(R.string.qr_scanner_permission_detail), 14);
        fallbackDetail.setTextColor(getColor(R.color.muted));
        fallbackDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = Ui.dp(this, 8);
        fallbackCard.addView(fallbackDetail, detailLp);

        fallbackPermBtn = Ui.filledButton(this, getString(R.string.perm_action_allow), v -> handleCameraPermissionAction());
        fallbackPermBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        permLp.topMargin = Ui.dp(this, 16);
        fallbackCard.addView(fallbackPermBtn, permLp);

        Button fallbackPickBtn = Ui.outlinedButton(this, getString(R.string.qr_scanner_choose_image), v -> openImagePicker());
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pickLp.topMargin = Ui.dp(this, 10);
        fallbackCard.addView(fallbackPickBtn, pickLp);

        Button fallbackCancelBtn = Ui.button(this, getString(R.string.profile_setup_cancel), v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelLp.topMargin = Ui.dp(this, 8);
        fallbackCard.addView(fallbackCancelBtn, cancelLp);

        FrameLayout.LayoutParams fallbackLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        int sideMargin = Ui.dp(this, 24);
        fallbackLp.leftMargin = sideMargin;
        fallbackLp.rightMargin = sideMargin;
        root.addView(fallbackCard, fallbackLp);

        // Bottom guidance & 1-tap "Choose QR image" action
        bottomBar = Ui.column(this);
        bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(bottomBar, 20, 14);

        statusText = Ui.text(this, getString(R.string.qr_scanner_hint), 14);
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setGravity(Gravity.CENTER);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bottomBar.addView(statusText);

        Button chooseImgBtn = overlayButton(getString(R.string.qr_scanner_choose_image), v -> openImagePicker());
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chooseLp.topMargin = Ui.dp(this, 12);
        bottomBar.addView(chooseImgBtn, chooseLp);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottomBar, bottomLp);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            int leftInset;
            int rightInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                topInset = bars.top;
                bottomInset = bars.bottom;
                leftInset = bars.left;
                rightInset = bars.right;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
                leftInset = insets.getSystemWindowInsetLeft();
                rightInset = insets.getSystemWindowInsetRight();
            }
            topBar.setPadding(
                    Ui.dp(this, 12) + leftInset,
                    Ui.dp(this, 12) + topInset,
                    Ui.dp(this, 12) + rightInset,
                    Ui.dp(this, 12));
            bottomBar.setPadding(
                    Ui.dp(this, 20) + leftInset,
                    Ui.dp(this, 14),
                    Ui.dp(this, 20) + rightInset,
                    Ui.dp(this, 18) + bottomInset);
            return insets;
        });

        setContentView(root);
    }

    private Button overlayButton(String label, View.OnClickListener listener) {
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int touchMin = Ui.dp(this, 48);
        btn.setMinHeight(touchMin);
        btn.setMinimumHeight(touchMin);
        btn.setMinWidth(touchMin);
        int hPad = Ui.dp(this, 14);
        int vPad = Ui.dp(this, 8);
        btn.setPadding(hPad, vPad, hPad, vPad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 24));
        bg.setColor(0x88182022);
        bg.setStroke(Math.max(1, Ui.dp(this, 1)), 0x55FFFFFF);
        btn.setBackground(bg);
        btn.setOnClickListener(listener);
        return btn;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resultDelivered.get()) return;
        startCameraThread();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hideFallback();
            maybeStartCamera();
        } else if (!permissionRequested) {
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        } else {
            showFallback(R.string.qr_scanner_permission_title, R.string.qr_scanner_permission_detail, true);
        }
    }

    private void handleCameraPermissionAction() {
        PermissionCenter.State camState = PermissionCenter.state(this, PermissionCenter.Key.CAMERA);
        if (camState == PermissionCenter.State.PERMANENTLY_DENIED) {
            PermissionCenter.openAppSettings(this);
        } else {
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        stopCameraThread();
        imageIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA_PERMISSION) return;
        if (grantResults != null && grantResults.length > 0) {
            PermissionCenter.recordRequested(this, PermissionCenter.Key.CAMERA);
        }
        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            Diagnostics.event("qr_scanner", "camera_permission_granted");
            hideFallback();
            maybeStartCamera();
        } else {
            Diagnostics.event("qr_scanner", "camera_permission_denied");
            showFallback(R.string.qr_scanner_permission_title, R.string.qr_scanner_permission_detail, true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        setStatus(getString(R.string.qr_scanner_reading_image));
        imageIo.execute(() -> decodePickedImage(uri));
    }

    private void openImagePicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQ_PICK_IMAGE);
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("qr_scanner", "image_picker_unavailable");
            setStatus(getString(R.string.qr_scanner_image_no_qr));
        }
    }

    private void decodePickedImage(Uri uri) {
        String decoded = null;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                postImageResult(ScanVerdict.NO_QR, "");
                return;
            }
            int sample = 1;
            int maxEdge = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxEdge / sample > MAX_PICKED_IMAGE_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bitmap != null) {
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                int[] pixels = new int[w * h];
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
                bitmap.recycle();
                bitmap = null;
                decoded = QrCodeDecoder.decodePixels(pixels, w, h);
            }
        } catch (IOException | OutOfMemoryError | RuntimeException err) {
            Diagnostics.warning("qr_scanner", "image_decode_failed");
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        ScanEvaluation eval = evaluateDecodedText(decoded);
        postImageResult(eval.verdict(), eval.rawText());
    }

    private void postImageResult(ScanVerdict verdict, String rawText) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (verdict == ScanVerdict.VALID_HERMES) {
                deliverSuccess(rawText, "scanned_image");
            } else if (verdict == ScanVerdict.NOT_HERMES) {
                setStatus(getString(R.string.qr_scanner_not_hermes));
                if (fallbackCard.getVisibility() == View.VISIBLE) {
                    fallbackDetail.setText(R.string.qr_scanner_not_hermes);
                }
            } else {
                setStatus(getString(R.string.qr_scanner_image_no_qr));
                if (fallbackCard.getVisibility() == View.VISIBLE) {
                    fallbackDetail.setText(R.string.qr_scanner_image_no_qr);
                }
            }
        });
    }

    private void startCameraThread() {
        synchronized (cameraLock) {
            if (cameraThread != null) return;
            cameraThread = new HandlerThread("hermes-qr-camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    private void stopCameraThread() {
        HandlerThread thread;
        synchronized (cameraLock) {
            thread = cameraThread;
            cameraThread = null;
            cameraHandler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(400L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void maybeStartCamera() {
        if (!surfaceReady || resultDelivered.get() || isFinishing() || isDestroyed()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        Handler bgHandler;
        int openEpoch;
        synchronized (cameraLock) {
            if (cameraDevice != null || cameraOpening || cameraHandler == null) return;
            cameraOpening = true;
            openEpoch = ++cameraEpoch;
            bgHandler = cameraHandler;
        }

        CameraManager manager = getSystemService(CameraManager.class);
        if (manager == null) {
            clearCameraOpening(openEpoch);
            showFallback(R.string.qr_scanner_unavailable_title, R.string.qr_scanner_unavailable_detail, false);
            return;
        }

        String camId = selectCameraId(manager);
        if (camId == null) {
            clearCameraOpening(openEpoch);
            Diagnostics.warning("qr_scanner", "no_camera_hardware");
            showFallback(R.string.qr_scanner_unavailable_title, R.string.qr_scanner_unavailable_detail, false);
            return;
        }

        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(camId);
            Integer sensorOri = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientationDeg = sensorOri != null ? sensorOri : 90;
            Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashAvailable = Boolean.TRUE.equals(hasFlash);

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            boolean swapAxes = shouldSwapAxes(sensorOrientationDeg, currentDisplayRotationDeg());
            double viewportAspect = currentViewportAspect();
            previewSize = choosePreviewSize(map, viewportAspect, swapAxes);
            double previewOrientedAspect = previewSize != null
                    ? (swapAxes
                            ? (double) previewSize.getHeight() / previewSize.getWidth()
                            : (double) previewSize.getWidth() / previewSize.getHeight())
                    : viewportAspect;
            analysisSize = chooseAnalysisSize(map, previewOrientedAspect, swapAxes);
            if (analysisSize == null) {
                clearCameraOpening(openEpoch);
                showFallback(R.string.qr_scanner_unavailable_title, R.string.qr_scanner_unavailable_detail, false);
                return;
            }
            Size chosenPreview = previewSize != null ? previewSize : analysisSize;
            previewSize = chosenPreview;
            Runnable configureSurface = () -> {
                if (!isFinishing() && !isDestroyed()) {
                    torchButton.setVisibility(flashAvailable ? View.VISIBLE : View.GONE);
                    previewView.getHolder().setFixedSize(chosenPreview.getWidth(), chosenPreview.getHeight());
                    applyPreviewLayout();
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                configureSurface.run();
            } else {
                mainHandler.post(configureSurface);
            }

            final int sessionEpoch = openEpoch;
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    synchronized (cameraLock) {
                        if (sessionEpoch != cameraEpoch || isFinishing() || isDestroyed()
                                || cameraHandler == null || cameraDevice != null) {
                            if (sessionEpoch == cameraEpoch) cameraOpening = false;
                            device.close();
                            return;
                        }
                        cameraOpening = false;
                        cameraDevice = device;
                    }
                    createCaptureSession(device, sessionEpoch);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    synchronized (cameraLock) {
                        if (sessionEpoch != cameraEpoch) return;
                        cameraOpening = false;
                        if (cameraDevice == device) cameraDevice = null;
                    }
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    boolean stale;
                    synchronized (cameraLock) {
                        stale = sessionEpoch != cameraEpoch;
                        if (!stale) {
                            cameraOpening = false;
                            if (cameraDevice == device) cameraDevice = null;
                        }
                    }
                    if (stale) return;
                    Diagnostics.warning("qr_scanner", "camera_open_error_" + error);
                    mainHandler.post(() -> showFallback(
                            R.string.qr_scanner_unavailable_title, R.string.qr_scanner_unavailable_detail, false));
                }
            }, bgHandler);
        } catch (CameraAccessException | RuntimeException err) {
            clearCameraOpening(openEpoch);
            Diagnostics.warning("qr_scanner", "camera_start_failed");
            showFallback(R.string.qr_scanner_unavailable_title, R.string.qr_scanner_unavailable_detail, false);
        }
    }

    private void clearCameraOpening(int epoch) {
        synchronized (cameraLock) {
            if (epoch == cameraEpoch) cameraOpening = false;
        }
    }

    private void createCaptureSession(CameraDevice device, int sessionEpoch) {
        Surface previewSurface = previewView.getHolder().getSurface();
        if (previewSurface == null || !previewSurface.isValid() || analysisSize == null) {
            synchronized (cameraLock) {
                if (sessionEpoch != cameraEpoch) return;
            }
            closeCamera();
            return;
        }

        Handler bgHandler;
        ImageReader reader;
        synchronized (cameraLock) {
            bgHandler = cameraHandler;
            if (sessionEpoch != cameraEpoch || bgHandler == null || cameraDevice != device) return;
            if (imageReader != null) {
                try { imageReader.close(); } catch (RuntimeException ignored) {}
            }
            reader = ImageReader.newInstance(
                    analysisSize.getWidth(), analysisSize.getHeight(), android.graphics.ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrameAvailable, bgHandler);
            imageReader = reader;
        }

        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (flashAvailable && torchOn) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }
            synchronized (cameraLock) {
                if (sessionEpoch != cameraEpoch || cameraDevice != device) return;
                previewRequestBuilder = builder;
            }
            device.createCaptureSession(
                    Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            synchronized (cameraLock) {
                                if (sessionEpoch != cameraEpoch || cameraDevice != device || cameraHandler == null) {
                                    session.close();
                                    return;
                                }
                                captureSession = session;
                                try {
                                    session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                } catch (CameraAccessException | IllegalStateException err) {
                                    Diagnostics.warning("qr_scanner", "preview_request_failed");
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            synchronized (cameraLock) {
                                if (sessionEpoch != cameraEpoch) return;
                            }
                            Diagnostics.warning("qr_scanner", "session_configure_failed");
                            closeCamera();
                            mainHandler.post(() -> showFallback(
                                    R.string.qr_scanner_unavailable_title, R.string.qr_scanner_unavailable_detail, false));
                        }
                    },
                    bgHandler);
        } catch (CameraAccessException | RuntimeException err) {
            synchronized (cameraLock) {
                if (sessionEpoch != cameraEpoch) return;
            }
            Diagnostics.warning("qr_scanner", "capture_session_failed");
            closeCamera();
            mainHandler.post(() -> showFallback(
                    R.string.qr_scanner_unavailable_title, R.string.qr_scanner_unavailable_detail, false));
        }
    }

    private void onFrameAvailable(ImageReader reader) {
        if (resultDelivered.get()) {
            try (Image ignored = reader.acquireLatestImage()) {
                // drain frame cleanly
            } catch (RuntimeException ignored) {}
            return;
        }
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastAnalyzeElapsedMs < MIN_FRAME_INTERVAL_MS) return;
            if (!decodingFrame.compareAndSet(false, true)) return;
            lastAnalyzeElapsedMs = now;
            try {
                Image.Plane[] planes = image.getPlanes();
                if (planes == null || planes.length == 0) return;
                Image.Plane yPlane = planes[0];
                int width = image.getWidth();
                int height = image.getHeight();
                int rowStride = yPlane.getRowStride();
                int pixelStride = yPlane.getPixelStride();
                ByteBuffer buf = yPlane.getBuffer();
                if (buf == null) return;
                buf.rewind();

                String decoded;
                if (pixelStride == 1) {
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    decoded = QrCodeDecoder.decodeLuminance(bytes, width, height, rowStride);
                } else {
                    byte[] packed = new byte[width * height];
                    int limit = buf.limit();
                    for (int row = 0; row < height; row++) {
                        int srcRow = row * rowStride;
                        int dstRow = row * width;
                        for (int col = 0; col < width; col++) {
                            int pos = srcRow + col * pixelStride;
                            if (pos < limit) packed[dstRow + col] = buf.get(pos);
                        }
                    }
                    decoded = QrCodeDecoder.decodeLuminance(packed, width, height, width);
                }

                ScanEvaluation eval = evaluateDecodedText(decoded);
                if (eval.verdict() == ScanVerdict.VALID_HERMES) {
                    mainHandler.post(() -> deliverSuccess(eval.rawText(), "scanned_camera"));
                } else if (eval.verdict() == ScanVerdict.NOT_HERMES) {
                    if (now - lastHintElapsedMs >= HINT_THROTTLE_MS) {
                        lastHintElapsedMs = now;
                        mainHandler.post(() -> setStatus(getString(R.string.qr_scanner_not_hermes)));
                    }
                }
            } finally {
                decodingFrame.set(false);
            }
        } catch (RuntimeException ignored) {
            // Frame closed or camera shutting down
        }
    }

    private void toggleTorch() {
        if (!flashAvailable) return;
        torchOn = !torchOn;
        torchButton.setText(torchOn ? R.string.qr_scanner_torch_off : R.string.qr_scanner_torch_on);
        synchronized (cameraLock) {
            if (captureSession != null && previewRequestBuilder != null && cameraHandler != null) {
                try {
                    previewRequestBuilder.set(
                            CaptureRequest.FLASH_MODE,
                            torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler);
                } catch (CameraAccessException | IllegalStateException ignored) {
                    Diagnostics.warning("qr_scanner", "torch_toggle_failed");
                }
            }
        }
    }

    private void closeCamera() {
        boolean resetTorchUi;
        synchronized (cameraLock) {
            cameraEpoch++;
            resetTorchUi = torchOn;
            torchOn = false;
            cameraOpening = false;
            if (captureSession != null) {
                try { captureSession.close(); } catch (RuntimeException ignored) {}
                captureSession = null;
            }
            if (cameraDevice != null) {
                try { cameraDevice.close(); } catch (RuntimeException ignored) {}
                cameraDevice = null;
            }
            if (imageReader != null) {
                try { imageReader.close(); } catch (RuntimeException ignored) {}
                imageReader = null;
            }
            previewRequestBuilder = null;
        }
        if (resetTorchUi) {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed() && torchButton != null) {
                    torchButton.setText(R.string.qr_scanner_torch_on);
                }
            });
        }
    }

    private String selectCameraId(CameraManager manager) {
        try {
            String fallbackId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
                if (fallbackId == null) fallbackId = id;
            }
            return fallbackId;
        } catch (CameraAccessException | RuntimeException err) {
            return null;
        }
    }

    private int currentDisplayRotationDeg() {
        try {
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            return switch (rot) {
                case Surface.ROTATION_90 -> 90;
                case Surface.ROTATION_180 -> 180;
                case Surface.ROTATION_270 -> 270;
                default -> 0;
            };
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private double currentViewportAspect() {
        int cw = previewContainer != null ? previewContainer.getWidth() : 0;
        int ch = previewContainer != null ? previewContainer.getHeight() : 0;
        if (cw > 0 && ch > 0) return (double) cw / ch;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0)
                ? (double) dm.widthPixels / dm.heightPixels : (9.0 / 16.0);
    }

    private void applyPreviewLayout() {
        if (previewContainer == null || previewView == null || previewSize == null) return;
        int cw = previewContainer.getWidth();
        int ch = previewContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        boolean swapAxes = shouldSwapAxes(sensorOrientationDeg, currentDisplayRotationDeg());
        PreviewLayout target = computeCenterCropLayout(
                cw, ch, previewSize.getWidth(), previewSize.getHeight(), swapAxes);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewView.getLayoutParams();
        if (lp == null || lp.width != target.width() || lp.height != target.height()
                || lp.gravity != Gravity.CENTER) {
            previewView.setLayoutParams(new FrameLayout.LayoutParams(
                    target.width(), target.height(), Gravity.CENTER));
        }
    }

    private static Size choosePreviewSize(
            StreamConfigurationMap map, double targetOrientedAspect, boolean swapAxes) {
        if (map == null) return null;
        return pickMatchingSize(map.getOutputSizes(SurfaceHolder.class), MAX_PREVIEW_EDGE, targetOrientedAspect, swapAxes);
    }

    private static Size chooseAnalysisSize(
            StreamConfigurationMap map, double targetOrientedAspect, boolean swapAxes) {
        if (map == null) return null;
        return pickMatchingSize(
                map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888),
                MAX_ANALYSIS_EDGE, targetOrientedAspect, swapAxes);
    }

    private static Size pickMatchingSize(
            Size[] sizes, int maxLongEdge, double targetOrientedAspect, boolean swapAxes) {
        if (sizes == null || sizes.length == 0) return null;
        int[][] raw = new int[sizes.length][2];
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] == null) continue;
            raw[i][0] = sizes[i].getWidth();
            raw[i][1] = sizes[i].getHeight();
        }
        int[] best = chooseBestSize(raw, maxLongEdge, targetOrientedAspect, swapAxes);
        return best != null ? new Size(best[0], best[1]) : null;
    }

    private void deliverSuccess(String validatedRawLink, String diagEvent) {
        if (!resultDelivered.compareAndSet(false, true)) return;
        Diagnostics.event("qr_scanner", diagEvent);
        closeCamera();
        Intent result = new Intent();
        result.putExtra(EXTRA_SCANNED_LINK, validatedRawLink);
        setResult(RESULT_OK, result);
        finish();
    }

    private void setStatus(String message) {
        if (statusText != null && !isFinishing() && !isDestroyed()) {
            statusText.setText(message);
        }
    }

    private void showFallback(int titleRes, int detailRes, boolean permissionRecovery) {
        if (isFinishing() || isDestroyed()) return;
        fallbackTitle.setText(titleRes);
        fallbackDetail.setText(detailRes);
        if (fallbackPermBtn != null) {
            if (permissionRecovery) {
                PermissionCenter.State camState = PermissionCenter.state(this, PermissionCenter.Key.CAMERA);
                fallbackPermBtn.setText(camState == PermissionCenter.State.PERMANENTLY_DENIED
                        ? R.string.perm_action_open_settings
                        : R.string.perm_action_allow);
                fallbackPermBtn.setVisibility(View.VISIBLE);
            } else {
                fallbackPermBtn.setVisibility(View.GONE);
            }
        }
        fallbackCard.setVisibility(View.VISIBLE);
        overlayView.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        torchButton.setVisibility(View.GONE);
    }

    private void hideFallback() {
        fallbackCard.setVisibility(View.GONE);
        overlayView.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
    }

    /** Translucent viewfinder scrim with centered square cutout and crisp corner brackets. */
    private static final class ViewfinderOverlay extends View {
        private final Paint scrimPaint = new Paint();
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float cornerLen;

        ViewfinderOverlay(Context context, int accentColor) {
            super(context);
            scrimPaint.setColor(0x99000000);
            scrimPaint.setStyle(Paint.Style.FILL);

            borderPaint.setColor(0x66FFFFFF);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Math.max(1f, Ui.dp(context, 1)));

            framePaint.setColor(accentColor);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(Ui.dp(context, 4));
            framePaint.setStrokeCap(Paint.Cap.ROUND);

            cornerLen = Ui.dp(context, 28);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float side = Math.min(w, h) * 0.68f;
            float left = (w - side) * 0.5f;
            float top = (h - side) * 0.46f;
            float right = left + side;
            float bottom = top + side;
            box.set(left, top, right, bottom);

            // 4 scrim rectangles around cutout
            canvas.drawRect(0, 0, w, top, scrimPaint);
            canvas.drawRect(0, bottom, w, h, scrimPaint);
            canvas.drawRect(0, top, left, bottom, scrimPaint);
            canvas.drawRect(right, top, w, bottom, scrimPaint);

            // Subtle inner guide
            canvas.drawRect(box, borderPaint);

            // 4 corner brackets
            canvas.drawLine(left, top, left + cornerLen, top, framePaint);
            canvas.drawLine(left, top, left, top + cornerLen, framePaint);

            canvas.drawLine(right - cornerLen, top, right, top, framePaint);
            canvas.drawLine(right, top, right, top + cornerLen, framePaint);

            canvas.drawLine(left, bottom - cornerLen, left, bottom, framePaint);
            canvas.drawLine(left, bottom, left + cornerLen, bottom, framePaint);

            canvas.drawLine(right - cornerLen, bottom, right, bottom, framePaint);
            canvas.drawLine(right, bottom - cornerLen, right, bottom, framePaint);
        }
    }
}
