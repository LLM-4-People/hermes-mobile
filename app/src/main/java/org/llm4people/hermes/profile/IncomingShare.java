package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One temporary encrypted envelope, addressed only by an unguessable receipt. */
public final class IncomingShare {
    private static final String ALIAS = "hermes.share.text.v1";
    private static final long LIFETIME = 30 * 60_000L;
    public static final int MAX_TEXT = 32 * 1024;

    private IncomingShare() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ALIAS, Context.MODE_PRIVATE);
    }

    public static synchronized String pending(Context context) {
        SharedPreferences prefs = prefs(context);
        String id = prefs.getString("id", null);
        if (id == null) return null;
        long remaining = expiry(prefs) - System.currentTimeMillis();
        if (remaining > 0 && remaining <= LIFETIME) return id;
        discard(context, id);
        return null;
    }

    /** A busy entry never overwrites the other share. */
    public static synchronized String create(Context context, String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT) throw new IllegalArgumentException();
        if (pending(context) != null) return null;
        String id = UUID.randomUUID().toString();
        try {
            save(context, id, System.currentTimeMillis() + LIFETIME, new JSONObject().put("text", text));
            return id;
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
    }

    public static synchronized String owner(Context context, String id) {
        JSONObject data = read(context, id);
        return data == null ? null : data.optString("owner", null);
    }

    public record Entry(String text, String confirmedThread) {}

    /** Only the first unlocked profile may claim and subsequently open this receipt. */
    public static synchronized Entry open(Context context, String id, Account account) {
        if (!account.unlocked()) return null;
        JSONObject data = read(context, id);
        if (data == null) return null;
        String owner = data.optString("owner", null);
        if (owner != null && !owner.equals(account.id)) return null;
        if (owner == null) {
            try { data.put("owner", account.id); }
            catch (JSONException invalid) { throw new IllegalStateException(invalid); }
            save(context, id, expiry(prefs(context)), data);
        }
        return new Entry(data.optString("text"), data.optString("thread", null));
    }

    /** After explicit confirmation, a retry can append only in the originally selected discussion. */
    public static synchronized boolean target(Context context, String id, Account account, String session) {
        JSONObject data = read(context, id);
        if (data == null || !account.unlocked() || !account.id.equals(data.optString("owner"))) return false;
        if (data.has("thread")) return session.equals(data.optString("thread"));
        try { data.put("thread", session); }
        catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        save(context, id, expiry(prefs(context)), data);
        return true;
    }

    public static synchronized void discard(Context context, String id) {
        SharedPreferences prefs = prefs(context);
        if (id == null || !id.equals(prefs.getString("id", null))) return;
        write(prefs, null, null, null);
    }

    /** Discard any pending shared text envelope claimed by {@code profileId} when that profile is forgotten. */
    public static synchronized void forgetProfile(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        String id = pending(context);
        if (id == null) return;
        try {
            if (profileId.equals(owner(context, id))) discard(context, id);
        } catch (IllegalStateException unreadable) {
            org.llm4people.hermes.Diagnostics.warning("share", "text_forget_read_failed", unreadable);
        }
    }

    private static long expiry(SharedPreferences prefs) {
        try { return Long.parseLong(prefs.getString("expires", "0")); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private static void write(SharedPreferences prefs, String id, String expires, String sealed) {
        Map<String, String> values = new HashMap<>();
        values.put("id", id);
        values.put("expires", expires);
        values.put("data", sealed);
        PreferenceTransaction.commit(prefs, values);
    }

    private static JSONObject read(Context context, String id) {
        if (id == null || !id.equals(pending(context))) return null;
        SharedPreferences prefs = prefs(context);
        long expires = expiry(prefs);
        try {
            byte[] envelope = Base64.decode(prefs.getString("data", ""), Base64.NO_WRAP);
            byte[] plain = ProfileEnvelope.decrypt(AccountStore.deviceKey(ALIAS, false), binding(id, expires), envelope, 0);
            try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
            finally { Arrays.fill(plain, (byte) 0); }
        } catch (IOException | GeneralSecurityException | JSONException | IllegalArgumentException failed) {
            throw new IllegalStateException("Shared text unavailable", failed);
        }
    }

    private static byte[] binding(String id, long expires) {
        return (ALIAS + "\n" + id + "\n" + expires).getBytes(StandardCharsets.UTF_8);
    }

    private static void save(Context context, String id, long expires, JSONObject value) {
        byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
        try {
            byte[] sealed = ProfileEnvelope.encrypt(AccountStore.deviceKey(ALIAS, true), binding(id, expires), plain);
            SharedPreferences prefs = prefs(context);
            write(prefs, id, Long.toString(expires), Base64.encodeToString(sealed, Base64.NO_WRAP));
        } catch (IOException | GeneralSecurityException failed) {
            throw new IllegalStateException("Share encryption failed", failed);
        } finally { Arrays.fill(plain, (byte) 0); }
    }
}
