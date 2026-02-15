package com.ocrbook.repository;

import com.ocrbook.model.OcrJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OcrJobRepository extends JpaRepository<OcrJob, String> {
    List<OcrJob> findAllByOrderByCreatedAtDesc();
}
