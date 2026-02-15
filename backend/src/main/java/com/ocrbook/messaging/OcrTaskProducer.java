package com.ocrbook.messaging;

import com.ocrbook.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OcrTaskProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Send an OCR task message to the queue.
     */
    public void sendOcrTask(OcrTaskMessage message) {
        log.info("Sending OCR task to queue: jobId={}, taskId={}, part={}",
                message.getJobId(), message.getTaskId(), message.getPartNumber());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.OCR_EXCHANGE,
                RabbitMQConfig.OCR_ROUTING_KEY,
                message);
    }
}
