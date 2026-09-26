package com.ocrbook.repository;

import com.ocrbook.model.OcrJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OcrJobRepository extends JpaRepository<OcrJob, String> {
    List<OcrJob> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from OcrJob j where j.id = :id")
    Optional<OcrJob> findByIdForUpdate(@Param("id") String id);
}
