package in.gov.slate.pii;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pii")
public class PiiController {

    private final PiiService service;

    public PiiController(PiiService service) {
        this.service = service;
    }

    @PostMapping("/reveal")
    public ResponseEntity<Map<String, Object>> reveal(@RequestBody PiiService.RevealRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reveal(request));
    }
}
