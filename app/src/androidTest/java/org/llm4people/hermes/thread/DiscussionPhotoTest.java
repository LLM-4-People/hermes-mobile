package org.llm4people.hermes.thread;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

public final class DiscussionPhotoTest extends InstrumentationTestCase {
    public void testOrientationResizeAndMetadataAreBakedIntoPixels() throws Exception {
        File file = File.createTempFile("photo-fixture", ".jpg", getInstrumentation().getTargetContext().getCacheDir());
        try {
            Bitmap original = Bitmap.createBitmap(2500, 1000, Bitmap.Config.ARGB_8888);
            original.eraseColor(Color.BLUE);
            try (FileOutputStream out = new FileOutputStream(file)) {
                assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 75, out));
            } finally { original.recycle(); }
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
            exif.setAttribute(ExifInterface.TAG_MODEL, "PrivateCameraId");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "45/1,30/1,0/1");
            exif.saveAttributes();
            DiscussionPhoto photo = DiscussionPhoto.fromBytes(Files.readAllBytes(file.toPath()), "Capture privée.jpg", org.llm4people.hermes.camera.CapturedMedia.effectiveByteLimit(0L));
            byte[] bytes = Base64.getDecoder().decode(photo.pixels);
            assertTrue(bytes.length <= DiscussionPhoto.PAYLOAD_LIMIT);
            Bitmap actual = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            try { assertEquals(640, actual.getWidth()); assertEquals(1600, actual.getHeight()); }
            finally { actual.recycle(); }
            ExifInterface exported = new ExifInterface(new ByteArrayInputStream(bytes));
            assertNull(exported.getAttribute(ExifInterface.TAG_MODEL));
            assertNull(exported.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
            assertTrue(DiscussionPhoto.valid(photo.info));
            JSONObject wire = DiscussionPhoto.wire(ThreadData.body("test", "Où ?", null), photo.info, photo.pixels);
            assertFalse(wire.toString().contains("Capture privée"));
            assertFalse(wire.toString().contains("PrivateCameraId"));
        } finally { assertTrue(file.delete()); }
    }

    public void testRejectsNonImageBytes() {
        try { DiscussionPhoto.fromBytes(new byte[]{1, 2, 3}, "test", org.llm4people.hermes.camera.CapturedMedia.effectiveByteLimit(0L)); fail(); }
        catch (IOException | IllegalArgumentException expected) { /* Invalid data never enters the draft. */ }
    }
}
