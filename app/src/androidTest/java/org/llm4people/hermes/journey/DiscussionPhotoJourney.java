package org.llm4people.hermes.journey;

import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.OutputStream;
import java.util.Locale;
import java.util.function.Predicate;

/** Opt-in dedicated-device proof. Only a generated image in this run's own album is selected. */
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
        clickSystem(node -> "Albums".equals(name(node)));
        clickSystem(node -> name(node).contains(album));
        clickSystem(node -> {
            String id = node.getViewIdResourceName();
            String name = name(node).toLowerCase(Locale.ROOT);
            return id != null && (id.endsWith("/icon_thumbnail") || id.endsWith("/photo_thumbnail"))
                    || name.startsWith("photo taken") || name.startsWith("photo prise")
                    || name.startsWith("image taken") || name.contains(album.toLowerCase(Locale.ROOT) + ".png");
        });
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            if (ui.has("Choisir un profil")) { unlock.run(); return; }
            if (ui.has("Retirer")) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Picker did not return to the protected composer");
    }

    private static String name(AccessibilityNodeInfo node) {
        CharSequence text = node.getText(), description = node.getContentDescription();
        return text != null ? text.toString() : description == null ? "" : description.toString();
    }

    private void clickSystem(Predicate<AccessibilityNodeInfo> predicate) {
        long end = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < end) {
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String pkg = root.getPackageName().toString();
                if (pkg.contains("providers.media") || pkg.contains("photopicker")) {
                    AccessibilityNodeInfo match = find(root, predicate);
                    while (match != null) {
                        if (match.isClickable() && match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                        match = match.getParent();
                    }
                }
            }
            SystemClock.sleep(125);
        }
        throw new AssertionError("System photo picker did not expose the synthetic album or image");
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
