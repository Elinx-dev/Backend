package in.gov.slate.transaction;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import in.gov.slate.common.ApiException;
import in.gov.slate.config.ConfigService;

@Repository
public class TransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService config;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc, ConfigService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public TransactionContext load(String txnRef, String stateCode) {
        var rows = jdbc.queryForList(
                "SELECT * FROM core.transaction WHERE txn_ref = :ref AND state_code = :stateCode",
                new MapSqlParameterSource().addValue("ref", txnRef).addValue("stateCode", stateCode));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Transaction " + txnRef);
        }
        Map<String, Object> txn = rows.get(0);
        long id = ((Number) txn.get("id")).longValue();
        long propertyId = ((Number) txn.get("property_id")).longValue();
        var txnParam = new MapSqlParameterSource("txnId", id);
        var propParam = new MapSqlParameterSource("propertyId", propertyId);

        Map<String, Object> property = jdbc.queryForList(
                "SELECT * FROM core.property WHERE id = :propertyId", propParam).get(0);

        List<Map<String, Object>> parties = jdbc.queryForList("""
                SELECT id, side, role, seq, party_type, name, aadhaar_last4, karta_name, pan, address,
                       relationship_code, existing_share_pct, share_transferred_pct, extent_transferred,
                       resulting_share_pct, authority_poa_reference,
                       (aadhaar_hash IS NOT NULL) AS aadhaar_captured
                  FROM core.transaction_party WHERE transaction_id = :txnId ORDER BY side, seq
                """, txnParam);

        List<Map<String, Object>> witnesses = jdbc.queryForList("""
                SELECT id, transaction_id, seq, name, address, id_proof_type, id_proof_ref
                  FROM core.witness
                 WHERE transaction_id = :txnId AND is_active = 'Y'
                 ORDER BY seq
                """, txnParam);

        List<Map<String, Object>> consents = jdbc.queryForList("""
                SELECT id, party_id, otp_request_reference, status, requested_at, verified_at, attempt_count
                  FROM core.consent_record WHERE transaction_id = :txnId
                """, txnParam);

        List<Map<String, Object>> ruleResults = jdbc.queryForList("""
                                                                SELECT engine, overall_outcome, reason_code, result_payload, advisory, checked_at
                                                                        FROM (
                                                                                SELECT r.engine, r.overall_outcome, r.reason_code, r.result_payload, r.advisory, r.checked_at,
                                                                                                         row_number() OVER (
                                                                                                                         PARTITION BY r.engine
                                                                                                                         ORDER BY r.checked_at DESC, q.requested_at DESC, r.id DESC
                                                                                                         ) AS result_rank
                                                                                        FROM rules.rule_check_result r
                                                                                        JOIN rules.rule_check_request q ON q.id = r.request_id
                                                                                 WHERE q.transaction_id = :txnId
                                                                        ) latest
                                                                 WHERE result_rank = 1
                                                                 ORDER BY engine
                """, txnParam);

        var fees = jdbc.queryForList("""
                SELECT * FROM core.fee_calculation WHERE transaction_id = :txnId
                 ORDER BY calculated_at DESC LIMIT 1
                """, txnParam);

        List<Map<String, Object>> payments = jdbc.queryForList(
                "SELECT * FROM core.payment WHERE transaction_id = :txnId ORDER BY paid_at", txnParam);

        List<Map<String, Object>> parcels = jdbc.queryForList("""
                SELECT rp.* FROM survey.resulting_parcel rp
                  JOIN survey.submission s ON s.id = rp.submission_id
                 WHERE s.transaction_id = :txnId ORDER BY rp.seq
                """, txnParam);

        List<Map<String, Object>> owners = jdbc.queryForList("""
                SELECT owner_name, aadhaar_number, pan, address, share_pct, source FROM core.property_owner
                 WHERE property_id = :propertyId AND effective_to IS NULL ORDER BY id
                """, propParam);

        Map<String, Object> deedType = config.deedType(stateCode, (String) txn.get("deed_type_code"));

        return new TransactionContext(txn, property, deedType, parties, witnesses, consents, ruleResults,
                fees.isEmpty() ? null : fees.get(0), payments, parcels, owners);
    }

    public long idOf(String txnRef, String stateCode) {
        Long id = jdbc.query("SELECT id FROM core.transaction WHERE txn_ref = :ref AND state_code = :stateCode",
                new MapSqlParameterSource().addValue("ref", txnRef).addValue("stateCode", stateCode),
                rs -> rs.next() ? rs.getLong(1) : null);
        if (id == null) {
            throw ApiException.notFound("Transaction " + txnRef);
        }
        return id;
    }
}
