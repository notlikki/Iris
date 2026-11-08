package com.iris.controller;

import com.iris.dto.VerificationResponseDto;
import com.iris.service.VerificationService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class VerificationController {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VerificationResponseDto> verify(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(verificationService.verify(file));
    }

    @GetMapping("/verifications/{id}/candidate")
    public ResponseEntity<Resource> getCandidate(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(verificationService.getCandidateResource(id));
    }

    @GetMapping("/verifications/{id}/diff")
    public ResponseEntity<Resource> getDiff(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(verificationService.getDiffResource(id));
    }
}
