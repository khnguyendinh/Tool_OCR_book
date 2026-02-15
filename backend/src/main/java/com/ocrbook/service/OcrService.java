package com.ocrbook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import com.ocrbook.model.OcrPageResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class OcrService {

    private ImageAnnotatorClient visionClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            visionClient = ImageAnnotatorClient.create();
            log.info("Google Cloud Vision client initialized successfully.");
        } catch (IOException e) {
            log.error("Failed to initialize Vision client: {}. OCR will not work.", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (visionClient != null) {
            visionClient.close();
            log.info("Google Cloud Vision client closed.");
        }
    }

    /**
     * Perform OCR on a PDF file, returning structured JSON with formatting info.
     */
    public String performOcr(String pdfFilePath) throws IOException {
        byte[] pdfBytes = Files.readAllBytes(Path.of(pdfFilePath));
        return performOcrOnBytes(pdfBytes);
    }

    /**
     * Perform OCR and return structured JSON.
     * The JSON contains page results with blocks, paragraphs, font sizes,
     * bold/italic.
     */
    public String performOcrOnBytes(byte[] pdfBytes) throws IOException {
        if (visionClient == null) {
            throw new IllegalStateException("Vision client not initialized. Check GOOGLE_APPLICATION_CREDENTIALS.");
        }

        long startTime = System.currentTimeMillis();

        ByteString content = ByteString.copyFrom(pdfBytes);

        InputConfig inputConfig = InputConfig.newBuilder()
                .setMimeType("application/pdf")
                .setContent(content)
                .build();

        Feature feature = Feature.newBuilder()
                .setType(Feature.Type.DOCUMENT_TEXT_DETECTION)
                .build();

        AnnotateFileRequest fileRequest = AnnotateFileRequest.newBuilder()
                .setInputConfig(inputConfig)
                .addFeatures(feature)
                .build();

        BatchAnnotateFilesRequest batchRequest = BatchAnnotateFilesRequest.newBuilder()
                .addRequests(fileRequest)
                .build();

        BatchAnnotateFilesResponse response = visionClient.batchAnnotateFiles(batchRequest);

        List<OcrPageResult> allPages = new ArrayList<>();
        int pageNum = 1;

        for (AnnotateFileResponse fileResponse : response.getResponsesList()) {
            if (fileResponse.hasError()) {
                log.error("File-level OCR error: {}", fileResponse.getError().getMessage());
                throw new IOException("OCR error: " + fileResponse.getError().getMessage());
            }

            for (AnnotateImageResponse imageResponse : fileResponse.getResponsesList()) {
                if (imageResponse.hasError()) {
                    log.warn("Page OCR error: {}", imageResponse.getError().getMessage());
                    pageNum++;
                    continue;
                }

                TextAnnotation fullText = imageResponse.getFullTextAnnotation();
                if (fullText != null && fullText.getPagesCount() > 0) {
                    for (Page page : fullText.getPagesList()) {
                        OcrPageResult pageResult = extractPageStructure(page, pageNum);
                        allPages.add(pageResult);
                        pageNum++;
                    }
                } else {
                    pageNum++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("OCR completed in {}ms, extracted {} pages", elapsed, allPages.size());

        return objectMapper.writeValueAsString(allPages);
    }

    /**
     * Parse structured JSON back to OcrPageResult list.
     */
    public List<OcrPageResult> parseStructuredResult(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<OcrPageResult>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse structured OCR result, falling back to plain text");
            return null;
        }
    }

    /**
     * Extract structural information from a Vision API Page object.
     */
    private OcrPageResult extractPageStructure(Page page, int pageNumber) {
        List<OcrPageResult.TextBlock> textBlocks = new ArrayList<>();

        // Collect all font sizes on the page to determine what's "normal" vs "heading"
        List<Float> allFontSizes = new ArrayList<>();
        for (Block block : page.getBlocksList()) {
            for (Paragraph paragraph : block.getParagraphsList()) {
                float avgSize = getAverageFontSize(paragraph);
                if (avgSize > 0) {
                    allFontSizes.add(avgSize);
                }
            }
        }

        // Calculate the median font size (= "body text" size)
        float medianFontSize = calculateMedian(allFontSizes);

        for (Block block : page.getBlocksList()) {
            OcrPageResult.BlockType blockType = convertBlockType(block.getBlockType());
            List<OcrPageResult.TextParagraph> paragraphs = new ArrayList<>();

            for (Paragraph paragraph : block.getParagraphsList()) {
                String paraText = extractParagraphText(paragraph);
                if (paraText.isBlank())
                    continue;

                float avgFontSize = getAverageFontSize(paragraph);
                boolean isBold = detectBold(paragraph);
                boolean isItalic = detectItalic(paragraph);

                // Determine heading level based on font size relative to median
                int headingLevel = 0;
                boolean isHeading = false;

                if (medianFontSize > 0 && avgFontSize > 0) {
                    float sizeRatio = avgFontSize / medianFontSize;
                    if (sizeRatio >= 1.8) {
                        headingLevel = 1;
                        isHeading = true;
                    } else if (sizeRatio >= 1.4) {
                        headingLevel = 2;
                        isHeading = true;
                    } else if (sizeRatio >= 1.15 && (isBold || paraText.length() < 100)) {
                        headingLevel = 3;
                        isHeading = true;
                    }
                }

                // Short bold lines are likely headings too
                if (!isHeading && isBold && paraText.length() < 80 && !paraText.endsWith(".")) {
                    headingLevel = 3;
                    isHeading = true;
                }

                paragraphs.add(OcrPageResult.TextParagraph.builder()
                        .text(paraText)
                        .avgFontSize(avgFontSize)
                        .isBold(isBold)
                        .isItalic(isItalic)
                        .isHeading(isHeading)
                        .headingLevel(headingLevel)
                        .build());
            }

            if (!paragraphs.isEmpty()) {
                textBlocks.add(OcrPageResult.TextBlock.builder()
                        .text(paragraphs.stream()
                                .map(OcrPageResult.TextParagraph::getText)
                                .reduce("", (a, b) -> a + "\n" + b).trim())
                        .blockType(blockType)
                        .paragraphs(paragraphs)
                        .build());
            }
        }

        return OcrPageResult.builder()
                .pageNumber(pageNumber)
                .blocks(textBlocks)
                .build();
    }

    /**
     * Extract text from a paragraph by concatenating all words.
     */
    private String extractParagraphText(Paragraph paragraph) {
        StringBuilder sb = new StringBuilder();
        for (Word word : paragraph.getWordsList()) {
            StringBuilder wordText = new StringBuilder();
            for (Symbol symbol : word.getSymbolsList()) {
                wordText.append(symbol.getText());

                // Handle detected breaks (space, newline, etc.)
                if (symbol.hasProperty() && symbol.getProperty().hasDetectedBreak()) {
                    TextAnnotation.DetectedBreak.BreakType breakType = symbol.getProperty().getDetectedBreak()
                            .getType();
                    switch (breakType) {
                        case SPACE, SURE_SPACE -> sb.append(wordText).append(" ");
                        case EOL_SURE_SPACE -> sb.append(wordText).append("\n");
                        case HYPHEN -> sb.append(wordText).append("-\n");
                        case LINE_BREAK -> sb.append(wordText).append("\n");
                        default -> sb.append(wordText).append(" ");
                    }
                    wordText = new StringBuilder();
                }
            }
            if (!wordText.isEmpty()) {
                sb.append(wordText).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Calculate average font size from word symbols in a paragraph.
     */
    private float getAverageFontSize(Paragraph paragraph) {
        float totalSize = 0;
        int count = 0;

        for (Word word : paragraph.getWordsList()) {
            if (word.hasProperty() && word.getProperty().getDetectedLanguagesCount() >= 0) {
                for (Symbol symbol : word.getSymbolsList()) {
                    if (symbol.hasProperty()) {
                        // Font size is estimated from bounding box height
                        if (symbol.getBoundingBox() != null && symbol.getBoundingBox().getVerticesCount() >= 4) {
                            int height = Math.abs(
                                    symbol.getBoundingBox().getVertices(3).getY() -
                                            symbol.getBoundingBox().getVertices(0).getY());
                            if (height > 0) {
                                totalSize += height;
                                count++;
                            }
                        }
                    }
                }
            }
        }

        return count > 0 ? totalSize / count : 0;
    }

    /**
     * Detect if the majority of text in a paragraph is bold.
     * Uses word-level font weight detection when available.
     */
    private boolean detectBold(Paragraph paragraph) {
        int boldCount = 0;
        int totalWords = 0;

        for (Word word : paragraph.getWordsList()) {
            totalWords++;
            if (word.hasProperty()) {
                // Check for bold via detected font weight
                var styles = word.getProperty().getDetectedLanguagesList();
                // Font weight detection from symbol properties
                for (Symbol symbol : word.getSymbolsList()) {
                    if (symbol.hasProperty() && symbol.getProperty().hasDetectedBreak()) {
                        // Google Vision doesn't directly expose bold, but we can infer
                        // from stroke width relative to height in bounding box
                    }
                }
                // Simple heuristic: check if word bounding box suggests bold
                // (wider strokes = bold text has higher width-to-height ratio)
                if (word.getBoundingBox() != null && word.getBoundingBox().getVerticesCount() >= 4) {
                    int width = Math.abs(
                            word.getBoundingBox().getVertices(1).getX() -
                                    word.getBoundingBox().getVertices(0).getX());
                    int height = Math.abs(
                            word.getBoundingBox().getVertices(3).getY() -
                                    word.getBoundingBox().getVertices(0).getY());
                    int charCount = word.getSymbolsCount();

                    if (charCount > 0 && height > 0) {
                        float charWidth = (float) width / charCount;
                        float ratio = charWidth / height;
                        // Bold text typically has higher ratio (wider chars)
                        if (ratio > 0.65) {
                            boldCount++;
                        }
                    }
                }
            }
        }

        return totalWords > 0 && (float) boldCount / totalWords > 0.5;
    }

    /**
     * Detect italic text from bounding box skew.
     */
    private boolean detectItalic(Paragraph paragraph) {
        int italicCount = 0;
        int totalWords = 0;

        for (Word word : paragraph.getWordsList()) {
            totalWords++;
            if (word.getBoundingBox() != null && word.getBoundingBox().getVerticesCount() >= 4) {
                // Italic text has skewed bounding boxes - top-left x > bottom-left x
                int topLeftX = word.getBoundingBox().getVertices(0).getX();
                int bottomLeftX = word.getBoundingBox().getVertices(3).getX();
                int height = Math.abs(
                        word.getBoundingBox().getVertices(3).getY() -
                                word.getBoundingBox().getVertices(0).getY());

                if (height > 0) {
                    float skew = (float) Math.abs(topLeftX - bottomLeftX) / height;
                    if (skew > 0.1) {
                        italicCount++;
                    }
                }
            }
        }

        return totalWords > 0 && (float) italicCount / totalWords > 0.5;
    }

    private OcrPageResult.BlockType convertBlockType(Block.BlockType type) {
        return switch (type) {
            case TEXT -> OcrPageResult.BlockType.TEXT;
            case TABLE -> OcrPageResult.BlockType.TABLE;
            case PICTURE -> OcrPageResult.BlockType.PICTURE;
            case RULER -> OcrPageResult.BlockType.RULER;
            case BARCODE -> OcrPageResult.BlockType.BARCODE;
            default -> OcrPageResult.BlockType.UNKNOWN;
        };
    }

    private float calculateMedian(List<Float> values) {
        if (values.isEmpty())
            return 0;
        List<Float> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        }
        return sorted.get(mid);
    }
}
