package in.gov.slate.transaction;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/transactions/{txnRef}/partition")
public class PartitionController {

    private final PartitionService partition;

    public PartitionController(PartitionService partition) {
        this.partition = partition;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String txnRef) {
        return partition.get(txnRef);
    }

    @PutMapping("/members")
    public Map<String, Object> saveMembers(@PathVariable String txnRef,
                                           @RequestBody PartitionService.PartitionRequest body) {
        return partition.saveMembers(txnRef, body);
    }

    @PutMapping("/schedules")
    public Map<String, Object> saveSchedules(@PathVariable String txnRef,
                                             @RequestBody List<PartitionService.ScheduleInput> body) {
        return partition.saveSchedules(txnRef, body);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable String txnRef,
                                      @RequestParam(required = false) String documentType,
                                      @RequestParam("file") MultipartFile file) {
        return partition.upload(txnRef, documentType, file);
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<byte[]> download(@PathVariable String txnRef, @PathVariable long documentId) {
        Map<String, Object> doc = partition.download(txnRef, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType((String) doc.get("mime_type")))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.get("file_name") + "\"")
                .body((byte[]) doc.get("content"));
    }
}
