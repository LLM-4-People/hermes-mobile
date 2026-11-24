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
    public void deferredTorchUiSyncReadsLiveSynchronizedStateAfterResetAndReenable() {
        assertFalse(QrScannerActivity.currentTorchEnabled(false, false));
        assertFalse(QrScannerActivity.currentTorchEnabled(false, true));
        assertFalse(QrScannerActivity.currentTorchEnabled(true, false));
        assertTrue(QrScannerActivity.currentTorchEnabled(true, true));

        // Simulate closeCamera() resetting torchOn=false and posting a UI refresh runnable,
        // followed by camera reopen + user re-enabling torch BEFORE the posted runnable flushes.
        Object lock = new Object();
        boolean[] flashAvailable = {true};
        boolean[] torchOn = {false};
        boolean[] renderedButtonOn = {false};

        Runnable deferredUiRefresh = () -> {
            final boolean liveEnabled;
            synchronized (lock) {
                liveEnabled = QrScannerActivity.currentTorchEnabled(flashAvailable[0], torchOn[0]);
            }
            renderedButtonOn[0] = liveEnabled;
        };

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
    }
}
