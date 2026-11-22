import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Check the private acceptance leaf with the emulator's ordinary platform trust manager. */
public final class PlatformTrust {
    private static X509Certificate certificate(String path) throws Exception {
        try (FileInputStream input = new FileInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    public static void main(String[] args) throws Exception {
        boolean present = args[0].equals("present");
        X509Certificate root = certificate(args[2]);
        X509Certificate leaf = certificate(args[3]);
        KeyStore store = KeyStore.getInstance("AndroidCAStore");
        store.load(null);
        if (root.equals(store.getCertificate("system:" + args[1])) != present) {
            throw new IllegalStateException("Unexpected system trust anchor");
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                boolean trusted;
                try {
                    ((X509TrustManager) manager).checkServerTrusted(new X509Certificate[]{leaf}, "RSA");
                    trusted = true;
                } catch (CertificateException untrusted) {
                    trusted = false;
                }
                if (trusted != present) throw new IllegalStateException("Unexpected platform trust decision");
                System.out.println("platform-trust-" + args[0]);
                return;
            }
        }
        throw new IllegalStateException("Missing platform X509 trust manager");
    }
}
