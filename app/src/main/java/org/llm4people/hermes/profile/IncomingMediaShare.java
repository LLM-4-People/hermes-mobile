package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.camera.CapturedMedia;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary encrypted staging for inbound shared images and documents (up to MAX_ITEMS).
 * Zero plaintext files touch disk; sealed with the device keystore key and bound to one unlocked profile.
 */
public final class IncomingMediaShare {
    private static final String ALIAS = "hermes.share.media.v1";
    private static final String BLOB_DIR = "incoming_media_sealed";
    private static final long LIFETIME = 30 * 60_000L;
    public static final int MAX_ITEMS = 6;

    private IncomingMediaShare() {}

    public record StagedFile(
            CapturedMedia.Kind kind,
            String name,
            String mimeType,
            byte[] bytes,
            String sha256Hex,
            String thumbnailBase64) {}

    public record Batch(String receiptId, List<StagedFile> items, String confirmedThread) {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ALIAS, Context.MODE_PRIVATE);
    }

    private static File blobDir(Context context) {
        return new File(context.getCacheDir(), BLOB_DIR);
    }

    private static File sealedFile(Context context, String id) {
        return new File(blobDir(context), id + ".enc");
    }

    public static synchronized String pending(Context context) {
        SharedPreferences prefs = prefs(context);
        String id = pendingIdFromPrefs(prefs, System.currentTimeMillis(),
                expiredId -> {
                    deleteSealedBlob(context, expiredId);
                    sweepOrphanedBlobs(context, null);
                },
                () -> sweepOrphanedBlobs(context, null));
        if (id == null) return null;
        if (sealedFile(context, id).isFile()) return id;
        discard(context, id);
        return null;
    }

    static String pendingIdFromPrefs(SharedPreferences prefs, long nowMs) {
        return pendingIdFromPrefs(prefs, nowMs, null, null);
    }

    static String pendingIdFromPrefs(SharedPreferences prefs, long nowMs,
                                     java.util.function.Consumer<String> onDeleteBlob) {
        return pendingIdFromPrefs(prefs, nowMs, onDeleteBlob, null);
    }

    static String pendingIdFromPrefs(SharedPreferences prefs, long nowMs,
                                     java.util.function.Consumer<String> onDeleteBlob,
                                     Runnable onCorruptSweep) {
        if (prefs == null) return null;
        String id;
        try {
            id = prefs.getString("id", null);
        } catch (ClassCastException corrupt) {
            Diagnostics.warning("share", "media_pref_corrupt");
            clearCorruptPrefs(prefs);
            if (onCorruptSweep != null) onCorruptSweep.run();
            return null;
        }
        if (id == null) return null;
        long remaining = expiry(prefs) - nowMs;
        if (remaining > 0 && remaining <= LIFETIME) return id;
        if (onDeleteBlob != null) onDeleteBlob.accept(id);
        clearCorruptPrefs(prefs);
        return null;
    }

    static int sweepOrphanedBlobs(Context context, String activeId) {
        if (context == null) return 0;
        return sweepOrphanedBlobs(blobDir(context), activeId);
    }

    static int sweepOrphanedBlobs(File dir, String activeId) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        String expected = (activeId != null && !activeId.isEmpty()) ? activeId + ".enc" : null;
        int removed = 0;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (expected != null && expected.equals(file.getName())) continue;
            if (file.delete()) {
                removed++;
            } else {
                Diagnostics.warning("share", "media_orphan_delete_failed");
            }
        }
        return removed;
    }

    private static void deleteSealedBlob(Context context, String id) {
        if (context == null || id == null || id.isEmpty()) return;
        File blob = sealedFile(context, id);
        if (blob.exists() && !blob.delete()) {
            Diagnostics.warning("share", "media_blob_delete_failed");
        }
    }

    /** Stage 1..MAX_ITEMS sanitized items in one encrypted blob; never overwrites an existing pending share. */
    public static synchronized String create(Context context, List<CapturedMedia.Item> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Expected 1.." + MAX_ITEMS + " items");
        }
        if (pending(context) != null) return null;
        String id = UUID.randomUUID().toString();
        long expires = System.currentTimeMillis() + LIFETIME;
        try {
            JSONArray array = new JSONArray();
            for (CapturedMedia.Item item : items) {
                JSONObject row = new JSONObject();
                row.put("kind", item.kind().name());
                row.put("name", item.name());
                row.put("mime", item.mimeType());
                row.put("sha256", item.sha256Hex());
                row.put("bytes", Base64.encodeToString(item.bytes(), Base64.NO_WRAP));
                if (item.thumbnailBase64() != null) row.put("thumbnail", item.thumbnailBase64());
                array.put(row);
            }
            JSONObject payload = new JSONObject().put("items", array);
            save(context, id, expires, payload, true);
            return id;
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    public static synchronized String owner(Context context, String id) {
        JSONObject data = read(context, id);
        return data == null ? null : data.optString("owner", null);
    }

    public static synchronized Batch open(Context context, String id, Account account) {
        if (account == null || !account.unlocked()) return null;
        JSONObject data = read(context, id);
        if (data == null) return null;
        String owner = data.optString("owner", null);
        if (owner != null && !owner.equals(account.id)) return null;
        if (owner == null) {
            try {
                data.put("owner", account.id);
            } catch (JSONException invalid) {
                throw new IllegalStateException(invalid);
            }
            save(context, id, expiry(prefs(context)), data, false);
        }
        JSONArray rows = data.optJSONArray("items");
        if (rows == null || rows.length() == 0) return null;
        String defaultFileName = AppLanguage.wrapContext(context).getString(R.string.share_default_file_name);
        List<StagedFile> staged = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            CapturedMedia.Kind kind = "IMAGE".equals(row.optString("kind"))
                    ? CapturedMedia.Kind.IMAGE : CapturedMedia.Kind.DOCUMENT;
            byte[] decoded = Base64.decode(row.optString("bytes", ""), Base64.NO_WRAP);
            staged.add(new StagedFile(
                    kind,
                    resolveFileName(row.optString("name", ""), defaultFileName),
                    row.optString("mime", "application/octet-stream"),
                    decoded,
                    row.optString("sha256", ""),
                    row.optString("thumbnail", null)));
        }
        if (staged.isEmpty()) return null;
        return new Batch(id, Collections.unmodifiableList(staged), data.optString("thread", null));
    }

    static String resolveFileName(String rawName, String localizedFallback) {
        if (rawName != null && !rawName.trim().isEmpty()) return rawName.trim();
        return localizedFallback == null ? "" : localizedFallback;
    }

    public static synchronized boolean target(Context context, String id, Account account, String session) {
        JSONObject data = read(context, id);
        if (data == null || account == null || !account.unlocked() || !account.id.equals(data.optString("owner"))) {
            return false;
        }
        if (data.has("thread")) return session.equals(data.optString("thread"));
        try {
            data.put("thread", session);
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        }
        save(context, id, expiry(prefs(context)), data, false);
        return true;
    }

    public static synchronized void discard(Context context, String id) {
        SharedPreferences prefs = prefs(context);
        String storedId;
        try {
            storedId = prefs.getString("id", null);
        } catch (ClassCastException corrupt) {
            Diagnostics.warning("share", "media_pref_corrupt");
            clearCorruptPrefs(prefs);
            sweepOrphanedBlobs(context, null);
            return;
        }
        if (id == null || !id.equals(storedId)) return;
        deleteSealedBlob(context, id);
        sweepOrphanedBlobs(context, null);
        Map<String, String> cleared = new HashMap<>();
        cleared.put("id", null);
        cleared.put("expires", null);
        PreferenceTransaction.commit(prefs, cleared);
    }

    /** Discard any pending sealed media share claimed by {@code profileId} when that profile is forgotten. */
    public static synchronized void forgetProfile(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        try {
            String id = pending(context);
            if (id == null) {
                sweepOrphanedBlobs(context, null);
                return;
            }
            if (profileId.equals(owner(context, id))) {
                discard(context, id);
            } else {
                sweepOrphanedBlobs(context, id);
            }
        } catch (RuntimeException unreadable) {
            Diagnostics.warning("share", "media_forget_read_failed", unreadable);
        }
    }

    private static void clearCorruptPrefs(SharedPreferences prefs) {
        Map<String, String> cleared = new HashMap<>();
        cleared.put("id", null);
        cleared.put("expires", null);
        try {
            PreferenceTransaction.commit(prefs, cleared);
        } catch (IllegalStateException failed) {
            Diagnostics.warning("share", "media_corrupt_clear_failed");
        }
    }

    private static long expiry(SharedPreferences prefs) {
        try { return Long.parseLong(prefs.getString("expires", "0")); }
        catch (NumberFormatException invalid) { return 0; }
        catch (ClassCastException corrupt) {
            Diagnostics.warning("share", "media_expiry_corrupt");
            return 0;
        }
    }

    private static byte[] binding(String id, long expires) {
        return (ALIAS + "\n" + id + "\n" + expires).getBytes(StandardCharsets.UTF_8);
    }

    private static void save(Context context, String id, long expires, JSONObject value, boolean isNewReceipt) {
        byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
        try {
            byte[] sealed = ProfileEnvelope.encrypt(AccountStore.deviceKey(ALIAS, true), binding(id, expires), plain);
            Map<String, String> meta = new HashMap<>();
            meta.put("id", id);
            meta.put("expires", Long.toString(expires));
            writeSealedAtomically(blobDir(context), id, sealed,
                    () -> PreferenceTransaction.commit(prefs(context), meta), isNewReceipt);
        } catch (IOException | GeneralSecurityException failed) {
            Diagnostics.warning("share", "media_seal_failed", failed);
            throw new IllegalStateException("Media share encryption failed", failed);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Writes sealed bytes via synced temp file + atomic rename, cleaning up temp/orphan files on failure. */
    static void writeSealedAtomically(File dir, String id, byte[] sealed,
                                      Runnable commitMeta, boolean deleteTargetOnCommitFailure) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create sealed media cache dir");
        }
        File target = new File(dir, id + ".enc");
        File tmp = new File(dir, id + ".enc.tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(sealed);
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                Diagnostics.warning("share", "media_tmp_delete_failed");
            }
        }
        try {
            commitMeta.run();
        } catch (RuntimeException commitFailed) {
            if (deleteTargetOnCommitFailure && target.exists() && !target.delete()) {
                Diagnostics.warning("share", "media_blob_delete_failed");
            }
            throw commitFailed;
        }
    }

    private static JSONObject read(Context context, String id) {
        if (id == null || !id.equals(pending(context))) return null;
        SharedPreferences prefs = prefs(context);
        long expires = expiry(prefs);
        try {
            byte[] envelope = Files.readAllBytes(sealedFile(context, id).toPath());
            byte[] plain = ProfileEnvelope.decrypt(AccountStore.deviceKey(ALIAS, false), binding(id, expires), envelope, 0);
            try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
            finally { Arrays.fill(plain, (byte) 0); }
        } catch (IOException | GeneralSecurityException | JSONException | IllegalArgumentException failed) {
            Diagnostics.warning("share", "media_unseal_failed", failed);
            throw new IllegalStateException("Shared media unavailable", failed);
        }
    }
}
