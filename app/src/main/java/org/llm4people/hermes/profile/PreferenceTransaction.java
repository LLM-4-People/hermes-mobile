package org.llm4people.hermes.profile;

import android.content.SharedPreferences;
import java.util.Map;
import java.util.Set;

/** Commits a group of string records, restoring their previous values if the disk write fails. */
final class PreferenceTransaction {
    private PreferenceTransaction() {}

    /** A null value removes the key. Readers and conditional writers synchronize on the same preferences. */
    static void commit(SharedPreferences prefs, Map<String, String> changes) {
        synchronized (prefs) {
            SharedPreferences.Editor previous = prefs.edit();
            try {
                for (String key : changes.keySet()) previous.putString(key, prefs.getString(key, null));
            } catch (ClassCastException wrongType) {
                // A corrupt slot may contain any Android preference type. Copy the vault only in this case.
                Map<String, ?> records = prefs.getAll();
                for (String key : changes.keySet()) restore(previous, key, records.get(key));
            }
            SharedPreferences.Editor next = prefs.edit();
            changes.forEach(next::putString);
            if (next.commit()) return;
            // A failed Android disk write still changes its process cache. Repair it before releasing readers.
            if (!previous.commit()) throw new IllegalStateException("Preference rollback could not be committed");
            throw new IllegalStateException("Preferences could not be committed");
        }
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences.Editor editor, String key, Object value) {
        if (value == null) editor.remove(key);
        else if (value instanceof String text) editor.putString(key, text);
        else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
        else if (value instanceof Integer number) editor.putInt(key, number);
        else if (value instanceof Long number) editor.putLong(key, number);
        else if (value instanceof Float number) editor.putFloat(key, number);
        else if (value instanceof Set<?>) editor.putStringSet(key, (Set<String>) value);
        else throw new IllegalStateException("Unsupported preference type");
    }
}
