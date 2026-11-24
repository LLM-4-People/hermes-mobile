package org.llm4people.hermes.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.test.InstrumentationTestCase;
import android.view.View;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.RouteRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class UpdatePanelTest extends InstrumentationTestCase {
    public void testRouteRegistrationProviderSecurityAndUiRender() throws Throwable {
        Context context = getInstrumentation().getTargetContext();

        // 1. Route registration under SETTINGS.
        RouteRegistry registry = new RouteRegistry();
        AtomicBoolean opened = new AtomicBoolean(false);
        UpdatePanel.registerRoute(registry, context, () -> opened.set(true));
        List<RouteRegistry.Destination> settings = registry.destinationsIn(RouteRegistry.Section.SETTINGS, Set.of());
        assertEquals(1, settings.size());
        assertEquals(UpdatePanel.ROUTE_ID, settings.get(0).id());
        assertTrue(registry.dispatch(UpdatePanel.ROUTE_ID));
        assertTrue(opened.get());

        // 2. UpdateApkProvider path-traversal rejection & valid verified APK resolution.
        String authority = UpdateApkProvider.authority(context);
        assertNull(UpdateApkProvider.resolveVerifiedFile(context,
                Uri.parse("content://" + authority + "/../shared_prefs/secret.xml")));
        assertNull(UpdateApkProvider.resolveVerifiedFile(context,
                Uri.parse("content://" + authority + "/sub/hermes-update-v1.0.0.apk")));
        assertNull(UpdateApkProvider.resolveVerifiedFile(context,
                Uri.parse("content://com.other.app.updates/hermes-update-v1.0.0.apk")));
        assertNull(UpdateApkProvider.resolveVerifiedFile(context,
                Uri.parse("content://" + authority + "/hermes-update-nonexistent.apk")));

        UpdateApkProvider.purgeAll(context);
        File dir = UpdateApkProvider.updatesDir(context);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File sample = new File(dir, UpdateApkProvider.fileNameForTag("v9.9.9"));
        byte[] bytes = "verified-apk-stub".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(sample)) {
            out.write(bytes);
        }
        try {
            Uri uri = UpdateApkProvider.uriForFile(context, sample);
            assertEquals(sample.getCanonicalFile(), UpdateApkProvider.resolveVerifiedFile(context, uri));
            assertEquals(UpdateApkProvider.MIME_APK, context.getContentResolver().getType(uri));

            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                assertNotNull(cursor);
                assertTrue(cursor.moveToFirst());
                assertEquals(sample.getName(), cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
                assertEquals(bytes.length, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
            }

            try {
                context.getContentResolver().openFileDescriptor(uri, "w");
                fail("Write mode must be rejected on UpdateApkProvider");
            } catch (SecurityException expected) {
                // ok
            }
        } finally {
            UpdateApkProvider.purgeAll(context);
        }

        // 3. Native UpdatePanel mounts cleanly on a real Activity.
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        AtomicReference<UpdatePanel> panelRef = new AtomicReference<>();
        try {
            runTestOnUiThread(() -> {
                UpdatePanel panel = new UpdatePanel(activity);
                panelRef.set(panel);
                View view = panel.buildView();
                assertNotNull(view);
                activity.setContentView(view);
                assertNotNull(UpdatePanel.installedVersionName(activity));
                panel.dismissDialogs();
                panel.close();
                panelRef.set(null);
            });
            getInstrumentation().waitForIdleSync();
        } finally {
            try {
                UpdatePanel openPanel = panelRef.getAndSet(null);
                if (openPanel != null) {
                    runTestOnUiThread(() -> {
                        openPanel.dismissDialogs();
                        openPanel.close();
                    });
                }
            } finally {
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }
}
