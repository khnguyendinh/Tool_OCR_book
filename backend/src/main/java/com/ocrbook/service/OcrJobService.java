package com.ocrbook.service;

import com.ocrbook.messaging.OcrTaskMessage;
import com.ocrbook.messaging.OcrTaskProducer;
import com.ocrbook.model.OcrJob;
import com.ocrbook.model.OcrTask;
import com.ocrbook.repository.OcrJobRepository;
import com.ocrbook.repository.OcrTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OcrJobService {

    private final OcrJobRepository jobRepository;
    private final OcrTaskRepository taskRepository;
    private final PdfSplitterService pdfSplitterService;
    private final WordGeneratorService wordGeneratorService;
    private final OcrTaskProducer ocrTaskProducer;

    @Value("${ocr.split-parts:100}")
    private int defaultSplitParts;

    @Value("${ocr.temp-dir:./temp/pdf-parts}")
    private String defaultSplitDir;

    @Value("${ocr.output-dir:./output}")
    private String defaultOutputDir;

    /** Cấu hình người dùng đổi trên giao diện, lưu ra file để không mất khi restart backend. */
    private static final Path SETTINGS_FILE = Path.of("./data/ocr-settings.properties");

    @PostConstruct
    void loadSettings() {
        if (!Files.exists(SETTINGS_FILE)) {
            return;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(SETTINGS_FILE, StandardCharsets.UTF_8)) {
            props.load(reader);
            defaultSplitParts = Integer.parseInt(props.getProperty("splitParts", String.valueOf(defaultSplitParts)));
            defaultSplitDir = props.getProperty("splitDir", defaultSplitDir);
            defaultOutputDir = props.getProperty("outputDir", defaultOutputDir);
            log.info("Loaded settings: splitParts={}, splitDir={}, outputDir={}", defaultSplitParts, defaultSplitDir, defaultOutputDir);
        } catch (IOException | NumberFormatException e) {
            log.warn("Cannot read {}: {}", SETTINGS_FILE, e.toString());
        }
    }

    private void saveSettings() {
        Properties props = new Properties();
        props.setProperty("splitParts", String.valueOf(defaultSplitParts));
        props.setProperty("splitDir", defaultSplitDir);
        props.setProperty("outputDir", defaultOutputDir);
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            try (var writer = Files.newBufferedWriter(SETTINGS_FILE, StandardCharsets.UTF_8)) {
                props.store(writer, "OCR Book settings");
            }
        } catch (IOException e) {
            log.warn("Cannot save {}: {}", SETTINGS_FILE, e.toString());
        }
    }

    /**
     * Start a new OCR job: upload PDF → split → queue tasks.
     */
    @Transactional
    public OcrJob startJob(MultipartFile file, Integer numParts, String splitDir, String outputDir,
            OcrJob.OcrMode ocrMode) throws IOException {
        int parts = numParts != null ? numParts : defaultSplitParts;
        String resolvedSplitDir = (splitDir != null && !splitDir.isBlank()) ? splitDir : defaultSplitDir;
        String resolvedOutputDir = (outputDir != null && !outputDir.isBlank()) ? outputDir : defaultOutputDir;
        byte[] pdfBytes = file.getBytes();

        // Get total page count
        int totalPages = pdfSplitterService.getPageCount(pdfBytes);

        // Create job
        OcrJob job = OcrJob.builder()
                .originalFileName(file.getOriginalFilename())
                .totalPages(totalPages)
                .totalParts(Math.min(parts, totalPages))
                .completedParts(0)
                .status(OcrJob.JobStatus.SPLITTING)
                .splitDir(resolvedSplitDir)
                .outputDir(resolvedOutputDir)
                .ocrMode(ocrMode != null ? ocrMode : OcrJob.OcrMode.VISION)
                .build();
        job = jobRepository.save(job);

        final String jobId = job.getId();
        final int finalParts = job.getTotalParts();

        // Split PDF and create tasks asynchronously
        processPdfAsync(pdfBytes, jobId, finalParts, resolvedSplitDir);

        return job;
    }

    @Async
    public void processPdfAsync(byte[] pdfBytes, String jobId, int numParts, String splitDir) {
        try {
            // Split PDF
            List<PdfSplitterService.SplitResult> splitResults = pdfSplitterService.splitPdf(pdfBytes, jobId, numParts,
                    splitDir);

            // Create tasks and queue messages
            OcrJob job = jobRepository.findById(jobId).orElseThrow();
            List<OcrTaskMessage> messages = new java.util.ArrayList<>();

            for (PdfSplitterService.SplitResult result : splitResults) {
                OcrTask task = OcrTask.builder()
                        .job(job)
                        .partNumber(result.partNumber())
                        .startPage(result.startPage())
                        .endPage(result.endPage())
                        .pdfPartPath(result.filePath())
                        .status(OcrTask.TaskStatus.PENDING)
                        .build();
                task = taskRepository.save(task);

                messages.add(new OcrTaskMessage(
                        jobId, task.getId(), result.partNumber(), result.filePath(), job.getEffectiveOcrMode()));
            }
            sendAfterCommit(messages);

            // Update job status
            job.setStatus(OcrJob.JobStatus.QUEUED);
            job.setTotalParts(splitResults.size());
            jobRepository.save(job);

            log.info("Job {} queued with {} tasks", jobId, splitResults.size());

        } catch (Exception e) {
            log.error("Failed to process PDF for job {}: {}", jobId, e.getMessage(), e);
            jobRepository.findById(jobId).ifPresent(job -> {
                job.setStatus(OcrJob.JobStatus.FAILED);
                jobRepository.save(job);
            });
        }
    }

    /**
     * Update task status after OCR completion.
     */
    @Transactional
    public void completeTask(String taskId, String extractedText) {
        OcrTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.info("Task {} no longer exists (job deleted), dropping result", taskId);
            return;
        }

        // Khoá dòng job để các consumer chạy song song cập nhật tiến độ lần lượt;
        // nếu không, nhiều task xong cùng lúc sẽ đếm thiếu và job kẹt ở PROCESSING
        OcrJob job = jobRepository.findByIdForUpdate(task.getJob().getId()).orElseThrow();

        task.setStatus(OcrTask.TaskStatus.COMPLETED);
        task.setExtractedText(extractedText);
        taskRepository.saveAndFlush(task);

        // Update job progress
        long completed = taskRepository.countByJobIdAndStatus(job.getId(), OcrTask.TaskStatus.COMPLETED);
        job.setCompletedParts((int) completed);

        if (job.getStatus() != OcrJob.JobStatus.PROCESSING) {
            job.setStatus(OcrJob.JobStatus.PROCESSING);
        }

        if (completed >= job.getTotalParts()) {
            job.setStatus(OcrJob.JobStatus.COMPLETED);
            log.info("Job {} completed! All {} parts processed.", job.getId(), job.getTotalParts());

            // Generate Word document
            try {
                generateOutput(job.getId());
            } catch (IOException e) {
                log.error("Failed to generate Word document for job {}: {}", job.getId(), e.toString());
            }
        }

        jobRepository.save(job);
    }

    /**
     * Mark a task as failed.
     */
    @Transactional
    public void failTask(String taskId, String errorMessage) {
        OcrTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        task.setStatus(OcrTask.TaskStatus.FAILED);
        task.setErrorMessage(errorMessage);
        taskRepository.save(task);
    }

    /**
     * Generate Word output for a completed job.
     */
    public String generateOutput(String jobId) throws IOException {
        List<OcrTask> tasks = taskRepository.findByJobIdOrderByPartNumberAsc(jobId);
        List<String> textParts = tasks.stream()
                .map(OcrTask::getExtractedText)
                .collect(Collectors.toList());

        OcrJob job = jobRepository.findById(jobId).orElseThrow();
        String outDir = (job.getOutputDir() != null && !job.getOutputDir().isBlank())
                ? job.getOutputDir()
                : defaultOutputDir;
        String outputPath;
        try {
            outputPath = generateWord(job, textParts, outDir);
        } catch (IOException e) {
            if (outDir.equals(defaultOutputDir)) {
                throw e;
            }
            // Thư mục người dùng chọn không ghi được (vd: Windows Controlled Folder Access chặn Documents)
            // → lưu vào thư mục mặc định để vẫn tải về được
            log.warn("Cannot write to output dir {} ({}), falling back to {}", outDir, e.toString(), defaultOutputDir);
            outputPath = generateWord(job, textParts, defaultOutputDir);
        }
        job.setOutputFilePath(outputPath);
        jobRepository.save(job);

        return outputPath;
    }

    private String generateWord(OcrJob job, List<String> textParts, String outDir) throws IOException {
        if (job.getEffectiveOcrMode() == OcrJob.OcrMode.GEMINI) {
            return wordGeneratorService.generateWordFromMarkdown(textParts, job.getOriginalFileName(), outDir);
        }
        return wordGeneratorService.generateWordDocument(textParts, job.getOriginalFileName(), outDir);
    }

    /**
     * Get all jobs.
     */
    public List<OcrJob> getAllJobs() {
        return jobRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Xoá lịch sử job. Luôn xoá job đã xong/lỗi; job chưa xong (đang chờ, đang chạy, treo) chỉ xoá khi
     * includeActive = true (giao diện đã hỏi người dùng). File Word đã xuất không bị xoá, chỉ dọn PDF tạm.
     *
     * @return số job đã xoá
     */
    @Transactional
    public int clearHistory(boolean includeActive) {
        List<OcrJob> removable = jobRepository.findAll().stream()
                .filter(job -> includeActive
                        || job.getStatus() == OcrJob.JobStatus.COMPLETED
                        || job.getStatus() == OcrJob.JobStatus.FAILED)
                .toList();
        jobRepository.deleteAll(removable);
        removable.forEach(job -> pdfSplitterService.cleanup(job.getId()));
        log.info("Cleared {} jobs from history (includeActive={})", removable.size(), includeActive);
        return removable.size();
    }

    /** Task còn tồn tại không (job có thể đã bị xoá khỏi lịch sử khi trang của nó vẫn nằm trong hàng đợi). */
    public boolean taskExists(String taskId) {
        return taskRepository.existsById(taskId);
    }

    /**
     * Gửi message vào hàng đợi SAU KHI transaction lưu job/task đã commit. Gửi sớm hơn thì consumer
     * nhận được trang nhưng chưa thấy bản ghi trong DB, tưởng job đã bị xoá và bỏ qua trang đó.
     */
    private void sendAfterCommit(List<OcrTaskMessage> messages) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messages.forEach(ocrTaskProducer::sendOcrTask);
                }
            });
        } else {
            messages.forEach(ocrTaskProducer::sendOcrTask);
        }
    }

    /**
     * Đẩy lại vào hàng đợi các trang chưa xong (PENDING/FAILED) của một job bị kẹt, không OCR lại trang đã xong.
     *
     * @return số trang đã đẩy lại
     */
    @Transactional
    public int resumeJob(String jobId) {
        OcrJob job = jobRepository.findById(jobId).orElseThrow();
        List<OcrTaskMessage> messages = taskRepository.findByJobIdOrderByPartNumberAsc(jobId).stream()
                .filter(t -> t.getStatus() != OcrTask.TaskStatus.COMPLETED)
                .map(t -> {
                    t.setStatus(OcrTask.TaskStatus.PENDING);
                    t.setErrorMessage(null);
                    return new OcrTaskMessage(jobId, t.getId(), t.getPartNumber(), t.getPdfPartPath(),
                            job.getEffectiveOcrMode());
                })
                .toList();
        if (!messages.isEmpty() && job.getStatus() != OcrJob.JobStatus.PROCESSING) {
            job.setStatus(OcrJob.JobStatus.QUEUED);
        }
        sendAfterCommit(messages);
        log.info("Resumed job {}: re-queued {} parts", jobId, messages.size());
        return messages.size();
    }

    /**
     * Get a job by ID with tasks.
     */
    @Transactional(readOnly = true)
    public Optional<OcrJob> getJobById(String id) {
        Optional<OcrJob> jobOpt = jobRepository.findById(id);
        jobOpt.ifPresent(job -> job.getTasks().size()); // force load tasks
        return jobOpt;
    }

    /**
     * Get download bytes for completed job.
     */
    public byte[] getOutputBytes(String jobId) throws IOException {
        OcrJob job = jobRepository.findById(jobId).orElseThrow();
        if (job.getStatus() != OcrJob.JobStatus.COMPLETED) {
            throw new IllegalStateException("Job is not completed yet");
        }
        // Luôn tạo lại từ kết quả OCR đã lưu (không gọi lại OCR) để file Word theo đúng cách xuất mới nhất
        String outputFilePath = generateOutput(jobId);
        return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(outputFilePath));
    }

    /**
     * Get/Set default split parts.
     */
    public int getDefaultSplitParts() {
        return defaultSplitParts;
    }

    public void setDefaultSplitParts(int parts) {
        this.defaultSplitParts = parts;
        saveSettings();
    }

    public String getDefaultSplitDir() {
        return defaultSplitDir;
    }

    public void setDefaultSplitDir(String dir) {
        this.defaultSplitDir = dir;
        saveSettings();
    }

    public String getDefaultOutputDir() {
        return defaultOutputDir;
    }

    public void setDefaultOutputDir(String dir) {
        this.defaultOutputDir = dir;
        saveSettings();
    }
}
