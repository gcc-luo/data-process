package com.example.governance.assets;

import org.junit.jupiter.api.Test;
import javax.crypto.AEADBadTagException;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class CredentialCipherTest {
    @Test void encryptsWithRandomNonceAndRecoversTheOriginal() throws Exception {
        var cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var first = cipher.encrypt("secret-密码");
        var second = cipher.encrypt("secret-密码");
        assertNotEquals(first, second);
        assertFalse(first.contains("secret"));
        assertEquals("secret-密码", cipher.decrypt(first));
    }
    @Test void rejectsTamperingAndWrongKey() throws Exception {
        byte[] key = new byte[32];
        var first = new CredentialCipher(Base64.getEncoder().encodeToString(key));
        var encoded = first.encrypt("secret");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        bytes[bytes.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class, () -> first.decrypt(Base64.getEncoder().encodeToString(bytes)));
        key[0] = 1;
        var other = new CredentialCipher(Base64.getEncoder().encodeToString(key));
        assertThrows(AEADBadTagException.class, () -> other.decrypt(encoded));
    }
    @Test void refusesInvalidEncryptionKeys() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher("invalid"));
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
