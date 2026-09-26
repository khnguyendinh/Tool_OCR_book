package com.ocrbook.messaging;

import com.ocrbook.model.OcrJob;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrTaskMessage implements Serializable {
    private String jobId;
    private String taskId;
    private int partNumber;
    private String pdfFilePath;
    /** null (message cũ) được coi là VISION */
    private OcrJob.OcrMode ocrMode;
}
