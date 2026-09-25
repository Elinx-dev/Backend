package in.gov.slate.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.WorkflowEngine;

/**
 * Runs the configured rule engines for a transaction and stores their results.
 * Results are advisory during the pilot: they are recorded and displayed, and
 * they never approve, block or alter a transaction.
 */
@Service
public class RuleCheckService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final List<RuleEngine> engines;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final String connectorMode;

    public RuleCheckService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository,
                            WorkflowEngine workflow, List<RuleEngine> engines, AuditService audit, ObjectMapper mapper,
                            @Value("${slate.connectors.mode}") String connectorMode) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.workflow = workflow;
        this.engines = engines;
        this.audit = audit;
        this.mapper = mapper;
        this.connectorMode = connectorMode;
    }

    @Transactional
    public List<Map<String, Object>> run(String txnRef, List<String> requestedEngines, String mode,
                                         LocalDate assessmentDate) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("RULE_CHECK_RUN");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        workflow.requireStatus(ctx, "RULE_CHECK_PENDING", "Rule checks", user);
        String effectiveMode = mode == null ? "PILOT_CURRENT_RECONCILIATION" : mode;
        LocalDate assessedOn = assessmentDate == null ? LocalDate.now() : assessmentDate;

        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleEngine engine : engines) {
            if (requestedEngines != null && !requestedEngines.isEmpty()
                    && !requestedEngines.contains(engine.engine())) {
                continue;
            }
            long requestId = createRequest(ctx, engine.engine(), effectiveMode, assessedOn, user.id());
            RuleEngine.Outcome outcome = engine.run(ctx, requestId, assessedOn, effectiveMode);
            storeResult(requestId, engine.engine(), outcome);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("engine", engine.engine());
            row.put("overallOutcome", outcome.overallOutcome());
            row.put("reasonCode", outcome.reasonCode());
            row.put("advisory", true);
            row.put("payload", outcome.payload());
            out.add(row);

            audit.record("RULE_CHECK_RUN", "RULE_CHECK", engine.engine(), txnRef, ctx.propertyRef(),
                    Map.of("mode", effectiveMode), Map.of("outcome", outcome.overallOutcome()),
                    "SUCCESS", outcome.reasonCode());
        }
        return out;
    }

    public List<Map<String, Object>> results(String txnRef) {
        CurrentUser user = CurrentUser.require();
        long txnId = repository.idOf(txnRef, user.stateCode());
        return jdbc.queryForList("""
                SELECT engine, mode, assessment_date, connector_mode, requested_at,
                       overall_outcome, reason_code, result_payload, advisory, checked_at
                  FROM (
                    SELECT q.engine, q.mode, q.assessment_date, q.connector_mode, q.requested_at,
                           r.overall_outcome, r.reason_code, r.result_payload, r.advisory, r.checked_at,
                           row_number() OVER (
                               PARTITION BY q.engine
                               ORDER BY r.checked_at DESC, q.requested_at DESC, r.id DESC
                           ) AS result_rank
                      FROM rules.rule_check_request q
                      JOIN rules.rule_check_result r ON r.request_id = q.id
                     WHERE q.transaction_id = :txnId
                  ) latest
                 WHERE result_rank = 1
                 ORDER BY engine
                """, new MapSqlParameterSource("txnId", txnId));
    }

    private long createRequest(TransactionContext ctx, String engine, String mode, LocalDate assessmentDate,
                               long userId) {
        var keyHolder = new GeneratedKeyHolder();
        Map<String, Object> payload = Map.of(
                "propertyRef", ctx.propertyRef(),
                "village", String.valueOf(ctx.property().get("village_code")),
                "surveyNo", String.valueOf(ctx.property().get("survey_no")),
                "subdivisionNo", String.valueOf(ctx.property().get("subdivision_no")),
                "engine", engine);
        jdbc.update("""
                INSERT INTO rules.rule_check_request (state_code, transaction_id, property_id, engine, mode,
                    assessment_date, request_payload, idempotency_key, requested_by, connector_mode)
                VALUES (:stateCode, :txnId, :propertyId, :engine, :mode, :assessmentDate,
                    cast(:payload AS jsonb), :idempotencyKey, :userId, :connectorMode)
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.transaction().get("state_code"))
                .addValue("txnId", ctx.id())
                .addValue("propertyId", ctx.property().get("id"))
                .addValue("engine", engine)
                .addValue("mode", mode)
                .addValue("assessmentDate", assessmentDate)
                .addValue("payload", json(payload))
                .addValue("idempotencyKey", UUID.randomUUID())
                .addValue("userId", userId)
                .addValue("connectorMode", connectorMode), keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    private void storeResult(long requestId, String engine, RuleEngine.Outcome outcome) {
        String payload = json(outcome.payload());
        jdbc.update("""
                INSERT INTO rules.rule_check_result (request_id, engine, overall_outcome, reason_code,
                    result_payload, result_hash, advisory)
                VALUES (:requestId, :engine, :outcome, :reason, cast(:payload AS jsonb), :hash, TRUE)
                """, new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("engine", engine)
                .addValue("outcome", outcome.overallOutcome())
                .addValue("reason", outcome.reasonCode())
                .addValue("payload", payload)
                .addValue("hash", Hashes.sha256(payload)));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise rule payload", e);
        }
    }
}
