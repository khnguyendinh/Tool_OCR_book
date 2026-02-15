package com.ocrbook.repository;

import com.ocrbook.model.OcrTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OcrTaskRepository extends JpaRepository<OcrTask, String> {
    List<OcrTask> findByJobIdOrderByPartNumberAsc(String jobId);
    long countByJobIdAndStatus(String jobId, OcrTask.TaskStatus status);
}
