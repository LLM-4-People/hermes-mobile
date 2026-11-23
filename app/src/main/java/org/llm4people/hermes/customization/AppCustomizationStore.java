package org.llm4people.hermes.customization;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.profile.Account;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Encrypted per-profile persistence for {@link AppCustomization} with a bounded 1-tap Undo stack,
 * Reset-to-default, fail-closed corruption handling, and atomic {@link #wipe(Account)} for forget.
 * Testable directly on the host JVM via {@link Backing}.
 */
public final class AppCustomizationStore {
    public static final String VAULT_KEY = "workspace.customization.v1";
    public static final int MAX_UNDO_DEPTH = 10;

    public interface Backing {
        String read();
        void write(String value);
        void remove();
    }

    private final Backing backing;
    private AppCustomization current;
    private final Deque<AppCustomization> undoStack = new ArrayDeque<>();

    public static AppCustomizationStore forAccount(Account account) {
        Objects.requireNonNull(account, "account");
        return new AppCustomizationStore(new AccountBacking(account));
    }

    public AppCustomizationStore(Backing backing) {
        this.backing = Objects.requireNonNull(backing, "backing");
        loadFromBacking();
    }

    public synchronized AppCustomization current() {
        return current;
    }

    public synchronized boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public synchronized int undoDepth() {
        return undoStack.size();
    }

    /** Applies a validated customization, pushing the previous state onto the Undo stack. */
    public synchronized AppCustomization apply(AppCustomization next) {
        Objects.requireNonNull(next, "next");
        if (next.equals(current)) return current;
        pushUndoLocked(current);
        current = next;
        persistLocked();
        Diagnostics.event("customization", "applied");
        return current;
    }

    /** Restores the previous customization from the Undo stack, or returns current if stack is empty. */
    public synchronized AppCustomization undo() {
        if (undoStack.isEmpty()) return current;
        current = undoStack.removeLast();
        persistLocked();
        Diagnostics.event("customization", "undone");
        return current;
    }

    /** Resets customization to {@link AppCustomization#defaults()}, preserving Undo recovery. */
    public synchronized AppCustomization resetToDefault() {
        AppCustomization defaults = AppCustomization.defaults();
        if (defaults.equals(current)) return current;
        pushUndoLocked(current);
        current = defaults;
        persistLocked();
        Diagnostics.event("customization", "reset");
        return current;
    }

    /** Atomic removal of encrypted customization state during profile forget/wipe. */
    public static void wipe(Account account) {
        if (account == null) return;
        try {
            account.remove(VAULT_KEY);
            Diagnostics.debug("customization", "wiped");
        } catch (RuntimeException skippedIfLocked) {
            Diagnostics.warning("customization", "wipe_skipped");
        }
    }

    public synchronized void clearAll() {
        undoStack.clear();
        current = AppCustomization.defaults();
        backing.remove();
        Diagnostics.debug("customization", "cleared");
    }

    private void loadFromBacking() {
        current = AppCustomization.defaults();
        undoStack.clear();
        String raw;
        try {
            raw = backing.read();
        } catch (RuntimeException unreadable) {
            Diagnostics.warning("customization", "vault_unreadable");
            return;
        }
        if (raw == null || raw.trim().isEmpty()) return;
        String[] lines = raw.split("\n", -1);
        if (lines.length < 2 || !"cust-store-v1".equals(lines[0])) {
            Diagnostics.warning("customization", "store_decode_invalid");
            return;
        }
        AppCustomization parsedCurrent = AppCustomization.decode(lines[1]);
        if (parsedCurrent == null) {
            Diagnostics.warning("customization", "store_decode_invalid");
            return;
        }
        Deque<AppCustomization> parsedUndo = new ArrayDeque<>();
        for (int i = 2; i < lines.length && parsedUndo.size() < MAX_UNDO_DEPTH; i++) {
            if (lines[i].isEmpty()) continue;
            AppCustomization item = AppCustomization.decode(lines[i]);
            if (item == null) {
                Diagnostics.warning("customization", "store_undo_invalid");
                return;
            }
            parsedUndo.addLast(item);
        }
        current = parsedCurrent;
        undoStack.addAll(parsedUndo);
    }

    private void pushUndoLocked(AppCustomization previous) {
        while (undoStack.size() >= MAX_UNDO_DEPTH) {
            undoStack.removeFirst();
        }
        undoStack.addLast(previous);
    }

    private void persistLocked() {
        StringBuilder sb = new StringBuilder();
        sb.append("cust-store-v1\n").append(current.encode());
        for (AppCustomization item : undoStack) {
            sb.append('\n').append(item.encode());
        }
        backing.write(sb.toString());
    }

    private static final class AccountBacking implements Backing {
        private final Account account;

        AccountBacking(Account account) { this.account = account; }

        @Override public String read() { return account.read(VAULT_KEY); }
        @Override public void write(String value) { account.write(VAULT_KEY, value); }
        @Override public void remove() { account.remove(VAULT_KEY); }
    }
}
