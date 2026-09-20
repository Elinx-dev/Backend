package in.gov.slate.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import in.gov.slate.common.ApiException;

@Service
public class PasswordCryptoService {

    private static final OAEPParameterSpec OAEP_SHA_256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final KeyPair keyPair;

    public PasswordCryptoService() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to initialise password encryption", ex);
        }
    }

    public String publicKey() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public String decrypt(String encryptedPassword) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), OAEP_SHA_256);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedPassword));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENCRYPTED_PASSWORD",
                    "Password payload could not be decrypted; refresh the page and try again");
        }
    }
}
