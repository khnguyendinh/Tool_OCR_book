package com.ocrbook.messaging;

import com.ocrbook.config.RabbitMQConfig;
import com.ocrbook.service.OcrJobService;
import com.ocrbook.service.OcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OcrTaskConsumer {

    private final OcrService ocrService;
    private final OcrJobService ocrJobService;

    /**
     * Listen for OCR task messages and process them sequentially.
     * prefetch=1 ensures one message at a time.
     */
    @RabbitListener(queues = RabbitMQConfig.OCR_QUEUE)
    public void processOcrTask(OcrTaskMessage message) {
        log.info("Received OCR task: jobId={}, taskId={}, part={}",
                message.getJobId(), message.getTaskId(), message.getPartNumber());

        try {
            long startTime = System.currentTimeMillis();

            // Perform OCR on the PDF part
            String extractedText = ocrService.performOcr(message.getPdfFilePath());

            // Update task with result
            ocrJobService.completeTask(message.getTaskId(), extractedText);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("OCR task completed: jobId={}, part={}, chars={}, time={}ms",
                    message.getJobId(), message.getPartNumber(),
                    extractedText.length(), elapsed);

        } catch (Exception e) {
            log.error("OCR task failed: jobId={}, part={}: {}",
                    message.getJobId(), message.getPartNumber(), e.getMessage(), e);

            ocrJobService.failTask(message.getTaskId(), e.getMessage());
        }
    }
}
