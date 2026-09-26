package com.ocrbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * OCR bằng Gemini (Vertex AI): gửi ảnh từng trang, nhận về Markdown + LaTeX.
 * Dùng cho tài liệu toán/công thức mà Cloud Vision không dựng lại được.
 */
@Service
@Slf4j
public class GeminiOcrService {

    private static final String PROMPT = """
            Bạn là công cụ OCR. Hãy chép lại NGUYÊN VĂN toàn bộ nội dung trong ảnh trang sách tiếng Việt này sang Markdown.

            Quy tắc:
            - Chép đúng từng chữ, từng số, đúng thứ tự đọc từ trên xuống. KHÔNG giải bài, KHÔNG sửa lỗi chính tả, KHÔNG thêm hay bớt nội dung, KHÔNG bình luận.
            - Mọi biểu thức toán (phân số, hỗn số, lũy thừa, căn, phép tính có phân số...) viết bằng LaTeX: inline dùng $...$, công thức đứng riêng một dòng dùng $$...$$. Ví dụ phân số: $\\frac{5}{7}$, hỗn số: $2\\frac{1}{3}$.
            - Tiêu đề lớn dùng #, ##; chữ in đậm dùng **...**; giữ nguyên các nhãn như "Bài 1:", "a)", "b)".
            - Bảng thì dùng bảng Markdown.
            - BỎ HẲN phần header và footer của trang (dòng tên đơn vị/website/hotline/số điện thoại ở đầu trang, dòng quảng cáo và số trang ở cuối trang, logo). Không chép các dòng đó.
            - Hình vẽ: ghi [Hình: mô tả ngắn một dòng].
            - Chỉ trả về Markdown, không bọc trong ```, không dùng đường kẻ ngang ---.
            """;

    private static final int MAX_ATTEMPTS = 6;

    @Value("${ocr.gemini.model:gemini-3.8-flash}")
    private String model;

    @Value("${ocr.gemini.location:global}")
    private String location;

    @Value("${ocr.gemini.project:}")
    private String configuredProject;

    @Value("${ocr.gemini.dpi:200}")
    private int dpi;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private GoogleCredentials credentials;
    private String projectId;

    @PostConstruct
    public void init() {
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            projectId = !configuredProject.isBlank() ? configuredProject
                    : credentials instanceof ServiceAccountCredentials sa ? sa.getProjectId() : null;
            log.info("Gemini OCR ready: model={}, project={}, location={}", model, projectId, location);
        } catch (IOException e) {
            log.error("Failed to load Google credentials for Gemini: {}. Gemini OCR will not work.", e.getMessage());
        }
    }

    /**
     * OCR một file PDF (một phần đã split), trả về Markdown của các trang nối lại.
     */
    public String performOcr(String pdfFilePath) throws IOException, InterruptedException {
        if (credentials == null || projectId == null) {
            throw new IllegalStateException("Gemini chưa sẵn sàng. Kiểm tra GOOGLE_APPLICATION_CREDENTIALS / ocr.gemini.project.");
        }

        List<String> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(new File(pdfFilePath))) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                pages.add(callGemini(png.toByteArray()).strip());
            }
        }
        return String.join("\n\n", pages);
    }

    private String callGemini(byte[] pngBytes) throws IOException, InterruptedException {
        String url = "https://aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(projectId, location, model);

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().put("role", "user").putArray("parts");
        parts.addObject().putObject("inlineData")
                .put("mimeType", "image/png")
                .put("data", Base64.getEncoder().encodeToString(pngBytes));
        parts.addObject().put("text", PROMPT);
        body.putObject("generationConfig").put("temperature", 0);
        String json = objectMapper.writeValueAsString(body);

        for (int attempt = 1; ; attempt++) {
            credentials.refreshIfExpired();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 200) {
                return extractText(response.body());
            }

            // 429 (hết quota tạm thời) và lỗi 5xx → chờ rồi thử lại
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt >= MAX_ATTEMPTS) {
                throw new IOException("Gemini HTTP " + status + ": " + abbreviate(response.body()));
            }
            long waitMs = Math.min(60_000L, 5_000L * (1L << (attempt - 1)));
            log.warn("Gemini HTTP {} (attempt {}/{}), retrying in {}s", status, attempt, MAX_ATTEMPTS, waitMs / 1000);
            Thread.sleep(waitMs);
        }
    }

    private String extractText(String responseBody) throws IOException {
        JsonNode candidate = objectMapper.readTree(responseBody).path("candidates").path(0);
        String finishReason = candidate.path("finishReason").asText();
        if (!"STOP".equals(finishReason) && !"MAX_TOKENS".equals(finishReason)) {
            throw new IOException("Gemini không trả kết quả, finishReason=" + finishReason);
        }
        if ("MAX_TOKENS".equals(finishReason)) {
            log.warn("Gemini output truncated (MAX_TOKENS)");
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) {
                sb.append(part.path("text").asText());
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
