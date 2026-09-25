package in.gov.slate.fees;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.WorkflowEngine;

@Service
public class PaymentService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final AuditService audit;

    public PaymentService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository, WorkflowEngine workflow,
                          AuditService audit) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.workflow = workflow;
        this.audit = audit;
    }

    public record PaymentInput(String mode, String referenceNo, BigDecimal amount, OffsetDateTime paidAt) {
    }

    @Transactional
    public Map<String, Object> record(String txnRef, PaymentInput input) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("PAYMENT_RECORD");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        workflow.requireStatus(ctx, "FEE_PAYMENT_PENDING", "Payment recording", user);
        if (ctx.totalPayable() == null) {
            throw ApiException.conflict("Fees have not been calculated for " + txnRef);
        }
        if (input.amount() == null || input.amount().signum() <= 0) {
            throw ApiException.badRequest("Payment amount must be positive");
        }

        jdbc.update("""
                INSERT INTO core.payment (transaction_id, mode, reference_no, amount, paid_at, received_by, status)
                VALUES (:txnId, :mode, :reference, :amount, coalesce(:paidAt, now()), :userId, 'SUCCESS')
                """, new MapSqlParameterSource()
                .addValue("txnId", ctx.id())
                .addValue("mode", input.mode())
                .addValue("reference", input.referenceNo())
                .addValue("amount", input.amount())
                .addValue("paidAt", input.paidAt())
                .addValue("userId", user.id()));

        audit.record("PAYMENT_RECORDED", "PAYMENT", input.referenceNo(), txnRef, ctx.propertyRef(),
                Map.of("amount", input.amount(), "mode", String.valueOf(input.mode())), null);
        return summary(txnRef);
    }

    public Map<String, Object> summary(String txnRef) {
        CurrentUser user = CurrentUser.require();
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        BigDecimal payable = ctx.totalPayable();
        BigDecimal paid = ctx.paidTotal();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPayable", payable);
        out.put("totalPaid", paid);
        out.put("balance", payable == null ? null : payable.subtract(paid).max(BigDecimal.ZERO));
        out.put("fullyPaid", payable != null && paid.compareTo(payable) >= 0);
        out.put("payments", ctx.payments());
        return out;
    }

    public List<Map<String, Object>> list(String txnRef) {
        CurrentUser user = CurrentUser.require();
        return jdbc.queryForList("SELECT * FROM core.payment WHERE transaction_id = :id ORDER BY paid_at",
                new MapSqlParameterSource("id", repository.idOf(txnRef, user.stateCode())));
    }
}
