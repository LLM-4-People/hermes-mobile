package org.llm4people.hermes.templates;

import android.content.Context;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-profile encrypted custom workflow template store backed by {@link Account}'s AES-GCM vault.
 * Testable deterministically on the host JVM via {@link BackingStore}.
 */
public final class TemplateStore {
    public static final String STORAGE_KEY = "workspace.templates.v1";

    public interface BackingStore {
        String read(String key);
        void write(String key, String value);
        void remove(String key);
    }

    public static final class LimitExceededException extends IOException {
        public LimitExceededException() {
            super("Custom template limit reached");
        }
    }

    private final BackingStore backing;

    public TemplateStore(BackingStore backing) {
        this.backing = Objects.requireNonNull(backing, "backing");
    }

    public static TemplateStore forAccount(Account account) {
        Objects.requireNonNull(account, "account");
        return new TemplateStore(new BackingStore() {
            @Override public String read(String key) { return account.read(key); }
            @Override public void write(String key, String value) { account.write(key, value); }
            @Override public void remove(String key) { account.remove(key); }
        });
    }

    /** Wipes custom templates stored inside the profile vault (called on profile forget or explicit reset). */
    public static void wipe(Account account) {
        if (account == null) return;
        try {
            account.remove(STORAGE_KEY);
            logEvent("wiped");
        } catch (IllegalStateException lockedOrClosed) {
            logWarning("wipe_skipped_locked");
        }
    }

    public synchronized List<WorkflowTemplate> listCustom() throws IOException {
        String raw;
        try {
            raw = backing.read(STORAGE_KEY);
        } catch (RuntimeException unreadable) {
            logWarning("read_failed");
            throw new IOException("Stored templates unreadable", unreadable);
        }
        return WorkflowTemplate.decodeList(raw);
    }

    /** Convenience list combining the 6 built-in curated workflows followed by the profile's custom templates. */
    public List<WorkflowTemplate> allTemplates(Context context) {
        List<WorkflowTemplate> all = new ArrayList<>(WorkflowTemplate.builtIn(context));
        try {
            all.addAll(listCustom());
        } catch (IOException corrupted) {
            logWarning("custom_list_corrupt");
        }
        return Collections.unmodifiableList(all);
    }

    /** Creates or updates a profile-scoped custom template with strict length and secret validation. */
    public synchronized WorkflowTemplate saveCustom(String existingId, String title, String subtitle, String draft)
            throws IOException {
        List<WorkflowTemplate> current = new ArrayList<>(listCustom());
        String targetId;
        int replaceIndex = -1;
        if (existingId != null && !existingId.trim().isEmpty()) {
            targetId = WorkflowTemplate.validateId(existingId);
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).id.equals(targetId)) {
                    replaceIndex = i;
                    break;
                }
            }
        } else {
            targetId = generateId(current);
        }
        if (replaceIndex < 0 && current.size() >= WorkflowTemplate.MAX_CUSTOM_TEMPLATES) {
            throw new LimitExceededException();
        }
        WorkflowTemplate entry = new WorkflowTemplate(targetId, title, subtitle, draft, false);
        if (replaceIndex >= 0) current.set(replaceIndex, entry);
        else current.add(entry);
        persist(current);
        logEvent(replaceIndex >= 0 ? "updated" : "created");
        return entry;
    }

    public synchronized boolean deleteCustom(String id) throws IOException {
        if (!WorkflowTemplate.validId(id)) return false;
        List<WorkflowTemplate> current = new ArrayList<>(listCustom());
        boolean removed = current.removeIf(item -> item.id.equals(id));
        if (!removed) return false;
        persist(current);
        logEvent("deleted");
        return true;
    }

    public synchronized void wipe() {
        backing.remove(STORAGE_KEY);
        logEvent("wiped");
    }

    private void persist(List<WorkflowTemplate> list) throws IOException {
        if (list.isEmpty()) {
            backing.remove(STORAGE_KEY);
        } else {
            backing.write(STORAGE_KEY, WorkflowTemplate.encodeList(list));
        }
    }

    private static String generateId(List<WorkflowTemplate> existing) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
            String candidate = "tpl_" + suffix;
            boolean collision = false;
            for (WorkflowTemplate t : existing) {
                if (t.id.equals(candidate)) {
                    collision = true;
                    break;
                }
            }
            if (!collision) return candidate;
        }
        throw new IllegalStateException("Could not allocate unique template id");
    }

    private static void logEvent(String action) {
        try { Diagnostics.event("templates", action); }
        catch (RuntimeException unmockedHostJvm) {}
    }

    private static void logWarning(String action) {
        try { Diagnostics.warning("templates", action); }
        catch (RuntimeException unmockedHostJvm) {}
    }
}
