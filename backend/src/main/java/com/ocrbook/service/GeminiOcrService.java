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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            - Mọi biểu thức toán (phân số, hỗn số, lũy thừa, căn, phép tính có phân số...) viết bằng LaTeX: inline dùng $...$, công thức đứng riêng một dòng dùng $$...$$. Ví dụ phân số: $\\frac{5}{7}$, hỗn số: $2\\frac{1}{3}$. Số mũ/chỉ số dưới LUÔN đặt trong ngoặc nhọn, ví dụ $2hm^{2}\\ 15m^{2}$, $x^{10}$ (không viết hm^215m^2).
            - Tiêu đề lớn dùng #, ##; chữ in đậm dùng **...**; giữ nguyên các nhãn như "Bài 1:", "a)", "b)".
            - Bảng (có kẻ ô) viết bằng HTML: <table><tr><td>...</td></tr></table>, KHÔNG dùng bảng Markdown. Chép đúng số hàng, số cột như trong ảnh; ô gộp nhiều cột dùng colspan, ô gộp nhiều hàng dùng rowspan (không thêm ô trống thay cho ô gộp); trong một ô có nhiều dòng thì ngăn cách bằng <br>; công thức trong ô vẫn viết $...$.
            - Mỗi dòng trên trang là một dòng riêng trong Markdown (dòng chữ dài bị ngắt do hết khổ giấy thì nối lại thành một dòng).
            - Dòng được căn GIỮA trang (tiêu đề giữa trang, dòng chữ/công thức đứng giữa): thêm [[GIUA]] vào đầu nội dung dòng (sau các dấu # nếu là tiêu đề). Ví dụ: ## [[GIUA]]BÀI 8: ĐẾM SỐ CÁC PHÂN SỐ
            - Nhiều mục xếp thành cột trên CÙNG một hàng của trang (ví dụ "a) ...   b) ..." hoặc "a) ... b) ... c) ... d) ..."): viết trên CÙNG một dòng, ngăn cách các mục bằng [[TAB]]. Không tách mỗi mục ra một dòng.
            - Ô vuông trống để điền (□): viết [[O]], kể cả khi nằm giữa hai biểu thức toán, ví dụ: $\\frac{1}{2}$ [[O]] $\\frac{3}{4}$.
            - BỎ HẲN phần header và footer của trang (dòng tên đơn vị/website/hotline/số điện thoại ở đầu trang, dòng quảng cáo và số trang ở cuối trang, logo). Không chép các dòng đó.
            - Hình vẽ (hình học, sơ đồ, đoạn thẳng, hình minh họa, biểu đồ...), KỂ CẢ sơ đồ vẽ bằng ký tự hoặc có chữ màu/ngoặc/mũi tên (ví dụ sơ đồ đoạn thẳng "Tử số: |=====|===|", sơ đồ tóm tắt có ngoặc nhọn và nhãn): KHÔNG mô tả hay chép lại bằng chữ, mà đặt đúng tại vị trí của hình một dòng riêng dạng [[HINH: ymin, xmin, ymax, xmax]] — là khung bao quanh TOÀN BỘ hình kể cả các chữ cái/nhãn/số đo ghi trên hình, tọa độ chuẩn hóa 0-1000 theo kích thước ảnh trang. Các hình con đứng sát nhau (ví dụ hình a, b, c cạnh nhau) thì gộp chung một khung. Chữ, nhãn nằm trong khung hình thì KHÔNG chép lại ra ngoài.
            - Chỉ trả về Markdown, không bọc trong ```, không dùng đường kẻ ngang ---.
            """;

    private static final int MAX_ATTEMPTS = 6;

    /** Marker Gemini đặt tại vị trí hình vẽ: [[HINH: ymin, xmin, ymax, xmax]] (chuẩn hoá 0-1000). */
    private static final Pattern FIGURE_MARKER = Pattern.compile(
            "\\[\\[\\s*HINH\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*]]");

    /** Nới khung hình thêm một chút (theo thang 0-1000) để không cắt mất nét/nhãn sát mép. */
    private static final int FIGURE_PADDING = 4;

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
                pages.add(embedFigures(callGemini(png.toByteArray()).strip(), image));
            }
        }
        return String.join("\n\n", pages);
    }

    /**
     * Thay mỗi marker [[HINH: ...]] bằng ảnh cắt từ ảnh trang, nhúng thẳng vào Markdown (data URI)
     * để kết quả OCR lưu trong DB tự đủ, tạo lại file Word lúc nào cũng được.
     */
    private String embedFigures(String markdown, BufferedImage page) {
        Matcher m = FIGURE_MARKER.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                replacement = "\n\n" + cropFigure(page,
                        Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                        Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))) + "\n\n";
            } catch (Exception e) {
                log.warn("Cannot crop figure {}: {}", m.group(), e.toString());
                replacement = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String cropFigure(BufferedImage page, double ymin, double xmin, double ymax, double xmax) throws IOException {
        int w = page.getWidth();
        int h = page.getHeight();
        int x0 = clamp((int) Math.floor((Math.min(xmin, xmax) - FIGURE_PADDING) * w / 1000), 0, w - 1);
        int y0 = clamp((int) Math.floor((Math.min(ymin, ymax) - FIGURE_PADDING) * h / 1000), 0, h - 1);
        int x1 = clamp((int) Math.ceil((Math.max(xmin, xmax) + FIGURE_PADDING) * w / 1000), x0 + 1, w);
        int y1 = clamp((int) Math.ceil((Math.max(ymin, ymax) + FIGURE_PADDING) * h / 1000), y0 + 1, h);

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(page.getSubimage(x0, y0, x1 - x0, y1 - y0), "png", png);

        // Giữ đúng kích thước in như trên trang gốc (pandoc mặc định coi ảnh là 96 dpi)
        double widthCm = (x1 - x0) * 2.54 / dpi;
        String image = String.format(Locale.ROOT, "![](data:image/png;base64,%s){width=%.2fcm}",
                Base64.getEncoder().encodeToString(png.toByteArray()), widthCm);

        // Căn lề theo vị trí hình trên trang gốc; style này được WordGeneratorService gán căn lề trong Word
        String style = figureStyle((double) x0 / w, 1 - (double) x1 / w);
        return style == null ? image : ":::: {custom-style=\"" + style + "\"}\n" + image + "\n::::";
    }

    /** Hai khoảng trống trái/phải chênh nhau không quá ngưỡng này (tỉ lệ bề rộng trang) thì coi là hình căn giữa. */
    private static final double CENTER_TOLERANCE = 0.06;

    private static String figureStyle(double leftGap, double rightGap) {
        if (Math.abs(leftGap - rightGap) <= CENTER_TOLERANCE) {
            return WordGeneratorService.FIGURE_CENTER_STYLE;
        }
        return rightGap < leftGap ? WordGeneratorService.FIGURE_RIGHT_STYLE : null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
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
