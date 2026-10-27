package in.gov.slate.bulk;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/bulk-upload")
@PreAuthorize("hasAnyRole('STATE_ADMIN','CENTRAL_ADMIN')")
public class BulkUploadController {

    private final BulkUploadService service;

    public BulkUploadController(BulkUploadService service) {
        this.service = service;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"SLATE_bulk_upload_template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.template());
    }

    /** Checks every row of the sheet; nothing is created. */
    @PostMapping(path = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> validate(@RequestParam("file") MultipartFile file) {
        return service.validate(file);
    }

    /** Validates the whole sheet, then mints, transacts and rule-checks each case. */
    @PostMapping(path = "/run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> run(@RequestParam("file") MultipartFile file) {
        return service.run(file);
    }
}
