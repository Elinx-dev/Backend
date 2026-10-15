package in.gov.slate.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PartitionUploadSafetyTest {

    @Test
    void fileNameLosesPathsAndUnsafeCharacters() {
        assertThat(PartitionService.safeFileName("..\\..\\windows\\evil.pdf")).isEqualTo("evil.pdf");
        assertThat(PartitionService.safeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(PartitionService.safeFileName("a\"; x=\r\n.pdf")).isEqualTo("a__ x___.pdf");
        assertThat(PartitionService.safeFileName("..")).isEqualTo("certificate");
        assertThat(PartitionService.safeFileName(null)).isEqualTo("certificate");
        assertThat(PartitionService.safeFileName("x".repeat(300) + ".pdf")).hasSize(120).endsWith(".pdf");
    }

    @Test
    void contentMustMatchDeclaredType() {
        byte[] pdf = "%PDF-1.7 body".getBytes(StandardCharsets.US_ASCII);
        byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
        byte[] jpg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00 };
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00 };
        assertThat(PartitionService.contentMatches("application/pdf", pdf)).isTrue();
        assertThat(PartitionService.contentMatches("application/pdf", html)).isFalse();
        assertThat(PartitionService.contentMatches("image/jpeg", jpg)).isTrue();
        assertThat(PartitionService.contentMatches("image/png", png)).isTrue();
        assertThat(PartitionService.contentMatches("image/png", jpg)).isFalse();
        assertThat(PartitionService.contentMatches("image/png", new byte[0])).isFalse();
    }
}
