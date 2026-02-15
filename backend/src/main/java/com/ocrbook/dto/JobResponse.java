package com.ocrbook.dto;

import com.ocrbook.model.OcrJob;
import com.ocrbook.model.OcrTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobResponse {
    private String id;
    private String originalFileName;
    private int totalPages;
    private int totalParts;
    private int completedParts;
    private String status;
    private double progressPercent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<TaskResponse> tasks;

    public static JobResponse fromEntity(OcrJob job) {
        double progress = job.getTotalParts() > 0
                ? (double) job.getCompletedParts() / job.getTotalParts() * 100
                : 0;

        return JobResponse.builder()
                .id(job.getId())
                .originalFileName(job.getOriginalFileName())
                .totalPages(job.getTotalPages())
                .totalParts(job.getTotalParts())
                .completedParts(job.getCompletedParts())
                .status(job.getStatus().name())
                .progressPercent(Math.round(progress * 100.0) / 100.0)
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    public static JobResponse fromEntityWithTasks(OcrJob job) {
        JobResponse response = fromEntity(job);
        if (job.getTasks() != null) {
            response.setTasks(job.getTasks().stream()
                    .map(TaskResponse::fromEntity)
                    .collect(Collectors.toList()));
        }
        return response;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskResponse {
        private String id;
        private int partNumber;
        private int startPage;
        private int endPage;
        private String status;
        private String errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static TaskResponse fromEntity(OcrTask task) {
            return TaskResponse.builder()
                    .id(task.getId())
                    .partNumber(task.getPartNumber())
                    .startPage(task.getStartPage())
                    .endPage(task.getEndPage())
                    .status(task.getStatus().name())
                    .errorMessage(task.getErrorMessage())
                    .createdAt(task.getCreatedAt())
                    .updatedAt(task.getUpdatedAt())
                    .build();
        }
    }
}
