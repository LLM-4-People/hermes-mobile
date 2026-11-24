package org.llm4people.hermes.onboarding;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for QrScannerActivity result delivery lifecycle fencing. */
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
}
