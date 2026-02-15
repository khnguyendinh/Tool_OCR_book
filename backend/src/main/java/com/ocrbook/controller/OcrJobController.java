package com.ocrbook.controller;

import com.ocrbook.dto.JobResponse;
import com.ocrbook.dto.ConfigResponse;
import com.ocrbook.dto.ConfigUpdateRequest;
import com.ocrbook.model.OcrJob;
import com.ocrbook.service.OcrJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
@Slf4j
public class OcrJobController {

    private final OcrJobService ocrJobService;

    /**
     * Upload a PDF file and start OCR processing.
     */
    @PostMapping("/upload")
    public ResponseEntity<JobResponse> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "numParts", required = false) Integer numParts,
            @RequestParam(value = "splitDir", required = false) String splitDir,
            @RequestParam(value = "outputDir", required = false) String outputDir) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            String contentType = file.getContentType();
            if (contentType == null || !contentType.equals("application/pdf")) {
                return ResponseEntity.badRequest().build();
            }

            OcrJob job = ocrJobService.startJob(file, numParts, splitDir, outputDir);
            return ResponseEntity.ok(JobResponse.fromEntity(job));

        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all OCR jobs.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<JobResponse>> getAllJobs() {
        List<JobResponse> jobs = ocrJobService.getAllJobs().stream()
                .map(JobResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(jobs);
    }

    /**
     * Get details of a specific job.
     */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobResponse> getJob(@PathVariable String id) {
        return ocrJobService.getJobById(id)
                .map(job -> ResponseEntity.ok(JobResponse.fromEntityWithTasks(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Download the Word document result.
     */
    @GetMapping("/jobs/{id}/download")
    public ResponseEntity<byte[]> downloadResult(@PathVariable String id) {
        try {
            OcrJob job = ocrJobService.getJobById(id).orElse(null);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }

            if (job.getStatus() != OcrJob.JobStatus.COMPLETED) {
                return ResponseEntity.badRequest().build();
            }

            byte[] bytes = ocrJobService.getOutputBytes(id);
            String fileName = job.getOriginalFileName().replaceAll("\\.[^.]+$", "") + "_ocr_result.docx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType
                            .parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .body(bytes);

        } catch (Exception e) {
            log.error("Download failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get current OCR configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> getConfig() {
        return ResponseEntity.ok(new ConfigResponse(
                ocrJobService.getDefaultSplitParts(),
                ocrJobService.getDefaultSplitDir(),
                ocrJobService.getDefaultOutputDir()));
    }

    /**
     * Update OCR configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<ConfigResponse> updateConfig(@RequestBody ConfigUpdateRequest request) {
        if (request.getSplitParts() != null && request.getSplitParts() > 0) {
            ocrJobService.setDefaultSplitParts(request.getSplitParts());
        }
        if (request.getSplitDir() != null && !request.getSplitDir().isBlank()) {
            ocrJobService.setDefaultSplitDir(request.getSplitDir());
        }
        if (request.getOutputDir() != null && !request.getOutputDir().isBlank()) {
            ocrJobService.setDefaultOutputDir(request.getOutputDir());
        }
        return ResponseEntity.ok(new ConfigResponse(
                ocrJobService.getDefaultSplitParts(),
                ocrJobService.getDefaultSplitDir(),
                ocrJobService.getDefaultOutputDir()));
    }
}
