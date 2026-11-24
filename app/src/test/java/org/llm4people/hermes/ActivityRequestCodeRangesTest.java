package org.llm4people.hermes;

import org.junit.Test;
import org.llm4people.hermes.camera.ThreadCameraCapture;
import org.llm4people.hermes.onboarding.QrScannerActivity;
import org.llm4people.hermes.thread.ThreadPhotoPicker;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM invariants ensuring startActivityForResult request-code ranges never overlap. */
public final class ActivityRequestCodeRangesTest {

    @Test
    public void photoPickerAndCameraCaptureRequestCodeRangesAreStrictlyDisjoint() {
        assertTrue("Photo picker min must be positive 16-bit code",
                ThreadPhotoPicker.REQUEST_CODE_MIN > 0 && ThreadPhotoPicker.REQUEST_CODE_MIN < ThreadPhotoPicker.REQUEST_CODE_MAX);
        assertTrue("Photo picker max must fit 16-bit request code",
                ThreadPhotoPicker.REQUEST_CODE_MAX <= 0xFFFF);

        assertTrue("Camera capture min must be positive 16-bit code",
                ThreadCameraCapture.REQUEST_CODE_MIN > 0 && ThreadCameraCapture.REQUEST_CODE_MIN < ThreadCameraCapture.REQUEST_CODE_MAX);
        assertTrue("Camera capture max must fit 16-bit request code",
                ThreadCameraCapture.REQUEST_CODE_MAX <= 0xFFFF);

        boolean disjoint = ThreadPhotoPicker.REQUEST_CODE_MAX < ThreadCameraCapture.REQUEST_CODE_MIN
                || ThreadCameraCapture.REQUEST_CODE_MAX < ThreadPhotoPicker.REQUEST_CODE_MIN;
        assertTrue("ThreadPhotoPicker and ThreadCameraCapture request-code ranges must not overlap", disjoint);

        Set<Integer> photoCodes = new HashSet<>();
        int photo = ThreadPhotoPicker.REQUEST_CODE_MIN;
        int photoRangeSize = ThreadPhotoPicker.REQUEST_CODE_MAX - ThreadPhotoPicker.REQUEST_CODE_MIN + 1;
        for (int i = 0; i < photoRangeSize; i++) {
            assertTrue("Photo code out of bounds: " + photo,
                    photo >= ThreadPhotoPicker.REQUEST_CODE_MIN && photo <= ThreadPhotoPicker.REQUEST_CODE_MAX);
            assertTrue("Duplicate photo code before wrap: " + photo, photoCodes.add(photo));
            photo = ThreadPhotoPicker.nextRequestCode(photo);
        }
        assertEquals("Photo picker must wrap cleanly back to MIN", ThreadPhotoPicker.REQUEST_CODE_MIN, photo);
        assertEquals(ThreadPhotoPicker.REQUEST_CODE_MIN, ThreadPhotoPicker.nextRequestCode(ThreadPhotoPicker.REQUEST_CODE_MIN - 1));
        assertEquals(ThreadPhotoPicker.REQUEST_CODE_MIN, ThreadPhotoPicker.nextRequestCode(ThreadPhotoPicker.REQUEST_CODE_MAX + 1));

        Set<Integer> cameraCodes = new HashSet<>();
        int camera = ThreadCameraCapture.REQUEST_CODE_MIN;
        int cameraRangeSize = ThreadCameraCapture.REQUEST_CODE_MAX - ThreadCameraCapture.REQUEST_CODE_MIN + 1;
        for (int i = 0; i < cameraRangeSize; i++) {
            assertTrue("Camera code out of bounds: " + camera,
                    camera >= ThreadCameraCapture.REQUEST_CODE_MIN && camera <= ThreadCameraCapture.REQUEST_CODE_MAX);
            assertFalse("Camera code collided with photo picker range: " + camera, photoCodes.contains(camera));
            assertTrue("Duplicate camera code before wrap: " + camera, cameraCodes.add(camera));
            camera = ThreadCameraCapture.nextRequestCode(camera);
        }
        assertEquals("Camera capture must wrap cleanly back to MIN", ThreadCameraCapture.REQUEST_CODE_MIN, camera);
        assertEquals(ThreadCameraCapture.REQUEST_CODE_MIN, ThreadCameraCapture.nextRequestCode(ThreadCameraCapture.REQUEST_CODE_MIN - 1));
        assertEquals(ThreadCameraCapture.REQUEST_CODE_MIN, ThreadCameraCapture.nextRequestCode(ThreadCameraCapture.REQUEST_CODE_MAX + 1));

        assertFalse("QR scanner request code must not overlap photo picker", photoCodes.contains(QrScannerActivity.REQUEST_CODE));
        assertFalse("QR scanner request code must not overlap camera capture", cameraCodes.contains(QrScannerActivity.REQUEST_CODE));
    }
}
