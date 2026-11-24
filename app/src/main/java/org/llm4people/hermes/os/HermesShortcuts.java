package org.llm4people.hermes.os;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes opaque, zero-leak dynamic Android app shortcuts.
 * Never includes profile names, thread titles, or private message content.
 */
public final class HermesShortcuts {
    public record Descriptor(String id, int shortLabelRes, int longLabelRes, int iconRes,
                             OsEntryIntent.Target target) {}

    private static final List<Descriptor> DESCRIPTORS = List.of(
            new Descriptor("hermes_new_discussion", R.string.shortcut_new_short, R.string.shortcut_new_long,
                    R.drawable.thread_new, OsEntryIntent.Target.NEW_DISCUSSION),
            new Descriptor("hermes_dictate", R.string.shortcut_dictate_short, R.string.shortcut_dictate_long,
                    R.drawable.ic_voice, OsEntryIntent.Target.DICTATE),
            new Descriptor("hermes_atelier", R.string.shortcut_atelier_short, R.string.shortcut_atelier_long,
                    R.drawable.ic_desk, OsEntryIntent.Target.ATELIER),
            new Descriptor("hermes_activity", R.string.shortcut_activity_short, R.string.shortcut_activity_long,
                    R.drawable.ic_activity, OsEntryIntent.Target.ACTIVITY)
    );

    private HermesShortcuts() {}

    public static List<Descriptor> descriptors() {
        return DESCRIPTORS;
    }

    public static void publish(Context context) {
        publish(context, null);
    }

    /** Publishes opaque dynamic shortcuts using a context explicitly localized for {@code mode}. */
    public static void publish(Context context, AppLanguage.Mode mode) {
        if (context == null) return;
        Context localized = AppLanguage.localizedContext(context, mode);
        if (localized == null) return;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        int limit = Math.max(0, Math.min(DESCRIPTORS.size(), manager.getMaxShortcutCountPerActivity()));
        List<ShortcutInfo> infos = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Descriptor item = DESCRIPTORS.get(i);
            infos.add(new ShortcutInfo.Builder(context, item.id())
                    .setShortLabel(localized.getString(item.shortLabelRes()))
                    .setLongLabel(localized.getString(item.longLabelRes()))
                    .setIcon(Icon.createWithResource(context, item.iconRes()))
                    .setIntent(OsEntryIntent.intent(context, item.target()))
                    .setRank(i)
                    .build());
        }
        try {
            manager.setDynamicShortcuts(infos);
        } catch (RuntimeException error) {
            Diagnostics.warning("os", "shortcuts_publish_failed");
        }
    }

    /** Reports opaque shortcut usage so Android launchers rank frequently used shortcuts naturally. */
    public static void reportUsed(Context context, OsEntryIntent.Target target) {
        if (context == null || target == null) return;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        for (Descriptor item : DESCRIPTORS) {
            if (item.target() == target) {
                try {
                    manager.reportShortcutUsed(item.id());
                } catch (RuntimeException error) {
                    Diagnostics.warning("os", "shortcut_report_failed");
                }
                return;
            }
        }
    }
}
