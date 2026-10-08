package in.gov.slate.transaction;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.survey.AreaUnits;

/**
 * Partition-only logic: owner status, deceased owners' legal-heir branches (any depth), the final
 * participating parties and schedule allocation. SLATE never calculates shares or percentages here.
 */
@Service
public class PartitionService {

    static final String PARTITION = "PARTITION";
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    private static final Set<String> UPLOAD_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<String> STATUSES = Set.of("LIVING", "DECEASED");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final TransactionService transactions;
    private final AuditService audit;

    public PartitionService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository,
                            TransactionService transactions, AuditService audit) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.audit = audit;
    }

    public record DeathInput(LocalDate dateOfDeath, String certificateNo, LocalDate certificateDate,
                             Long certificateDocumentId) {
    }

    public record LegalHeirCertificateInput(Boolean available, String certificateNo, LocalDate issueDate,
                                            Long documentId) {
    }

    public record HeirInput(String name, String relationship, String status, String maritalStatus,
                            DeathInput death, Boolean successorsAvailable, List<HeirInput> successors,
                            String aadhaarNumber, String mobile, String address, String pan) {
    }

    public record OwnerInput(Long propertyOwnerId, String status, DeathInput death,
                             LegalHeirCertificateInput legalHeirCertificate, List<HeirInput> heirs) {
    }

    public record PartitionRequest(List<OwnerInput> owners) {
    }

    public record ScheduleSurveyInput(String surveyNo, String subdivisionNo, BigDecimal extent, String extentUnit,
                                      BigDecimal value) {
    }

    public record ScheduleInput(String label, List<String> allottedRefs, String extentUnit, BigDecimal value,
                                String northBoundary, String southBoundary, String eastBoundary,
                                String westBoundary, String remarks, List<ScheduleSurveyInput> surveys) {
    }

    // ---------------------------------------------------------------- read

    public Map<String, Object> get(String txnRef) {
        CurrentUser user = CurrentUser.require();
        TransactionContext ctx = requirePartition(txnRef, user);
        return view(ctx);
    }

    private Map<String, Object> view(TransactionContext ctx) {
        List<Map<String, Object>> members = members(ctx.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owners", currentOwners(ctx));
        out.put("members", members);
        out.put("participants", participants(members));
        out.put("parcels", parcels(ctx));
        out.put("schedules", schedules(ctx.id()));
        return out;
    }

    private List<Map<String, Object>> currentOwners(TransactionContext ctx) {
        return jdbc.queryForList("""
                SELECT id, owner_name, owner_type_code FROM core.property_owner
                 WHERE property_id = :propertyId AND effective_to IS NULL ORDER BY id
                """, new MapSqlParameterSource("propertyId", ctx.transaction().get("property_id")));
    }

    private List<Map<String, Object>> parcels(TransactionContext ctx) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT seq, ulpin, survey_no, subdivision_no, extent_value, extent_unit
                  FROM core.property_survey WHERE property_id = :propertyId ORDER BY seq
                """, new MapSqlParameterSource("propertyId", ctx.transaction().get("property_id")));
        if (!rows.isEmpty()) {
            return rows;
        }
        Map<String, Object> p = ctx.property();
        Map<String, Object> only = new LinkedHashMap<>();
        only.put("seq", 1);
        only.put("ulpin", p.get("ulpin"));
        only.put("survey_no", p.get("survey_no"));
        only.put("subdivision_no", p.get("subdivision_no"));
        only.put("extent_value", p.get("extent_value"));
        only.put("extent_unit", p.get("extent_unit"));
        return p.get("survey_no") == null ? List.of() : List.of(only);
    }

    private List<Map<String, Object>> members(long txnId) {
        return jdbc.queryForList("""
                SELECT m.id, m.seq, m.member_ref, m.parent_member_id, m.member_type, m.property_owner_id,
                       m.party_id, m.name, m.relationship, m.living_status, m.marital_status, m.date_of_death,
                       m.death_cert_no, m.death_cert_date, m.death_cert_document_id, m.legal_heir_cert_available,
                       m.legal_heir_cert_no, m.legal_heir_cert_date, m.legal_heir_document_id,
                       m.successors_available, dd.file_name AS death_cert_file_name,
                       ld.file_name AS legal_heir_file_name, p.aadhaar_last4, p.mobile, p.address, p.pan
                  FROM core.partition_member m
                  LEFT JOIN core.document dd ON dd.id = m.death_cert_document_id
                  LEFT JOIN core.document ld ON ld.id = m.legal_heir_document_id
                  LEFT JOIN core.transaction_party p ON p.id = m.party_id
                 WHERE m.transaction_id = :txnId ORDER BY m.seq
                """, new MapSqlParameterSource("txnId", txnId));
    }

    /** Living current owners and living heirs at the end of each branch, with their lineage. */
    static List<Map<String, Object>> participants(List<Map<String, Object>> members) {
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        members.forEach(m -> byId.put(((Number) m.get("id")).longValue(), m));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : members) {
            if (!"LIVING".equals(m.get("living_status"))) {
                continue;
            }
            List<String> lineage = new ArrayList<>();
            Map<String, Object> cursor = m;
            Map<String, Object> root = m;
            while (cursor != null) {
                lineage.add(0, cursor.get("name") + ("DECEASED".equals(cursor.get("living_status")) ? " (deceased)" : ""));
                root = cursor;
                Object parent = cursor.get("parent_member_id");
                cursor = parent == null ? null : byId.get(((Number) parent).longValue());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberRef", m.get("member_ref"));
            row.put("name", m.get("name"));
            row.put("sourceBranch", root.get("name"));
            row.put("relationship", "OWNER".equals(m.get("member_type")) ? "Current owner" : m.get("relationship"));
            row.put("lineage", String.join(" → ", lineage));
            row.put("partyId", m.get("party_id"));
            out.add(row);
        }
        return out;
    }

    // ---------------------------------------------------------------- owners & heirs

    @Transactional
    public Map<String, Object> saveMembers(String txnRef, PartitionRequest request) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_EDIT");
        TransactionContext ctx = requirePartition(txnRef, user);
        if (!ctx.consents().isEmpty()) {
            throw ApiException.conflict("Partition parties cannot be changed after consent has been requested");
        }
        List<Map<String, Object>> owners = currentOwners(ctx);
        if (owners.isEmpty()) {
            throw ApiException.badRequest("Property " + ctx.propertyRef() + " has no recorded owners");
        }
        List<OwnerInput> input = request == null || request.owners() == null ? List.of() : request.owners();
        Map<Long, OwnerInput> byOwner = new LinkedHashMap<>();
        for (OwnerInput o : input) {
            if (o.propertyOwnerId() == null || byOwner.put(o.propertyOwnerId(), o) != null) {
                throw ApiException.badRequest("Give the status of each current owner once");
            }
        }
        for (Map<String, Object> owner : owners) {
            if (!byOwner.containsKey(((Number) owner.get("id")).longValue())) {
                throw ApiException.badRequest("Give the status (Living / Deceased) of " + owner.get("owner_name"));
            }
        }
        if (byOwner.size() != owners.size()) {
            throw ApiException.badRequest("Only current owners of " + ctx.propertyRef() + " can be listed");
        }
        Set<Long> documents = documentIds(ctx.id());
        for (Map<String, Object> owner : owners) {
            validateOwner((String) owner.get("owner_name"), byOwner.get(((Number) owner.get("id")).longValue()),
                    documents);
        }
        if (owners.size() == 1 && "LIVING".equals(status(byOwner.values().iterator().next().status()))) {
            throw ApiException.badRequest("Partition not applicable: the only current owner is living");
        }

        jdbc.update("DELETE FROM core.partition_member WHERE transaction_id = :id",
                new MapSqlParameterSource("id", ctx.id()));

        List<TransactionService.PartyInput> heirParties = new ArrayList<>();
        for (OwnerInput o : byOwner.values()) {
            collectHeirParties(o.heirs(), heirParties);
        }
        transactions.saveParties(txnRef, heirParties);
        TransactionContext saved = repository.load(txnRef, user.stateCode());

        Map<Long, Long> partyByOwner = new HashMap<>();
        for (Map<String, Object> party : saved.side("SIDE_1")) {
            if (party.get("property_owner_id") != null) {
                partyByOwner.put(((Number) party.get("property_owner_id")).longValue(),
                        ((Number) party.get("id")).longValue());
            }
        }
        List<Long> heirPartyIds = saved.side("SIDE_2").stream().map(p -> ((Number) p.get("id")).longValue()).toList();
        int[] seq = {0};
        int[] heirCursor = {0};
        int ownerNo = 0;
        Map<Long, String> names = new HashMap<>();
        owners.forEach(o -> names.put(((Number) o.get("id")).longValue(), (String) o.get("owner_name")));
        for (OwnerInput o : byOwner.values()) {
            String ref = "O" + (++ownerNo);
            boolean deceased = "DECEASED".equals(status(o.status()));
            Long partyId = partyByOwner.get(o.propertyOwnerId());
            if (partyId != null) {
                jdbc.update("UPDATE core.transaction_party SET deceased = :deceased WHERE id = :id",
                        new MapSqlParameterSource().addValue("deceased", deceased).addValue("id", partyId));
            }
            DeathInput death = deceased ? o.death() : null;
            LegalHeirCertificateInput lhc = deceased ? o.legalHeirCertificate() : null;
            long ownerMemberId = insertMember(ctx.id(), ++seq[0], ref, null, "OWNER", o.propertyOwnerId(), partyId,
                    names.get(o.propertyOwnerId()), null, deceased ? "DECEASED" : "LIVING", null, death,
                    lhc, null);
            if (deceased) {
                insertHeirs(ctx.id(), ownerMemberId, ref, o.heirs(), seq, heirPartyIds, heirCursor);
            }
        }
        audit.record("PARTITION_PARTIES_SAVED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("owners", owners.size(), "heirParties", heirParties.size()), null);
        return view(repository.load(txnRef, user.stateCode()));
    }

    private void validateOwner(String name, OwnerInput o, Set<Long> documents) {
        String status = status(o.status());
        if (status == null) {
            throw ApiException.badRequest("Give the status (Living / Deceased) of " + name);
        }
        if ("LIVING".equals(status)) {
            return;
        }
        validateDeath(name, o.death(), documents);
        LegalHeirCertificateInput lhc = o.legalHeirCertificate();
        if (lhc == null || !Boolean.TRUE.equals(lhc.available())) {
            throw ApiException.badRequest("A legal heir certificate is required for deceased owner " + name
                    + "; the partition cannot proceed without it");
        }
        if (blank(lhc.certificateNo()) || lhc.issueDate() == null) {
            throw ApiException.badRequest("Enter the legal heir certificate number and issue date for " + name);
        }
        requireDocument(lhc.documentId(), documents);
        if (o.heirs() == null || o.heirs().isEmpty()) {
            throw ApiException.badRequest("Add the legal heirs of deceased owner " + name);
        }
        validateHeirs(o.heirs(), documents);
    }

    private void validateHeirs(List<HeirInput> heirs, Set<Long> documents) {
        for (HeirInput h : heirs) {
            if (blank(h.name()) || blank(h.relationship())) {
                throw ApiException.badRequest("Every legal heir needs a name and relationship");
            }
            String status = status(h.status());
            if (status == null) {
                throw ApiException.badRequest("Give the status (Living / Deceased) of legal heir " + h.name());
            }
            if ("LIVING".equals(status)) {
                if (h.aadhaarNumber() == null || !h.aadhaarNumber().replaceAll("\\s", "").matches("\\d{12}")) {
                    throw ApiException.badRequest("Enter the 12-digit Aadhaar of living legal heir " + h.name());
                }
                continue;
            }
            validateDeath(h.name(), h.death(), documents);
            if (h.successorsAvailable() == null) {
                throw ApiException.badRequest("Say whether successors are available for " + h.name());
            }
            if (h.successorsAvailable()) {
                if (h.successors() == null || h.successors().isEmpty()) {
                    throw ApiException.badRequest("Add the successors of deceased legal heir " + h.name());
                }
                validateHeirs(h.successors(), documents);
            }
        }
    }

    private void validateDeath(String name, DeathInput death, Set<Long> documents) {
        if (death == null || death.dateOfDeath() == null || blank(death.certificateNo())
                || death.certificateDate() == null) {
            throw ApiException.badRequest("Enter the date of death and death certificate number and date for " + name);
        }
        if (death.dateOfDeath().isAfter(LocalDate.now()) || death.certificateDate().isBefore(death.dateOfDeath())) {
            throw ApiException.badRequest("Check the date of death and death certificate date for " + name);
        }
        requireDocument(death.certificateDocumentId(), documents);
    }

    private static void requireDocument(Long documentId, Set<Long> documents) {
        if (documentId != null && !documents.contains(documentId)) {
            throw ApiException.badRequest("Uploaded certificate " + documentId + " does not belong to this transaction");
        }
    }

    private static void collectHeirParties(List<HeirInput> heirs, List<TransactionService.PartyInput> out) {
        if (heirs == null) {
            return;
        }
        for (HeirInput h : heirs) {
            if ("LIVING".equals(status(h.status()))) {
                out.add(new TransactionService.PartyInput("SIDE_2", null, null, "INDIVIDUAL", h.name().trim(),
                        h.aadhaarNumber(), null, null, emptyToNull(h.pan()), emptyToNull(h.mobile()),
                        emptyToNull(h.address()), null, null, null, null, null, null));
            } else if (Boolean.TRUE.equals(h.successorsAvailable())) {
                collectHeirParties(h.successors(), out);
            }
        }
    }

    private void insertHeirs(long txnId, long parentId, String parentRef, List<HeirInput> heirs, int[] seq,
                             List<Long> heirPartyIds, int[] heirCursor) {
        if (heirs == null) {
            return;
        }
        int n = 0;
        for (HeirInput h : heirs) {
            String ref = parentRef + "." + (++n);
            boolean living = "LIVING".equals(status(h.status()));
            Long partyId = living && heirCursor[0] < heirPartyIds.size() ? heirPartyIds.get(heirCursor[0]++) : null;
            long id = insertMember(txnId, ++seq[0], ref, parentId, "HEIR", null, partyId, h.name().trim(),
                    h.relationship().trim(), living ? "LIVING" : "DECEASED", emptyToNull(h.maritalStatus()),
                    living ? null : h.death(), null, living ? null : h.successorsAvailable());
            if (!living && Boolean.TRUE.equals(h.successorsAvailable())) {
                insertHeirs(txnId, id, ref, h.successors(), seq, heirPartyIds, heirCursor);
            }
        }
    }

    private long insertMember(long txnId, int seq, String ref, Long parentId, String type, Long ownerId,
                              Long partyId, String name, String relationship, String status, String marital,
                              DeathInput death, LegalHeirCertificateInput lhc, Boolean successors) {
        return jdbc.queryForObject("""
                INSERT INTO core.partition_member (transaction_id, seq, member_ref, parent_member_id, member_type,
                    property_owner_id, party_id, name, relationship, living_status, marital_status, date_of_death,
                    death_cert_no, death_cert_date, death_cert_document_id, legal_heir_cert_available,
                    legal_heir_cert_no, legal_heir_cert_date, legal_heir_document_id, successors_available)
                VALUES (:txnId, :seq, :ref, :parentId, :type, :ownerId, :partyId, :name, :relationship, :status,
                    :marital, :dod, :dcNo, :dcDate, :dcDoc, :lhcAvailable, :lhcNo, :lhcDate, :lhcDoc, :successors)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("txnId", txnId).addValue("seq", seq).addValue("ref", ref).addValue("parentId", parentId)
                .addValue("type", type).addValue("ownerId", ownerId).addValue("partyId", partyId)
                .addValue("name", name).addValue("relationship", relationship).addValue("status", status)
                .addValue("marital", marital)
                .addValue("dod", death == null ? null : death.dateOfDeath())
                .addValue("dcNo", death == null ? null : death.certificateNo().trim())
                .addValue("dcDate", death == null ? null : death.certificateDate())
                .addValue("dcDoc", death == null ? null : death.certificateDocumentId())
                .addValue("lhcAvailable", lhc == null ? null : lhc.available())
                .addValue("lhcNo", lhc == null ? null : lhc.certificateNo().trim())
                .addValue("lhcDate", lhc == null ? null : lhc.issueDate())
                .addValue("lhcDoc", lhc == null ? null : lhc.documentId())
                .addValue("successors", successors), Long.class);
    }

    // ---------------------------------------------------------------- schedules

    @Transactional
    public Map<String, Object> saveSchedules(String txnRef, List<ScheduleInput> schedules) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_EDIT");
        TransactionContext ctx = requirePartition(txnRef, user);
        if (ctx.paidTotal().signum() > 0) {
            throw ApiException.conflict("Schedules cannot be changed after payment has been recorded");
        }
        List<Map<String, Object>> participants = participants(members(ctx.id()));
        if (participants.isEmpty()) {
            throw ApiException.badRequest("Save the partition parties before allotting schedules");
        }
        Map<String, Map<String, Object>> byRef = new HashMap<>();
        participants.forEach(p -> byRef.put((String) p.get("memberRef"), p));
        Map<String, Map<String, Object>> parcels = new LinkedHashMap<>();
        parcels(ctx).forEach(p -> parcels.put(parcelKey(p.get("survey_no"), p.get("subdivision_no")), p));

        List<ScheduleInput> input = schedules == null ? List.of() : schedules;
        if (input.isEmpty()) {
            throw ApiException.badRequest("Add at least one schedule");
        }
        Set<String> labels = new HashSet<>();
        Map<String, BigDecimal> allotted = new LinkedHashMap<>();
        List<BigDecimal> values = new ArrayList<>();
        for (ScheduleInput s : input) {
            if (blank(s.label()) || !labels.add(s.label().trim().toUpperCase())) {
                throw ApiException.badRequest("Every schedule needs a unique name, e.g. Schedule A");
            }
            String label = s.label().trim();
            if (s.allottedRefs() == null || s.allottedRefs().isEmpty()) {
                throw ApiException.badRequest("Choose the person(s) allotted " + label);
            }
            for (String ref : s.allottedRefs()) {
                if (!byRef.containsKey(ref)) {
                    throw ApiException.badRequest(label + " is allotted to someone who is not a participating party");
                }
            }
            if (s.surveys() == null || s.surveys().isEmpty()) {
                throw ApiException.badRequest("Add the survey number(s) in " + label);
            }
            BigDecimal total = BigDecimal.ZERO;
            boolean lineValues = false;
            for (ScheduleSurveyInput line : s.surveys()) {
                String key = parcelKey(line.surveyNo(), line.subdivisionNo());
                Map<String, Object> parcel = parcels.get(key);
                if (parcel == null) {
                    throw ApiException.badRequest("Survey " + line.surveyNo() + " in " + label
                            + " is not part of property " + ctx.propertyRef());
                }
                if (line.extent() == null || line.extent().signum() <= 0 || blank(line.extentUnit())) {
                    throw ApiException.badRequest("Enter the extent allotted and unit for survey " + line.surveyNo()
                            + " in " + label);
                }
                if (line.value() != null && line.value().signum() < 0) {
                    throw ApiException.badRequest("Survey values in " + label + " cannot be negative");
                }
                if (line.value() != null) {
                    total = total.add(line.value());
                    lineValues = true;
                }
                allotted.merge(key, inUnit(line.extent(), line.extentUnit(), (String) parcel.get("extent_unit"),
                        line.surveyNo()), BigDecimal::add);
            }
            BigDecimal value = lineValues ? total : s.value();
            if (value == null || value.signum() <= 0) {
                throw ApiException.badRequest("Enter a value above zero for " + label);
            }
            values.add(value);
        }
        for (Map.Entry<String, BigDecimal> e : allotted.entrySet()) {
            Map<String, Object> parcel = parcels.get(e.getKey());
            BigDecimal available = parcel.get("extent_value") == null ? null
                    : new BigDecimal(parcel.get("extent_value").toString());
            if (available != null && e.getValue().compareTo(available) > 0) {
                throw ApiException.badRequest("Extent allotted for survey " + label(parcel) + " (" + e.getValue()
                        .stripTrailingZeros().toPlainString() + " " + parcel.get("extent_unit")
                        + ") is more than the available " + available.stripTrailingZeros().toPlainString() + " "
                        + parcel.get("extent_unit"));
            }
        }

        jdbc.update("DELETE FROM core.transaction_schedule WHERE transaction_id = :id",
                new MapSqlParameterSource("id", ctx.id()));
        int seq = 0;
        for (ScheduleInput s : input) {
            String unit = blank(s.extentUnit()) ? s.surveys().get(0).extentUnit() : s.extentUnit();
            BigDecimal totalExtent = BigDecimal.ZERO;
            for (ScheduleSurveyInput line : s.surveys()) {
                totalExtent = totalExtent.add(inUnit(line.extent(), line.extentUnit(), unit, line.surveyNo()));
            }
            Set<String> branches = new java.util.LinkedHashSet<>();
            s.allottedRefs().forEach(ref -> branches.add((String) byRef.get(ref).get("sourceBranch")));
            Long scheduleId = jdbc.queryForObject("""
                    INSERT INTO core.transaction_schedule (transaction_id, seq, label, manual_value, schedule_value,
                        allotted_member_refs, source_branch, total_extent, extent_unit, north_boundary,
                        south_boundary, east_boundary, west_boundary, remarks)
                    VALUES (:txnId, :seq, :label, :manual, :value, :refs, :branch, :extent, :unit, :north, :south,
                        :east, :west, :remarks)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("txnId", ctx.id()).addValue("seq", seq + 1).addValue("label", s.label().trim())
                    .addValue("manual", s.value()).addValue("value", values.get(seq))
                    .addValue("refs", s.allottedRefs().toArray(new String[0]))
                    .addValue("branch", String.join(", ", branches))
                    .addValue("extent", totalExtent).addValue("unit", unit)
                    .addValue("north", emptyToNull(s.northBoundary())).addValue("south", emptyToNull(s.southBoundary()))
                    .addValue("east", emptyToNull(s.eastBoundary())).addValue("west", emptyToNull(s.westBoundary()))
                    .addValue("remarks", emptyToNull(s.remarks())), Long.class);
            seq++;
            int lineSeq = 0;
            for (ScheduleSurveyInput line : s.surveys()) {
                Map<String, Object> parcel = parcels.get(parcelKey(line.surveyNo(), line.subdivisionNo()));
                jdbc.update("""
                        INSERT INTO core.transaction_schedule_survey (schedule_id, seq, survey_no, subdivision_no,
                            value, ulpin, extent_allotted, extent_unit)
                        VALUES (:scheduleId, :seq, :surveyNo, :sub, :value, :ulpin, :extent, :unit)
                        """, new MapSqlParameterSource()
                        .addValue("scheduleId", scheduleId).addValue("seq", ++lineSeq)
                        .addValue("surveyNo", parcel.get("survey_no")).addValue("sub", parcel.get("subdivision_no"))
                        .addValue("value", line.value()).addValue("ulpin", parcel.get("ulpin"))
                        .addValue("extent", line.extent()).addValue("unit", line.extentUnit()));
            }
        }
        jdbc.update("UPDATE core.transaction SET resulting_subparcel_count = :n WHERE id = :id",
                new MapSqlParameterSource().addValue("n", input.size()).addValue("id", ctx.id()));
        audit.record("PARTITION_SCHEDULES_SAVED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("schedules", input.size()), null);
        return view(repository.load(txnRef, user.stateCode()));
    }

    private List<Map<String, Object>> schedules(long txnId) {
        var param = new MapSqlParameterSource("txnId", txnId);
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT ss.schedule_id, ss.seq, ss.survey_no, ss.subdivision_no, ss.ulpin, ss.extent_allotted,
                       ss.extent_unit, ss.value
                  FROM core.transaction_schedule_survey ss
                  JOIN core.transaction_schedule s ON s.id = ss.schedule_id
                 WHERE s.transaction_id = :txnId ORDER BY ss.seq
                """, param);
        return jdbc.queryForList("""
                SELECT id, seq, label, manual_value, schedule_value, allotted_member_refs, source_branch,
                       total_extent, extent_unit, north_boundary, south_boundary, east_boundary, west_boundary, remarks
                  FROM core.transaction_schedule WHERE transaction_id = :txnId ORDER BY seq
                """, param).stream().map(s -> {
                    Map<String, Object> out = new LinkedHashMap<>(s);
                    Object refs = s.get("allotted_member_refs");
                    try {
                        out.put("allotted_member_refs", refs instanceof java.sql.Array a
                                ? List.of((Object[]) a.getArray()) : List.of());
                    } catch (java.sql.SQLException e) {
                        out.put("allotted_member_refs", List.of());
                    }
                    long id = ((Number) s.get("id")).longValue();
                    out.put("surveys", lines.stream()
                            .filter(l -> ((Number) l.get("schedule_id")).longValue() == id).toList());
                    return out;
                }).toList();
    }

    private static BigDecimal inUnit(BigDecimal value, String from, String to, String surveyNo) {
        if (to == null || from == null || AreaUnits.code(from).equals(AreaUnits.code(to))) {
            return value;
        }
        BigDecimal converted = AreaUnits.convert(value, from, to);
        if (converted == null) {
            throw ApiException.badRequest("Unit " + from + " cannot be compared with " + to + " for survey " + surveyNo);
        }
        return converted;
    }

    private static String parcelKey(Object surveyNo, Object subdivisionNo) {
        String s = surveyNo == null ? "" : surveyNo.toString().trim().toUpperCase();
        String d = subdivisionNo == null ? "" : subdivisionNo.toString().trim().toUpperCase();
        return s + "|" + d;
    }

    private static String label(Map<String, Object> parcel) {
        Object sub = parcel.get("subdivision_no");
        return parcel.get("survey_no") + (sub == null || sub.toString().isBlank() ? "" : "/" + sub);
    }

    // ---------------------------------------------------------------- documents

    @Transactional
    public Map<String, Object> upload(String txnRef, String documentType, MultipartFile file) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_EDIT");
        TransactionContext ctx = requirePartition(txnRef, user);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a file to upload");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.badRequest("Certificates can be at most 5 MB");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!UPLOAD_TYPES.contains(mime)) {
            throw ApiException.badRequest("Upload a PDF, JPG or PNG file");
        }
        String type = blank(documentType) ? "PARTITION_CERTIFICATE" : documentType.trim().toUpperCase();
        if (!Set.of("DEATH_CERTIFICATE", "LEGAL_HEIR_CERTIFICATE", "PARTITION_CERTIFICATE").contains(type)) {
            throw ApiException.badRequest("Unsupported document type " + documentType);
        }
        try {
            byte[] content = file.getBytes();
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(content);
            String name = file.getOriginalFilename() == null ? "certificate" : file.getOriginalFilename();
            Long id = jdbc.queryForObject("""
                    INSERT INTO core.document (state_code, transaction_id, document_type_code, file_name, mime_type,
                        size_bytes, storage_bucket, storage_key, sha256, uploaded_by)
                    VALUES (:state, :txnId, :type, :name, :mime, :size, 'DB', 'core.document_content', :sha, :user)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("state", ctx.transaction().get("state_code")).addValue("txnId", ctx.id())
                    .addValue("type", type).addValue("name", name).addValue("mime", mime)
                    .addValue("size", content.length).addValue("sha", sha).addValue("user", user.id()), Long.class);
            jdbc.update("INSERT INTO core.document_content (document_id, content) VALUES (:id, :content)",
                    new MapSqlParameterSource().addValue("id", id).addValue("content", content));
            audit.record("PARTITION_DOCUMENT_UPLOADED", "DOCUMENT", String.valueOf(id), txnRef, ctx.propertyRef(),
                    Map.of("documentType", type), null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("documentId", id);
            out.put("fileName", name);
            out.put("documentType", type);
            return out;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw ApiException.badRequest("The file could not be read");
        }
    }

    public Map<String, Object> download(String txnRef, long documentId) {
        CurrentUser user = CurrentUser.require();
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        var rows = jdbc.queryForList("""
                SELECT d.file_name, d.mime_type, c.content FROM core.document d
                  JOIN core.document_content c ON c.document_id = d.id
                 WHERE d.id = :id AND d.transaction_id = :txnId
                """, new MapSqlParameterSource().addValue("id", documentId).addValue("txnId", ctx.id()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Document " + documentId);
        }
        return rows.get(0);
    }

    private Set<Long> documentIds(long txnId) {
        return new HashSet<>(jdbc.queryForList("SELECT id FROM core.document WHERE transaction_id = :id",
                new MapSqlParameterSource("id", txnId), Long.class));
    }

    // ---------------------------------------------------------------- helpers

    private TransactionContext requirePartition(String txnRef, CurrentUser user) {
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        if (!PARTITION.equals(ctx.deedTypeCode())) {
            throw ApiException.badRequest("Transaction " + txnRef + " is not a Partition");
        }
        return ctx;
    }

    private static String status(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim().toUpperCase();
        return STATUSES.contains(s) ? s : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}
