package com.iris.controller;

import com.iris.dto.AuditEventDto;
import com.iris.dto.ImageRecordDto;
import com.iris.service.AuditService;
import com.iris.service.ImageRecordService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RecordController {

    private final ImageRecordService recordService;
    private final AuditService auditService;

    public RecordController(ImageRecordService recordService, AuditService auditService) {
        this.recordService = recordService;
        this.auditService = auditService;
    }

    @PostMapping(value = "/records", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageRecordDto> register(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(recordService.register(file));
    }

    @GetMapping("/records")
    public ResponseEntity<List<ImageRecordDto>> listRecords() {
        return ResponseEntity.ok(recordService.getAll());
    }

    @GetMapping("/records/{id}")
    public ResponseEntity<ImageRecordDto> getRecord(@PathVariable("id") Long id) {
        return ResponseEntity.ok(recordService.getById(id));
    }

    @PostMapping("/records/{id}/resign")
    public ResponseEntity<ImageRecordDto> resign(@PathVariable("id") Long id) {
        return ResponseEntity.ok(recordService.resign(id));
    }

    @GetMapping("/records/{id}/original")
    public ResponseEntity<Resource> getOriginal(@PathVariable("id") Long id) {
        ImageRecordDto record = recordService.getById(id);
        Resource resource = recordService.getOriginalResource(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(record.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + record.originalFilename() + "\"")
                .body(resource);
    }

    @GetMapping("/records/{id}/signed")
    public ResponseEntity<Resource> getSigned(@PathVariable("id") Long id) {
        Resource resource = recordService.getSignedResource(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"signed_image.png\"")
                .body(resource);
    }

    @GetMapping("/records/{id}/download")
    public ResponseEntity<Resource> downloadOriginal(@PathVariable("id") Long id) {
        ImageRecordService.DownloadInfo download = recordService.getDownloadInfo(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .headers(headers -> headers.setContentDisposition(disposition))
                .body(download.resource());
    }

    @DeleteMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") Long id) {
        recordService.delete(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Record #" + id + " and its stored files were deleted."
        ));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditEventDto>> getAuditLogs() {
        return ResponseEntity.ok(auditService.getRecentEvents()
                .stream()
                .map(AuditEventDto::fromEntity)
                .toList());
    }
}
