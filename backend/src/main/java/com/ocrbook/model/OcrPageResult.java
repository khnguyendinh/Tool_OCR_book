package com.ocrbook.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents structured OCR output with formatting information
 * extracted from Google Cloud Vision API response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OcrPageResult implements Serializable {

    private int pageNumber;
    private List<TextBlock> blocks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TextBlock implements Serializable {
        private String text;
        private BlockType blockType;
        private List<TextParagraph> paragraphs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TextParagraph implements Serializable {
        private String text;
        private float avgFontSize;
        private boolean isBold;
        private boolean isItalic;
        private boolean isHeading;
        private int headingLevel; // 1=H1, 2=H2, 3=H3, 0=normal
    }

    public enum BlockType {
        TEXT, TABLE, PICTURE, RULER, BARCODE, UNKNOWN
    }
}
