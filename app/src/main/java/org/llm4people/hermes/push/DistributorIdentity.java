package org.llm4people.hermes.push;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Bundle;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Package names can be reused after uninstall. Bind consent to Android's verified APK signatures. */
final class DistributorIdentity {
    private DistributorIdentity() {}

    @SuppressWarnings("deprecation") // API 28 supports SigningInfo through the integer flags overload.
    private static SigningInfo installed(Context context, String name) {
        try {
            return context.getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
        } catch (PackageManager.NameNotFoundException gone) { return null; }
    }

    private static String digest(Signature cert) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String current(SigningInfo signing) {
        if (signing == null || signing.getApkContentsSigners() == null) return null;
        List<String> certificates = new ArrayList<>();
        for (Signature cert : signing.getApkContentsSigners()) certificates.add(digest(cert));
        Collections.sort(certificates);
        return certificates.isEmpty() ? null : String.join(".", certificates);
    }

    static String current(Context context, String name) { return current(installed(context, name)); }

    static boolean matches(Context context, String name, String authorized) {
        if (authorized == null) return false;
        SigningInfo signing = installed(context, name);
        if (authorized.equals(current(signing))) return true;
        if (signing != null && !signing.hasMultipleSigners() && signing.getSigningCertificateHistory() != null)
            for (Signature ancestor : signing.getSigningCertificateHistory())
                if (authorized.equals(digest(ancestor))) return true;
        return false;
    }

    // The SDK also broadcasts the old wire token during unregister, even to saved fallbacks.
    // Always clear its local state, but only send capabilities to the signer this profile chose.
    static Context outgoing(Context context, PushStore.Device device) {
        return new ContextWrapper(context) {
            private boolean allowed(Intent intent) {
                return device.distributor.equals(intent.getPackage()) && matches(context, device.distributor, device.signer);
            }
            @Override public void sendBroadcast(Intent intent) {
                if (allowed(intent)) super.sendBroadcast(intent);
            }
            @Override public void sendBroadcast(Intent intent, String permission, Bundle options) {
                if (allowed(intent)) super.sendBroadcast(intent, permission, options);
            }
        };
    }
}
