package org.llm4people.hermes.journey;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Opt-in dedicated-device proof. Only the generated image with the verified blue-and-yellow thumbnail is selected. */
final class DiscussionPhotoJourney {
    static final int PATTERN_WIDTH = 96;
    static final int PATTERN_HEIGHT = 64;
    static final int GOLD_LEFT = 25;
    static final int GOLD_RIGHT = 73;
    static final int GOLD_TOP = 16;
    static final int GOLD_BOTTOM = 48;
    static final int BACKGROUND_BLUE = Color.rgb(38, 75, 123);
    static final int CENTER_GOLD = Color.rgb(247, 195, 63);
    private static final int COLOR_TOLERANCE = 25;
    private static final long TOTAL_PICKER_TIMEOUT_MS = 60_000L;
    private static final long PICKER_ATTEMPT_TIMEOUT_MS = 18_000L;
    private static final long EXPAND_AFTER_MS = 1_200L;
    private static final long EMPTY_PICKER_RETRY_MS = 2_500L;
    private static final int MAX_PICKER_ATTEMPTS = 3;

    private final Instrumentation test;
    private final JourneyUi ui;
    private final String album;
    private final Runnable unlock;

    DiscussionPhotoJourney(Instrumentation test, JourneyUi ui, String run, Runnable unlock) {
        this.test = test;
        this.ui = ui;
        this.album = "Hermes-" + run;
        this.unlock = unlock;
    }

    void run() throws Exception {
        ContentResolver resolver = test.getTargetContext().getContentResolver();
        Uri uri = insertSyntheticPhoto(resolver);
        try {
            ui.tap("Nouvelle conversation");
            ui.fill("Message à Hermes", "Décris les deux couleurs.");
            ui.hideKeyboard();
            select();
            ui.see("Retirer");
            ui.capture("01-photo-brouillon");
            ui.tapButton("Retirer");
            ui.contains("Décris les deux couleurs.");
            ui.absent("Retirer");
            select();
            ui.see("Retirer");
            // Preserve both parts of an accepted draft across an explicit lock and unlock.
            ui.tap("Autres actions");
            ui.tap("Verrouiller le profil");
            ui.see("Choisir un profil");
            unlock.run();
            ui.see("Retirer");
            ui.fill("Votre question sur l’image", "Quelles couleurs voyez-vous sur ma capture ?");
            ui.hideKeyboard();
            ui.tap("Envoyer");
            ui.contains("Rectangle jaune sur fond bleu.");
            ui.capture("02-photo-reponse");
        } finally {
            resolver.delete(uri, null, null);
        }
    }

    private Uri insertSyntheticPhoto(ContentResolver resolver) throws Exception {
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000L;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, album + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + album);
        values.put(MediaStore.Images.Media.DATE_TAKEN, nowMs);
        values.put(MediaStore.Images.Media.DATE_ADDED, nowSec);
        values.put(MediaStore.Images.Media.DATE_MODIFIED, nowSec);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new AssertionError("Synthetic photo insert failed");
        try {
            Bitmap image = createSyntheticPattern();
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null || !image.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new AssertionError("Synthetic photo write failed");
                }
            } finally {
                image.recycle();
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            values.put(MediaStore.Images.Media.DATE_TAKEN, nowMs);
            values.put(MediaStore.Images.Media.DATE_MODIFIED, nowSec);
            if (resolver.update(uri, values, null, null) != 1) {
                throw new AssertionError("Synthetic photo publish update failed");
            }
            verifyMediaStoreReady(resolver, uri);
            return uri;
        } catch (Throwable failure) {
            resolver.delete(uri, null, null);
            throw failure;
        }
    }

    static Bitmap createSyntheticPattern() {
        Bitmap image = Bitmap.createBitmap(PATTERN_WIDTH, PATTERN_HEIGHT, Bitmap.Config.ARGB_8888);
        image.eraseColor(BACKGROUND_BLUE);
        for (int y = GOLD_TOP; y < GOLD_BOTTOM; y++) {
            for (int x = GOLD_LEFT; x < GOLD_RIGHT; x++) {
                image.setPixel(x, y, CENTER_GOLD);
            }
        }
        return image;
    }

    private static void verifyMediaStoreReady(ContentResolver resolver, Uri uri) {
        String[] columns = {MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.SIZE};
        try (Cursor row = resolver.query(uri, columns, null, null, null)) {
            if (row == null || !row.moveToFirst()) {
                throw new AssertionError("Synthetic photo missing from MediaStore after publish");
            }
            int pending = row.getInt(0);
            long size = row.getLong(1);
            if (pending != 0 || size <= 0) {
                throw new AssertionError("Synthetic photo not ready in MediaStore (pending="
                        + pending + ", size=" + size + ")");
            }
        }
    }

    private void select() {
        UiAutomation automation = test.getUiAutomation();
        long overallDeadline = SystemClock.elapsedRealtime() + TOTAL_PICKER_TIMEOUT_MS;
        PickerDiagnostics diagnostics = new PickerDiagnostics();
        for (int attempt = 1; attempt <= MAX_PICKER_ATTEMPTS; attempt++) {
            long remaining = overallDeadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            diagnostics.attempts = attempt;
            ui.tap("Autres actions");
            ui.tap("Joindre une image…");
            long attemptDeadline = Math.min(overallDeadline,
                    SystemClock.elapsedRealtime() + PICKER_ATTEMPT_TIMEOUT_MS);
            if (trySelectInOpenPicker(automation, attemptDeadline, diagnostics)) {
                awaitReturnToComposer();
                return;
            }
            if (attempt < MAX_PICKER_ATTEMPTS && SystemClock.elapsedRealtime() < overallDeadline) {
                dismissPicker(automation);
                SystemClock.sleep(750);
            }
        }
        throw new AssertionError("System picker did not show the generated blue and yellow thumbnail ("
                + diagnostics.describe() + ")");
    }

    private void awaitReturnToComposer() {
        long end = SystemClock.elapsedRealtime() + 15_000L;
        while (SystemClock.elapsedRealtime() < end) {
            if (ui.has("Choisir un profil")) {
                unlock.run();
                return;
            }
            if (ui.has("Retirer")) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Picker did not return to the protected composer");
    }

    private boolean trySelectInOpenPicker(UiAutomation automation, long deadline, PickerDiagnostics diagnostics) {
        boolean bannerDismissed = false;
        boolean expanded = false;
        boolean scrolled = false;
        long pickerSeenSince = 0L;
        long emptySince = 0L;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : null;
            diagnostics.lastPkg = pkg;
            if (isSystemPicker(pkg)) {
                long now = SystemClock.elapsedRealtime();
                if (pickerSeenSince == 0L) pickerSeenSince = now;
                Bitmap shot = automation.takeScreenshot();
                if (shot == null) {
                    diagnostics.shotState = "null";
                } else {
                    Bitmap pixels = shot.getConfig() == Bitmap.Config.HARDWARE
                            ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
                    try {
                        diagnostics.observePicker(root, pixels);
                        if (clickSyntheticTile(root, pixels)) return true;
                        if (!bannerDismissed && dismissPrivacyBanner(root)) {
                            bannerDismissed = true;
                            diagnostics.bannerDismissed = true;
                            SystemClock.sleep(300);
                            continue;
                        }
                        if (!expanded && shouldExpandBottomSheet(root, pixels, now - pickerSeenSince)) {
                            expanded = true;
                            diagnostics.expanded |= expandBottomSheet(automation, root, pixels.getHeight());
                            continue;
                        }
                        if (!scrolled && expanded && scrollClippedPhotoGrid(root, pixels)) {
                            scrolled = true;
                            diagnostics.scrolled = true;
                            SystemClock.sleep(300);
                            continue;
                        }
                        if (isPickerEmpty(root)) {
                            if (emptySince == 0L) emptySince = now;
                            if (now - emptySince >= EMPTY_PICKER_RETRY_MS) return false;
                        } else {
                            emptySince = 0L;
                        }
                    } finally {
                        if (pixels != shot) pixels.recycle();
                        shot.recycle();
                    }
                }
            }
            SystemClock.sleep(250);
        }
        return false;
    }

    private static boolean clickSyntheticTile(AccessibilityNodeInfo root, Bitmap pixels) {
        AccessibilityNodeInfo tile = find(root, node -> syntheticTile(node, pixels));
        while (tile != null) {
            if (tile.isClickable() && tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            tile = tile.getParent();
        }
        return false;
    }

    private static boolean dismissPrivacyBanner(AccessibilityNodeInfo root) {
        boolean hasPrivacyNotice = find(root, node -> {
            String label = nodeLabel(node).toLowerCase(Locale.ROOT);
            return label.contains("only have access") || label.contains("only access the photos")
                    || label.contains("accès uniquement") || label.contains("accès qu");
        }) != null;
        if (!hasPrivacyNotice) return false;
        AccessibilityNodeInfo dismiss = find(root, node -> {
            String label = nodeLabel(node).toLowerCase(Locale.ROOT);
            return node.isClickable() && ("dismiss".equals(label) || "ignorer".equals(label));
        });
        return dismiss != null && dismiss.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean shouldExpandBottomSheet(AccessibilityNodeInfo root, Bitmap pixels, long pickerAgeMs) {
        AccessibilityNodeInfo handle = findSheetHandle(root);
        if (handle == null) return false;
        Rect bounds = new Rect();
        handle.getBoundsInScreen(bounds);
        return bounds.top > pixels.getHeight() / 6 || pickerAgeMs >= EXPAND_AFTER_MS;
    }

    private static AccessibilityNodeInfo findSheetHandle(AccessibilityNodeInfo root) {
        return find(root, node -> {
            String id = node.getViewIdResourceName();
            if (id != null && (id.endsWith("/drag_bar") || id.endsWith("/privacy_text")
                    || id.endsWith("/bottom_sheet"))) {
                return true;
            }
            return isSheetHandleLabel(nodeLabel(node));
        });
    }

    static boolean isSheetHandleLabel(String label) {
        if (label == null) return false;
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return "drag handle".equals(normalized) || normalized.contains("poignée");
    }

    private static boolean expandBottomSheet(UiAutomation automation, AccessibilityNodeInfo root, int screenHeight) {
        AccessibilityNodeInfo handle = findSheetHandle(root);
        if (handle == null) return false;
        Rect bounds = new Rect();
        handle.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;
        float x = bounds.centerX();
        float startY = Math.min(screenHeight - 8f, Math.max(bounds.top + 4f, bounds.centerY()));
        float endY = Math.max(16f, screenHeight * 0.06f);
        if (startY - endY < 24f) return false;
        long downTime = SystemClock.uptimeMillis();
        injectTouch(automation, downTime, downTime, MotionEvent.ACTION_DOWN, x, startY);
        int steps = 10;
        for (int i = 1; i <= steps; i++) {
            long eventTime = downTime + i * 20L;
            float y = startY + (endY - startY) * (i / (float) steps);
            injectTouch(automation, downTime, eventTime, MotionEvent.ACTION_MOVE, x, y);
            SystemClock.sleep(20);
        }
        injectTouch(automation, downTime, downTime + (steps + 1) * 20L, MotionEvent.ACTION_UP, x, endY);
        SystemClock.sleep(300);
        return true;
    }

    private static boolean scrollClippedPhotoGrid(AccessibilityNodeInfo root, Bitmap pixels) {
        AccessibilityNodeInfo clippedPhoto = find(root, node -> {
            if (!isMediaTileNode(node)) return false;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds.bottom > pixels.getHeight() || bounds.top < 0;
        });
        if (clippedPhoto == null) return false;
        AccessibilityNodeInfo scroller = clippedPhoto;
        while (scroller != null) {
            if (scroller.isScrollable()
                    && scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true;
            }
            scroller = scroller.getParent();
        }
        return false;
    }

    private static void injectTouch(UiAutomation automation, long downTime, long eventTime,
                                    int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            automation.injectInputEvent(event, true);
        } finally {
            event.recycle();
        }
    }

    private void dismissPicker(UiAutomation automation) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null && isSystemPicker(root.getPackageName() == null ? null : root.getPackageName().toString())) {
            AccessibilityNodeInfo cancel = find(root, node -> {
                String label = nodeLabel(node).toLowerCase(Locale.ROOT);
                return node.isClickable() && ("cancel".equals(label) || "annuler".equals(label)
                        || "close".equals(label) || "fermer".equals(label));
            });
            if (cancel == null || !cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            }
        }
        long end = SystemClock.elapsedRealtime() + 10_000L;
        while (SystemClock.elapsedRealtime() < end) {
            if (ui.has("Choisir un profil")) {
                unlock.run();
                ui.see("Autres actions");
                return;
            }
            if (ui.has("Autres actions")) return;
            SystemClock.sleep(150);
        }
    }

    static boolean isSystemPicker(String pkg) {
        return pkg != null && (pkg.contains("providers.media") || pkg.contains("photopicker"));
    }

    static boolean isMediaTileLabel(String label) {
        if (label == null) return false;
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("photo taken") || normalized.startsWith("photo prise")
                || normalized.startsWith("image taken") || normalized.startsWith("image prise");
    }

    private static boolean isMediaTileNode(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id != null && (id.endsWith("/icon_thumbnail") || id.endsWith("/photo_thumbnail"))) return true;
        return isMediaTileLabel(nodeLabel(node));
    }

    private static boolean isPickerEmpty(AccessibilityNodeInfo root) {
        return find(root, node -> {
            String id = node.getViewIdResourceName();
            if (id != null && id.endsWith("/empty_text_view")) return true;
            String label = nodeLabel(node).toLowerCase(Locale.ROOT);
            return label.contains("no photos") || label.contains("aucune photo");
        }) != null;
    }

    private static boolean syntheticTile(AccessibilityNodeInfo node, Bitmap screenshot) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return syntheticTile(bounds, screenshot);
    }

    static boolean syntheticTile(Rect bounds, Bitmap screenshot) {
        if (bounds == null || screenshot == null) return false;
        if (bounds.width() < 24 || bounds.height() < 24 || bounds.width() > 2 * bounds.height()
                || bounds.height() > 2 * bounds.width() || bounds.left < 0 || bounds.top < 0
                || bounds.right > screenshot.getWidth() || bounds.bottom > screenshot.getHeight()) return false;
        // Match the image, never the first or newest gallery item. The system may center-crop or round corners.
        return colorAt(screenshot, bounds, .5, .5, CENTER_GOLD) && colorAt(screenshot, bounds, .41, .43, CENTER_GOLD)
                && colorAt(screenshot, bounds, .59, .57, CENTER_GOLD) && colorAt(screenshot, bounds, .5, .14, BACKGROUND_BLUE)
                && colorAt(screenshot, bounds, .5, .86, BACKGROUND_BLUE) && colorAt(screenshot, bounds, .22, .14, BACKGROUND_BLUE)
                && colorAt(screenshot, bounds, .78, .86, BACKGROUND_BLUE);
    }

    static boolean isBlackFrame(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return false;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        for (int gy = 1; gy <= 5; gy++) {
            for (int gx = 1; gx <= 5; gx++) {
                int pixel = bitmap.getPixel((w * gx) / 6, (h * gy) / 6);
                if (Color.red(pixel) != 0 || Color.green(pixel) != 0 || Color.blue(pixel) != 0) return false;
            }
        }
        return true;
    }

    static String describePixel(int pixel) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Color.red(pixel), Color.green(pixel), Color.blue(pixel));
    }

    private static boolean colorAt(Bitmap bitmap, Rect bounds, double x, double y, int expected) {
        int actual = bitmap.getPixel(bounds.left + (int) (bounds.width() * x),
                bounds.top + (int) (bounds.height() * y));
        return Math.abs(Color.red(actual) - Color.red(expected)) < COLOR_TOLERANCE
                && Math.abs(Color.green(actual) - Color.green(expected)) < COLOR_TOLERANCE
                && Math.abs(Color.blue(actual) - Color.blue(expected)) < COLOR_TOLERANCE;
    }

    private static String nodeLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence value = text != null && text.length() > 0 ? text : desc;
        return value == null ? "" : value.toString().trim();
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
        if (node.isVisibleToUser() && predicate.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo match = find(child, predicate);
            if (match != null) return match;
        }
        return null;
    }

    private static final class PickerDiagnostics {
        int attempts;
        boolean bannerDismissed;
        boolean expanded;
        boolean scrolled;
        String lastPkg;
        String shotState = "none";
        List<String> pickerTexts = List.of();
        List<String> candidates = List.of();

        void observePicker(AccessibilityNodeInfo root, Bitmap pixels) {
            String size = pixels.getWidth() + "x" + pixels.getHeight();
            shotState = isBlackFrame(pixels) ? "black(" + size + ")" : size;
            List<String> texts = new ArrayList<>();
            List<String> mediaTiles = new ArrayList<>();
            List<String> fallbackTiles = new ArrayList<>();
            collectPickerSnapshot(root, pixels, texts, mediaTiles, fallbackTiles);
            pickerTexts = texts;
            candidates = mediaTiles.isEmpty() ? fallbackTiles : mediaTiles;
        }

        private static void collectPickerSnapshot(AccessibilityNodeInfo node, Bitmap pixels,
                                                  List<String> texts, List<String> mediaTiles,
                                                  List<String> fallbackTiles) {
            if (node == null || !node.isVisibleToUser()) return;
            if (texts.size() < 8) {
                String label = nodeLabel(node);
                if (!label.isEmpty()) {
                    String clipped = label.length() > 48 ? label.substring(0, 48) + "…" : label;
                    if (!texts.contains(clipped)) texts.add(clipped);
                }
            }
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (isMediaTileNode(node) && mediaTiles.size() < 4) {
                mediaTiles.add(describeCandidate(b, pixels));
            } else if (fallbackTiles.size() < 3 && node.isClickable()
                    && b.width() >= 24 && b.height() >= 24
                    && b.width() <= 2 * b.height() && b.height() <= 2 * b.width()
                    && b.width() < pixels.getWidth()) {
                fallbackTiles.add(describeCandidate(b, pixels));
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collectPickerSnapshot(node.getChild(i), pixels, texts, mediaTiles, fallbackTiles);
            }
        }

        private static String describeCandidate(Rect b, Bitmap pixels) {
            if (b.left >= 0 && b.top >= 0 && b.right <= pixels.getWidth() && b.bottom <= pixels.getHeight()) {
                int center = pixels.getPixel(b.left + b.width() / 2, b.top + b.height() / 2);
                int top = pixels.getPixel(b.left + b.width() / 2, b.top + (int) (b.height() * 0.14));
                int corner = pixels.getPixel(b.left + (int) (b.width() * 0.22), b.top + (int) (b.height() * 0.14));
                return b.toShortString() + " center=" + describePixel(center)
                        + " top=" + describePixel(top) + " corner=" + describePixel(corner);
            }
            return b.toShortString() + " out-of-bounds(" + pixels.getWidth() + "x" + pixels.getHeight() + ")";
        }

        String describe() {
            return "attempts=" + attempts
                    + ", bannerDismissed=" + bannerDismissed
                    + ", expanded=" + expanded
                    + ", scrolled=" + scrolled
                    + ", pkg=" + (lastPkg == null ? "<null>" : lastPkg)
                    + ", shot=" + shotState
                    + ", texts=" + pickerTexts
                    + ", candidates=" + candidates;
        }
    }
}
