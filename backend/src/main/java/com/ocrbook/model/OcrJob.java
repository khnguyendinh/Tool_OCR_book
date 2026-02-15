package com.ocrbook.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ocr_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OcrJob {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false)
    private int totalPages;

    @Column(nullable = false)
    private int totalParts;

    @Column(nullable = false)
    private int completedParts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String outputFilePath;

    private String splitDir;

    private String outputDir;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("partNumber ASC")
    @Builder.Default
    private List<OcrTask> tasks = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (id == null)
            id = UUID.randomUUID().toString();
        if (createdAt == null)
            createdAt = LocalDateTime.now();
        if (status == null)
            status = JobStatus.SPLITTING;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum JobStatus {
        SPLITTING, QUEUED, PROCESSING, COMPLETED, FAILED
    }
}
