package org.llm4people.hermes.perf;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.test.InstrumentationTestCase;

import org.llm4people.hermes.HermesConfig;

import java.io.ByteArrayOutputStream;

@SuppressWarnings("deprecation")
public final class RuntimePerformanceContractTest extends InstrumentationTestCase {
    public void testHermesConfigLoadMemoizesSameInstanceAcrossRepeatedCalls() {
        HermesConfig.clearCacheForTest();
        Context context = getInstrumentation().getTargetContext();
        HermesConfig first = HermesConfig.load(context);
        HermesConfig second = HermesConfig.load(context);
        assertSame(first, second);
        assertTrue(first.httpConnectTimeoutMs > 0);
        assertTrue(first.artifactDefaultMaxBytes > 0);
    }

    public void testThumbnailCacheDownsamplesLargeImageAndEvictsOnMemoryPressure() {
        MemoryPressure.clearAll();
        Bitmap source = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.rgb(13, 110, 110));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
        source.recycle();
        byte[] encoded = out.toByteArray();

        String key = "perf-test-thumb";
        Bitmap decoded = ThumbnailCache.getOrDecode(key, encoded, 150);
        assertNotNull(decoded);
        assertTrue("Downsampled bitmap must stay <= 300px wide", decoded.getWidth() <= 300);
        assertSame("Second lookup must return cached bitmap", decoded, ThumbnailCache.get(key));

        MemoryPressure.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        assertNull("UI hidden trim must evict cached thumbnails", ThumbnailCache.get(key));
    }
}
