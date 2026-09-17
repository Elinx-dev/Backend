package in.gov.slate.connectors.mock;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.connectors.AadhaarConnector;
import in.gov.slate.connectors.EcConnector;
import in.gov.slate.connectors.MockFixtureStore;
import in.gov.slate.connectors.RevenueConnector;
import in.gov.slate.connectors.model.EcModels.EcCertificate;
import in.gov.slate.connectors.model.EcModels.EcRequest;
import in.gov.slate.connectors.model.RevenueModels.MutationPushRequest;
import in.gov.slate.connectors.model.RevenueModels.MutationPushResponse;
import in.gov.slate.connectors.model.RevenueModels.RevenueLookupRequest;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwnershipResponse;

/**
 * HTTP surface of the mock external systems. The in-process connectors read the
 * same fixtures, so what this endpoint returns is exactly what the rule engines
 * consumed. Documented in docs/SLATE_Mock_External_APIs.md.
 */
@RestController
@RequestMapping("/mock")
public class MockApiController {

    private final MockFixtureStore fixtures;
    private final EcConnector ec;
    private final RevenueConnector revenue;
    private final AadhaarConnector aadhaar;

    public MockApiController(MockFixtureStore fixtures, EcConnector ec, RevenueConnector revenue,
                             AadhaarConnector aadhaar) {
        this.fixtures = fixtures;
        this.ec = ec;
        this.revenue = revenue;
        this.aadhaar = aadhaar;
    }

    @GetMapping("/ec/certificate")
    public ResponseEntity<EcCertificate> ecCertificate(@RequestParam String village,
                                                       @RequestParam String surveyNo,
                                                       @RequestParam(required = false) String subdivisionNo,
                                                       @RequestParam(required = false) String searchFrom,
                                                       @RequestParam(required = false) String searchTo) {
        EcCertificate certificate = ec.fetch(new EcRequest(village, surveyNo, subdivisionNo,
                searchFrom == null ? null : LocalDate.parse(searchFrom),
                searchTo == null ? null : LocalDate.parse(searchTo)));
        return certificate == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(certificate);
    }

    @GetMapping("/revenue/ownership")
    public RevenueOwnershipResponse revenueOwnership(@RequestParam(required = false) String district,
                                                     @RequestParam(required = false) String taluk,
                                                     @RequestParam String village,
                                                     @RequestParam String surveyNo,
                                                     @RequestParam(required = false) String subdivisionNo,
                                                     @RequestParam(required = false) String landType) {
        return revenue.lookup(new RevenueLookupRequest(district, taluk, village, surveyNo, subdivisionNo, landType));
    }

    @PostMapping("/revenue/mutation")
    public MutationPushResponse pushMutation(@RequestBody MutationPushRequest request) {
        return revenue.pushMutation(request);
    }

    public record OtpBody(String aadhaarNumber, String consentTextVersion) {
    }

    public record OtpVerifyBody(String requestReference, String otp) {
    }

    @PostMapping("/aadhaar/otp")
    public AadhaarConnector.OtpRequestResult requestOtp(@RequestBody OtpBody body) {
        return aadhaar.requestOtp(body.aadhaarNumber(), body.consentTextVersion());
    }

    @PostMapping("/aadhaar/otp/verify")
    public Map<String, Object> verifyOtp(@RequestBody OtpVerifyBody body) {
        boolean verified = aadhaar.verifyOtp(body.requestReference(), body.otp());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestReference", body.requestReference());
        out.put("verified", verified);
        return out;
    }

    /** A demo payment gateway: every challan is accepted and echoed back. */
    public record PaymentBody(String txnRef, String mode, java.math.BigDecimal amount) {
    }

    @PostMapping("/payment/challan")
    public Map<String, Object> challan(@RequestBody PaymentBody body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "SUCCESS");
        out.put("referenceNo", "CHLN-" + Math.abs((body.txnRef() + body.amount()).hashCode()));
        out.put("amount", body.amount());
        out.put("mode", body.mode());
        out.put("paidAt", java.time.OffsetDateTime.now().toString());
        return out;
    }

    /** Lists every fixture so testers can see which parcel drives which outcome. */
    @GetMapping("/fixtures")
    public Map<String, Object> allFixtures() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ec", fixtures.allEc());
        out.put("revenue", fixtures.allRevenue());
        return out;
    }
}
