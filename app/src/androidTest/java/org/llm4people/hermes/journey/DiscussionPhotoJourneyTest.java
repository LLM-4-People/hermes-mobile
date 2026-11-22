package org.llm4people.hermes.journey;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import junit.framework.TestCase;

/** Deterministic checks for synthetic photo picker thumbnail recognition and failure diagnostics. */
public final class DiscussionPhotoJourneyTest extends TestCase {
    public void testMatchesExactAndCenterCroppedSyntheticTiles() {
        Bitmap pattern = DiscussionPhotoJourney.createSyntheticPattern();
        Bitmap frame = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888);
        try {
            frame.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(frame);
            // Uncropped 3:2 tile.
            Rect full = new Rect(10, 40, 106, 104);
            canvas.drawBitmap(pattern, null, full, null);
            assertTrue(DiscussionPhotoJourney.syntheticTile(full, frame));

            // Center-cropped 1:1 square tile matching Android PhotoPickerActivity grid cells.
            Rect squareCrop = new Rect(16, 0, 80, 64);
            Rect gridCell = new Rect(0, 427, 105, 532);
            canvas.drawBitmap(pattern, squareCrop, gridCell, null);
            assertTrue(DiscussionPhotoJourney.syntheticTile(gridCell, frame));
            assertFalse(DiscussionPhotoJourney.isBlackFrame(frame));
        } finally {
            pattern.recycle();
            frame.recycle();
        }
    }

    public void testRejectsClippedWrongColorInvertedAndTinyTiles() {
        Bitmap pattern = DiscussionPhotoJourney.createSyntheticPattern();
        Bitmap frame = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        try {
            frame.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(frame);
            // Clipped below the bottom of a 320px landscape viewport.
            assertFalse(DiscussionPhotoJourney.syntheticTile(new Rect(0, 260, 105, 365), frame));
            // Undersized and extreme aspect ratio bounds.
            assertFalse(DiscussionPhotoJourney.syntheticTile(new Rect(0, 0, 20, 20), frame));
            assertFalse(DiscussionPhotoJourney.syntheticTile(new Rect(0, 0, 200, 40), frame));

            // Uniform colors must never match.
            Bitmap uniformBlue = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
            uniformBlue.eraseColor(DiscussionPhotoJourney.BACKGROUND_BLUE);
            canvas.drawBitmap(uniformBlue, 0, 0, null);
            uniformBlue.recycle();
            assertFalse(DiscussionPhotoJourney.syntheticTile(new Rect(0, 0, 100, 100), frame));

            // Inverted colors (blue center, gold border) must never match.
            Bitmap inverted = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
            inverted.eraseColor(DiscussionPhotoJourney.CENTER_GOLD);
            for (int y = 25; y < 75; y++) {
                for (int x = 25; x < 75; x++) {
                    inverted.setPixel(x, y, DiscussionPhotoJourney.BACKGROUND_BLUE);
                }
            }
            canvas.drawBitmap(inverted, 120, 0, null);
            inverted.recycle();
            assertFalse(DiscussionPhotoJourney.syntheticTile(new Rect(120, 0, 220, 100), frame));
        } finally {
            pattern.recycle();
            frame.recycle();
        }
    }

    public void testDetectsBlackFrameAndSystemPickerPackages() {
        Bitmap black = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888);
        try {
            black.eraseColor(Color.BLACK);
            assertTrue(DiscussionPhotoJourney.isBlackFrame(black));
            assertFalse(DiscussionPhotoJourney.syntheticTile(new Rect(0, 0, 105, 105), black));
            assertEquals("#f7c33f", DiscussionPhotoJourney.describePixel(DiscussionPhotoJourney.CENTER_GOLD));
            assertEquals("#264b7b", DiscussionPhotoJourney.describePixel(DiscussionPhotoJourney.BACKGROUND_BLUE));
        } finally {
            black.recycle();
        }
        assertTrue(DiscussionPhotoJourney.isSystemPicker("com.google.android.providers.media.module"));
        assertTrue(DiscussionPhotoJourney.isSystemPicker("com.google.android.photopicker"));
        assertFalse(DiscussionPhotoJourney.isSystemPicker("org.llm4people.hermes"));
        assertFalse(DiscussionPhotoJourney.isSystemPicker(null));
        assertTrue(DiscussionPhotoJourney.isSheetHandleLabel("Drag handle"));
        assertTrue(DiscussionPhotoJourney.isSheetHandleLabel("Poignée de glissement"));
        assertFalse(DiscussionPhotoJourney.isSheetHandleLabel("Photos"));
        assertTrue(DiscussionPhotoJourney.isMediaTileLabel("Photo taken on Nov 22, 2026 7:23 PM"));
        assertTrue(DiscussionPhotoJourney.isMediaTileLabel("Photo prise le 22 nov. 2026"));
        assertFalse(DiscussionPhotoJourney.isMediaTileLabel("Albums"));
    }
}
