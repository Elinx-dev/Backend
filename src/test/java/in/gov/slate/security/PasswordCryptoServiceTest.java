package in.gov.slate.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.junit.jupiter.api.Test;

import in.gov.slate.common.ApiException;

class PasswordCryptoServiceTest {

    @Test
    void decryptsRsaOaepPasswordPayload() throws Exception {
        PasswordCryptoService service = new PasswordCryptoService();
        byte[] publicKeyBytes = Base64.getDecoder().decode(service.publicKey());
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));

        String encrypted = Base64.getEncoder().encodeToString(
                cipher.doFinal("Slate@123".getBytes(StandardCharsets.UTF_8)));

        assertThat(service.decrypt(encrypted)).isEqualTo("Slate@123");
    }

    @Test
    void rejectsInvalidCiphertext() {
        PasswordCryptoService service = new PasswordCryptoService();

        assertThatThrownBy(() -> service.decrypt("not-base64"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not be decrypted");
    }
}
