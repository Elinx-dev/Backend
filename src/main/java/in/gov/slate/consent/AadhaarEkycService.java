package in.gov.slate.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.PiiCrypto;
import in.gov.slate.config.ConfigService;
import in.gov.slate.connectors.AadhaarConnector;

/**
 * Aadhaar OTP e-KYC for buyers and witnesses: OTP to the Aadhaar holder, then the verified profile
 * (name, address, mobile, gender, date of birth) fills the form. In MOCK mode the profile comes from
 * {@link #mockProfile}; the live e-KYC response is mapped the same way.
 */
@Service
public class AadhaarEkycService {

    private static final Pattern AADHAAR = Pattern.compile("[2-9]\\d{11}");
    private static final String[] FIRST = {"Arun", "Priya", "Karthik", "Lakshmi", "Suresh", "Divya", "Vignesh", "Meena",
            "Ramesh", "Kavitha", "Senthil", "Anitha", "Prakash", "Revathi", "Manoj", "Deepa"};
    private static final String[] LAST = {"Kumar", "Raman", "Selvam", "Krishnan", "Murugan", "Natarajan", "Pillai",
            "Sundaram", "Balaji", "Ganesan"};
    private static final String[] STREET = {"Lake Road", "Gandhi Street", "Temple Street", "Main Road", "Nehru Nagar"};

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, AadhaarConnector> connectors = new HashMap<>();
    private final ConfigService config;
    private final PiiCrypto pii;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public AadhaarEkycService(NamedParameterJdbcTemplate jdbc, List<AadhaarConnector> aadhaarConnectors,
                              ConfigService config, PiiCrypto pii, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        aadhaarConnectors.forEach(c -> connectors.put(c.mode(), c));
        this.config = config;
        this.pii = pii;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> requestOtp(String aadhaarNumber) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("CONSENT_CAPTURE");
        String number = digits(aadhaarNumber);
        String mode = config.flagEnabled(user.stateCode(), ConsentService.AADHAAR_OTP_LIVE) ? "LIVE" : "MOCK";
        AadhaarConnector connector = connectors.get(mode);
        var result = connector.requestOtp("LIVE".equals(mode) ? number : number.substring(8), "EKYC-v1");
        String ref = "EKYC-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.aadhaar_ekyc (ekyc_ref, state_code, lookup_hash, aadhaar_enc, aadhaar_last4,
                    otp_request_reference, otp_mode, requested_by)
                VALUES (:ref, :state, :hash, :enc, :last4, :otpRef, :mode, :user)
                """, new MapSqlParameterSource()
                .addValue("ref", ref).addValue("state", user.stateCode()).addValue("hash", lookupHash(number))
                .addValue("enc", pii.protect(number)).addValue("last4", number.substring(8))
                .addValue("otpRef", result.requestReference()).addValue("mode", mode).addValue("user", user.id()));
        audit.record("AADHAAR_EKYC_OTP_REQUESTED", "AADHAAR_EKYC", ref, null, null, Map.of("last4", number.substring(8)), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ekycRef", ref);
        out.put("maskedTarget", result.maskedTarget());
        out.put("validitySeconds", result.validitySeconds());
        out.put("otpMode", mode);
        if (result.demoOtp() != null) {
            out.put("demoOtp", result.demoOtp());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> verify(String ekycRef, String otp) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("CONSENT_CAPTURE");
        var rows = jdbc.queryForList("""
                SELECT id, aadhaar_enc, otp_request_reference, otp_mode, status, attempt_count
                  FROM core.aadhaar_ekyc WHERE ekyc_ref = :ref AND state_code = :state
                """, new MapSqlParameterSource().addValue("ref", ekycRef).addValue("state", user.stateCode()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Aadhaar OTP request " + ekycRef);
        }
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("attempt_count")).intValue() >= 3 && !"VERIFIED".equals(row.get("status"))) {
            throw ApiException.badRequest("Too many wrong OTPs; request a new OTP");
        }
        String number = pii.reveal((String) row.get("aadhaar_enc"));
        boolean ok = "VERIFIED".equals(row.get("status"))
                || connectors.get((String) row.get("otp_mode")).verifyOtp((String) row.get("otp_request_reference"), otp);
        Map<String, Object> profile = ok ? mockProfile(number) : null;
        jdbc.update("""
                UPDATE core.aadhaar_ekyc SET status = :status, attempt_count = attempt_count + 1,
                       verified_at = CASE WHEN :status = 'VERIFIED' THEN now() ELSE verified_at END,
                       profile = coalesce(cast(:profile AS jsonb), profile)
                 WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", row.get("id")).addValue("status", ok ? "VERIFIED" : "FAILED")
                .addValue("profile", profile == null ? null : json(Map.of("enc", pii.protect(json(profile))))));
        audit.record("AADHAAR_EKYC_VERIFIED", "AADHAAR_EKYC", ekycRef, null, null, null, Map.of("verified", ok),
                ok ? "SUCCESS" : "FAILURE", null);
        if (!ok) {
            throw ApiException.badRequest("Incorrect OTP");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ekycRef", ekycRef);
        out.put("verified", true);
        out.put("profile", profile);
        return out;
    }

    /** The verified profile for this Aadhaar number (same state, last 2 hours), or null. */
    public Map<String, Object> verifiedProfile(String stateCode, String aadhaarNumber) {
        String number = aadhaarNumber == null ? "" : aadhaarNumber.replaceAll("\\D", "");
        if (!AADHAAR.matcher(number).matches()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT profile ->> 'enc' AS enc, ekyc_ref, otp_mode
                  FROM core.aadhaar_ekyc
                 WHERE state_code = :state AND lookup_hash = :hash AND status = 'VERIFIED'
                   AND verified_at > now() - interval '2 hours'
                 ORDER BY verified_at DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("state", stateCode).addValue("hash", lookupHash(number)));
        if (rows.isEmpty() || rows.get(0).get("enc") == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = new LinkedHashMap<>(
                    mapper.readValue(pii.reveal((String) rows.get(0).get("enc")), Map.class));
            profile.put("ekycRef", rows.get(0).get("ekyc_ref"));
            profile.put("otpMode", rows.get(0).get("otp_mode"));
            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    /** Mock Aadhaar e-KYC response: a stable profile derived from the number. */
    static Map<String, Object> mockProfile(String number) {
        int a = Integer.parseInt(number.substring(0, 4));
        int b = Integer.parseInt(number.substring(4, 8));
        int c = Integer.parseInt(number.substring(8));
        Map<String, Object> p = new LinkedHashMap<>();
        String first = FIRST[a % FIRST.length];
        p.put("name", first + " " + LAST[b % LAST.length]);
        p.put("gender", a % 2 == 0 ? "M" : "F");
        p.put("dateOfBirth", String.format("%d-%02d-%02d", 1960 + b % 40, 1 + c % 12, 1 + c % 28));
        p.put("careOf", (a % 2 == 0 ? "S/O " : "D/O ") + FIRST[(a + 3) % FIRST.length] + " " + LAST[b % LAST.length]);
        p.put("address", (c % 200 + 1) + ", " + STREET[c % STREET.length] + ", Perungudi, Chennai, Tamil Nadu 600096");
        p.put("mobile", "9" + number.substring(3));
        p.put("aadhaarLast4", number.substring(8));
        return p;
    }

    private static String digits(String aadhaarNumber) {
        String number = aadhaarNumber == null ? "" : aadhaarNumber.replaceAll("\\D", "");
        if (!AADHAAR.matcher(number).matches()) {
            throw ApiException.badRequest("Aadhaar must be 12 digits and not start with 0 or 1");
        }
        return number;
    }

    private static String lookupHash(String number) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("slate-ekyc:" + number).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
