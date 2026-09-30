package in.gov.slate.property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.NumberingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Property entry creates a property reference and nothing else: no token, no
 * rule check, no consent request. Those only happen inside a transaction.
 */
@Service
public class PropertyService {

  private static final int MAX_BOUNDARY_MEASUREMENTS = 8;
  private static final Set<String> BOUNDARY_POINTS = Set.of("NORTH", "SOUTH", "EAST", "WEST",
      "NORTH_EAST", "NORTH_WEST", "SOUTH_EAST", "SOUTH_WEST");

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

        public record BoundaryMeasurementInput(
          @NotBlank @Pattern(regexp = "NORTH|SOUTH|EAST|WEST|NORTH_EAST|NORTH_WEST|SOUTH_EAST|SOUTH_WEST") String fromPoint,
          @NotBlank @Pattern(regexp = "NORTH|SOUTH|EAST|WEST|NORTH_EAST|NORTH_WEST|SOUTH_EAST|SOUTH_WEST") String toPoint,
          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal value,
          @NotBlank String unit) {
        }

        public record ChainOfTitleInput(
          @NotBlank String executorName,
          @NotBlank String claimantName,
          @NotNull LocalDate transactionDate,
          @NotBlank String natureOfTransaction,
          String referenceNo,
          @NotBlank String surveyNo) {
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
            List<OwnerInput> owners,
            @NotEmpty @Size(max = MAX_BOUNDARY_MEASUREMENTS) @Valid List<BoundaryMeasurementInput> boundaryMeasurements,
            @Valid List<ChainOfTitleInput> chainOfTitle) {
    }

    public record ApartmentDetail(Long parentLandPropertyId, String flatNo, String blockTower, String floor,
                                  BigDecimal builtupArea, String areaUnit, BigDecimal udsFraction,
                                  String parentSurveyNo, String parentSubdivisionNo) {
    }

    @Transactional
    public Map<String, Object> create(CreatePropertyRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("PROPERTY_CREATE");
        validateBoundaryMeasurements(req.boundaryMeasurements());
        validateChainOfTitle(req.chainOfTitle());
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

        for (int index = 0; index < req.boundaryMeasurements().size(); index++) {
            BoundaryMeasurementInput measurement = req.boundaryMeasurements().get(index);
            jdbc.update("""
                INSERT INTO core.property_measurement (property_id, seq, from_point, to_point, value, unit)
                VALUES (:propertyId, :seq, :fromPoint, :toPoint, :value, :unit)
                """, new MapSqlParameterSource()
                .addValue("propertyId", id)
                .addValue("seq", index + 1)
                .addValue("fromPoint", measurement.fromPoint())
                .addValue("toPoint", measurement.toPoint())
                .addValue("value", measurement.value())
                .addValue("unit", measurement.unit()));
          }

          if (req.chainOfTitle() != null) {
            for (int historyIndex = 0; historyIndex < req.chainOfTitle().size(); historyIndex++) {
              ChainOfTitleInput history = req.chainOfTitle().get(historyIndex);
              jdbc.queryForObject("""
                  INSERT INTO core.chain_of_title (property_id, seq, executor_name, claimant_name,
                    transaction_date, nature_of_transaction, reference_no, survey_no)
                  VALUES (:propertyId, :seq, :executorName, :claimantName,
                    :transactionDate, :natureOfTransaction, :referenceNo, :surveyNo)
                  RETURNING id
                  """, new MapSqlParameterSource()
                  .addValue("propertyId", id)
                  .addValue("seq", historyIndex + 1)
                  .addValue("executorName", history.executorName().trim())
                  .addValue("claimantName", history.claimantName().trim())
                  .addValue("transactionDate", history.transactionDate())
                  .addValue("natureOfTransaction", history.natureOfTransaction())
                  .addValue("referenceNo", blankToNull(history.referenceNo()))
                  .addValue("surveyNo", history.surveyNo().trim()), Long.class);
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
              property.put("boundaryMeasurements", jdbc.queryForList("""
                SELECT seq, from_point, to_point, value, unit
                  FROM core.property_measurement
                 WHERE property_id = :propertyId
                 ORDER BY seq
                """, idParam));
        property.put("revenueOwners", jdbc.queryForList("""
                SELECT revenue_record_ref, owners, extent_value, extent_unit, fetched_at
                  FROM revenue.current_state WHERE property_id = :propertyId
                 ORDER BY fetched_at DESC LIMIT 1
                """, idParam));
        List<Map<String, Object>> chainRows = jdbc.queryForList("""
          SELECT id, seq, executor_name, claimant_name, transaction_date, nature_of_transaction,
                 reference_no, survey_no
            FROM core.chain_of_title WHERE property_id = :propertyId ORDER BY seq
          """, idParam);
        List<Map<String, Object>> chainOfTitle = new ArrayList<>();
        for (Map<String, Object> chainRow : chainRows) {
          chainOfTitle.add(new LinkedHashMap<>(chainRow));
        }
        property.put("chainOfTitle", chainOfTitle);
        property.put("transactions", transactionHistory(id));
        property.put("propertyRelations", propertyRelations(id));
        if (Boolean.TRUE.equals(property.get("is_apartment_unit"))) {
            property.put("apartmentDetail", jdbc.queryForList(
                    "SELECT * FROM core.property_apartment_detail WHERE property_id = :propertyId", idParam));
        }
        return property;
    }

      private void validateBoundaryMeasurements(List<BoundaryMeasurementInput> measurements) {
        if (measurements == null || measurements.isEmpty()) {
          throw ApiException.badRequest("At least one boundary measurement is required");
        }
        if (measurements.size() > MAX_BOUNDARY_MEASUREMENTS) {
          throw ApiException.badRequest("No more than " + MAX_BOUNDARY_MEASUREMENTS
              + " boundary measurements may be provided");
        }
        for (BoundaryMeasurementInput measurement : measurements) {
          if (measurement == null || measurement.fromPoint() == null || measurement.toPoint() == null
              || measurement.value() == null || measurement.unit() == null || measurement.unit().isBlank()) {
            throw ApiException.badRequest("Boundary measurements require From, To, extent, and unit values");
          }
          if (!BOUNDARY_POINTS.contains(measurement.fromPoint()) || !BOUNDARY_POINTS.contains(measurement.toPoint())) {
            throw ApiException.badRequest("Boundary measurement points must be compass directions");
          }
          if (measurement.value().signum() <= 0) {
            throw ApiException.badRequest("Boundary measurement extent must be greater than zero");
          }
          if (measurement.fromPoint().equals(measurement.toPoint())) {
            throw ApiException.badRequest("Boundary measurement From and To points must differ");
          }
        }
      }

        private void validateChainOfTitle(List<ChainOfTitleInput> entries) {
          if (entries == null) {
            return;
          }
          for (ChainOfTitleInput entry : entries) {
            if (entry == null || entry.executorName() == null || entry.executorName().isBlank()
                || entry.claimantName() == null || entry.claimantName().isBlank()
                || entry.transactionDate() == null
                || entry.natureOfTransaction() == null || entry.natureOfTransaction().isBlank()
                || entry.surveyNo() == null || entry.surveyNo().isBlank()) {
              throw ApiException.badRequest("Each chain-of-title record must include executor, claimant, transaction date, nature of transaction, and survey number");
            }
          }
        }

    private List<Map<String, Object>> transactionHistory(long propertyId) {
        return jdbc.queryForList("""
                SELECT tx.id AS transaction_id, tx.txn_ref,
                       transaction_property.property_ref AS transaction_property_ref,
                       tx.deed_type_code, tx.subtype, tx.transfer_scope,
                       tx.status, tx.current_stage_code, tx.sro_code, tx.relationship_category,
                       tx.declared_consideration, tx.mode_of_consideration,
                       tx.extent_or_share_transferred, tx.extent_unit,
                       tx.guideline_value AS guideline_value_at_registration,
                       tx.guideline_value_reference, tx.basis_of_settlement, tx.share_being_released,
                       tx.resulting_subparcel_count, tx.initiated_at, tx.registered_at,
                       rr.registered_document_no, rr.registration_year, rr.registration_date,
                       rr.registering_sro, rr.registration_status, rr.registration_reference,
                       encode(rr.deed_sha256, 'hex') AS deed_sha256_hex,
                       fc.valuation_basis_used, fc.valuation_amount, fc.stamp_duty, fc.registration_fee,
                       fc.tds_amount, fc.other_charges, fc.total_payable, fc.calculated_at AS fee_calculated_at,
                       COALESCE((
                           SELECT sum(payment.amount) FILTER (WHERE payment.status = 'SUCCESS')
                             FROM core.payment payment WHERE payment.transaction_id = tx.id
                       ), 0) AS amount_paid,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'name', party.name,
                                      'role', party.role,
                                      'existingSharePct', party.existing_share_pct,
                                      'shareTransferredPct', party.share_transferred_pct,
                                      'extentTransferred', party.extent_transferred
                                  ) ORDER BY party.seq)
                             FROM core.transaction_party party
                            WHERE party.transaction_id = tx.id AND party.side = 'SIDE_1'
                       ), '[]'::jsonb) AS old_owners,
                       (
                           SELECT string_agg(party.name, ', ' ORDER BY party.seq)
                             FROM core.transaction_party party
                            WHERE party.transaction_id = tx.id AND party.side = 'SIDE_1'
                       ) AS old_owner_names,
                       COALESCE(history.owner_set_json, (
                           SELECT jsonb_agg(jsonb_build_object(
                                      'name', party.name,
                                      'role', party.role,
                                      'shareTransferredPct', party.share_transferred_pct,
                                      'resultingSharePct', party.resulting_share_pct,
                                      'extentTransferred', party.extent_transferred
                                  ) ORDER BY party.seq)
                             FROM core.transaction_party party
                            WHERE party.transaction_id = tx.id AND party.side = 'SIDE_2'
                       ), '[]'::jsonb) AS new_owners,
                       COALESCE((
                           SELECT string_agg(owner.value ->> 'name', ', ' ORDER BY owner.ordinality)
                             FROM jsonb_array_elements(history.owner_set_json)
                                  WITH ORDINALITY AS owner(value, ordinality)
                       ), (
                           SELECT string_agg(party.name, ', ' ORDER BY party.seq)
                             FROM core.transaction_party party
                            WHERE party.transaction_id = tx.id AND party.side = 'SIDE_2'
                       )) AS new_owner_names,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'mode', payment.mode,
                                      'referenceNo', payment.reference_no,
                                      'amount', payment.amount,
                                      'status', payment.status,
                                      'paidAt', payment.paid_at
                                  ) ORDER BY payment.paid_at)
                             FROM core.payment payment WHERE payment.transaction_id = tx.id
                       ), '[]'::jsonb) AS payments,
                       token.token_ref, history.state_version AS token_state_version,
                       history.operation AS token_operation,
                       encode(history.owner_set_hash, 'hex') AS owner_set_hash_hex,
                       encode(history.state_hash, 'hex') AS state_hash_hex,
                       encode(history.prev_state_hash, 'hex') AS previous_state_hash_hex,
                       encode(history.evidence_root, 'hex') AS evidence_root_hex,
                       COALESCE(history.onchain_tx_hash, anchor.tx_hash) AS onchain_tx_hash,
                       anchor.status AS blockchain_anchor_status, anchor.block_number,
                       anchor.error_message AS blockchain_anchor_error,
                       history.recorded_at AS chain_recorded_at,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'stateVersion', state.state_version,
                                      'operation', state.operation,
                                      'ownerSet', state.owner_set_json,
                                      'ownerSetHash', encode(state.owner_set_hash, 'hex'),
                                      'stateHash', encode(state.state_hash, 'hex'),
                                      'previousStateHash', encode(state.prev_state_hash, 'hex'),
                                      'evidenceRoot', encode(state.evidence_root, 'hex'),
                                      'onchainTxHash', COALESCE(state.onchain_tx_hash, state_anchor.tx_hash),
                                      'anchorStatus', state_anchor.status,
                                      'blockNumber', state_anchor.block_number,
                                      'anchorError', state_anchor.error_message,
                                      'recordedAt', state.recorded_at
                                  ) ORDER BY state.state_version)
                             FROM chain.token_state_history state
                             LEFT JOIN LATERAL (
                                 SELECT blockchain.status, blockchain.tx_hash, blockchain.block_number,
                                        blockchain.error_message
                                   FROM chain.blockchain_transaction blockchain
                                  WHERE blockchain.token_id = state.token_id
                                    AND blockchain.transaction_id = state.transaction_id
                                    AND blockchain.args_json ->> 'stateHash' =
                                        '0x' || encode(state.state_hash, 'hex')
                                  ORDER BY blockchain.id DESC
                                  LIMIT 1
                             ) state_anchor ON TRUE
                            WHERE state.token_id = token.id AND state.transaction_id = tx.id
                       ), '[]'::jsonb) AS blockchain_states,
                       CASE WHEN history.id IS NULL THEN 'OFF_CHAIN_TRANSACTION'
                            ELSE 'BLOCKCHAIN_LINKED_REGISTRATION' END AS history_source
                  FROM core.transaction tx
                  JOIN core.property transaction_property ON transaction_property.id = tx.property_id
                  LEFT JOIN core.registration_result rr ON rr.transaction_id = tx.id
                  LEFT JOIN LATERAL (
                      SELECT calculation.*
                        FROM core.fee_calculation calculation
                       WHERE calculation.transaction_id = tx.id
                       ORDER BY calculation.calculated_at DESC, calculation.id DESC
                       LIMIT 1
                  ) fc ON TRUE
                  LEFT JOIN chain.token token ON token.property_id = :propertyId
                  LEFT JOIN LATERAL (
                      SELECT state.*
                        FROM chain.token_state_history state
                       WHERE state.token_id = token.id AND state.transaction_id = tx.id
                       ORDER BY CASE WHEN state.operation IN ('MINT', 'UPDATE', 'SPLIT') THEN 0 ELSE 1 END,
                                state.state_version
                       LIMIT 1
                  ) history ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT blockchain.status, blockchain.tx_hash, blockchain.block_number,
                             blockchain.error_message
                        FROM chain.blockchain_transaction blockchain
                       WHERE blockchain.token_id = token.id AND blockchain.transaction_id = tx.id
                         AND blockchain.args_json ->> 'stateHash' =
                             '0x' || encode(history.state_hash, 'hex')
                       ORDER BY blockchain.id DESC
                       LIMIT 1
                  ) anchor ON TRUE
                 WHERE tx.property_id = :propertyId
                    OR EXISTS (
                        SELECT 1
                          FROM chain.token_state_history related_history
                         WHERE related_history.token_id = token.id
                           AND related_history.transaction_id = tx.id
                    )
                 ORDER BY COALESCE(rr.registration_date, tx.registered_at::date, tx.initiated_at::date) DESC,
                          tx.initiated_at DESC
                """, new MapSqlParameterSource("propertyId", propertyId));
    }

    private List<Map<String, Object>> propertyRelations(long propertyId) {
        return jdbc.queryForList("""
                SELECT relation_type, property_ref, property_status, survey_no, subdivision_no,
                       extent_value, extent_unit, token_ref, token_status, token_state_version,
                       parent_token_ref, token_lineage_status, lineage_transaction_ref, parcel_sequence,
                       official_subdivision_no
                  FROM (
                        SELECT 'PARENT' AS relation_type, parent.property_ref,
                               parent.status AS property_status, parent.survey_no, parent.subdivision_no,
                               parent.extent_value, parent.extent_unit, parent_token.token_ref,
                               parent_token.status AS token_status,
                               parent_token.state_version AS token_state_version,
                               parent_token.token_ref AS parent_token_ref,
                               CASE WHEN property_token.parent_token_id = parent_token.id
                                    THEN 'TOKEN_LINKED' ELSE 'PROPERTY_LINK_ONLY'
                               END AS token_lineage_status,
                               lineage_tx.txn_ref AS lineage_transaction_ref,
                               parcel.seq AS parcel_sequence,
                               parcel.official_subdivision_no
                          FROM core.property property
                          JOIN core.property parent ON parent.id = property.parent_property_id
                          LEFT JOIN chain.token parent_token ON parent_token.id = parent.token_id
                          LEFT JOIN chain.token property_token ON property_token.id = property.token_id
                          LEFT JOIN core.transaction lineage_tx
                            ON lineage_tx.id = property_token.minted_txn_id
                          LEFT JOIN survey.resulting_parcel parcel
                            ON parcel.child_property_id = property.id
                         WHERE property.id = :propertyId
                        UNION ALL
                        SELECT 'CHILD' AS relation_type, child.property_ref,
                               child.status AS property_status, child.survey_no, child.subdivision_no,
                               child.extent_value, child.extent_unit, child_token.token_ref,
                               child_token.status AS token_status,
                               child_token.state_version AS token_state_version,
                               COALESCE(linked_parent_token.token_ref,
                                        property_parent_token.token_ref) AS parent_token_ref,
                               CASE WHEN linked_parent_token.id IS NOT NULL
                                    THEN 'TOKEN_LINKED' ELSE 'PROPERTY_LINK_ONLY'
                               END AS token_lineage_status,
                               lineage_tx.txn_ref AS lineage_transaction_ref,
                               parcel.seq AS parcel_sequence,
                               parcel.official_subdivision_no
                          FROM core.property child
                          JOIN core.property property ON property.id = :propertyId
                          LEFT JOIN chain.token child_token ON child_token.id = child.token_id
                          LEFT JOIN chain.token linked_parent_token
                            ON linked_parent_token.id = child_token.parent_token_id
                          LEFT JOIN chain.token property_parent_token
                            ON property_parent_token.id = property.token_id
                          LEFT JOIN core.transaction lineage_tx
                            ON lineage_tx.id = child_token.minted_txn_id
                          LEFT JOIN survey.resulting_parcel parcel
                            ON parcel.child_property_id = child.id
                         WHERE child.parent_property_id = property.id
                  ) relation
                 ORDER BY CASE relation_type WHEN 'PARENT' THEN 0 ELSE 1 END, parcel_sequence, property_ref
                """, new MapSqlParameterSource("propertyId", propertyId));
    }

    public List<Map<String, Object>> search(String query, String villageCode, String surveyNo, int limit) {
        CurrentUser user = CurrentUser.require();
        return jdbc.queryForList("""
                SELECT p.id, p.property_ref, p.ulpin, p.property_type_code, p.survey_no, p.subdivision_no,
                       p.extent_value, p.extent_unit, p.village_code, p.district_code, p.sro_code, p.status,
                       t.token_ref, latest_registration.registered_at AS latest_registered_at
                  FROM core.property p
                  LEFT JOIN chain.token t ON t.id = p.token_id
                  LEFT JOIN LATERAL (
                      SELECT COALESCE(rr.registration_date::timestamp, tx.registered_at) AS registered_at
                        FROM core.transaction tx
                        LEFT JOIN core.registration_result rr ON rr.transaction_id = tx.id
                       WHERE tx.property_id = p.id
                         AND (rr.registration_date IS NOT NULL OR tx.registered_at IS NOT NULL)
                       ORDER BY COALESCE(rr.registration_date::timestamp, tx.registered_at) DESC,
                                tx.id DESC
                       LIMIT 1
                  ) latest_registration ON TRUE
                 WHERE p.state_code = :stateCode
                   AND (CAST(:query AS text) IS NULL OR p.property_ref ILIKE '%'||:query||'%'
                        OR coalesce(p.ulpin,'') ILIKE '%'||:query||'%'
                        OR p.survey_no ILIKE '%'||:query||'%'
                        OR coalesce(p.door_no,'') ILIKE '%'||:query||'%')
                   AND (CAST(:villageCode AS text) IS NULL OR p.village_code = :villageCode)
                   AND (CAST(:surveyNo AS text) IS NULL OR p.survey_no = :surveyNo)
                 ORDER BY latest_registration.registered_at DESC NULLS LAST, p.property_ref
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
