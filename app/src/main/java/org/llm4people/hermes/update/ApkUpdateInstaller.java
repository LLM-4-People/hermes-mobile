package org.llm4people.hermes.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Fail-closed verified APK downloader, SHA-256 + signing-certificate verifier, and native
 * Android package installer launcher. Deletes any unverified, tampered, mismatched, or cancelled
 * download immediately before returning.
 */
public final class ApkUpdateInstaller {
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public enum InstallAction {
        LAUNCHED,
        NEEDS_UNKNOWN_SOURCES_PERMISSION
    }

    public static final class VerificationException extends IOException {
        public enum Reason {
            CANCELLED,
            TOO_LARGE,
            CHECKSUM_MISMATCH,
            PACKAGE_MISMATCH,
            SIGNATURE_MISMATCH
        }

        public final Reason reason;

        public VerificationException(Reason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
        }
    }

    public interface ProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
    }

    /** Injectable streaming source for deterministic host-JVM checksum/size/cancel tests. */
    public interface StreamSource {
        StreamResponse open(String url) throws IOException;
    }

    public record StreamResponse(int statusCode, String locationHeader, long contentLength, InputStream body)
            implements AutoCloseable {
        @Override public void close() throws IOException {
            if (body != null) body.close();
        }
    }

    private ApkUpdateInstaller() {}

    /** Constant-time equality check over normalized lowercase 64-character SHA-256 hex strings. */
    public static boolean constantTimeHexEquals(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) return false;
        String a = expectedHex.trim().toLowerCase(Locale.ROOT);
        String b = actualHex.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_HEX.matcher(a).matches() || !SHA256_HEX.matcher(b).matches()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** True when both installed and downloaded packages share at least one signing certificate digest. */
    public static boolean signaturesCompatible(Set<String> installedCertDigests, Set<String> archiveCertDigests) {
        if (installedCertDigests == null || archiveCertDigests == null
                || installedCertDigests.isEmpty() || archiveCertDigests.isEmpty()) {
            return false;
        }
        for (String installed : installedCertDigests) {
            for (String candidate : archiveCertDigests) {
                if (constantTimeHexEquals(installed, candidate)) return true;
            }
        }
        return false;
    }

    /**
     * Download an APK from an allowlisted HTTPS GitHub URL into {@code targetDir}, compute streaming
     * SHA-256, enforce {@code maxApkBytes} and cancellation, and delete the file on ANY failure.
     */
    public static File downloadAndVerifyChecksum(
            StreamSource source,
            String downloadUrl,
            String expectedSha256,
            File targetDir,
            String finalFileName,
            long expectedSizeBytes,
            long maxApkBytes,
            ProgressListener progress,
            AtomicBoolean cancelled) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetDir, "targetDir");
        if (!UpdateApkProvider.isValidFileName(finalFileName)) {
            throw new IllegalArgumentException("Invalid APK filename");
        }
        if (expectedSha256 == null || !SHA256_HEX.matcher(expectedSha256.trim().toLowerCase(Locale.ROOT)).matches()) {
            throw new VerificationException(VerificationException.Reason.CHECKSUM_MISMATCH, "Missing valid SHA-256");
        }
        if (maxApkBytes <= 0) throw new IllegalArgumentException("maxApkBytes must be positive");
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("Could not create private updates directory");
        }
        File tempFile = new File(targetDir, finalFileName + ".part");
        File finalFile = new File(targetDir, finalFileName);
        deleteQuietly(tempFile);
        deleteQuietly(finalFile);

        boolean succeeded = false;
        try {
            String actualHex = streamToFile(source, downloadUrl, tempFile, expectedSizeBytes,
                    maxApkBytes, progress, cancelled);
            if (!constantTimeHexEquals(expectedSha256, actualHex)) {
                logWarning("checksum_mismatch");
                throw new VerificationException(VerificationException.Reason.CHECKSUM_MISMATCH,
                        "Downloaded APK SHA-256 mismatch");
            }
            if (!tempFile.renameTo(finalFile)) {
                throw new IOException("Could not finalize verified APK");
            }
            succeeded = true;
            logEvent("apk_checksum_verified");
            return finalFile;
        } finally {
            if (!succeeded) {
                deleteQuietly(tempFile);
                deleteQuietly(finalFile);
            }
        }
    }

    /** Full Android download + SHA-256 + package name + signing certificate verification. */
    public static File downloadAndVerifyForInstall(
            Context context,
            GithubReleaseClient client,
            GithubReleaseClient.ReleaseInfo release,
            HermesConfig config,
            ProgressListener progress,
            AtomicBoolean cancelled) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(config, "config");

        UpdateApkProvider.purgeAll(context);
        String expectedSha256 = client.fetchExpectedSha256(release);
        if (cancelled != null && cancelled.get()) {
            throw new VerificationException(VerificationException.Reason.CANCELLED, "Cancelled");
        }
        StreamSource source = new HttpsStreamSource(config);
        File targetDir = UpdateApkProvider.updatesDir(context);
        String fileName = UpdateApkProvider.fileNameForTag(release.tagName());
        File verified = downloadAndVerifyChecksum(
                source,
                release.apkDownloadUrl(),
                expectedSha256,
                targetDir,
                fileName,
                release.apkSizeBytes(),
                config.updateMaxApkBytes,
                progress,
                cancelled);
        try {
            verifyArchivePackageAndSignature(context, verified);
            logEvent("apk_signature_verified");
            return verified;
        } catch (IOException | RuntimeException failed) {
            deleteQuietly(verified);
            throw failed;
        }
    }

    /** Verify the downloaded APK has the exact same package name and compatible signing certificate. */
    @SuppressWarnings("deprecation")
    public static void verifyArchivePackageAndSignature(Context context, File apkFile) throws IOException {
        PackageManager pm = context.getPackageManager();
        PackageInfo archiveInfo;
        PackageInfo installedInfo;
        if (Build.VERSION.SDK_INT >= 33) {
            PackageManager.PackageInfoFlags flags =
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES);
            archiveInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
            try {
                installedInfo = pm.getPackageInfo(context.getPackageName(), flags);
            } catch (PackageManager.NameNotFoundException missing) {
                throw new IOException("Installed package info unavailable", missing);
            }
        } else {
            archiveInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            try {
                installedInfo = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            } catch (PackageManager.NameNotFoundException missing) {
                throw new IOException("Installed package info unavailable", missing);
            }
        }
        if (archiveInfo == null || !context.getPackageName().equals(archiveInfo.packageName)) {
            logWarning("package_mismatch");
            throw new VerificationException(VerificationException.Reason.PACKAGE_MISMATCH,
                    "Archive package does not match installed application");
        }
        Set<String> installedCerts = certificateDigests(installedInfo.signingInfo);
        Set<String> archiveCerts = certificateDigests(archiveInfo.signingInfo);
        if (!signaturesCompatible(installedCerts, archiveCerts)) {
            logWarning("signature_mismatch");
            throw new VerificationException(VerificationException.Reason.SIGNATURE_MISMATCH,
                    "Archive signing certificate does not match installed application");
        }
    }

    public static boolean canRequestPackageInstalls(Context context) {
        return context != null && context.getPackageManager().canRequestPackageInstalls();
    }

    /** Launch the native Android package installer for an already-verified APK file. */
    public static InstallAction launchInstall(Activity activity, File verifiedApk) throws IOException {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(verifiedApk, "verifiedApk");
        if (!canRequestPackageInstalls(activity)) {
            return InstallAction.NEEDS_UNKNOWN_SOURCES_PERMISSION;
        }
        Uri apkUri = UpdateApkProvider.uriForFile(activity, verifiedApk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, UpdateApkProvider.MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
        logEvent("install_prompt_launched");
        return InstallAction.LAUNCHED;
    }

    private static String streamToFile(
            StreamSource source,
            String initialUrl,
            File target,
            long expectedSizeBytes,
            long maxApkBytes,
            ProgressListener progress,
            AtomicBoolean cancelled) throws IOException {
        String current = initialUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (cancelled != null && cancelled.get()) {
                throw new VerificationException(VerificationException.Reason.CANCELLED, "Cancelled");
            }
            if (!GithubReleaseClient.isAllowedDownloadUrl(current)) {
                logWarning("disallowed_download_host");
                throw new IOException("Disallowed APK download host");
            }
            try (StreamResponse resp = source.open(current)) {
                if (GithubReleaseClient.isRedirect(resp.statusCode())) {
                    String next = resp.locationHeader();
                    if (next == null || next.trim().isEmpty()) {
                        throw new IOException("Missing redirect location");
                    }
                    current = next.trim();
                    continue;
                }
                if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null) {
                    throw new IOException("Download failed with HTTP " + resp.statusCode());
                }
                if (resp.contentLength() > maxApkBytes) {
                    throw new VerificationException(VerificationException.Reason.TOO_LARGE,
                            "APK exceeds configured byte limit");
                }
                long totalExpected = resp.contentLength() > 0 ? resp.contentLength() : expectedSizeBytes;
                MessageDigest digest = sha256Digest();
                byte[] buffer = new byte[BUFFER_BYTES];
                long downloaded = 0L;
                int lastPercent = -1;
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = resp.body().read(buffer)) != -1) {
                        if (cancelled != null && cancelled.get()) {
                            throw new VerificationException(VerificationException.Reason.CANCELLED, "Cancelled");
                        }
                        downloaded += read;
                        if (downloaded > maxApkBytes) {
                            throw new VerificationException(VerificationException.Reason.TOO_LARGE,
                                    "APK stream exceeds configured byte limit");
                        }
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                        if (progress != null) {
                            int pct = totalExpected > 0
                                    ? (int) Math.min(100L, (downloaded * 100L) / totalExpected)
                                    : -1;
                            if (pct != lastPercent) {
                                lastPercent = pct;
                                progress.onProgress(pct, downloaded, totalExpected);
                            }
                        }
                    }
                    out.getFD().sync();
                }
                if (downloaded <= 0) {
                    throw new IOException("Empty APK download");
                }
                return toHex(digest.digest());
            }
        }
        throw new IOException("Too many redirects");
    }

    private static Set<String> certificateDigests(SigningInfo signingInfo) {
        if (signingInfo == null) return Collections.emptySet();
        Signature[] signatures = signingInfo.hasMultipleSigners()
                ? signingInfo.getApkContentsSigners()
                : signingInfo.getSigningCertificateHistory();
        if (signatures == null || signatures.length == 0) return Collections.emptySet();
        Set<String> digests = new LinkedHashSet<>();
        for (Signature signature : signatures) {
            if (signature == null) continue;
            digests.add(toHex(sha256Digest().digest(signature.toByteArray())));
        }
        return digests;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = alphabet[v >>> 4];
            hex[i * 2 + 1] = alphabet[v & 0x0F];
        }
        return new String(hex);
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logWarning("temp_delete_failed");
        }
    }

    private static void logEvent(String action) {
        Diagnostics.event("update", action);
    }

    private static void logWarning(String action) {
        Diagnostics.warning("update", action);
    }

    private static final class HttpsStreamSource implements StreamSource {
        private final HermesConfig config;

        HttpsStreamSource(HermesConfig config) {
            this.config = config;
        }

        @Override
        public StreamResponse open(String url) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(config.httpConnectTimeoutMs);
            conn.setReadTimeout(config.httpReadTimeoutMs);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setRequestProperty("User-Agent", "Hermes-Mobile");
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (GithubReleaseClient.isRedirect(code)) {
                conn.disconnect();
                return new StreamResponse(code, location, -1L, null);
            }
            long contentLength = conn.getContentLengthLong();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            InputStream wrapped = stream == null ? null : new InputStream() {
                @Override public int read() throws IOException { return stream.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException { return stream.read(b, off, len); }
                @Override public void close() throws IOException {
                    try { stream.close(); } finally { conn.disconnect(); }
                }
            };
            return new StreamResponse(code, location, contentLength, wrapped);
        }
    }
}
