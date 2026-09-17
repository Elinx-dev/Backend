package in.gov.slate.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.connectors.RevenueConnector;
import in.gov.slate.connectors.model.RevenueModels.RevenueLookupRequest;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwner;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwnershipResponse;
import in.gov.slate.transaction.TransactionContext;

/**
 * Compares registered ownership with the Revenue record. The two remain
 * separate facts: this engine reports differences, it does not reconcile them.
 * Lookup is by survey identity; the record number is display-only.
 */
@Component
public class RevenueOwnershipEngine implements RuleEngine {

    private final RevenueConnector connector;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RevenueOwnershipEngine(RevenueConnector connector, NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.connector = connector;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String engine() {
        return "REVENUE_OWNERSHIP";
    }

    @Override
    public Outcome run(TransactionContext ctx, long requestId, LocalDate assessmentDate, String mode) {
        Map<String, Object> property = ctx.property();
        var request = new RevenueLookupRequest(
                (String) property.get("district_code"), (String) property.get("taluk_code"),
                (String) property.get("village_code"), (String) property.get("survey_no"),
                (String) property.get("subdivision_no"), (String) property.get("land_type_code"));

        RevenueOwnershipResponse response = connector.lookup(request);
        boolean lineageApplied = false;

        if (response == null || "UNAVAILABLE".equals(response.responseStatus())) {
            persistSnapshot(requestId, ctx, response, "SURVEY_IDENTITY_UNRESOLVED", false);
            return new Outcome("NOT_CHECKED", "REVENUE_DATA_UNAVAILABLE", Map.of(
                    "note", "The Revenue source did not return a usable record; this is not a discrepancy",
                    "advisory", true));
        }

        if ("NOT_FOUND".equals(response.responseStatus())) {
            var lineage = lineageTarget(ctx);
            if (lineage != null) {
                response = connector.lookup(new RevenueLookupRequest(request.district(), request.taluk(),
                        request.village(), lineage[0], lineage[1], request.landType()));
                lineageApplied = true;
            }
        }

        String parcelMatch = switch (response.responseStatus()) {
            case "FOUND" -> "PARCEL_MATCH";
            case "MULTIPLE" -> "MULTIPLE_REVENUE_RECORDS";
            default -> "PARCEL_NOT_FOUND";
        };
        long snapshotId = persistSnapshot(requestId, ctx, response, parcelMatch, lineageApplied);
        persistOwners(snapshotId, response.owners());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parcelMatchStatus", parcelMatch);
        payload.put("lineageApplied", lineageApplied);
        payload.put("recordNumberDisplayOnly", response.recordNumber());
        payload.put("revenueOwners", response.owners());
        payload.put("advisory", true);

        if (!"PARCEL_MATCH".equals(parcelMatch)) {
            payload.put("findings", List.of(Map.of("code", parcelMatch,
                    "message", "MULTIPLE_REVENUE_RECORDS".equals(parcelMatch)
                            ? "More than one Revenue record matches this survey identity"
                            : "No Revenue record was found for this survey identity")));
            return new Outcome("REVIEW_REQUIRED", parcelMatch, payload);
        }

        List<String> registeredNames = registeredNames(ctx, mode);
        payload.put("registeredOwners", registeredNames);
        if (registeredNames.isEmpty()) {
            payload.put("findings", List.of(Map.of("code", "NO_REGISTERED_OWNER_ON_RECORD",
                    "message", "There is no registered owner to compare against")));
            return new Outcome("NOT_CHECKED", "NO_REGISTERED_OWNER_ON_RECORD", payload);
        }

        List<Map<String, Object>> comparisons = new ArrayList<>();
        boolean anyPossible = false;
        boolean anyNoMatch = false;
        for (String registered : registeredNames) {
            NameMatcher.Result best = null;
            String bestAgainst = null;
            for (RevenueOwner owner : response.owners()) {
                NameMatcher.Result result = NameMatcher.compare(registered, owner.name());
                if (best == null || rank(result.decision()) > rank(best.decision())) {
                    best = result;
                    bestAgainst = owner.name();
                }
            }
            if (best == null) {
                anyNoMatch = true;
                continue;
            }
            persistNameMatch(requestId, registered, bestAgainst, best);
            comparisons.add(Map.of("registeredOwner", registered, "revenueOwner", bestAgainst,
                    "decision", best.decision().name(), "rationale", best.rationale()));
            if (best.decision() == NameMatcher.Decision.POSSIBLE_NAME_MATCH) {
                anyPossible = true;
            }
            if (best.decision() == NameMatcher.Decision.NO_MATCH) {
                anyNoMatch = true;
            }
        }
        payload.put("nameComparisons", comparisons);

        if (anyNoMatch) {
            return new Outcome("DISCREPANCY_DETECTED", "OWNER_NAME_MISMATCH", payload);
        }
        if (anyPossible) {
            return new Outcome("REVIEW_REQUIRED", "POSSIBLE_NAME_MATCH", payload);
        }
        if (response.owners().size() != registeredNames.size()) {
            return new Outcome("REVIEW_REQUIRED", "OWNER_COUNT_DIFFERS", payload);
        }
        return new Outcome("NO_DISCREPANCY_DETECTED", "OWNERSHIP_CONSISTENT", payload);
    }

    private int rank(NameMatcher.Decision decision) {
        return switch (decision) {
            case MATCH -> 3;
            case POSSIBLE_NAME_MATCH -> 2;
            case NO_MATCH -> 1;
        };
    }

    /**
     * Pilot reconciliation compares the currently recorded owners; pre-registration
     * clearance compares the transferring side of the pending transaction.
     */
    private List<String> registeredNames(TransactionContext ctx, String mode) {
        if ("PRE_REGISTRATION_CLEARANCE".equals(mode)) {
            return ctx.side("SIDE_1").stream().map(p -> (String) p.get("name")).toList();
        }
        return ctx.propertyOwners().stream().map(o -> (String) o.get("owner_name")).toList();
    }

    private String[] lineageTarget(TransactionContext ctx) {
        var rows = jdbc.queryForList("""
                SELECT to_survey_no, to_subdivision FROM rules.survey_lineage
                 WHERE state_code = :stateCode AND revenue_village = :village
                   AND from_survey_no = :surveyNo
                   AND (from_subdivision IS NOT DISTINCT FROM :subdivisionNo)
                   AND verified_at IS NOT NULL
                 ORDER BY effective_date DESC LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.property().get("state_code"))
                .addValue("village", ctx.property().get("village_code"))
                .addValue("surveyNo", ctx.property().get("survey_no"))
                .addValue("subdivisionNo", ctx.property().get("subdivision_no")));
        return rows.isEmpty() ? null
                : new String[]{(String) rows.get(0).get("to_survey_no"), (String) rows.get(0).get("to_subdivision")};
    }

    private long persistSnapshot(long requestId, TransactionContext ctx, RevenueOwnershipResponse response,
                                 String parcelMatch, boolean lineageApplied) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO rules.revenue_ownership_snapshot (request_id, property_id, record_no, district_code,
                    taluk_code, revenue_village, survey_no, subdivision_no, land_type, classification,
                    extent_value, extent_unit, record_status, portal_reference, parcel_match_status,
                    lineage_applied, raw_payload)
                VALUES (:requestId, :propertyId, :recordNo, :district, :taluk, :village, :surveyNo, :subdivisionNo,
                    :landType, :classification, :extent, :extentUnit, :recordStatus, :portalRef, :parcelMatch,
                    :lineageApplied, cast(:raw AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("propertyId", ctx.property().get("id"))
                .addValue("recordNo", response == null ? null : response.recordNumber())
                .addValue("district", ctx.property().get("district_code"))
                .addValue("taluk", ctx.property().get("taluk_code"))
                .addValue("village", ctx.property().get("village_code"))
                .addValue("surveyNo", ctx.property().get("survey_no"))
                .addValue("subdivisionNo", ctx.property().get("subdivision_no"))
                .addValue("landType", ctx.property().get("land_type_code"))
                .addValue("classification", response == null ? null : response.classification())
                .addValue("extent", response == null ? null : response.extent())
                .addValue("extentUnit", response == null ? null : response.extentUnit())
                .addValue("recordStatus", response == null ? "UNAVAILABLE" : response.responseStatus())
                .addValue("portalRef", response == null ? null : response.sourceReference())
                .addValue("parcelMatch", parcelMatch)
                .addValue("lineageApplied", lineageApplied)
                .addValue("raw", json(response)), keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    private void persistOwners(long snapshotId, List<RevenueOwner> owners) {
        if (owners == null) {
            return;
        }
        int seq = 0;
        for (RevenueOwner owner : owners) {
            seq++;
            jdbc.update("""
                    INSERT INTO rules.revenue_owner (snapshot_id, seq, name, relation_type, related_person_name, share_pct)
                    VALUES (:snapshotId, :seq, :name, :relationType, :relatedName, :sharePct)
                    """, new MapSqlParameterSource()
                    .addValue("snapshotId", snapshotId)
                    .addValue("seq", seq)
                    .addValue("name", owner.name())
                    .addValue("relationType", owner.relationType())
                    .addValue("relatedName", owner.relatedPersonName())
                    .addValue("sharePct", owner.sharePct()));
        }
    }

    private void persistNameMatch(long requestId, String left, String right, NameMatcher.Result result) {
        jdbc.update("""
                INSERT INTO rules.name_match_audit (request_id, left_name, left_source, right_name, right_source,
                    normalized_left, normalized_right, decision, rationale)
                VALUES (:requestId, :left, 'REGISTERED', :right, 'REVENUE', :nl, :nr, :decision, :rationale)
                """, new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("left", left)
                .addValue("right", right)
                .addValue("nl", result.normalizedLeft())
                .addValue("nr", result.normalizedRight())
                .addValue("decision", result.decision().name())
                .addValue("rationale", result.rationale()));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise Revenue payload", e);
        }
    }
}
