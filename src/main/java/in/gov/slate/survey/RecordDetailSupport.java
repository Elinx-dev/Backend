package in.gov.slate.survey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import in.gov.slate.registration.RegistrationService;
import in.gov.slate.transaction.TransactionContext;

/** Read-only transaction, property and survey details shown to the VAO and Tahsildar while they act on a record. */
public final class RecordDetailSupport {

    private static final List<String> TRANSACTION_FIELDS = List.of(
            "txn_ref", "deed_type_code", "subtype", "transfer_scope", "survey_required", "sro_code",
            "relationship_category", "declared_consideration", "mode_of_consideration",
            "extent_or_share_transferred", "extent_unit", "guideline_value", "basis_of_settlement",
            "share_being_released", "resulting_subparcel_count", "remarks", "initiated_at", "registered_at");

    private RecordDetailSupport() {
    }

    public static void addRecordDetails(NamedParameterJdbcTemplate jdbc, TransactionContext ctx, Map<String, Object> out) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        TRANSACTION_FIELDS.forEach(field -> transaction.put(field, ctx.transaction().get(field)));
        out.put("transaction", transaction);

        var byProperty = new MapSqlParameterSource("propertyId", ctx.property().get("id"));
        out.put("surveyRecords", jdbc.queryForList("""
                SELECT seq, ulpin, survey_no, subdivision_no, extent_value, extent_unit
                  FROM core.property_survey WHERE property_id = :propertyId ORDER BY seq
                """, byProperty));
        out.put("boundaryMeasurements", jdbc.queryForList("""
                SELECT seq, from_point, to_point, value, unit
                  FROM core.property_measurement WHERE property_id = :propertyId ORDER BY seq
                """, byProperty));

        var byTxn = new MapSqlParameterSource("txnId", ctx.id());
        out.put("childProperties", jdbc.queryForList(RegistrationService.CHILD_PROPERTIES_SQL, byTxn));
        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT id FROM survey.submission WHERE transaction_id = :txnId ORDER BY id DESC LIMIT 1
                """, byTxn);
        if (latest.isEmpty()) {
            out.put("segments", List.of());
            out.put("boundary_points", List.of());
            out.put("parcels", List.of());
            return;
        }
        var bySubmission = new MapSqlParameterSource("id", latest.get(0).get("id"));
        out.put("segments", jdbc.queryForList("""
                SELECT seq, from_point, to_point, length_value, length_unit FROM survey.measurement_segment
                 WHERE submission_id = :id ORDER BY seq
                """, bySubmission));
        out.put("boundary_points", jdbc.queryForList("""
                SELECT seq, point_label, marker_status, latitude, longitude FROM survey.boundary_point
                 WHERE submission_id = :id ORDER BY seq
                """, bySubmission));
        out.put("parcels", jdbc.queryForList("""
                SELECT rp.seq, rp.survey_no, rp.official_subdivision_no, rp.extent_value, rp.extent_unit,
                       rp.boundary_north, rp.boundary_south, rp.boundary_east, rp.boundary_west,
                       cp.property_ref AS child_property_ref
                  FROM survey.resulting_parcel rp
                  LEFT JOIN core.property cp ON cp.id = rp.child_property_id
                 WHERE rp.submission_id = :id ORDER BY rp.seq
                """, bySubmission));
    }
}
