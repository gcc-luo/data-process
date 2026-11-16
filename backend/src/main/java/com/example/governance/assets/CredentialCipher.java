package com.example.governance.assets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypts credentials with AES-256-GCM. The caller must keep the key outside persisted data. */
public final class CredentialCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(String base64Key) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(base64Key); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Credential key must be base64-encoded", e); }
        if (decoded.length != 32) throw new IllegalArgumentException("Credential key must contain 32 bytes");
        key = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    public String encrypt(String plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
        return Base64.getEncoder().encodeToString(result);
    }

    public String decrypt(String encoded) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < NONCE_BYTES + TAG_BITS / 8) throw new IllegalArgumentException("Invalid encrypted credential");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
        return new String(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES), StandardCharsets.UTF_8);
    }
}
