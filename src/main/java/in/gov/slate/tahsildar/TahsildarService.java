package in.gov.slate.tahsildar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.revenue.RevenueService;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;

/**
 * Tahsildar portal: every record in the Tahsildar's taluk(s) from registration to Revenue approval.
 * Approval itself stays in {@link RevenueService#approve}.
 */
@Service
public class TahsildarService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static final String RECORD_SELECT = """
            SELECT t.id AS transaction_id, t.txn_ref, t.status, t.deed_type_code, t.subtype, t.sro_code,
                   t.declared_consideration, t.guideline_value, t.initiated_at, t.registered_at,
                   t.assigned_vao_id, vao.full_name AS assigned_vao_name,
                   t.assigned_surveyor_id, sur.full_name AS assigned_surveyor_name,
                   p.id AS property_id, p.property_ref, p.ulpin, p.survey_no, p.subdivision_no, p.village_code,
                   p.taluk_code, p.extent_value, p.extent_unit, p.classification_code, p.land_type_code,
                   (SELECT rv.village_name FROM master.revenue_village rv
                     WHERE rv.village_code = p.village_code LIMIT 1) AS village_name,
                   (SELECT string_agg(tp.name, ', ' ORDER BY tp.seq) FROM core.transaction_party tp
                     WHERE tp.transaction_id = t.id AND tp.side = 'SIDE_1') AS sellers,
                   (SELECT string_agg(tp.name, ', ' ORDER BY tp.seq) FROM core.transaction_party tp
                     WHERE tp.transaction_id = t.id AND tp.side = 'SIDE_2') AS buyers,
                   rr.registration_date, m.mutation_type, m.created_at AS forwarded_at, m.vao_remarks,
                   m.tahsildar_remarks, vv.full_name AS vao_verified_by_name, sub.submitted_at AS survey_submitted_at,
                   ar.approved_at, ar.revenue_record_number, ar.mutation_register_number,
                   %s
              FROM core.transaction t
              JOIN core.property p ON p.id = t.property_id
              LEFT JOIN revenue.proposed_mutation m ON m.transaction_id = t.id
              LEFT JOIN revenue.approved_record ar ON ar.mutation_id = m.id
              LEFT JOIN sec."user" vao ON vao.id = t.assigned_vao_id
              LEFT JOIN sec."user" sur ON sur.id = t.assigned_surveyor_id
              LEFT JOIN sec."user" vv ON vv.id = m.vao_verified_by
              %s
             WHERE t.state_code = :stateCode
               AND t.status IN ('SURVEY_PENDING','VAO_PENDING','OBJECTION_PENDING','TAHSILDAR_PENDING',
                                'REVENUE_APPROVED','COMPLETED')
               AND p.village_code IN (:villages)
            """.formatted(ApprovalChecklist.READINESS_COLUMNS, ApprovalChecklist.READINESS_JOINS);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final RevenueService revenue;

    public TahsildarService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository,
                            RevenueService revenue) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.revenue = revenue;
    }

    public Map<String, Object> dashboard() {
        CurrentUser user = requireTahsildar();
        List<Map<String, Object>> rows = records(user);
        LocalDate today = LocalDate.now(ZONE);

        Map<TahsildarStage, Integer> byStage = new EnumMap<>(TahsildarStage.class);
        for (TahsildarStage s : TahsildarStage.values()) {
            byStage.put(s, 0);
        }
        Map<String, Map<String, Object>> byVillage = new TreeMap<>();
        int approvedToday = 0;
        int approvedThisMonth = 0;
        for (Map<String, Object> row : rows) {
            TahsildarStage stage = TahsildarStage.valueOf((String) row.get("stage"));
            byStage.merge(stage, 1, Integer::sum);
            String village = String.valueOf(row.getOrDefault("village_name", row.get("village_code")));
            Map<String, Object> v = byVillage.computeIfAbsent(village, k -> new LinkedHashMap<>(Map.of(
                    "village_name", k, "total", 0, "pending", 0, "approved", 0)));
            v.merge("total", 1, (a, b) -> (Integer) a + (Integer) b);
            if (stage.actionRequired()) {
                v.merge("pending", 1, (a, b) -> (Integer) a + (Integer) b);
            }
            if (stage == TahsildarStage.APPROVED) {
                v.merge("approved", 1, (a, b) -> (Integer) a + (Integer) b);
                LocalDate approvedOn = localDate(row.get("approved_at"));
                if (today.equals(approvedOn)) {
                    approvedToday++;
                }
                if (approvedOn != null && approvedOn.getYear() == today.getYear()
                        && approvedOn.getMonth() == today.getMonth()) {
                    approvedThisMonth++;
                }
            }
        }

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("total", rows.size());
        kpis.put("awaitingApproval", byStage.get(TahsildarStage.READY_FOR_APPROVAL)
                + byStage.get(TahsildarStage.ON_HOLD));
        kpis.put("readyForApproval", byStage.get(TahsildarStage.READY_FOR_APPROVAL));
        kpis.put("onHold", byStage.get(TahsildarStage.ON_HOLD));
        kpis.put("inProgress", byStage.get(TahsildarStage.WITH_SURVEYOR) + byStage.get(TahsildarStage.WITH_VAO)
                + byStage.get(TahsildarStage.OBJECTION_PENDING));
        kpis.put("objections", byStage.get(TahsildarStage.OBJECTION_PENDING));
        kpis.put("approved", byStage.get(TahsildarStage.APPROVED));
        kpis.put("approvedToday", approvedToday);
        kpis.put("approvedThisMonth", approvedThisMonth);

        List<Map<String, Object>> stages = new ArrayList<>();
        byStage.forEach((stage, count) -> stages.add(Map.of("stage", stage.name(), "label", stage.label(),
                "count", count)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("officer", Map.of("name", user.fullName(), "username", user.username(),
                "jurisdiction", jurisdiction(user)));
        out.put("today", today.toString());
        out.put("kpis", kpis);
        out.put("byStage", stages);
        out.put("byVillage", List.copyOf(byVillage.values()));
        out.put("approvalQueue", rows.stream()
                .filter(r -> TahsildarStage.valueOf((String) r.get("stage")).actionRequired())
                .limit(10).toList());
        out.put("recentApprovals", rows.stream()
                .filter(r -> r.get("approved_at") != null)
                .sorted(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("approved_at")))
                        .reversed())
                .limit(5).toList());
        return out;
    }

    public List<Map<String, Object>> records() {
        return records(requireTahsildar());
    }

    public Map<String, Object> record(String txnRef) {
        CurrentUser user = requireTahsildar();
        Map<String, Object> row = loadRecord(user, txnRef);
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        long txnId = ((Number) row.get("transaction_id")).longValue();
        var byTxn = new MapSqlParameterSource("txnId", txnId);

        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("property", ctx.property());
        out.put("parties", ctx.parties());
        out.put("witnesses", ctx.witnesses());
        out.put("registeredOwners", ctx.propertyOwners());
        out.put("feeCalculation", ctx.feeCalculation());
        out.put("payments", ctx.payments());
        out.put("ruleCheckResults", ctx.ruleResults());
        out.put("registration", first(jdbc.queryForList(
                "SELECT * FROM core.registration_result WHERE transaction_id = :txnId", byTxn)));
        Map<String, Object> survey = first(jdbc.queryForList("""
                SELECT s.*, u.full_name AS submitted_by_name FROM survey.submission s
                  LEFT JOIN sec."user" u ON u.id = s.submitted_by
                 WHERE s.transaction_id = :txnId ORDER BY s.submitted_at DESC LIMIT 1
                """, byTxn));
        out.put("survey", survey);
        out.put("siteVisits", jdbc.queryForList("""
                SELECT v.id, v.visit_purpose, v.status, v.proposed_by_role,
                       COALESCE(v.counter_visit_date, v.visit_date) AS agreed_date,
                       COALESCE(v.counter_visit_time, v.visit_time) AS agreed_time,
                       v.accepted_at, v.vao_checkin_at, v.surveyor_checkin_at
                  FROM survey.site_visit v WHERE v.transaction_id = :txnId ORDER BY v.created_at
                """, byTxn));
        Object mutationId = row.get("mutation_id");
        out.put("mutation", mutationId == null ? null : revenue.detail(((Number) mutationId).longValue()));
        out.put("timeline", jdbc.queryForList("""
                SELECT occurred_at, actor_username, actor_role, action, from_status, to_status, decision, detail
                  FROM sec.audit_log
                 WHERE state_code = :stateCode AND transaction_ref = :txnRef
                 ORDER BY occurred_at
                """, new MapSqlParameterSource().addValue("stateCode", user.stateCode())
                .addValue("txnRef", txnRef)));
        return out;
    }

    /**
     * Refuses records outside the Tahsildar's taluk before delegating to the authoritative
     * Revenue approval, which re-checks the checklist under a row lock.
     */
    @Transactional
    public Map<String, Object> approve(String txnRef, RevenueService.ApprovalRequest req) {
        CurrentUser user = requireTahsildar();
        Map<String, Object> row = loadRecord(user, txnRef);
        if (row.get("mutation_id") == null) {
            throw ApiException.conflict("Record " + txnRef + " has no mutation forwarded by the VAO yet");
        }
        revenue.approve(((Number) row.get("mutation_id")).longValue(), req);
        return record(txnRef);
    }

    private List<Map<String, Object>> records(CurrentUser user) {
        return jdbc.queryForList(RECORD_SELECT + " ORDER BY t.registered_at DESC NULLS LAST, t.id DESC",
                        scope(user)).stream()
                .map(TahsildarService::enrich)
                .sorted(Comparator.comparing((Map<String, Object> r) ->
                        TahsildarStage.valueOf((String) r.get("stage")).actionRequired() ? 0 : 1))
                .toList();
    }

    private Map<String, Object> loadRecord(CurrentUser user, String txnRef) {
        var rows = jdbc.queryForList(RECORD_SELECT + " AND t.txn_ref = :txnRef",
                scope(user).addValue("txnRef", txnRef));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Record " + txnRef + " in your taluk");
        }
        return enrich(rows.get(0));
    }

    static Map<String, Object> enrich(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>(raw);
        List<Map<String, Object>> checklist = ApprovalChecklist.evaluate(row);
        TahsildarStage stage = TahsildarStage.of(row, checklist);
        row.put("stage", stage.name());
        row.put("stage_label", stage.label());
        row.put("action_required", stage.actionRequired());
        row.put("checklist", checklist);
        row.put("can_approve", stage == TahsildarStage.READY_FOR_APPROVAL);
        return row;
    }

    /** Villages of every taluk the Tahsildar is posted to, plus any villages assigned directly. */
    private MapSqlParameterSource scope(CurrentUser user) {
        List<String> villages = new ArrayList<>(jdbc.queryForList("""
                SELECT DISTINCT rv.village_code FROM sec.user_jurisdiction uj
                  JOIN master.taluk tk ON tk.taluk_code = uj.taluk_code
                  JOIN master.revenue_village rv ON rv.taluk_id = tk.id
                 WHERE uj.user_id = :userId AND uj.village_code IS NULL
                """, new MapSqlParameterSource("userId", user.id()), String.class));
        villages.addAll(user.villageCodes());
        if (villages.isEmpty()) {
            villages.add("__NONE__");
        }
        return new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("villages", villages);
    }

    private List<Map<String, Object>> jurisdiction(CurrentUser user) {
        return jdbc.queryForList("""
                SELECT DISTINCT uj.district_code, uj.taluk_code, tk.taluk_name
                  FROM sec.user_jurisdiction uj
                  LEFT JOIN master.taluk tk ON tk.taluk_code = uj.taluk_code
                 WHERE uj.user_id = :userId
                """, new MapSqlParameterSource("userId", user.id()));
    }

    private static CurrentUser requireTahsildar() {
        CurrentUser user = CurrentUser.require();
        if (!user.hasRole("TAHSILDAR")) {
            throw ApiException.forbidden("The Tahsildar portal is only available to Tahsildars");
        }
        user.requirePermission("TXN_READ");
        return user;
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(ZONE).toLocalDate();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atZone(ZONE).toLocalDate();
        }
        return null;
    }
}
