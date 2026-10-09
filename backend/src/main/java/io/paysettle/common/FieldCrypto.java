package io.paysettle.common;

import io.paysettle.config.PaySettleProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Field-level protection for account numbers (PCI DSS / GLBA style data minimisation):
 * <ul>
 *   <li>AES-256-GCM ciphertext, so the number can be recovered to build outbound files and messages</li>
 *   <li>HMAC-SHA256 fingerprint, so duplicate and velocity checks work without decrypting</li>
 *   <li>last 4 digits for display</li>
 * </ul>
 * Keys come from Secret Manager in production (in a bank, typically from an HSM / Cloud KMS).
 */
@Component
public class FieldCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec dataKey;
    private final SecretKeySpec fingerprintKey;

    public FieldCrypto(PaySettleProperties props) {
        this.dataKey = key(props.crypto().dataKey(), "AES");
        this.fingerprintKey = key(props.crypto().fingerprintKey(), "HmacSHA256");
    }

    public record Protected(String ciphertext, String fingerprint, String last4) {
    }

    public Protected protect(String accountNumber) {
        String normalized = accountNumber.trim();
        return new Protected(encrypt(normalized), fingerprint(normalized),
                normalized.length() <= 4 ? normalized : normalized.substring(normalized.length() - 4));
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String value) {
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SecretKeySpec key(String base64, String algorithm) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length != 32) {
            throw new IllegalStateException(algorithm + " key must be 32 bytes (base64-encoded)");
        }
        return new SecretKeySpec(bytes, algorithm);
    }
}
