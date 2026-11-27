package org.llm4people.hermes.thread;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.profile.Account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Profile-epoch-fenced bounded LRU tail cache for instant (&lt;5ms, zero blank screen) thread switching
 * plus optional bounded encrypted vault tail snapshots inside {@link Account}.
 * Cleared immediately on profile switch, lock, {@code onTrimMemory}, or {@code forget(id)}.
 */
public final class ThreadTailCache {
    public static final int MAX_THREADS = 24;
    public static final int MAX_MESSAGES_PER_THREAD = 60;
    public static final int MAX_SNAPSHOT_CHARS = 96 * 1024;
    public static final String VAULT_KEY_PREFIX = "thread.tail.v1.";

    public interface VaultBacking {
        String read(String key);
        void write(String key, String value);
        void remove(String key);
    }

    public record Entry(
            String sessionId,
            String remoteSessionId,
            String title,
            List<String> encodedRows,
            boolean hasMore,
            Long oldestId,
            Long newestId,
            int totalCount,
            long cachedAtMs) {
        public Entry {
            if (sessionId == null || sessionId.trim().isEmpty()) {
                throw new IllegalArgumentException("sessionId required");
            }
            sessionId = sessionId.trim();
            remoteSessionId = remoteSessionId == null ? "" : remoteSessionId.trim();
            title = title == null ? "" : title;
            List<String> safe = new ArrayList<>();
            if (encodedRows != null) {
                int start = Math.max(0, encodedRows.size() - MAX_MESSAGES_PER_THREAD);
                for (int i = start; i < encodedRows.size(); i++) {
                    String r = encodedRows.get(i);
                    if (r != null && !r.isEmpty()) safe.add(r);
                }
            }
            encodedRows = Collections.unmodifiableList(safe);
        }
    }

    private final int maxThreads;
    private final LinkedHashMap<String, Entry> lru;
    private String activeProfileId;
    private long epoch;

    public ThreadTailCache() {
        this(MAX_THREADS);
    }

    public ThreadTailCache(int maxThreads) {
        if (maxThreads < 1) throw new IllegalArgumentException("maxThreads must be >= 1");
        this.maxThreads = maxThreads;
        this.lru = new LinkedHashMap<>(Math.min(32, maxThreads + 1), 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, ThreadTailCache.Entry> eldest) {
                return size() > ThreadTailCache.this.maxThreads;
            }
        };
    }

    public static VaultBacking fromAccount(Account account) {
        Objects.requireNonNull(account, "account");
        return new VaultBacking() {
            @Override public String read(String key) { return account.read(key); }
            @Override public void write(String key, String value) { account.write(key, value); }
            @Override public void remove(String key) { account.remove(key); }
        };
    }

    /** Switches active profile scope, incrementing the epoch and wiping all prior profile entries from RAM. */
    public synchronized long bindProfile(String profileId) {
        if (!Objects.equals(activeProfileId, profileId)) {
            lru.clear();
            activeProfileId = profileId;
            epoch++;
        }
        return epoch;
    }

    public synchronized int size() { return lru.size(); }

    /** Stores an entry only if {@code expectedEpoch} matches the active profile epoch (drops stale async writes). */
    public synchronized boolean put(long expectedEpoch, Entry entry) {
        if (entry == null || expectedEpoch != epoch || activeProfileId == null) return false;
        lru.put(entry.sessionId(), entry);
        return true;
    }

    /** Returns the cached tail only if {@code expectedEpoch} matches the active profile epoch. */
    public synchronized Entry get(long expectedEpoch, String sessionId) {
        if (expectedEpoch != epoch || activeProfileId == null || sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        return lru.get(sessionId);
    }

    public synchronized void remove(String sessionId) {
        if (sessionId != null) lru.remove(sessionId);
    }

    /** Clears all in-memory entries and advances the epoch (call on lock, switch, or logout). */
    public synchronized void clear() {
        lru.clear();
        activeProfileId = null;
        epoch++;
    }

    /** Releases RAM under Android {@code onTrimMemory} without changing profile identity. */
    public synchronized void onTrimMemory() {
        lru.clear();
    }

    /** Derives a safe deterministic vault key for a session ID (never leaks raw paths). */
    public static String vaultKey(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId required");
        }
        return VAULT_KEY_PREFIX + org.llm4people.hermes.Digests.sha256Hex(sessionId.trim()).substring(0, 32);
    }

    /**
     * Persists a bounded tail snapshot into the profile's encrypted {@link Account} vault.
     * Trims oldest rows automatically if needed to stay strictly within {@link #MAX_SNAPSHOT_CHARS}.
     */
    public static boolean saveSnapshot(VaultBacking vault, Entry entry) {
        if (vault == null || entry == null) return false;
        try {
            String encoded = encodeSnapshot(entry, MAX_SNAPSHOT_CHARS);
            if (encoded == null) return false;
            vault.write(vaultKey(entry.sessionId()), encoded);
            return true;
        } catch (RuntimeException failed) {
            logWarning("tail_snapshot_save_failed");
            return false;
        }
    }

    /** Loads a persisted encrypted tail snapshot; fails open (returns null) on missing or corrupted data. */
    public static Entry loadSnapshot(VaultBacking vault, String sessionId) {
        if (vault == null || sessionId == null || sessionId.trim().isEmpty()) return null;
        try {
            String raw = vault.read(vaultKey(sessionId));
            if (raw == null || raw.isEmpty()) return null;
            Entry decoded = decodeSnapshot(raw);
            if (decoded == null || !sessionId.trim().equals(decoded.sessionId())) {
                logWarning("tail_snapshot_corrupt");
                return null;
            }
            return decoded;
        } catch (RuntimeException unreadable) {
            logWarning("tail_snapshot_corrupt");
            return null;
        }
    }

    public static void wipeSnapshot(VaultBacking vault, String sessionId) {
        if (vault == null || sessionId == null || sessionId.trim().isEmpty()) return;
        try {
            vault.remove(vaultKey(sessionId));
        } catch (RuntimeException ignored) {
            logWarning("tail_snapshot_wipe_failed");
        }
    }

    static String encodeSnapshot(Entry entry, int maxChars) {
        String header = "v1\t" + escape(entry.sessionId()) + "\t" + escape(entry.remoteSessionId()) + "\t"
                + escape(entry.title()) + "\t" + (entry.hasMore() ? "1" : "0") + "\t"
                + (entry.oldestId() == null ? "" : entry.oldestId()) + "\t"
                + (entry.newestId() == null ? "" : entry.newestId()) + "\t"
                + entry.totalCount() + "\t" + entry.cachedAtMs() + "\n";
        if (header.length() > maxChars) return null;

        List<String> rows = entry.encodedRows();
        List<String> keptReverse = new ArrayList<>();
        int budget = maxChars - header.length();
        for (int i = rows.size() - 1; i >= 0; i--) {
            String line = escape(rows.get(i)) + "\n";
            if (line.length() > budget) break;
            keptReverse.add(line);
            budget -= line.length();
        }
        StringBuilder sb = new StringBuilder(maxChars - budget);
        sb.append(header);
        for (int i = keptReverse.size() - 1; i >= 0; i--) sb.append(keptReverse.get(i));
        return sb.toString();
    }

    static Entry decodeSnapshot(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_SNAPSHOT_CHARS) return null;
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0) return null;
        String[] hdr = lines[0].split("\t", -1);
        if (hdr.length != 9 || !"v1".equals(hdr[0])) return null;
        try {
            String sessionId = unescape(hdr[1]);
            String remoteId = unescape(hdr[2]);
            String title = unescape(hdr[3]);
            boolean hasMore = "1".equals(hdr[4]);
            Long oldestId = hdr[5].isEmpty() ? null : Long.parseLong(hdr[5]);
            Long newestId = hdr[6].isEmpty() ? null : Long.parseLong(hdr[6]);
            int total = Integer.parseInt(hdr[7]);
            long cachedAt = Long.parseLong(hdr[8]);
            List<String> rows = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) continue;
                rows.add(unescape(lines[i]));
            }
            return new Entry(sessionId, remoteId, title, rows, hasMore, oldestId, newestId, total, cachedAt);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 >= text.length()) throw new IllegalArgumentException("Truncated escape");
                char next = text.charAt(++i);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> throw new IllegalArgumentException("Unknown escape");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void logWarning(String action) {
        Diagnostics.warning("threads", action);
    }
}
