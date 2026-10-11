package in.gov.slate.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import in.gov.slate.chain.TokenService;
import in.gov.slate.transaction.TransactionContext;

class RegistrationResultingOwnersTest {

    private final RegistrationService service = new RegistrationService(null, null, null, null, null, null, null, null);

    private static TransactionContext ctx(List<Map<String, Object>> owners, List<Map<String, Object>> parties) {
        return new TransactionContext(Map.of("id", 1L), Map.of(), Map.of(), parties, List.of(), List.of(),
                List.of(), null, List.of(), List.of(), owners);
    }

    @Test
    void transferringOwnersAreReplacedByIncomingPartiesWithoutShares() {
        var result = service.resultingOwners(ctx(
                List.of(Map.of("owner_name", "Raman", "share_pct", new BigDecimal("100"))),
                List.of(Map.of("side", "SIDE_1", "name", "Raman"),
                        Map.of("side", "SIDE_2", "name", "Adyar Builders Pvt Ltd",
                                "owner_type_code", "PRIVATE_LIMITED_COMPANY"))));

        assertThat(result).containsExactly(new TokenService.Owner("Adyar Builders Pvt Ltd", null));
    }

    @Test
    void ownersNotPartyToTheTransferKeepTheirRecordedShare() {
        var result = service.resultingOwners(ctx(
                List.of(Map.of("owner_name", "Raman", "share_pct", new BigDecimal("50")),
                        Map.of("owner_name", "Lakshmi", "share_pct", new BigDecimal("50"))),
                List.of(Map.of("side", "SIDE_1", "name", "Raman"),
                        Map.of("side", "SIDE_2", "name", "Kumar"))));

        assertThat(result).containsExactly(new TokenService.Owner("Lakshmi", new BigDecimal("50")),
                new TokenService.Owner("Kumar", null));
    }
}
