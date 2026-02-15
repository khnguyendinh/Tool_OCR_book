package com.ocrbook.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class PdfSplitterService {

    @Value("${ocr.temp-dir:./temp/pdf-parts}")
    private String tempDir;

    /**
     * Split a PDF file into N parts.
     * Each part is saved as a separate PDF file.
     *
     * @param pdfBytes the original PDF bytes
     * @param jobId    the job ID for naming
     * @param numParts number of parts to split into
     * @return list of SplitResult containing file paths and page ranges
     */
    public List<SplitResult> splitPdf(byte[] pdfBytes, String jobId, int numParts, String baseDir) throws IOException {
        String resolvedDir = (baseDir != null && !baseDir.isBlank()) ? baseDir : tempDir;
        Path jobTempDir = Paths.get(resolvedDir, jobId);
        Files.createDirectories(jobTempDir);

        List<SplitResult> results = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            log.info("PDF has {} pages, splitting into {} parts", totalPages, numParts);

            // Adjust numParts if more than totalPages
            if (numParts > totalPages) {
                numParts = totalPages;
                log.info("Adjusted numParts to {} (equal to total pages)", numParts);
            }

            int pagesPerPart = totalPages / numParts;
            int remainder = totalPages % numParts;

            int currentPage = 0;
            for (int i = 0; i < numParts; i++) {
                int pagesInThisPart = pagesPerPart + (i < remainder ? 1 : 0);
                int startPage = currentPage;
                int endPage = currentPage + pagesInThisPart - 1;

                // Create a new document for this part
                try (PDDocument partDoc = new PDDocument()) {
                    for (int p = startPage; p <= endPage; p++) {
                        PDPage page = document.getPage(p);
                        partDoc.addPage(page);
                    }

                    String fileName = String.format("part_%04d.pdf", i + 1);
                    Path partPath = jobTempDir.resolve(fileName);
                    partDoc.save(partPath.toFile());

                    results.add(new SplitResult(
                            partPath.toString(),
                            i + 1,
                            startPage + 1, // 1-indexed
                            endPage + 1 // 1-indexed
                    ));

                    log.info("Created part {}: pages {}-{} -> {}", i + 1, startPage + 1, endPage + 1, partPath);
                }

                currentPage += pagesInThisPart;
            }
        }

        return results;
    }

    /**
     * Get total page count of a PDF.
     */
    public int getPageCount(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * Clean up temporary files for a job.
     */
    public void cleanup(String jobId) {
        Path jobTempDir = Paths.get(tempDir, jobId);
        try {
            if (Files.exists(jobTempDir)) {
                Files.walk(jobTempDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .map(Path::toFile)
                        .forEach(File::delete);
                log.info("Cleaned up temp directory for job {}", jobId);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp directory for job {}: {}", jobId, e.getMessage());
        }
    }

    public record SplitResult(String filePath, int partNumber, int startPage, int endPage) {
    }
}
