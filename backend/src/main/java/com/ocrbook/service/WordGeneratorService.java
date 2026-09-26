package com.ocrbook.service;

import com.ocrbook.model.OcrPageResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WordGeneratorService {

    @Value("${ocr.output-dir:./output}")
    private String outputDir;

    @Value("${ocr.pandoc-path:}")
    private String pandocPath;

    @Autowired
    private OcrService ocrService;

    /**
     * Generate a formatted Word document from structured OCR data.
     * Uses font size, bold/italic, and heading detection for proper formatting.
     */
    public String generateWordDocument(List<String> textParts, String fileName, String customOutputDir)
            throws IOException {
        String resolvedDir = (customOutputDir != null && !customOutputDir.isBlank()) ? customOutputDir : outputDir;
        Path outputPath = Paths.get(resolvedDir);
        Files.createDirectories(outputPath);

        String outputFileName = fileName.replaceAll("\\.[^.]+$", "") + "_ocr_result.docx";
        Path filePath = outputPath.resolve(outputFileName);

        try (XWPFDocument document = new XWPFDocument()) {
            // Set page size to A4
            CTBody body = document.getDocument().getBody();
            CTSectPr sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
            CTPageSz pageSize = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
            pageSize.setW(BigInteger.valueOf(11906));
            pageSize.setH(BigInteger.valueOf(16838));

            // Add title
            addTitle(document, fileName.replaceAll("\\.[^.]+$", ""));

            // Process each part
            for (int i = 0; i < textParts.size(); i++) {
                String partJson = textParts.get(i);
                if (partJson == null || partJson.isBlank())
                    continue;

                // Try to parse as structured JSON
                List<OcrPageResult> pages = ocrService.parseStructuredResult(partJson);

                if (pages != null && !pages.isEmpty()) {
                    // Use structured formatting
                    writeFormattedPages(document, pages);
                } else {
                    // Fallback to plain text
                    writePlainText(document, partJson);
                }
            }

            // Write to file
            try (var fos = Files.newOutputStream(filePath)) {
                document.write(fos);
            }
        }

        log.info("Generated formatted Word document: {}", filePath);
        return filePath.toString();
    }

    /**
     * Ghép các phần Markdown (+ LaTeX) do Gemini trả về rồi dùng pandoc chuyển sang .docx.
     * Công thức $...$ được pandoc chuyển thành equation gốc của Word (OMML).
     */
    public String generateWordFromMarkdown(List<String> markdownParts, String fileName, String customOutputDir)
            throws IOException {
        String resolvedDir = (customOutputDir != null && !customOutputDir.isBlank()) ? customOutputDir : outputDir;
        Path outputPath = Paths.get(resolvedDir);
        Files.createDirectories(outputPath);

        String baseName = fileName.replaceAll("\\.[^.]+$", "");
        Path filePath = outputPath.resolve(baseName + "_ocr_result.docx").toAbsolutePath();

        String markdown = markdownParts.stream()
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.joining("\n\n"));

        Path mdFile = Files.createTempFile("ocr-", ".md");
        Path pandocLog = Files.createTempFile("pandoc-", ".log");
        try {
            Files.writeString(mdFile, markdown, StandardCharsets.UTF_8);

            // -fancy_lists: giữ nguyên nhãn "a)", "b)"... là chữ, không biến thành danh sách tự đánh số
            Process process = new ProcessBuilder(resolvePandoc(), mdFile.toString(),
                    "-f", "markdown-fancy_lists", "-o", filePath.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(pandocLog.toFile())
                    .start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("pandoc timeout");
            }
            if (process.exitValue() != 0) {
                throw new IOException("pandoc exit " + process.exitValue() + ": "
                        + Files.readString(pandocLog, StandardCharsets.UTF_8).strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pandoc interrupted", e);
        } finally {
            Files.deleteIfExists(mdFile);
            Files.deleteIfExists(pandocLog);
        }

        log.info("Generated Word document via pandoc: {}", filePath);
        return filePath.toString();
    }

    /** Lấy đường dẫn pandoc: cấu hình → nơi winget cài mặc định → PATH. */
    private String resolvePandoc() {
        if (pandocPath != null && !pandocPath.isBlank()) {
            return pandocPath;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path winget = Paths.get(localAppData, "Pandoc", "pandoc.exe");
            if (Files.exists(winget)) {
                return winget.toString();
            }
        }
        return "pandoc";
    }

    /**
     * Write pages with full formatting (headings, bold, italic).
     */
    private void writeFormattedPages(XWPFDocument document, List<OcrPageResult> pages) {
        for (OcrPageResult page : pages) {
            if (page.getBlocks() == null)
                continue;

            for (OcrPageResult.TextBlock block : page.getBlocks()) {
                if (block.getParagraphs() == null)
                    continue;

                for (OcrPageResult.TextParagraph para : block.getParagraphs()) {
                    String text = para.getText();
                    if (text == null || text.isBlank())
                        continue;

                    if (para.isHeading()) {
                        addHeading(document, text, para.getHeadingLevel(), para.isBold());
                    } else {
                        addFormattedParagraph(document, text, para.isBold(), para.isItalic());
                    }
                }
            }
        }
    }

    /**
     * Add document title.
     */
    private void addTitle(XWPFDocument document, String title) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(400);
        paragraph.setSpacingBefore(200);

        XWPFRun run = paragraph.createRun();
        run.setText(title);
        run.setBold(true);
        run.setFontSize(26);
        run.setFontFamily("Times New Roman");
        run.setColor("1a1a2e");

        // Add separator line
        XWPFParagraph sep = document.createParagraph();
        sep.setAlignment(ParagraphAlignment.CENTER);
        sep.setSpacingAfter(400);
        XWPFRun sepRun = sep.createRun();
        sepRun.setText("─────────────────────────────────────");
        sepRun.setFontSize(10);
        sepRun.setColor("888888");
    }

    /**
     * Add a heading paragraph with appropriate style.
     */
    private void addHeading(XWPFDocument document, String text, int level, boolean isBold) {
        XWPFParagraph paragraph = document.createParagraph();

        int fontSize;
        int spacingBefore;
        int spacingAfter;

        switch (level) {
            case 1 -> {
                fontSize = 22;
                spacingBefore = 480;
                spacingAfter = 240;
                paragraph.setAlignment(ParagraphAlignment.CENTER);
            }
            case 2 -> {
                fontSize = 18;
                spacingBefore = 360;
                spacingAfter = 180;
                paragraph.setAlignment(ParagraphAlignment.LEFT);
            }
            default -> {
                fontSize = 15;
                spacingBefore = 240;
                spacingAfter = 120;
                paragraph.setAlignment(ParagraphAlignment.LEFT);
            }
        }

        paragraph.setSpacingBefore(spacingBefore);
        paragraph.setSpacingAfter(spacingAfter);

        // Handle multi-line headings
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            XWPFRun run = paragraph.createRun();
            run.setText(lines[i].trim());
            run.setBold(true);
            run.setFontSize(fontSize);
            run.setFontFamily("Times New Roman");
            run.setColor("1a1a2e");
            if (i < lines.length - 1) {
                run.addBreak();
            }
        }
    }

    /**
     * Add a formatted body paragraph.
     */
    private void addFormattedParagraph(XWPFDocument document, String text, boolean isBold, boolean isItalic) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.BOTH);
        paragraph.setSpacingAfter(100);
        paragraph.setSpacingBefore(40);

        // First line indent (like a book)
        paragraph.setIndentationFirstLine(400);

        // Handle line breaks within paragraph
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty())
                continue;

            XWPFRun run = paragraph.createRun();
            run.setText(line);
            run.setFontSize(12);
            run.setFontFamily("Times New Roman");

            if (isBold) {
                run.setBold(true);
            }
            if (isItalic) {
                run.setItalic(true);
            }

            if (i < lines.length - 1) {
                run.addBreak();
            }
        }
    }

    /**
     * Fallback: write plain text when structured data is not available.
     */
    private void writePlainText(XWPFDocument document, String text) {
        String[] paragraphs = text.split("\\n\\n+");
        for (String para : paragraphs) {
            if (para.isBlank())
                continue;

            XWPFParagraph contentParagraph = document.createParagraph();
            contentParagraph.setAlignment(ParagraphAlignment.BOTH);
            contentParagraph.setSpacingAfter(120);
            contentParagraph.setIndentationFirstLine(400);

            String[] lines = para.split("\\n");
            for (int j = 0; j < lines.length; j++) {
                XWPFRun run = contentParagraph.createRun();
                run.setText(lines[j].trim());
                run.setFontSize(12);
                run.setFontFamily("Times New Roman");
                if (j < lines.length - 1) {
                    run.addBreak();
                }
            }
        }
    }

    /**
     * Generate Word document as byte array for download.
     */
    public byte[] generateWordDocumentAsBytes(List<String> textParts, String fileName) throws IOException {
        String path = generateWordDocument(textParts, fileName, null);
        return Files.readAllBytes(Path.of(path));
    }
}
