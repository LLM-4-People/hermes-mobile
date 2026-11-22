package org.llm4people.hermes.profile;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** V1 seals the payload first with a profile passphrase, then with the device's non-exportable key. */
final class ProfileEnvelope {
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int V1_ITERATIONS = 600_000;
    private static final byte VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProfileEnvelope() {}

    static byte[] seal(SecretKey device, String id, String label, char[] pass, byte[] content)
            throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] aad = context(id, label);
        byte[] inner = encrypt(derive(pass, salt), aad, content);
        byte[] outer = encrypt(device, aad, ByteBuffer.allocate(salt.length + inner.length)
                .put(salt).put(inner).array());
        return ByteBuffer.allocate(1 + outer.length).put(VERSION).put(outer).array();
    }

    static byte[] open(SecretKey device, String id, String label, char[] pass, byte[] stored)
            throws GeneralSecurityException {
        if (stored.length < 1 + SALT_BYTES + 2 * (IV_BYTES + TAG_BYTES) || stored[0] != VERSION)
            throw new GeneralSecurityException("Unsupported account envelope");
        byte[] aad = context(id, label);
        byte[] inner = decrypt(device, aad, stored, 1);
        return decrypt(derive(pass, Arrays.copyOf(inner, SALT_BYTES)), aad, inner, SALT_BYTES);
    }

    private static byte[] context(String id, String label) {
        return ("hermes-profile-v1\0" + id + "\0" + label).getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKey derive(char[] pass, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, V1_ITERATIONS, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(raw, "AES");
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    static byte[] encrypt(SecretKey key, byte[] aad, byte[] clear) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore requires the implementation to generate a fresh IV during encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv.length != IV_BYTES) throw new GeneralSecurityException("Unexpected GCM IV");
        cipher.updateAAD(aad);
        byte[] sealed = cipher.doFinal(clear);
        return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
    }

    static byte[] decrypt(SecretKey key, byte[] aad, byte[] data, int offset)
            throws GeneralSecurityException {
        if (data.length - offset < IV_BYTES + TAG_BYTES) throw new GeneralSecurityException("Truncated envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, data, offset, IV_BYTES));
        cipher.updateAAD(aad);
        return cipher.doFinal(data, offset + IV_BYTES, data.length - offset - IV_BYTES);
    }
}
