package org.llm4people.hermes.presence;

import android.location.LocationListener;
import android.os.Bundle;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;

/** Regression tests for ContinuousLocationTracker API 28-29 LocationListener compatibility (R24.3.1). */
public final class ContinuousLocationTrackerTest {

    @Test
    public void locationListenerDeclaresLegacyApi28CallbacksDirectly() throws Exception {
        ContinuousLocationTracker tracker = new ContinuousLocationTracker();
        LocationListener listener = tracker.createLocationListener();
        assertNotNull(listener);

        Class<?> impl = listener.getClass();
        Method onStatusChanged = impl.getDeclaredMethod("onStatusChanged", String.class, int.class, Bundle.class);
        Method onProviderEnabled = impl.getDeclaredMethod("onProviderEnabled", String.class);
        Method onProviderDisabled = impl.getDeclaredMethod("onProviderDisabled", String.class);
        assertNotNull(onStatusChanged);
        assertNotNull(onProviderEnabled);
        assertNotNull(onProviderDisabled);

        // Invoking all three callbacks must succeed cleanly on API 28-29 without AbstractMethodError.
        listener.onStatusChanged("gps", 2, null);
        listener.onProviderEnabled("gps");
        listener.onProviderDisabled("gps");
    }
}
