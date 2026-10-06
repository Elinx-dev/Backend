package in.gov.slate.survey;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.chain.TokenService;
import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.NumberingService;
import in.gov.slate.registration.RegistrationService;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.WorkflowEngine;
import jakarta.validation.constraints.NotNull;

/**
 * Joint Surveyor/VAO site visit, measurement capture and parcel splitting.
 * Splitting a parcel is a survey outcome: registration alone never creates
 * child parcels or child tokens.
 */
@Service
public class SurveyService {

    private static final BigDecimal MAX_VARIANCE_PCT = new BigDecimal("99999999.9999");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final TokenService tokens;
    private final RegistrationService registration;
    private final NumberingService numbering;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final BigDecimal defaultTolerancePct;

    public SurveyService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository, WorkflowEngine workflow,
                         TokenService tokens, RegistrationService registration, NumberingService numbering,
                         AuditService audit, ObjectMapper mapper,
                         @Value("${slate.survey.tolerance-pct}") BigDecimal defaultTolerancePct) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.workflow = workflow;
        this.tokens = tokens;
        this.registration = registration;
        this.numbering = numbering;
        this.audit = audit;
        this.mapper = mapper;
        this.defaultTolerancePct = defaultTolerancePct;
    }

    public record VisitRequest(@NotNull LocalDate visitDate, LocalTime visitTime, String role) {
    }

    public record ParcelInput(@NotNull BigDecimal extentValue, String extentUnit,
                              List<OwnerShare> owners, Map<String, Object> geometryGeoJson,
                              String officialSubdivisionNo) {
    }

    public record OwnerShare(String name, BigDecimal sharePct) {
    }

    public record SegmentInput(String fromPoint, String toPoint, BigDecimal lengthValue, String lengthUnit,
                               String bearing, String adjoiningFeature) {
    }

    public record BoundaryPointInput(String pointLabel, String markerStatus, BigDecimal latitude,
                                     BigDecimal longitude) {
    }

    public record SubmissionRequest(@NotNull BigDecimal measuredExtent, String extentUnit, String fmbSketchReference,
                                    LocalDate surveyDate, BigDecimal centroidLat, BigDecimal centroidLon,
                                    String boundaryNorth, String boundarySouth, String boundaryEast,
                                    String boundaryWest, String siteNotes, List<SegmentInput> segments,
                                    List<BoundaryPointInput> boundaryPoints, List<ParcelInput> parcels) {
    }

    @Transactional
    public Map<String, Object> proposeVisit(String txnRef, VisitRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("SURVEY_SCHEDULE");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        requireAssignedSurveyor(ctx, user);
        String role = req.role() != null ? req.role() : (user.hasRole("VAO") ? "VAO" : "SURVEYOR");

        var existing = jdbc.queryForList("""
                SELECT id, status, proposed_by_role FROM survey.site_visit
                 WHERE transaction_id = :txnId AND status IN ('PROPOSED','COUNTER_PROPOSED')
                 ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource("txnId", ctx.id()));

        if (!existing.isEmpty() && !role.equals(existing.get(0).get("proposed_by_role"))) {
            long visitId = ((Number) existing.get(0).get("id")).longValue();
            jdbc.update("""
                    UPDATE survey.site_visit SET counter_visit_date = :date, counter_visit_time = :time,
                           counter_by_role = :role, status = 'COUNTER_PROPOSED'
                     WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", visitId).addValue("date", req.visitDate())
                    .addValue("time", req.visitTime()).addValue("role", role));
            audit.record("SURVEY_VISIT_COUNTER_PROPOSED", "SITE_VISIT", String.valueOf(visitId), txnRef,
                    ctx.propertyRef(), Map.of("visitDate", req.visitDate().toString()), null);
            return visit(visitId);
        }

        var keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO survey.site_visit (state_code, transaction_id, proposed_by_role, proposed_by_user_id,
                    visit_date, visit_time, status)
                VALUES (:stateCode, :txnId, :role, :userId, :date, :time, 'PROPOSED')
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.transaction().get("state_code"))
                .addValue("txnId", ctx.id())
                .addValue("role", role)
                .addValue("userId", user.id())
                .addValue("date", req.visitDate())
                .addValue("time", req.visitTime()), keyHolder, new String[]{"id"});
        long visitId = keyHolder.getKey().longValue();
        audit.record("SURVEY_VISIT_PROPOSED", "SITE_VISIT", String.valueOf(visitId), txnRef, ctx.propertyRef(),
                Map.of("visitDate", req.visitDate().toString(), "role", role), null);
        return visit(visitId);
    }

    @Transactional
    public Map<String, Object> acceptVisit(String txnRef, long visitId) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("SURVEY_SCHEDULE");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        jdbc.update("UPDATE survey.site_visit SET status = 'ACCEPTED', accepted_at = now() WHERE id = :id AND transaction_id = :txnId",
                new MapSqlParameterSource().addValue("id", visitId).addValue("txnId", ctx.id()));
        audit.record("SURVEY_VISIT_ACCEPTED", "SITE_VISIT", String.valueOf(visitId), txnRef, ctx.propertyRef(),
                Map.of("visitId", visitId), null);
        return visit(visitId);
    }

    /** Check-in time is taken from the server clock; it is never entered by the officer. */
    @Transactional
    public Map<String, Object> checkIn(String txnRef, long visitId) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("SURVEY_SCHEDULE");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        String column = user.hasRole("VAO") ? "vao_checkin_at" : "surveyor_checkin_at";
        jdbc.update("UPDATE survey.site_visit SET " + column + " = now() WHERE id = :id AND transaction_id = :txnId",
                new MapSqlParameterSource().addValue("id", visitId).addValue("txnId", ctx.id()));
        audit.record("SURVEY_CHECKIN", "SITE_VISIT", String.valueOf(visitId), txnRef, ctx.propertyRef(),
                Map.of("by", user.username()), null);
        return visit(visitId);
    }

    public Map<String, Object> visit(long visitId) {
        return jdbc.queryForList("SELECT * FROM survey.site_visit WHERE id = :id",
                new MapSqlParameterSource("id", visitId)).get(0);
    }

    public List<Map<String, Object>> visits(String txnRef) {
        CurrentUser user = CurrentUser.require();
        return jdbc.queryForList("SELECT * FROM survey.site_visit WHERE transaction_id = :id ORDER BY id",
                new MapSqlParameterSource("id", repository.idOf(txnRef, user.stateCode())));
    }

    @Transactional
    public Map<String, Object> submit(String txnRef, SubmissionRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("SURVEY_SUBMIT");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        requireAssignedSurveyor(ctx, user);
        if (!"SURVEY_PENDING".equals(ctx.status())) {
            throw ApiException.conflict("Survey can only be submitted while the transaction is SURVEY_PENDING");
        }

        Long visitId = jdbc.query("""
                SELECT id FROM survey.site_visit
                 WHERE transaction_id = :txnId AND status IN ('ACCEPTED','COMPLETED')
                 ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource("txnId", ctx.id()), rs -> rs.next() ? rs.getLong(1) : null);
        if (visitId == null) {
            throw ApiException.conflict("Book a site-visit slot with the VAO before submitting the survey");
        }
        validateCoordinates(req);

        BigDecimal recordedExtent = new BigDecimal(ctx.property().get("extent_value").toString());
        String recordedUnit = (String) ctx.property().get("extent_unit");
        String measuredUnit = req.extentUnit() == null || req.extentUnit().isBlank()
                ? recordedUnit : AreaUnits.code(req.extentUnit());
        BigDecimal measuredInRecordUnit = AreaUnits.convert(req.measuredExtent(), measuredUnit, recordedUnit);
        BigDecimal variance = recordedExtent.signum() == 0 ? BigDecimal.ZERO
                : measuredInRecordUnit.subtract(recordedExtent).abs()
                .divide(recordedExtent, 6, RoundingMode.HALF_UP).multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP)
                .min(MAX_VARIANCE_PCT);
        boolean withinTolerance = variance.compareTo(defaultTolerancePct) <= 0;

        var keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO survey.submission (state_code, transaction_id, site_visit_id, survey_purpose, survey_no,
                    subdivision_no, old_survey_reference, fmb_sketch_reference, authoritative_recorded_extent,
                    measured_extent, extent_unit, variance_pct, tolerance_pct, within_tolerance, survey_date,
                    centroid_lat, centroid_lon, boundary_north, boundary_south, boundary_east, boundary_west,
                    site_notes, resulting_parcel_count, submitted_by, attestation_user_ref, routed_to,
                    measured_extent_in_record_unit, boundary_geojson)
                VALUES (:stateCode, :txnId, :visitId, :purpose, :surveyNo, :subdivisionNo, :oldSurveyRef, :fmb,
                    :recordedExtent, :measuredExtent, :extentUnit, :variance, :tolerance, :within, :surveyDate,
                    :lat, :lon, :north, :south, :east, :west, :notes, :parcelCount, :userId, :attestation, :routedTo,
                    :measuredInRecordUnit, cast(:geojson AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.transaction().get("state_code"))
                .addValue("txnId", ctx.id())
                .addValue("visitId", visitId)
                .addValue("purpose", surveyPurpose(ctx))
                .addValue("surveyNo", ctx.property().get("survey_no"))
                .addValue("subdivisionNo", ctx.property().get("subdivision_no"))
                .addValue("oldSurveyRef", ctx.property().get("old_survey_reference"))
                .addValue("fmb", req.fmbSketchReference())
                .addValue("recordedExtent", recordedExtent)
                .addValue("measuredExtent", req.measuredExtent())
                .addValue("extentUnit", measuredUnit)
                .addValue("measuredInRecordUnit", measuredInRecordUnit)
                .addValue("geojson", polygonGeoJson(req.boundaryPoints()))
                .addValue("variance", variance)
                .addValue("tolerance", defaultTolerancePct)
                .addValue("within", withinTolerance)
                .addValue("surveyDate", req.surveyDate() != null ? req.surveyDate() : LocalDate.now())
                .addValue("lat", req.centroidLat())
                .addValue("lon", req.centroidLon())
                .addValue("north", req.boundaryNorth())
                .addValue("south", req.boundarySouth())
                .addValue("east", req.boundaryEast())
                .addValue("west", req.boundaryWest())
                .addValue("notes", req.siteNotes())
                .addValue("parcelCount", req.parcels() == null ? 0 : req.parcels().size())
                .addValue("userId", user.id())
                .addValue("attestation", user.username())
                .addValue("routedTo", withinTolerance ? "VAO_VERIFICATION" : "SURVEY_CORRECTION_REVIEW"),
                keyHolder, new String[]{"id"});
        long submissionId = keyHolder.getKey().longValue();

        if (withinTolerance) {
            jdbc.update("UPDATE survey.site_visit SET status = 'COMPLETED' WHERE id = :id",
                    new MapSqlParameterSource("id", visitId));
        }
        saveSegments(submissionId, req.segments());
        saveBoundaryPoints(submissionId, req.boundaryPoints());
        List<Map<String, Object>> childTokens = saveParcels(ctx, submissionId, req.parcels());

        audit.record("SURVEY_SUBMITTED", "SURVEY_SUBMISSION", String.valueOf(submissionId), txnRef, ctx.propertyRef(),
                Map.of("measuredExtent", req.measuredExtent(), "extentUnit", String.valueOf(measuredUnit),
                        "variancePct", variance,
                        "withinTolerance", withinTolerance), null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submissionId", submissionId);
        out.put("measuredExtentInRecordUnit", measuredInRecordUnit);
        out.put("variancePct", variance);
        out.put("tolerancePct", defaultTolerancePct);
        out.put("withinTolerance", withinTolerance);
        out.put("routedTo", withinTolerance ? "VAO_VERIFICATION" : "SURVEY_CORRECTION_REVIEW");
        out.put("childTokens", childTokens);

        if (withinTolerance) {
            TransactionContext reloaded = repository.load(txnRef, user.stateCode());
            long registrationId = jdbc.queryForObject(
                    "SELECT id FROM core.registration_result WHERE transaction_id = :id",
                    new MapSqlParameterSource("id", ctx.id()), Long.class);
            registration.createProposedMutation(reloaded, registrationId,
                    registration.resultingOwners(reloaded), submissionId);
            out.put("status", workflow.apply(reloaded, "SUBMIT_SURVEY", null, user));
        } else {
            out.put("status", ctx.status());
            out.put("note", "Measured extent is outside tolerance; the submission is routed to survey correction "
                    + "review before the Revenue queue.");
        }
        return out;
    }

    private static void validateCoordinates(SubmissionRequest req) {
        if (req.measuredExtent().signum() <= 0) {
            throw ApiException.badRequest("Measured extent must be greater than zero");
        }
        requireCoordinate(req.centroidLat(), 90, "Latitude");
        requireCoordinate(req.centroidLon(), 180, "Longitude");
        if (req.boundaryPoints() != null) {
            for (BoundaryPointInput point : req.boundaryPoints()) {
                requireCoordinate(point.latitude(), 90, "Polygon latitude");
                requireCoordinate(point.longitude(), 180, "Polygon longitude");
            }
        }
    }

    private static void requireCoordinate(BigDecimal value, int limit, String label) {
        if (value != null && value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
            throw ApiException.badRequest(label + " must be between -" + limit + " and " + limit);
        }
    }

    /** The polygon vertices as a closed GeoJSON ring, or null when fewer than three were captured. */
    private String polygonGeoJson(List<BoundaryPointInput> points) {
        if (points == null) {
            return null;
        }
        List<List<BigDecimal>> ring = new ArrayList<>();
        for (BoundaryPointInput point : points) {
            if (point.latitude() != null && point.longitude() != null) {
                ring.add(List.of(point.longitude(), point.latitude()));
            }
        }
        if (ring.size() < 3) {
            return null;
        }
        ring.add(ring.get(0));
        return json(Map.of("type", "Polygon", "coordinates", List.of(ring)));
    }

    /** When a surveyor was assigned at initiation, only that surveyor may act as SURVEYOR. */
    private void requireAssignedSurveyor(TransactionContext ctx, CurrentUser user) {
        Object assigned = ctx.transaction().get("assigned_surveyor_id");
        if (assigned != null && user.hasRole("SURVEYOR") && !user.hasRole("VAO")
                && ((Number) assigned).longValue() != user.id()) {
            throw ApiException.forbidden("This survey is assigned to another surveyor");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String surveyPurpose(TransactionContext ctx) {
        if (Boolean.TRUE.equals(ctx.transaction().get("subdivision_required"))) {
            return ctx.deedTypeCode() + "_SUBDIVISION";
        }
        if (Boolean.TRUE.equals(ctx.transaction().get("survey_required_by_party"))) {
            return "PARTY_REQUESTED_SURVEY";
        }
        return switch (ctx.deedTypeCode()) {
            case "PARTITION" -> "PARTITION_SUBDIVISION";
            case "SALE_PARTIAL_SUBDIVISION" -> "SALE_SUBDIVISION";
            default -> "EXTENT_VERIFICATION";
        };
    }

    private void saveSegments(long submissionId, List<SegmentInput> segments) {
        if (segments == null) {
            return;
        }
        int seq = 1;
        for (SegmentInput segment : segments) {
            jdbc.update("""
                    INSERT INTO survey.measurement_segment (submission_id, seq, from_point, to_point, length_value,
                        length_unit, bearing, adjoining_feature)
                    VALUES (:submissionId, :seq, :from, :to, :length, :unit, :bearing, :adjoining)
                    """, new MapSqlParameterSource()
                    .addValue("submissionId", submissionId)
                    .addValue("seq", seq++)
                    .addValue("from", segment.fromPoint())
                    .addValue("to", segment.toPoint())
                    .addValue("length", segment.lengthValue())
                    .addValue("unit", segment.lengthUnit())
                    .addValue("bearing", segment.bearing())
                    .addValue("adjoining", segment.adjoiningFeature()));
        }
    }

    private void saveBoundaryPoints(long submissionId, List<BoundaryPointInput> points) {
        if (points == null) {
            return;
        }
        int seq = 1;
        for (BoundaryPointInput point : points) {
            jdbc.update("""
                    INSERT INTO survey.boundary_point (submission_id, seq, point_label, marker_status, latitude,
                        longitude)
                    VALUES (:submissionId, :seq, :label, :status, :lat, :lon)
                    """, new MapSqlParameterSource()
                    .addValue("submissionId", submissionId)
                    .addValue("seq", seq++)
                    .addValue("label", point.pointLabel())
                    .addValue("status", point.markerStatus())
                    .addValue("lat", point.latitude())
                    .addValue("lon", point.longitude()));
        }
    }

    /**
     * Each resulting parcel becomes a child property and a child token; the parent
     * token is superseded by {@link TokenService#split}.
     */
    private List<Map<String, Object>> saveParcels(TransactionContext ctx, long submissionId,
                                                  List<ParcelInput> parcels) {
        if (parcels == null || parcels.isEmpty()) {
            return List.of();
        }
        String stateCode = (String) ctx.transaction().get("state_code");
        long parentPropertyId = ((Number) ctx.property().get("id")).longValue();
        List<TokenService.ChildParcel> children = new ArrayList<>();
        int seq = 1;
        for (ParcelInput parcel : parcels) {
            String childRef = numbering.next(stateCode, "PROPERTY_REF",
                    (String) ctx.property().get("district_code"));
            var keyHolder = new GeneratedKeyHolder();
            jdbc.update("""
                    INSERT INTO core.property (state_code, property_ref, property_type_code, nature_of_title_code,
                        land_type_code, classification_code, extent_value, extent_unit, survey_no, subdivision_no,
                        old_survey_reference, district_code, taluk_code, village_code, sro_code, street, door_no,
                        guideline_value, guideline_value_reference, parent_property_id, created_by)
                    SELECT p.state_code, :childRef, p.property_type_code, 'PARTITION', p.land_type_code,
                           p.classification_code, :extent, :extentUnit, p.survey_no,
                           :officialSubdivisionNo, p.subdivision_no, p.district_code,
                           p.taluk_code, p.village_code, p.sro_code, p.street, p.door_no, p.guideline_value,
                           p.guideline_value_reference, p.id, :userId
                      FROM core.property p WHERE p.id = :parentId
                    """, new MapSqlParameterSource()
                    .addValue("childRef", childRef)
                    .addValue("extent", parcel.extentValue())
                    .addValue("extentUnit", parcel.extentUnit() != null ? parcel.extentUnit()
                            : ctx.property().get("extent_unit"))
                    .addValue("officialSubdivisionNo", blankToNull(parcel.officialSubdivisionNo()))
                    .addValue("parentId", parentPropertyId)
                    .addValue("userId", CurrentUser.require().id()), keyHolder, new String[]{"id"});
            long childPropertyId = keyHolder.getKey().longValue();

            List<TokenService.Owner> owners = new ArrayList<>();
            if (parcel.owners() != null) {
                for (OwnerShare owner : parcel.owners()) {
                    owners.add(new TokenService.Owner(owner.name(), owner.sharePct()));
                    jdbc.update("""
                            INSERT INTO core.property_owner (property_id, owner_name, share_pct, source,
                                transaction_id, effective_from)
                            VALUES (:propertyId, :name, :share, 'REGISTRATION', :txnId, current_date)
                            """, new MapSqlParameterSource()
                            .addValue("propertyId", childPropertyId)
                            .addValue("name", owner.name())
                            .addValue("share", owner.sharePct())
                            .addValue("txnId", ctx.id()));
                }
            }

            jdbc.update("""
                    INSERT INTO survey.resulting_parcel (submission_id, seq, extent_value, extent_unit,
                        intended_owner_mapping, child_property_id, geometry_geojson, official_subdivision_no)
                    VALUES (:submissionId, :seq, :extent, :extentUnit, cast(:owners AS jsonb), :childPropertyId,
                        cast(:geometry AS jsonb), :officialSubdivisionNo)
                    """, new MapSqlParameterSource()
                    .addValue("submissionId", submissionId)
                    .addValue("seq", seq)
                    .addValue("extent", parcel.extentValue())
                    .addValue("extentUnit", parcel.extentUnit() != null ? parcel.extentUnit()
                            : ctx.property().get("extent_unit"))
                    .addValue("owners", json(parcel.owners() == null ? List.of() : parcel.owners()))
                    .addValue("childPropertyId", childPropertyId)
                    .addValue("officialSubdivisionNo", blankToNull(parcel.officialSubdivisionNo()))
                    .addValue("geometry", parcel.geometryGeoJson() == null ? null : json(parcel.geometryGeoJson())));

            children.add(new TokenService.ChildParcel(childRef, childPropertyId, owners));
            seq++;
        }

        List<Map<String, Object>> minted = tokens.split(stateCode, parentPropertyId, ctx.id(), children);
        for (int i = 0; i < minted.size(); i++) {
            jdbc.update("""
                    UPDATE survey.resulting_parcel SET child_token_id = :tokenId
                     WHERE submission_id = :submissionId AND seq = :seq
                    """, new MapSqlParameterSource()
                    .addValue("tokenId", minted.get(i).get("id"))
                    .addValue("submissionId", submissionId)
                    .addValue("seq", i + 1));
        }
        return minted;
    }

    public List<Map<String, Object>> submissions(String txnRef) {
        CurrentUser user = CurrentUser.require();
        return jdbc.queryForList("SELECT * FROM survey.submission WHERE transaction_id = :id ORDER BY id",
                new MapSqlParameterSource("id", repository.idOf(txnRef, user.stateCode())));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise survey payload", e);
        }
    }
}
