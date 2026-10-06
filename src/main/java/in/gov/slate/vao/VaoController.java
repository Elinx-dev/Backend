package in.gov.slate.vao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** VAO portal: records assigned to the signed-in VAO, the site-visit plan and slot booking. */
@RestController
@RequestMapping("/api/vao")
@PreAuthorize("hasRole('VAO')")
public class VaoController {

    private final VaoService vao;

    public VaoController(VaoService vao) {
        this.vao = vao;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return vao.dashboard();
    }

    @GetMapping("/records")
    public List<Map<String, Object>> records() {
        return vao.records();
    }

    @GetMapping("/records/{txnRef}")
    public Map<String, Object> record(@PathVariable String txnRef) {
        return vao.record(txnRef);
    }

    @GetMapping("/slots")
    public Map<String, Object> slots(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return vao.slots(date);
    }

    @PostMapping("/records/{txnRef}/book")
    public Map<String, Object> book(@PathVariable String txnRef, @Valid @RequestBody VaoService.BookingRequest body) {
        return vao.book(txnRef, body);
    }

    @PostMapping("/records/{txnRef}/visits/{visitId}/accept")
    public Map<String, Object> accept(@PathVariable String txnRef, @PathVariable long visitId) {
        return vao.accept(txnRef, visitId);
    }

    @PostMapping("/records/{txnRef}/visits/{visitId}/check-in")
    public Map<String, Object> checkIn(@PathVariable String txnRef, @PathVariable long visitId) {
        return vao.checkIn(txnRef, visitId);
    }
}
