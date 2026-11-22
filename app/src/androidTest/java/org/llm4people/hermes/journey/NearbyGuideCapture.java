package org.llm4people.hermes.journey;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/** Captures two app-owned native views while the host drives real permission and location services. */
final class NearbyGuideCapture {
    private NearbyGuideCapture() {}

    static void observe(File folder, Supplier<JourneyUi> current) throws IOException {
        if (!new File(folder, "ready").createNewFile()) throw new IOException("Capture folder already used");
        long expires = SystemClock.elapsedRealtime() + 20 * 60_000L;
        while (SystemClock.elapsedRealtime() < expires) {
            if (new File(folder, "finish").isFile()) return;
            for (String name : new String[]{"guide", "detail"}) {
                File request = new File(folder, "capture-" + name);
                if (request.isFile() && request.delete()) {
                    JourneyUi ui = current.get();
                    ui.capture(name);
                    if (!new File(folder, name + ".done").createNewFile())
                        throw new IOException("Duplicate guide capture");
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Timed out waiting for the nearby guide driver");
    }
}
