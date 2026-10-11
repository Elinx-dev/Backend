package in.gov.slate.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Field-level AES-256-GCM encryption for personal data (Aadhaar, PAN, mobile, address).
 * Stored values look like {@code enc:<keyId>:<base64(iv|ciphertext)>}. Values without the
 * prefix are legacy plain text and are returned unchanged, so existing rows need no migration.
 */
@Component
public class PiiCrypto {

    private static final Logger log = LoggerFactory.getLogger(PiiCrypto.class);
    private static final String PREFIX = "enc:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String LOCAL_DEMO_SECRET = "local-demo-pii-key";

    /** Columns holding personal data that is encrypted at rest. */
    public static final Set<String> ENCRYPTED_FIELDS = Set.of(
            "aadhaar_number", "representative_aadhaar", "pan", "representative_pan",
            "mobile", "representative_mobile", "address");

    private final SecureRandom random = new SecureRandom();
    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();

    public PiiCrypto(@Value("${slate.pii.key:}") String key,
                     @Value("${slate.pii.key-id:v1}") String keyId,
                     @Value("${slate.pii.previous-keys:}") String previousKeys) {
        this.activeKeyId = keyId;
        if (key == null || key.isBlank()) {
            log.warn("slate.pii.key is not set; using the local demo key. Set SLATE_PII_KEY outside local use.");
            keys.put(keyId, new SecretKeySpec(Hashes.sha256(LOCAL_DEMO_SECRET), "AES"));
        } else {
            keys.put(keyId, decodeKey(key));
        }
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String entry : previousKeys.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length == 2 && !keys.containsKey(parts[0])) {
                    keys.put(parts[0], decodeKey(parts[1]));
                }
            }
        }
    }

    private static SecretKeySpec decodeKey(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("PII encryption keys must be 32 bytes (base64-encoded)");
        }
        return new SecretKeySpec(raw, "AES");
    }

    public static boolean isEncrypted(Object value) {
        return value instanceof String s && s.startsWith(PREFIX);
    }

    /** Encrypts a value for storage. Null, blank and already-encrypted values are returned unchanged. */
    public String protect(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + activeKeyId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to encrypt personal data", ex);
        }
    }

    public String protect(Object plain) {
        return plain == null ? null : protect(plain.toString());
    }

    /** Decrypts a stored value. Legacy plain-text values are returned unchanged. */
    public String reveal(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        String[] parts = stored.split(":", 3);
        SecretKeySpec key = parts.length == 3 ? keys.get(parts[1]) : null;
        if (key == null) {
            throw new IllegalStateException("No PII key configured for key id " + (parts.length > 1 ? parts[1] : "?"));
        }
        try {
            byte[] raw = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(parts[1].getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Unable to decrypt personal data", ex);
        }
    }

    /** Decrypts the personal-data columns of query rows in place (rows from JDBC are mutable). */
    public List<Map<String, Object>> revealRows(List<Map<String, Object>> rows) {
        rows.forEach(this::revealRow);
        return rows;
    }

    public Map<String, Object> revealRow(Map<String, Object> row) {
        for (String field : ENCRYPTED_FIELDS) {
            Object value = row.get(field);
            if (isEncrypted(value)) {
                row.put(field, reveal((String) value));
            }
        }
        return row;
    }

    public static String mask(String field, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String digits = value.replaceAll("\\D", "");
        return switch (field) {
            case "aadhaar_number", "representative_aadhaar" ->
                    digits.length() >= 4 ? "XXXX XXXX " + digits.substring(digits.length() - 4) : "XXXX XXXX XXXX";
            case "pan", "representative_pan" -> value.length() == 10
                    ? "*****" + value.substring(5, 9) + "*" : "**********";
            case "mobile", "representative_mobile" ->
                    digits.length() >= 4 ? "******" + digits.substring(digits.length() - 4) : "**********";
            default -> value;
        };
    }

    /** True when {@code input} is exactly the masked form of {@code stored}, i.e. an unedited masked field. */
    public static boolean isMaskOf(String field, String input, String stored) {
        return input != null && stored != null && !input.equals(stored) && input.equals(mask(field, stored));
    }
}
