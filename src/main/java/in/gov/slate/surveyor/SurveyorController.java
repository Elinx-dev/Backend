package in.gov.slate.surveyor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.IdempotencyService;
import in.gov.slate.survey.SurveyService;
import in.gov.slate.vao.VaoService;
import jakarta.validation.Valid;

/** Surveyor portal: assigned records, site-visit plan, slot booking and the field survey form. */
@RestController
@RequestMapping("/api/surveyor")
@PreAuthorize("hasRole('SURVEYOR')")
public class SurveyorController {

    private final SurveyorService surveyor;
    private final IdempotencyService idempotency;

    public SurveyorController(SurveyorService surveyor, IdempotencyService idempotency) {
        this.surveyor = surveyor;
        this.idempotency = idempotency;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return surveyor.dashboard();
    }

    @GetMapping("/records")
    public List<Map<String, Object>> records() {
        return surveyor.records();
    }

    @GetMapping("/records/{txnRef}")
    public Map<String, Object> record(@PathVariable String txnRef) {
        return surveyor.record(txnRef);
    }

    @GetMapping("/slots")
    public Map<String, Object> slots(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return surveyor.slots(date);
    }

    @PostMapping("/records/{txnRef}/book")
    public Map<String, Object> book(@PathVariable String txnRef, @Valid @RequestBody VaoService.BookingRequest body) {
        return surveyor.book(txnRef, body);
    }

    @PostMapping("/records/{txnRef}/visits/{visitId}/check-in")
    public Map<String, Object> checkIn(@PathVariable String txnRef, @PathVariable long visitId) {
        return surveyor.checkIn(txnRef, visitId);
    }

    @PostMapping("/records/{txnRef}/survey")
    public Map<String, Object> submit(@PathVariable String txnRef,
                                      @Valid @RequestBody SurveyService.SubmissionRequest body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (idempotency.replay("survey." + txnRef, key, body).isPresent()) {
            return surveyor.record(txnRef);
        }
        Map<String, Object> result = surveyor.submit(txnRef, body);
        idempotency.store("survey." + txnRef, key, body,
                Map.of("submissionId", String.valueOf(result.get("submissionId"))));
        return result;
    }
}
