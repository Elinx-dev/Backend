package in.gov.slate.bulk;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import in.gov.slate.common.AuditEvent;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.consent.ConsentService;
import in.gov.slate.fees.FeeService;
import in.gov.slate.fees.PaymentService;
import in.gov.slate.property.PropertyService;
import in.gov.slate.registration.RegistrationService;
import in.gov.slate.revenue.RevenueService;
import in.gov.slate.rules.RuleCheckService;
import in.gov.slate.transaction.TransactionService;

/**
 * State-admin bulk upload: each sheet row mints a property and runs one whole-property transaction through
 * rule checks. Passing cases are registered and their mutation approved without Aadhaar OTP or field work;
 * failing cases stop at the rule check.
 */
@Service
public class BulkUploadService {

    private static final Logger log = LoggerFactory.getLogger(BulkUploadService.class);
    static final int MAX_ROWS = 500;
    static final List<String> DEED_TYPES = List.of("SALE", "GIFT", "SETTLEMENT", "RELEASE");
    static final List<String[]> COLUMNS = List.of(
            col("case_id", "Your reference for the row, unique in the sheet", true),
            col("deed_type", "SALE, GIFT, SETTLEMENT or RELEASE (whole property only)", true),
            col("sro_code", "Sub-registrar office code, e.g. SRO-ADYAR", true),
            col("district_code", "e.g. CHN", true),
            col("taluk_code", "e.g. SHOL", true),
            col("village_code", "e.g. PERUNGUDI", true),
            col("survey_no", "Survey number; must not exist in the village yet (active or inactive)", true),
            col("subdivision_no", "Subdivision number, optional", false),
            col("extent_value", "Extent, number > 0", true),
            col("extent_unit", "SQ_FT, SQ_M, CENT, ACRE or HECTARE", true),
            col("property_type_code", "e.g. HOUSE_SITE", true),
            col("land_type_code", "e.g. NATHAM", true),
            col("classification_code", "e.g. HOUSE_SITE", true),
            col("nature_of_title_code", "e.g. PURCHASE", true),
            col("guideline_value", "Guideline value per unit, number", true),
            col("door_no", "Door number", false),
            col("street", "Street", false),
            col("boundary_north", "North boundary", true),
            col("boundary_south", "South boundary", true),
            col("boundary_east", "East boundary", true),
            col("boundary_west", "West boundary", true),
            col("length_north_south_ft", "North-South length in feet, number > 0", true),
            col("length_east_west_ft", "East-West length in feet, number > 0", true),
            col("seller_name", "Current owner (as in the Revenue record)", true),
            col("seller_aadhaar", "12 digits", true),
            col("seller_pan", "e.g. ABCDE1234F (needed to mint the property)", true),
            col("seller_mobile", "10-digit mobile, optional", false),
            col("seller_address", "Address", true),
            col("buyer_name", "New owner / claimant", true),
            col("buyer_aadhaar", "12 digits", true),
            col("buyer_pan", "e.g. PQRSX6789K, optional", false),
            col("buyer_mobile", "10-digit mobile, optional", false),
            col("buyer_address", "Address", true),
            col("relationship_category", "GIFT/SETTLEMENT: FAMILY or NON_FAMILY; RELEASE: CO_OWNER; SALE: blank", false),
            col("relationship_code", "SETTLEMENT: blood relation, e.g. SON, DAUGHTER", false),
            col("consideration", "SALE: amount in rupees (> 0); others 0", true),
            col("consideration_mode", "SALE: BANK_TRANSFER, CHEQUE, DD or CASH", false),
            col("witness1_name", "Witness 1 name", true),
            col("witness1_address", "Witness 1 address", true),
            col("witness2_name", "Witness 2 name", true),
            col("witness2_address", "Witness 2 address", true));
    static final List<List<String>> SAMPLES = List.of(
            List.of("CASE-001", "SALE", "SRO-ADYAR", "CHN", "SHOL", "PERUNGUDI", "501", "1", "2400", "SQ_FT", "HOUSE_SITE",
                    "NATHAM", "HOUSE_SITE", "PURCHASE", "7500", "501", "Bulk Street", "Road", "Canal", "Plot 502", "Plot 500",
                    "60", "40", "Lakshmi Narayanan", "234567890123", "ABCDE1234F", "9812345601", "1 Main St, Perungudi",
                    "Arjun Mehta", "345678901234", "PQRSX6789K", "9712345601", "7 Lake Rd, Velachery", "", "", "18000000",
                    "BANK_TRANSFER", "Ravi Kumar", "Perungudi", "Suresh Babu", "Adyar"),
            List.of("CASE-002", "GIFT", "SRO-ADYAR", "CHN", "SHOL", "PERUNGUDI", "502", "1", "2400", "SQ_FT", "HOUSE_SITE",
                    "NATHAM", "HOUSE_SITE", "PURCHASE", "7500", "502", "Bulk Street", "Road", "Canal", "Plot 503", "Plot 501",
                    "60", "40", "Venkatesh Subramanian", "456789012345", "BCDEF2345G", "9812345602", "2 Main St, Perungudi",
                    "Priya Venkatesh", "567890123456", "CDEFG3456H", "9712345602", "2 Main St, Perungudi", "FAMILY", "DAUGHTER", "0",
                    "", "Mani Raj", "Perungudi", "Kala Devi", "Adyar"),
            List.of("CASE-003", "SALE", "SRO-ADYAR", "CHN", "SHOL", "PERUNGUDI", "503", "1", "2400", "SQ_FT", "HOUSE_SITE",
                    "NATHAM", "HOUSE_SITE", "PURCHASE", "7500", "503", "Bulk Street", "Road", "Canal", "Plot 504", "Plot 502",
                    "60", "40", "Wrong Owner Name", "678901234567", "DEFGH4567J", "9812345603", "3 Main St, Perungudi",
                    "Deepa Raman", "789012345678", "EFGHJ5678K", "9712345603", "9 Beach Rd, Besant Nagar", "", "", "15000000",
                    "BANK_TRANSFER", "Anand", "Perungudi", "Vijay", "Adyar"));

    private static final Pattern AADHAAR = Pattern.compile("[2-9]\\d{11}");
    private static final Pattern PAN = Pattern.compile("[A-Z]{5}\\d{4}[A-Z]");
    private static final Pattern MOBILE = Pattern.compile("[6-9]\\d{9}");
    private static final Set<String> UNITS = Set.of("SQ_FT", "SQ_M", "CENT", "ACRE", "HECTARE");
    private static final Set<String> PERMISSIONS = Set.of("PROPERTY_CREATE", "PROPERTY_READ", "TXN_CREATE", "TXN_EDIT",
            "TXN_READ", "CONSENT_CAPTURE", "RULE_CHECK_RUN", "RULE_CHECK_READ", "FEE_CALCULATE", "PAYMENT_RECORD",
            "REGISTER", "REVENUE_VERIFY", "REVENUE_APPROVE", "TOKEN_READ");
    private static final Set<String> ACTING_ROLES = Set.of("REGISTRATION_OFFICER", "VAO", "TAHSILDAR");

    private final NamedParameterJdbcTemplate jdbc;
    private final PropertyService properties;
    private final TransactionService transactions;
    private final ConsentService consent;
    private final RuleCheckService ruleChecks;
    private final FeeService fees;
    private final PaymentService payments;
    private final RegistrationService registration;
    private final RevenueService revenue;
    private final AuditService audit;

    public BulkUploadService(NamedParameterJdbcTemplate jdbc, PropertyService properties,
                             TransactionService transactions, ConsentService consent, RuleCheckService ruleChecks,
                             FeeService fees, PaymentService payments, RegistrationService registration,
                             RevenueService revenue, AuditService audit) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.transactions = transactions;
        this.consent = consent;
        this.ruleChecks = ruleChecks;
        this.fees = fees;
        this.payments = payments;
        this.registration = registration;
        this.revenue = revenue;
        this.audit = audit;
    }

    private static String[] col(String key, String help, boolean required) {
        return new String[]{key, help, required ? "Yes" : "No"};
    }

    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Sheet cases = wb.createSheet("Cases");
            Row header = cases.createRow(0);
            for (int i = 0; i < COLUMNS.size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(COLUMNS.get(i)[0]);
                c.setCellStyle(head);
                cases.setColumnWidth(i, 22 * 256);
            }
            for (int r = 0; r < SAMPLES.size(); r++) {
                Row row = cases.createRow(r + 1);
                for (int i = 0; i < SAMPLES.get(r).size(); i++) {
                    row.createCell(i).setCellValue(SAMPLES.get(r).get(i));
                }
            }
            cases.createFreezePane(1, 1);
            Sheet help = wb.createSheet("Instructions");
            String[] intro = {
                    "One row per case on the Cases sheet. Keep the header row (column keys) unchanged; text cells are fine for numbers.",
                    "The whole sheet is checked first. If any row has an error nothing is created; fix the rows and upload again.",
                    "For each valid row SLATE mints the property, creates the transaction, records consent as given by the uploading admin "
                            + "(no Aadhaar OTP) and runs the rule checks.",
                    "Rule checks pass -> fees are recorded, the deed is registered and the revenue mutation is approved (no VAO, Surveyor or Tahsildar step).",
                    "Rule checks fail -> that case stops at the rule check; the other cases continue.",
                    "Sample: CASE-001 and CASE-002 pass with the demo Revenue records; CASE-003 fails the Revenue owner check (wrong seller name).",
                    ""};
            int r = 0;
            for (String line : intro) {
                help.createRow(r++).createCell(0).setCellValue(line);
            }
            Row h = help.createRow(r++);
            String[] titles = {"Column", "Description", "Required"};
            for (int i = 0; i < 3; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(titles[i]);
                c.setCellStyle(head);
            }
            for (String[] column : COLUMNS) {
                Row row = help.createRow(r++);
                for (int i = 0; i < 3; i++) {
                    row.createCell(i).setCellValue(column[i]);
                }
            }
            help.setColumnWidth(0, 26 * 256);
            help.setColumnWidth(1, 90 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build the bulk upload template", ex);
        }
    }

    record Parsed(List<Map<String, String>> rows, List<Map<String, Object>> errors) {
    }

    public Map<String, Object> validate(MultipartFile file) {
        CurrentUser admin = CurrentUser.require();
        Parsed parsed = parse(file, admin.stateCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", parsed.errors().isEmpty());
        out.put("rowCount", parsed.rows().size());
        out.put("errors", parsed.errors());
        return out;
    }

    public Map<String, Object> run(MultipartFile file) {
        CurrentUser admin = CurrentUser.require();
        Parsed parsed = parse(file, admin.stateCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", parsed.errors().isEmpty());
        out.put("rowCount", parsed.rows().size());
        out.put("errors", parsed.errors());
        if (!parsed.errors().isEmpty()) {
            out.put("results", List.of());
            return out;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        var original = SecurityContextHolder.getContext().getAuthentication();
        try {
            for (Map<String, String> row : parsed.rows()) {
                CurrentUser acting = new CurrentUser(admin.id(), admin.username(), admin.fullName(), admin.stateCode(),
                        admin.department(), union(admin.roles(), ACTING_ROLES), union(admin.permissions(), PERMISSIONS),
                        Set.of(row.get("sro_code")), Set.of(row.get("village_code")));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(acting, null, List.of()));
                results.add(process(row));
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }
        long completed = results.stream().filter(r -> "COMPLETED".equals(r.get("outcome"))).count();
        out.put("completed", completed);
        out.put("failed", results.size() - completed);
        out.put("results", results);
        audit.record(AuditEvent.of("BULK_UPLOAD").stateCode(admin.stateCode())
                .category(AuditEvent.CATEGORY_SECURITY)
                .after(Map.of("rows", results.size(), "completed", completed))
                .detail("Bulk upload of " + results.size() + " case(s) by " + admin.username()));
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new HashSet<>(a);
        s.addAll(b);
        return s;
    }

    private Map<String, Object> process(Map<String, String> c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("row", Integer.parseInt(c.get("_row")));
        r.put("caseId", c.get("case_id"));
        String step = "MINT_PROPERTY";
        try {
            BigDecimal extent = new BigDecimal(c.get("extent_value"));
            var property = properties.create(new PropertyService.CreatePropertyRequest(
                    null, c.get("property_type_code"), c.get("nature_of_title_code"), c.get("land_type_code"),
                    c.get("classification_code"), "INDIVIDUAL", extent, c.get("extent_unit"), c.get("survey_no"),
                    blank(c.get("subdivision_no")), null, null, c.get("district_code"), c.get("taluk_code"),
                    c.get("village_code"), c.get("sro_code"), null, null, blank(c.get("street")),
                    blank(c.get("door_no")), c.get("boundary_north"), c.get("boundary_south"), c.get("boundary_east"),
                    c.get("boundary_west"), new BigDecimal(c.get("guideline_value")), null, false, null,
                    List.of(new PropertyService.OwnerInput(c.get("seller_name"), c.get("seller_aadhaar"),
                            blank(c.get("seller_pan")), blank(c.get("seller_mobile")), c.get("seller_address"), null, null)),
                    List.of(new PropertyService.BoundaryMeasurementInput("NORTH", "SOUTH",
                                    new BigDecimal(c.get("length_north_south_ft")), "FT"),
                            new PropertyService.BoundaryMeasurementInput("EAST", "WEST",
                                    new BigDecimal(c.get("length_east_west_ft")), "FT")),
                    List.of(new PropertyService.ChainOfTitleInput("Previous owner", c.get("seller_name"),
                            LocalDate.of(2000, 1, 1), "SALE", "BULK-" + c.get("case_id"), c.get("survey_no"))),
                    List.of(new PropertyService.SurveyRecordInput(null, c.get("survey_no"), blank(c.get("subdivision_no")),
                            extent, c.get("extent_unit")))));
            String propertyRef = (String) property.get("property_ref");
            r.put("propertyRef", propertyRef);

            step = "CREATE_TRANSACTION";
            String deed = c.get("deed_type");
            BigDecimal consideration = new BigDecimal(c.get("consideration"));
            var txn = transactions.create(new TransactionService.CreateRequest(propertyRef, deed, null, "FULL_PROPERTY",
                    c.get("sro_code"), "Bulk upload " + c.get("case_id"), consideration,
                    blank(c.get("consideration_mode")), extent, c.get("extent_unit"),
                    blank(c.get("relationship_category")), null, null, null, new BigDecimal(c.get("guideline_value")),
                    null, false, false, null, null));
            String txnRef = (String) txn.get("txn_ref");
            r.put("txnRef", txnRef);
            @SuppressWarnings("unchecked")
            var owners = (List<Map<String, Object>>) properties.get(propertyRef).get("registeredOwners");
            long ownerId = ((Number) owners.get(0).get("id")).longValue();
            transactions.saveParties(txnRef, List.of(
                    new TransactionService.PartyInput("SIDE_1", null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, ownerId),
                    new TransactionService.PartyInput("SIDE_2", null, null, "INDIVIDUAL", c.get("buyer_name"),
                            c.get("buyer_aadhaar"), null, null, blank(c.get("buyer_pan")), blank(c.get("buyer_mobile")),
                            c.get("buyer_address"), null, null, blank(c.get("relationship_code")), extent, null, null)));
            transactions.saveWitnesses(txnRef, List.of(
                    new TransactionService.WitnessInput(c.get("witness1_name"), c.get("witness1_address"), null, null),
                    new TransactionService.WitnessInput(c.get("witness2_name"), c.get("witness2_address"), null, null)));

            step = "CONSENT";
            transactions.transition(txnRef, "REQUEST_CONSENT", null);
            consent.recordBulkConsent(txnRef);

            step = "RULE_CHECK";
            var checks = ruleChecks.run(txnRef, null, null, null);
            r.put("ruleChecks", checks.stream().map(x -> x.get("engine") + ": " + x.get("overallOutcome")
                    + (x.get("reasonCode") == null ? "" : " (" + x.get("reasonCode") + ")")).toList());
            try {
                transactions.transition(txnRef, "RULE_CHECKS_CLEAR", null);
            } catch (ApiException ex) {
                r.put("outcome", "RULE_CHECK_FAILED");
                r.put("stoppedAt", step);
                r.put("message", ex.getMessage());
                r.put("status", status(txnRef));
                return r;
            }

            step = "FEES_AND_PAYMENT";
            var fee = fees.calculate(txnRef);
            BigDecimal payable = amount(fee);
            if (payable.signum() > 0) {
                payments.record(txnRef, new PaymentService.PaymentInput("CASH", "BULK-" + txnRef, payable,
                        OffsetDateTime.now()));
            }
            transactions.transition(txnRef, "RECORD_PAYMENT", null);

            step = "REGISTRATION";
            registration.register(txnRef);

            step = "REVENUE_APPROVAL";
            Long mutationId = jdbc.query("""
                    SELECT m.id FROM revenue.proposed_mutation m JOIN core.transaction t ON t.id = m.transaction_id
                     WHERE t.txn_ref = :ref ORDER BY m.id DESC LIMIT 1
                    """, new MapSqlParameterSource("ref", txnRef), rs -> rs.next() ? rs.getLong(1) : null);
            if (mutationId != null) {
                revenue.completeWithoutFieldWork(mutationId, "Bulk upload by state admin");
            }
            r.put("outcome", "COMPLETED");
            r.put("status", status(txnRef));
            r.put("documentNo", jdbc.query("""
                    SELECT rr.registered_document_no FROM core.registration_result rr
                      JOIN core.transaction t ON t.id = rr.transaction_id WHERE t.txn_ref = :ref
                    """, new MapSqlParameterSource("ref", txnRef), rs -> rs.next() ? rs.getString(1) : null));
        } catch (RuntimeException ex) {
            log.warn("Bulk case {} stopped at {}: {}", c.get("case_id"), step, ex.getMessage());
            r.put("outcome", "FAILED");
            r.put("stoppedAt", step);
            r.put("message", ex instanceof ApiException ? ex.getMessage() : "Unexpected error: " + ex.getClass().getSimpleName());
            if (r.get("txnRef") != null) {
                r.put("status", status((String) r.get("txnRef")));
            }
        }
        return r;
    }

    private String status(String txnRef) {
        return jdbc.query("SELECT status FROM core.transaction WHERE txn_ref = :ref",
                new MapSqlParameterSource("ref", txnRef), rs -> rs.next() ? rs.getString(1) : null);
    }

    private static BigDecimal amount(Map<String, Object> fee) {
        for (String key : List.of("total_payable", "totalPayable", "balance")) {
            Object v = fee.get(key);
            if (v != null) {
                return new BigDecimal(v.toString());
            }
        }
        return BigDecimal.ZERO;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    Parsed parse(MultipartFile file, String stateCode) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose the filled-in bulk upload .xlsx file");
        }
        List<Map<String, String>> rows = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheet("Cases") != null ? wb.getSheet("Cases") : wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            Row header = sheet.getRow(0);
            if (header == null) {
                throw ApiException.badRequest("The Cases sheet has no header row");
            }
            Map<Integer, String> keys = new LinkedHashMap<>();
            for (Cell cell : header) {
                keys.put(cell.getColumnIndex(), fmt.formatCellValue(cell).trim().toLowerCase(Locale.ROOT));
            }
            for (String[] col : COLUMNS) {
                if (!keys.containsValue(col[0])) {
                    errors.add(error(1, col[0], "Column is missing from the header row; download the template again"));
                }
            }
            if (!errors.isEmpty()) {
                return new Parsed(rows, errors);
            }
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean any = false;
                for (var e : keys.entrySet()) {
                    String v = fmt.formatCellValue(row.getCell(e.getKey())).trim();
                    any |= !v.isEmpty();
                    values.put(e.getValue(), v);
                }
                if (!any) {
                    continue;
                }
                values.put("_row", String.valueOf(i + 1));
                rows.add(values);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest("The file is not a readable .xlsx workbook");
        }
        if (rows.isEmpty()) {
            errors.add(error(2, "case_id", "The sheet has no cases"));
        }
        if (rows.size() > MAX_ROWS) {
            errors.add(error(MAX_ROWS + 2, "case_id", "At most " + MAX_ROWS + " cases per upload"));
        }
        Set<String> caseIds = new HashSet<>();
        Set<String> surveys = new LinkedHashSet<>();
        for (Map<String, String> v : rows) {
            int n = Integer.parseInt(v.get("_row"));
            for (String[] col : COLUMNS) {
                if ("Yes".equals(col[2]) && v.get(col[0]).isEmpty()) {
                    errors.add(error(n, col[0], "Required"));
                }
            }
            for (String k : List.of("deed_type", "sro_code", "district_code", "taluk_code", "village_code", "extent_unit",
                    "property_type_code", "land_type_code", "classification_code", "nature_of_title_code", "seller_pan",
                    "buyer_pan", "relationship_category", "relationship_code", "consideration_mode")) {
                v.put(k, v.get(k).toUpperCase(Locale.ROOT));
            }
            if (!v.get("case_id").isEmpty() && !caseIds.add(v.get("case_id"))) {
                errors.add(error(n, "case_id", "Duplicate case_id in the sheet"));
            }
            String deed = v.get("deed_type");
            if (!deed.isEmpty() && !DEED_TYPES.contains(deed)) {
                errors.add(error(n, "deed_type", "Must be one of " + DEED_TYPES
                        + " (sub-division and partition need a Surveyor and cannot be bulk uploaded)"));
            }
            if (!v.get("extent_unit").isEmpty() && !UNITS.contains(v.get("extent_unit"))) {
                errors.add(error(n, "extent_unit", "Must be one of " + UNITS));
            }
            positive(v, n, "extent_value", errors, false);
            positive(v, n, "guideline_value", errors, true);
            positive(v, n, "length_north_south_ft", errors, false);
            positive(v, n, "length_east_west_ft", errors, false);
            positive(v, n, "consideration", errors, true);
            if ("SALE".equals(deed) && number(v.get("consideration")) != null
                    && number(v.get("consideration")).signum() <= 0) {
                errors.add(error(n, "consideration", "A sale needs a consideration amount above 0"));
            }
            if ("SALE".equals(deed) && v.get("consideration_mode").isEmpty()) {
                errors.add(error(n, "consideration_mode", "Required for a sale"));
            }
            if (List.of("GIFT", "SETTLEMENT").contains(deed)
                    && !List.of("FAMILY", "NON_FAMILY").contains(v.get("relationship_category"))) {
                errors.add(error(n, "relationship_category", "GIFT and SETTLEMENT need FAMILY or NON_FAMILY"));
            }
            if ("SETTLEMENT".equals(deed) && v.get("relationship_code").isEmpty()) {
                errors.add(error(n, "relationship_code", "SETTLEMENT needs the blood relation, e.g. SON"));
            }
            if ("RELEASE".equals(deed) && !"CO_OWNER".equals(v.get("relationship_category"))) {
                errors.add(error(n, "relationship_category", "RELEASE needs CO_OWNER"));
            }
            for (String k : List.of("seller_aadhaar", "buyer_aadhaar")) {
                v.put(k, v.get(k).replaceAll("[\\s-]", ""));
                if (!v.get(k).isEmpty() && !AADHAAR.matcher(v.get(k)).matches()) {
                    errors.add(error(n, k, "Aadhaar must be 12 digits and not start with 0 or 1"));
                }
            }
            if (!v.get("seller_aadhaar").isEmpty() && v.get("seller_aadhaar").equals(v.get("buyer_aadhaar"))) {
                errors.add(error(n, "buyer_aadhaar", "Buyer and seller cannot have the same Aadhaar"));
            }
            for (String k : List.of("seller_pan", "buyer_pan")) {
                if (!v.get(k).isEmpty() && !PAN.matcher(v.get(k)).matches()) {
                    errors.add(error(n, k, "PAN must look like ABCDE1234F"));
                }
            }
            for (String k : List.of("seller_mobile", "buyer_mobile")) {
                v.put(k, v.get(k).replaceAll("\\D", ""));
                if (!v.get(k).isEmpty() && !MOBILE.matcher(v.get(k)).matches()) {
                    errors.add(error(n, k, "Mobile must be a 10-digit Indian number"));
                }
            }
            if (!v.get("village_code").isEmpty() && !v.get("sro_code").isEmpty()) {
                Integer match = jdbc.queryForObject("""
                        SELECT count(*) FROM master.jurisdiction
                         WHERE state_code = :state AND district_code = :d AND taluk_code = :t AND village_code = :v
                           AND sro_code = :s AND active
                        """, new MapSqlParameterSource().addValue("state", stateCode).addValue("d", v.get("district_code"))
                        .addValue("t", v.get("taluk_code")).addValue("v", v.get("village_code"))
                        .addValue("s", v.get("sro_code")), Integer.class);
                if (match == null || match == 0) {
                    errors.add(error(n, "village_code", "District / taluk / village / SRO combination not found in your state"));
                }
            }
            if (!v.get("survey_no").isEmpty() && !v.get("village_code").isEmpty()) {
                String key = v.get("village_code") + "|" + v.get("survey_no").toUpperCase(Locale.ROOT) + "|"
                        + v.get("subdivision_no").toUpperCase(Locale.ROOT);
                if (!surveys.add(key)) {
                    errors.add(error(n, "survey_no", "Same village / survey / subdivision number used twice in the sheet"));
                }
                Integer existing = jdbc.queryForObject("""
                        SELECT count(*) FROM core.property p
                         WHERE p.state_code = :state AND p.village_code = :v
                           AND (EXISTS (SELECT 1 WHERE upper(trim(p.survey_no)) = upper(:s)
                                          AND upper(coalesce(trim(p.subdivision_no), '')) = upper(:sub))
                                OR EXISTS (SELECT 1 FROM core.property_survey ps WHERE ps.property_id = p.id
                                             AND upper(trim(ps.survey_no)) = upper(:s)
                                             AND upper(coalesce(trim(ps.subdivision_no), '')) = upper(:sub)))
                        """, new MapSqlParameterSource().addValue("state", stateCode).addValue("v", v.get("village_code"))
                        .addValue("s", v.get("survey_no")).addValue("sub", v.get("subdivision_no")), Integer.class);
                if (existing != null && existing > 0) {
                    errors.add(error(n, "survey_no", "A property with survey " + v.get("survey_no")
                            + (v.get("subdivision_no").isEmpty() ? "" : "/" + v.get("subdivision_no"))
                            + " already exists in " + v.get("village_code") + " (active or inactive)"));
                }
            }
        }
        return new Parsed(rows, errors);
    }

    private static void positive(Map<String, String> v, int row, String key, List<Map<String, Object>> errors,
                                 boolean zeroAllowed) {
        if (v.get(key).isEmpty()) {
            return;
        }
        BigDecimal n = number(v.get(key));
        if (n == null || n.signum() < 0 || (!zeroAllowed && n.signum() == 0)) {
            errors.add(error(row, key, zeroAllowed ? "Must be a number, 0 or more" : "Must be a number above 0"));
        } else {
            v.put(key, n.toPlainString());
        }
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Map<String, Object> error(int row, String column, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("row", row);
        e.put("column", column);
        e.put("message", message);
        return e;
    }
}
