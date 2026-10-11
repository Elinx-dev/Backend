package in.gov.slate.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PiiCryptoTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void encryptsAndDecryptsWithoutStoringPlainText() {
        PiiCrypto crypto = new PiiCrypto(KEY_A, "v1", "");
        String stored = crypto.protect("ABCDE1234F");
        assertThat(stored).startsWith("enc:v1:").doesNotContain("ABCDE1234F");
        assertThat(crypto.protect("ABCDE1234F")).isNotEqualTo(stored);
        assertThat(crypto.reveal(stored)).isEqualTo("ABCDE1234F");
        assertThat(crypto.protect(stored)).isEqualTo(stored);
    }

    @Test
    void legacyPlainTextAndNullsPassThrough() {
        PiiCrypto crypto = new PiiCrypto(KEY_A, "v1", "");
        assertThat(crypto.reveal("9876543210")).isEqualTo("9876543210");
        assertThat(crypto.protect((String) null)).isNull();
        assertThat(crypto.protect("  ")).isEqualTo("  ");
    }

    @Test
    void rotatedKeyStillReadsOldValues() {
        String old = new PiiCrypto(KEY_A, "v1", "").protect("123412341234");
        PiiCrypto rotated = new PiiCrypto(KEY_B, "v2", "v1:" + KEY_A);
        assertThat(rotated.reveal(old)).isEqualTo("123412341234");
        assertThat(rotated.protect("x")).startsWith("enc:v2:");
        assertThatThrownBy(() -> new PiiCrypto(KEY_B, "v2", "").reveal(old)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revealRowDecryptsOnlyPersonalColumns() {
        PiiCrypto crypto = new PiiCrypto(KEY_A, "v1", "");
        Map<String, Object> row = new HashMap<>(Map.of("name", "Ravi", "mobile", crypto.protect("9876543210"),
                "address", crypto.protect("12 Main Rd")));
        crypto.revealRows(List.of(row));
        assertThat(row).containsEntry("mobile", "9876543210").containsEntry("address", "12 Main Rd")
                .containsEntry("name", "Ravi");
    }

    @Test
    void masksAndRecognisesUneditedMaskedValues() {
        assertThat(PiiCrypto.mask("aadhaar_number", "123412345678")).isEqualTo("XXXX XXXX 5678");
        assertThat(PiiCrypto.mask("pan", "ABCDE1234F")).isEqualTo("*****1234*");
        assertThat(PiiCrypto.mask("representative_mobile", "9876543210")).isEqualTo("******3210");
        assertThat(PiiCrypto.isMaskOf("pan", "*****1234*", "ABCDE1234F")).isTrue();
        assertThat(PiiCrypto.isMaskOf("pan", "PQRSX1234Z", "ABCDE1234F")).isFalse();
    }

    @Test
    void responseMaskingHidesNumbersAndLeftoverCiphertext() {
        PiiCrypto crypto = new PiiCrypto(KEY_A, "v1", "");
        Object masked = PiiMaskingAdvice.mask(Map.of("parties", List.of(Map.of(
                "pan", "ABCDE1234F", "mobile", "9876543210", "name", "Ravi",
                "address", "12 Main Rd", "note", crypto.protect("secret")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> party = ((List<Map<String, Object>>) ((Map<String, Object>) masked).get("parties")).get(0);
        assertThat(party).containsEntry("pan", "*****1234*").containsEntry("mobile", "******3210")
                .containsEntry("name", "Ravi").containsEntry("address", "12 Main Rd")
                .containsEntry("note", "[protected]");
    }

    @Test
    void jurisdictionScopesMatchSroVillageTalukOrDistrict() {
        Map<String, Object> property = Map.of("sro_code", "SRO-SHOL", "village_code", "PERUNGUDI",
                "taluk_code", "SHOL", "district_code", "CHN");
        Map<String, Object> village = new HashMap<>();
        village.put("village_code", "PERUNGUDI");
        Map<String, Object> otherVillage = new HashMap<>();
        otherVillage.put("village_code", "ADYAR");
        otherVillage.put("taluk_code", "SHOL");
        Map<String, Object> taluk = new HashMap<>();
        taluk.put("taluk_code", "SHOL");
        Map<String, Object> otherSro = new HashMap<>();
        otherSro.put("sro_code", "SRO-ADYAR");
        otherSro.put("taluk_code", "MYL");
        assertThat(JurisdictionGuard.matches(village, property)).isTrue();
        assertThat(JurisdictionGuard.matches(otherVillage, property)).isFalse();
        assertThat(JurisdictionGuard.matches(taluk, property)).isTrue();
        assertThat(JurisdictionGuard.matches(otherSro, property)).isFalse();
    }
}
