package in.gov.slate.revenue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.connectors.RevenueConnector;
import in.gov.slate.connectors.model.RevenueModels.MutationPushRequest;
import in.gov.slate.connectors.model.RevenueModels.MutationPushResponse;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.WorkflowEngine;

/**
 * Revenue verification and mutation. The VAO verifies and forwards; only the
 * Tahsildar issues the authoritative decision. Revenue ownership is recorded
 * separately from registered ownership and never overwrites it.
 */
@Service
public class RevenueService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final RevenueConnector connector;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public RevenueService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository, WorkflowEngine workflow,
                          RevenueConnector connector, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.workflow = workflow;
        this.connector = connector;
        this.audit = audit;
        this.mapper = mapper;
    }

    public record VerifyRequest(String remarks) {
    }

    public record ObjectionRequest(String objectorName, LocalDate objectionDate, String objectionReason,
                                   LocalDate hearingDate) {
    }

    public record DisposalRequest(String disposalDecision, String remarks) {
    }

    public record ApprovalRequest(String mutationRegisterNumber, String remarks) {
    }

    public List<Map<String, Object>> queue(String status, int limit) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_READ");
        return jdbc.queryForList("""
                SELECT m.id, m.status, m.mutation_type, m.created_at, t.txn_ref, t.deed_type_code,
                       p.property_ref, p.survey_no, p.subdivision_no, p.village_code,
                       m.proposed_owner_set, m.vao_remarks
                  FROM revenue.proposed_mutation m
                  JOIN core.transaction t ON t.id = m.transaction_id
                  JOIN core.property p ON p.id = m.property_id
                 WHERE m.state_code = :stateCode
                   AND (:status IS NULL OR m.status = :status)
                 ORDER BY m.created_at
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("status", status)
                .addValue("limit", limit));
    }

    public Map<String, Object> detail(long mutationId) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_READ");
        var rows = jdbc.queryForList("""
                SELECT m.*, t.txn_ref, p.property_ref FROM revenue.proposed_mutation m
                  JOIN core.transaction t ON t.id = m.transaction_id
                  JOIN core.property p ON p.id = m.property_id
                 WHERE m.id = :id AND m.state_code = :stateCode
                """, new MapSqlParameterSource().addValue("id", mutationId).addValue("stateCode", user.stateCode()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Mutation " + mutationId);
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("objections", jdbc.queryForList(
                "SELECT * FROM revenue.objection WHERE mutation_id = :id ORDER BY id",
                new MapSqlParameterSource("id", mutationId)));
        out.put("currentRevenueState", jdbc.queryForList("""
                SELECT * FROM revenue.current_state WHERE property_id = :propertyId
                 ORDER BY fetched_at DESC LIMIT 1
                """, new MapSqlParameterSource("propertyId", out.get("property_id"))));
        out.put("approvedRecord", jdbc.queryForList(
                "SELECT * FROM revenue.approved_record WHERE mutation_id = :id",
                new MapSqlParameterSource("id", mutationId)));
        return out;
    }

    @Transactional
    public Map<String, Object> verifyAndForward(long mutationId, VerifyRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("REVENUE_VERIFY");
        Map<String, Object> mutation = detail(mutationId);
        requireStatus(mutation, "VAO_PENDING");

        jdbc.update("""
                UPDATE revenue.proposed_mutation SET vao_verified_by = :userId, vao_verified_at = now(),
                       vao_remarks = :remarks, status = 'TAHSILDAR_PENDING'
                 WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", mutationId).addValue("userId", user.id())
                .addValue("remarks", req.remarks()));

        TransactionContext ctx = repository.load((String) mutation.get("txn_ref"), user.stateCode());
        String status = workflow.apply(ctx, "VAO_FORWARD", req.remarks(), user);
        audit.record("MUTATION_FORWARDED", "MUTATION", String.valueOf(mutationId), ctx.txnRef(), ctx.propertyRef(),
                Map.of("status", status), null);
        return detail(mutationId);
    }

    @Transactional
    public Map<String, Object> raiseObjection(long mutationId, ObjectionRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("REVENUE_OBJECTION");
        Map<String, Object> mutation = detail(mutationId);
        requireStatus(mutation, "VAO_PENDING");

        jdbc.update("""
                INSERT INTO revenue.objection (mutation_id, notice_date, objection_received, objector_name,
                    objection_date, objection_reason, hearing_date, recorded_by)
                VALUES (:id, current_date, TRUE, :objector, :objectionDate, :reason, :hearingDate, :userId)
                """, new MapSqlParameterSource()
                .addValue("id", mutationId)
                .addValue("objector", req.objectorName())
                .addValue("objectionDate", req.objectionDate() == null ? LocalDate.now() : req.objectionDate())
                .addValue("reason", req.objectionReason())
                .addValue("hearingDate", req.hearingDate())
                .addValue("userId", user.id()));
        jdbc.update("UPDATE revenue.proposed_mutation SET status = 'OBJECTION_PENDING' WHERE id = :id",
                new MapSqlParameterSource("id", mutationId));

        TransactionContext ctx = repository.load((String) mutation.get("txn_ref"), user.stateCode());
        workflow.apply(ctx, "RAISE_OBJECTION", req.objectionReason(), user);
        audit.record("OBJECTION_RAISED", "MUTATION", String.valueOf(mutationId), ctx.txnRef(), ctx.propertyRef(),
                Map.of("objector", String.valueOf(req.objectorName())), null);
        return detail(mutationId);
    }

    @Transactional
    public Map<String, Object> disposeObjection(long mutationId, DisposalRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("REVENUE_OBJECTION");
        Map<String, Object> mutation = detail(mutationId);
        requireStatus(mutation, "OBJECTION_PENDING");

        jdbc.update("""
                UPDATE revenue.objection SET disposal_decision = :decision
                 WHERE mutation_id = :id AND disposal_decision IS NULL
                """, new MapSqlParameterSource().addValue("id", mutationId).addValue("decision",
                req.disposalDecision()));
        jdbc.update("UPDATE revenue.proposed_mutation SET status = 'VAO_PENDING' WHERE id = :id",
                new MapSqlParameterSource("id", mutationId));

        TransactionContext ctx = repository.load((String) mutation.get("txn_ref"), user.stateCode());
        workflow.apply(ctx, "DISPOSE_OBJECTION", req.remarks() != null ? req.remarks() : req.disposalDecision(), user);
        audit.record("OBJECTION_DISPOSED", "MUTATION", String.valueOf(mutationId), ctx.txnRef(), ctx.propertyRef(),
                Map.of("decision", String.valueOf(req.disposalDecision())), null);
        return detail(mutationId);
    }

    /** Only the Tahsildar issues the authoritative Revenue decision. */
    @Transactional
    public Map<String, Object> approve(long mutationId, ApprovalRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("REVENUE_APPROVE");
        Map<String, Object> mutation = detail(mutationId);
        requireStatus(mutation, "TAHSILDAR_PENDING");

        TransactionContext ctx = repository.load((String) mutation.get("txn_ref"), user.stateCode());
        MutationPushResponse pushed = connector.pushMutation(new MutationPushRequest(
                ctx.propertyRef(), registeredDocumentNo(ctx.id()), String.valueOf(mutation.get("mutation_type")),
                ownerNames(mutation.get("proposed_owner_set"))));

        jdbc.update("""
                INSERT INTO revenue.approved_record (mutation_id, revenue_record_number, mutation_register_number,
                    mutation_date, approved_by, approved_owner_set, source_reference)
                VALUES (:id, :recordNumber, :registerNumber, current_date, :userId,
                    (SELECT proposed_owner_set FROM revenue.proposed_mutation WHERE id = :id), :sourceRef)
                """, new MapSqlParameterSource()
                .addValue("id", mutationId)
                .addValue("recordNumber", pushed.revenueRecordRef())
                .addValue("registerNumber", req.mutationRegisterNumber() != null ? req.mutationRegisterNumber()
                        : pushed.mutationNumber())
                .addValue("userId", user.id())
                .addValue("sourceRef", pushed.mutationNumber()));

        jdbc.update("""
                UPDATE revenue.proposed_mutation SET status = 'REVENUE_APPROVED', tahsildar_remarks = :remarks
                 WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", mutationId).addValue("remarks", req.remarks()));

        jdbc.update("""
                INSERT INTO revenue.current_state (property_id, revenue_record_ref, revenue_survey_no,
                    revenue_subdivision_no, land_context, owners, extent_value, extent_unit, classification,
                    record_status, source_reference)
                SELECT p.id, :recordNumber, p.survey_no, p.subdivision_no, p.land_type_code,
                       m.proposed_owner_set, p.extent_value, p.extent_unit, p.classification_code, 'ACTIVE',
                       :sourceRef
                  FROM revenue.proposed_mutation m JOIN core.property p ON p.id = m.property_id
                 WHERE m.id = :id
                """, new MapSqlParameterSource()
                .addValue("id", mutationId)
                .addValue("recordNumber", pushed.revenueRecordRef())
                .addValue("sourceRef", pushed.mutationNumber()));

        String status = workflow.apply(ctx, "TAHSILDAR_APPROVE", req.remarks(), user);
        audit.record("MUTATION_APPROVED", "MUTATION", String.valueOf(mutationId), ctx.txnRef(), ctx.propertyRef(),
                Map.of("status", status, "revenueRecordRef", pushed.revenueRecordRef()), null);
        return detail(mutationId);
    }

    private String registeredDocumentNo(long transactionId) {
        return jdbc.query("SELECT registered_document_no FROM core.registration_result WHERE transaction_id = :id",
                new MapSqlParameterSource("id", transactionId), rs -> rs.next() ? rs.getString(1) : null);
    }

    private List<String> ownerNames(Object proposedOwnerSet) {
        if (proposedOwnerSet == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> owners = mapper.readValue(proposedOwnerSet.toString(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return owners.stream().map(o -> String.valueOf(o.get("name"))).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void requireStatus(Map<String, Object> mutation, String expected) {
        if (!expected.equals(mutation.get("status"))) {
            throw ApiException.conflict("Mutation is " + mutation.get("status") + ", expected " + expected);
        }
    }
}
