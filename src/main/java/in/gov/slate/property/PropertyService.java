package in.gov.slate.property;

import java.math.BigDecimal;
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
import in.gov.slate.common.NumberingService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Property entry creates a property reference and nothing else: no token, no
 * rule check, no consent request. Those only happen inside a transaction.
 */
@Service
public class PropertyService {

    private final NamedParameterJdbcTemplate jdbc;
    private final NumberingService numbering;
    private final AuditService audit;

    public PropertyService(NamedParameterJdbcTemplate jdbc, NumberingService numbering, AuditService audit) {
        this.jdbc = jdbc;
        this.numbering = numbering;
        this.audit = audit;
    }

    public record OwnerInput(@NotBlank String ownerName, String aadhaarNumber, String pan, String address,
                             BigDecimal sharePct, String shareNote) {
    }

    public record CreatePropertyRequest(
            String ulpin,
            @NotBlank String propertyTypeCode,
            String natureOfTitleCode,
            String landTypeCode,
            String classificationCode,
            @NotNull @Positive BigDecimal extentValue,
            @NotBlank String extentUnit,
            @NotBlank String surveyNo,
            String subdivisionNo,
            String oldSurveyReference,
            String fmbReferenceNo,
            @NotBlank String districtCode,
            String talukCode,
            String villageCode,
            @NotBlank String sroCode,
            String panchayat,
            String wardNo,
            String street,
            String doorNo,
            String boundaryNorth,
            String boundarySouth,
            String boundaryEast,
            String boundaryWest,
            BigDecimal guidelineValue,
            String guidelineValueReference,
            Boolean isApartmentUnit,
            ApartmentDetail apartmentDetail,
            List<OwnerInput> owners) {
    }

    public record ApartmentDetail(Long parentLandPropertyId, String flatNo, String blockTower, String floor,
                                  BigDecimal builtupArea, String areaUnit, BigDecimal udsFraction,
                                  String parentSurveyNo, String parentSubdivisionNo) {
    }

    @Transactional
    public Map<String, Object> create(CreatePropertyRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("PROPERTY_CREATE");
        String propertyRef = numbering.next(user.stateCode(), "PROPERTY_REF", req.districtCode());

        var params = new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("propertyRef", propertyRef)
                .addValue("ulpin", blankToNull(req.ulpin()))
                .addValue("propertyTypeCode", req.propertyTypeCode())
                .addValue("natureOfTitleCode", req.natureOfTitleCode())
                .addValue("landTypeCode", req.landTypeCode())
                .addValue("classificationCode", req.classificationCode())
                .addValue("extentValue", req.extentValue())
                .addValue("extentUnit", req.extentUnit())
                .addValue("surveyNo", req.surveyNo())
                .addValue("subdivisionNo", req.subdivisionNo())
                .addValue("oldSurveyReference", req.oldSurveyReference())
                .addValue("fmbReferenceNo", req.fmbReferenceNo())
                .addValue("districtCode", req.districtCode())
                .addValue("talukCode", req.talukCode())
                .addValue("villageCode", req.villageCode())
                .addValue("sroCode", req.sroCode())
                .addValue("panchayat", req.panchayat())
                .addValue("wardNo", req.wardNo())
                .addValue("street", req.street())
                .addValue("doorNo", req.doorNo())
                .addValue("boundaryNorth", req.boundaryNorth())
                .addValue("boundarySouth", req.boundarySouth())
                .addValue("boundaryEast", req.boundaryEast())
                .addValue("boundaryWest", req.boundaryWest())
                .addValue("guidelineValue", req.guidelineValue())
                .addValue("guidelineValueReference", req.guidelineValueReference())
                .addValue("isApartmentUnit", Boolean.TRUE.equals(req.isApartmentUnit()))
                .addValue("createdBy", user.id());

        Long id = jdbc.queryForObject("""
                INSERT INTO core.property (state_code, property_ref, ulpin, property_type_code, nature_of_title_code,
                    land_type_code, classification_code, extent_value, extent_unit, survey_no, subdivision_no,
                    old_survey_reference, fmb_reference_no, district_code, taluk_code, village_code, sro_code,
                    panchayat, ward_no, street, door_no, boundary_north, boundary_south, boundary_east, boundary_west,
                    guideline_value, guideline_value_reference, guideline_value_entry_date, is_apartment_unit, created_by)
                VALUES (:stateCode, :propertyRef, :ulpin, :propertyTypeCode, :natureOfTitleCode,
                    :landTypeCode, :classificationCode, :extentValue, :extentUnit, :surveyNo, :subdivisionNo,
                    :oldSurveyReference, :fmbReferenceNo, :districtCode, :talukCode, :villageCode, :sroCode,
                    :panchayat, :wardNo, :street, :doorNo, :boundaryNorth, :boundarySouth, :boundaryEast, :boundaryWest,
                    :guidelineValue, :guidelineValueReference,
                    CASE WHEN :guidelineValue IS NULL THEN NULL ELSE current_date END,
                    :isApartmentUnit, :createdBy)
                RETURNING id
                """, params, Long.class);

        if (Boolean.TRUE.equals(req.isApartmentUnit()) && req.apartmentDetail() != null) {
            var d = req.apartmentDetail();
            jdbc.update("""
                    INSERT INTO core.property_apartment_detail (property_id, parent_land_property_id, flat_no,
                        block_tower, floor, builtup_area, area_unit, uds_fraction, parent_survey_no, parent_subdivision_no)
                    VALUES (:propertyId, :parent, :flatNo, :block, :floor, :area, :unit, :uds, :psn, :psdn)
                    """, new MapSqlParameterSource()
                    .addValue("propertyId", id)
                    .addValue("parent", d.parentLandPropertyId())
                    .addValue("flatNo", d.flatNo())
                    .addValue("block", d.blockTower())
                    .addValue("floor", d.floor())
                    .addValue("area", d.builtupArea())
                    .addValue("unit", d.areaUnit())
                    .addValue("uds", d.udsFraction())
                    .addValue("psn", d.parentSurveyNo())
                    .addValue("psdn", d.parentSubdivisionNo()));
        }

        if (req.owners() != null) {
            for (OwnerInput o : req.owners()) {
                jdbc.update("""
                        INSERT INTO core.property_owner (property_id, owner_name, aadhaar_number, pan, address,
                            share_pct, share_note, source, effective_from)
                        VALUES (:propertyId, :name, :aadhaarNumber, :pan, :address, :share, :note,
                            'PROPERTY_ENTRY', current_date)
                        """, new MapSqlParameterSource()
                        .addValue("propertyId", id)
                        .addValue("name", o.ownerName())
                        .addValue("aadhaarNumber", o.aadhaarNumber())
                        .addValue("pan", o.pan())
                        .addValue("address", o.address())
                        .addValue("share", o.sharePct())
                        .addValue("note", o.shareNote()));
            }
        }

        audit.record("PROPERTY_CREATED", "PROPERTY", String.valueOf(id), null, propertyRef,
                Map.of("propertyRef", propertyRef, "surveyNo", req.surveyNo()), null);
        return get(propertyRef);
    }

    public Map<String, Object> get(String propertyRef) {
        CurrentUser user = CurrentUser.require();
        var rows = jdbc.queryForList("""
                SELECT p.*, t.token_ref, t.state_version AS token_state_version, t.status AS token_status
                  FROM core.property p
                  LEFT JOIN chain.token t ON t.id = p.token_id
                 WHERE p.property_ref = :ref AND p.state_code = :stateCode
                """, new MapSqlParameterSource().addValue("ref", propertyRef).addValue("stateCode", user.stateCode()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Property " + propertyRef);
        }
        Map<String, Object> property = new LinkedHashMap<>(rows.get(0));
        long id = ((Number) property.get("id")).longValue();
        var idParam = new MapSqlParameterSource("propertyId", id);
        property.put("registeredOwners", jdbc.queryForList("""
                SELECT owner_name, aadhaar_number, pan, address, share_pct, share_note, source, effective_from
                  FROM core.property_owner WHERE property_id = :propertyId AND effective_to IS NULL
                 ORDER BY id
                """, idParam));
        property.put("revenueOwners", jdbc.queryForList("""
                SELECT revenue_record_ref, owners, extent_value, extent_unit, fetched_at
                  FROM revenue.current_state WHERE property_id = :propertyId
                 ORDER BY fetched_at DESC LIMIT 1
                """, idParam));
        property.put("chainOfTitle", jdbc.queryForList("""
                SELECT seq, executor_name, claimant_name, transaction_date, nature_of_transaction, reference_no
                  FROM core.chain_of_title WHERE property_id = :propertyId ORDER BY seq
                """, idParam));
        property.put("transactions", jdbc.queryForList("""
                SELECT txn_ref, deed_type_code, status, current_stage_code, initiated_at, registered_at
                  FROM core.transaction WHERE property_id = :propertyId ORDER BY initiated_at DESC
                """, idParam));
        if (Boolean.TRUE.equals(property.get("is_apartment_unit"))) {
            property.put("apartmentDetail", jdbc.queryForList(
                    "SELECT * FROM core.property_apartment_detail WHERE property_id = :propertyId", idParam));
        }
        return property;
    }

    public List<Map<String, Object>> search(String query, String villageCode, String surveyNo, int limit) {
        CurrentUser user = CurrentUser.require();
        return jdbc.queryForList("""
                SELECT p.id, p.property_ref, p.ulpin, p.property_type_code, p.survey_no, p.subdivision_no,
                       p.extent_value, p.extent_unit, p.village_code, p.district_code, p.sro_code, p.status,
                       t.token_ref
                  FROM core.property p
                  LEFT JOIN chain.token t ON t.id = p.token_id
                 WHERE p.state_code = :stateCode
                   AND (CAST(:query AS text) IS NULL OR p.property_ref ILIKE '%'||:query||'%'
                        OR coalesce(p.ulpin,'') ILIKE '%'||:query||'%'
                        OR p.survey_no ILIKE '%'||:query||'%'
                        OR coalesce(p.door_no,'') ILIKE '%'||:query||'%')
                   AND (CAST(:villageCode AS text) IS NULL OR p.village_code = :villageCode)
                   AND (CAST(:surveyNo AS text) IS NULL OR p.survey_no = :surveyNo)
                 ORDER BY p.property_ref
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("query", blankToNull(query))
                .addValue("villageCode", blankToNull(villageCode))
                .addValue("surveyNo", blankToNull(surveyNo))
                .addValue("limit", limit));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
