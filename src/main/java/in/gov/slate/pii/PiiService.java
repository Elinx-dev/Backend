package in.gov.slate.pii;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.JurisdictionGuard;
import in.gov.slate.common.PiiCrypto;

/** Audited "Show" of full Aadhaar, PAN and mobile for the Registrar and the Tahsildar. */
@Service
public class PiiService {

    static final List<String> REVEAL_ROLES = List.of("REGISTRATION_OFFICER", "TAHSILDAR");

    private final NamedParameterJdbcTemplate jdbc;
    private final PiiCrypto pii;
    private final JurisdictionGuard guard;
    private final AuditService audit;

    public PiiService(NamedParameterJdbcTemplate jdbc, PiiCrypto pii, JurisdictionGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.pii = pii;
        this.guard = guard;
        this.audit = audit;
    }

    public record RevealRequest(String source, Long id) {
    }

    public Map<String, Object> reveal(RevealRequest request) {
        CurrentUser user = CurrentUser.require();
        if (REVEAL_ROLES.stream().noneMatch(user::hasRole)) {
            throw ApiException.forbidden("Only the Registrar and the Tahsildar can view full personal details");
        }
        if (request == null || request.id() == null || request.source() == null) {
            throw ApiException.badRequest("source and id are required");
        }
        String source = request.source().trim().toUpperCase();
        String sql = switch (source) {
            case "PROPERTY_OWNER" -> """
                    SELECT o.id, o.owner_name AS name, o.aadhaar_number, o.pan, o.mobile, o.address,
                           o.representative_aadhaar, o.representative_pan, o.representative_mobile,
                           NULL::text AS aadhaar_last4, NULL::text AS txn_ref, p.property_ref,
                           p.state_code, p.district_code, p.taluk_code, p.village_code, p.sro_code
                      FROM core.property_owner o JOIN core.property p ON p.id = o.property_id
                     WHERE o.id = :id
                    """;
            case "TRANSACTION_PARTY" -> """
                    SELECT tp.id, tp.name, NULL::text AS aadhaar_number, tp.pan, tp.mobile, tp.address,
                           NULL::text AS representative_aadhaar, tp.representative_pan, tp.representative_mobile,
                           tp.aadhaar_last4, t.txn_ref, p.property_ref,
                           p.state_code, p.district_code, p.taluk_code, p.village_code, p.sro_code
                      FROM core.transaction_party tp
                      JOIN core.transaction t ON t.id = tp.transaction_id
                      JOIN core.property p ON p.id = t.property_id
                     WHERE tp.id = :id
                    """;
            default -> throw ApiException.badRequest("source must be PROPERTY_OWNER or TRANSACTION_PARTY");
        };
        var rows = jdbc.queryForList(sql, new MapSqlParameterSource("id", request.id()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Record");
        }
        Map<String, Object> row = pii.revealRow(rows.get(0));
        guard.require(user, row, "Record");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("id", row.get("id"));
        out.put("name", row.get("name"));
        String aadhaar = (String) row.get("aadhaar_number");
        out.put("aadhaarNumber", aadhaar != null ? aadhaar
                : row.get("aadhaar_last4") == null ? null : "XXXX XXXX " + row.get("aadhaar_last4"));
        out.put("aadhaarStoredAsHashOnly", aadhaar == null && row.get("aadhaar_last4") != null);
        out.put("pan", row.get("pan"));
        out.put("mobile", row.get("mobile"));
        out.put("address", row.get("address"));
        out.put("representativeAadhaar", row.get("representative_aadhaar"));
        out.put("representativePan", row.get("representative_pan"));
        out.put("representativeMobile", row.get("representative_mobile"));

        List<String> shown = out.entrySet().stream()
                .filter(e -> e.getValue() instanceof String && !"source".equals(e.getKey()) && !"name".equals(e.getKey()))
                .map(Map.Entry::getKey).toList();
        audit.record("PII_REVEALED", source, String.valueOf(row.get("id")), (String) row.get("txn_ref"),
                (String) row.get("property_ref"), Map.of("fields", shown, "role", String.join(",", user.roles())),
                "Full personal details shown to " + user.username());
        return out;
    }
}
