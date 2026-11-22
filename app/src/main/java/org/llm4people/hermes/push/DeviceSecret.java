package org.llm4people.hermes.push;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class DeviceSecret {
    private static final String ALIAS = "hermes.push.management";
    private static final String PROVIDER = "AndroidKeyStore";

    static String generate() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit(value >>> 4 & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static synchronized SecretKey key() throws GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static byte[] seal(String value, String binding) throws GeneralSecurityException, java.io.IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        return ByteBuffer.allocate(cipher.getIV().length + ciphertext.length)
                .put(cipher.getIV()).put(ciphertext).array();
    }

    static String open(byte[] envelope, String binding) throws GeneralSecurityException, java.io.IOException {
        if (envelope.length < 28) throw new GeneralSecurityException("device envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, envelope, 0, 12));
        cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(envelope, 12, envelope.length - 12), StandardCharsets.US_ASCII);
    }
}
