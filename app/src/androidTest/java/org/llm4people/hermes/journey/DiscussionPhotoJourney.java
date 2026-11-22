package org.llm4people.hermes.journey;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.OutputStream;
import java.util.function.Predicate;

/** Opt-in dedicated-device proof. Only the generated image with the verified blue-and-yellow thumbnail is selected. */
final class DiscussionPhotoJourney {
    private final Instrumentation test;
    private final JourneyUi ui;
    private final String album;
    private final Runnable unlock;
    DiscussionPhotoJourney(Instrumentation test, JourneyUi ui, String run, Runnable unlock) {
        this.test = test; this.ui = ui; this.album = "Hermes-" + run; this.unlock = unlock;
    }

    void run() throws Exception {
        ContentResolver resolver = test.getTargetContext().getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, album + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + album);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new AssertionError("Synthetic photo insert failed");
        try {
            Bitmap image = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.rgb(38, 75, 123));
            for (int y = 16; y < 48; y++) for (int x = 25; x < 73; x++) image.setPixel(x, y, Color.rgb(247, 195, 63));
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null || !image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError();
            } finally { image.recycle(); }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
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
        } finally { resolver.delete(uri, null, null); }
    }

    private void select() {
        ui.tap("Autres actions");
        ui.tap("Joindre une image…");
        selectSyntheticThumbnail();
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            if (ui.has("Choisir un profil")) { unlock.run(); return; }
            if (ui.has("Retirer")) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Picker did not return to the protected composer");
    }

    private void selectSyntheticThumbnail() {
        UiAutomation automation = test.getUiAutomation();
        long end = SystemClock.elapsedRealtime() + 60000;
        while (SystemClock.elapsedRealtime() < end) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String pkg = root.getPackageName().toString();
                if (pkg.contains("providers.media") || pkg.contains("photopicker")) {
                    Bitmap shot = automation.takeScreenshot();
                    if (shot != null) {
                        Bitmap pixels = shot.getConfig() == Bitmap.Config.HARDWARE
                                ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
                        try {
                            AccessibilityNodeInfo tile = find(root, node -> syntheticTile(node, pixels));
                            while (tile != null) {
                                if (tile.isClickable() && tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                                tile = tile.getParent();
                            }
                        } finally {
                            if (pixels != shot) pixels.recycle();
                            shot.recycle();
                        }
                    }
                }
            }
            SystemClock.sleep(350);
        }
        throw new AssertionError("System picker did not show the generated blue and yellow thumbnail");
    }

    private static boolean syntheticTile(AccessibilityNodeInfo node, Bitmap screenshot) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() < 24 || bounds.height() < 24 || bounds.width() > 2 * bounds.height()
                || bounds.height() > 2 * bounds.width() || bounds.left < 0 || bounds.top < 0
                || bounds.right > screenshot.getWidth() || bounds.bottom > screenshot.getHeight()) return false;
        // Match the image, never the first or newest gallery item. The system may center-crop the edges.
        int gold = Color.rgb(247, 195, 63), blue = Color.rgb(38, 75, 123);
        return colorAt(screenshot, bounds, .5, .5, gold) && colorAt(screenshot, bounds, .41, .43, gold)
                && colorAt(screenshot, bounds, .59, .57, gold) && colorAt(screenshot, bounds, .5, .13, blue)
                && colorAt(screenshot, bounds, .5, .87, blue) && colorAt(screenshot, bounds, .17, .13, blue)
                && colorAt(screenshot, bounds, .83, .87, blue);
    }

    private static boolean colorAt(Bitmap bitmap, Rect bounds, double x, double y, int expected) {
        int actual = bitmap.getPixel(bounds.left + (int) (bounds.width() * x),
                bounds.top + (int) (bounds.height() * y));
        return Math.abs(Color.red(actual) - Color.red(expected)) < 25
                && Math.abs(Color.green(actual) - Color.green(expected)) < 25
                && Math.abs(Color.blue(actual) - Color.blue(expected)) < 25;
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
}
