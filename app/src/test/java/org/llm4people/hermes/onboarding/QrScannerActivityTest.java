package org.llm4people.hermes.onboarding;

import android.hardware.camera2.CaptureRequest;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for QrScannerActivity lifecycle fencing and torch state helpers. */
public final class QrScannerActivityTest {
    @Test
    public void canDeliverSuccessEnforcesOnceOnlyAndBlocksFinishingDestroyedOrCanceledState() {
        AtomicBoolean delivered = new AtomicBoolean(false);
        assertTrue("Active scanner must deliver once",
                QrScannerActivity.canDeliverSuccess(false, false, delivered));
        assertTrue(delivered.get());
        assertFalse("Second delivery attempt must be rejected",
                QrScannerActivity.canDeliverSuccess(false, false, delivered));

        AtomicBoolean finishingFlag = new AtomicBoolean(false);
        assertFalse("Finishing activity must not deliver success",
                QrScannerActivity.canDeliverSuccess(true, false, finishingFlag));
        assertFalse("Finishing rejection must not flip delivered flag", finishingFlag.get());

        AtomicBoolean destroyedFlag = new AtomicBoolean(false);
        assertFalse("Destroyed activity must not deliver success",
                QrScannerActivity.canDeliverSuccess(false, true, destroyedFlag));
        assertFalse("Destroyed rejection must not flip delivered flag", destroyedFlag.get());

        AtomicBoolean canceledFlag = new AtomicBoolean(true);
        assertFalse("Already canceled/delivered scanner must not deliver success",
                QrScannerActivity.canDeliverSuccess(false, false, canceledFlag));

        assertFalse("Null delivered flag must fail closed",
                QrScannerActivity.canDeliverSuccess(false, false, null));
    }

    @Test
    public void torchStateHelpersRespectFlashAvailabilityAndRevertOnApplyFailure() {
        assertFalse(QrScannerActivity.nextTorchState(false, false));
        assertFalse(QrScannerActivity.nextTorchState(false, true));
        assertTrue(QrScannerActivity.nextTorchState(true, false));
        assertFalse(QrScannerActivity.nextTorchState(true, true));

        assertEquals(CaptureRequest.FLASH_MODE_OFF, QrScannerActivity.flashModeFor(false, true));
        assertEquals(CaptureRequest.FLASH_MODE_OFF, QrScannerActivity.flashModeFor(true, false));
        assertEquals(CaptureRequest.FLASH_MODE_TORCH, QrScannerActivity.flashModeFor(true, true));

        assertTrue(QrScannerActivity.resolveTorchAfterApply(false, true, true));
        assertFalse(QrScannerActivity.resolveTorchAfterApply(true, false, true));
        assertFalse("Failed toggle-on must revert to off",
                QrScannerActivity.resolveTorchAfterApply(false, true, false));
        assertTrue("Failed toggle-off must revert to on",
                QrScannerActivity.resolveTorchAfterApply(true, false, false));
    }

    @Test
    public void deferredTorchUiSyncReadsLiveSynchronizedStateAfterResetAndReenable() throws Exception {
        assertFalse(QrScannerActivity.currentTorchEnabled(false, false));
        assertFalse(QrScannerActivity.currentTorchEnabled(false, true));
        assertFalse(QrScannerActivity.currentTorchEnabled(true, false));
        assertTrue(QrScannerActivity.currentTorchEnabled(true, true));
        assertTrue(QrScannerActivity.isCurrentEpoch(3, 3));
        assertFalse(QrScannerActivity.isCurrentEpoch(2, 3));

        // Verify production postTorchButtonState() takes 0 args (no stale boolean snapshot overload).
        java.lang.reflect.Method zeroArgPost = QrScannerActivity.class.getDeclaredMethod("postTorchButtonState");
        assertEquals(void.class, zeroArgPost.getReturnType());
        boolean hasBooleanOverload = false;
        try {
            QrScannerActivity.class.getDeclaredMethod("postTorchButtonState", boolean.class);
            hasBooleanOverload = true;
        } catch (NoSuchMethodException expected) {
            // expected
        }
        assertFalse("Stale postTorchButtonState(boolean) overload must not exist", hasBooleanOverload);

        // Verify epoch-fenced closeCameraForEpoch(int, CameraDevice) exists for disconnect/error cleanup.
        java.lang.reflect.Method epochClose = QrScannerActivity.class.getDeclaredMethod(
                "closeCameraForEpoch", int.class, android.hardware.camera2.CameraDevice.class);
        assertEquals(boolean.class, epochClose.getReturnType());

        // Exercise production deferredTorchUiSync helper across reset -> re-enable before flush.
        Object lock = new Object();
        boolean[] flashAvailable = {true};
        boolean[] torchOn = {false};
        boolean[] renderedButtonOn = {false};

        Runnable deferredUiRefresh = QrScannerActivity.deferredTorchUiSync(
                () -> {
                    synchronized (lock) {
                        return QrScannerActivity.currentTorchEnabled(flashAvailable[0], torchOn[0]);
                    }
                },
                enabled -> renderedButtonOn[0] = enabled);

        // Before flush, user re-enables torch on reopened camera.
        synchronized (lock) {
            torchOn[0] = QrScannerActivity.nextTorchState(flashAvailable[0], torchOn[0]);
        }
        deferredUiRefresh.run();
        assertTrue("Deferred refresh must observe live re-enabled torch state", renderedButtonOn[0]);

        // If flash becomes unavailable before flush, deferred refresh reflects false.
        synchronized (lock) {
            flashAvailable[0] = false;
        }
        deferredUiRefresh.run();
        assertFalse("Deferred refresh must stay false when flash is unavailable", renderedButtonOn[0]);

        // Null-safe no-op on missing reader or updater.
        QrScannerActivity.deferredTorchUiSync(null, enabled -> renderedButtonOn[0] = enabled).run();
        QrScannerActivity.deferredTorchUiSync(() -> true, null).run();
    }
}
