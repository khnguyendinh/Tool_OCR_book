package com.ocrbook.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chuẩn hoá Markdown do Gemini trả về trước khi đưa vào pandoc, để bố cục Word giống trang sách:
 * mỗi dòng sách là một đoạn, dòng căn giữa, các mục xếp cột, ô vuông trống.
 * Những gì Markdown không diễn tả được thì để lại token, {@link DocxLayout} xử lý sau khi pandoc tạo file.
 */
final class BookMarkdown {

    /** Token đầu đoạn: căn giữa đoạn đó. */
    static final String CENTER_TOKEN = "⟦GIUA⟧";
    /** Token đầu đoạn: đoạn xếp thành N cột bằng tab stop, dạng ⟦COT3⟧. */
    static final String COLUMNS_TOKEN_PREFIX = "⟦COT";
    static final Pattern COLUMNS_TOKEN = Pattern.compile("⟦COT(\\d+)⟧");

    private static final Pattern CENTER_MARKER = Pattern.compile("^(#+\\s*)?\\[\\[\\s*GIUA\\s*]]\\s*");
    private static final Pattern TAB_MARKER = Pattern.compile("\\s*\\[\\[\\s*TAB\\s*]]\\s*");
    /** Kết quả OCR cũ giãn cột bằng nhiều dấu cách thay vì [[TAB]]. */
    private static final Pattern WIDE_GAP = Pattern.compile("(?<=\\S) {4,}(?=\\S)");
    private static final Pattern BOX_MARKER = Pattern.compile("\\[\\[\\s*O\\s*]]|\\$\\s*\\\\(?:square|Box)\\s*\\$");
    private static final Pattern UNDERLINE = Pattern.compile("<u>(.*?)</u>");
    private static final Pattern BOX_IN_MATH = Pattern.compile("\\\\(?:square|Box)(?![a-zA-Z])");

    /** Tab thật của Word (Markdown không có). */
    private static final String RAW_TAB = "`<w:r><w:tab/></w:r>`{=openxml}";
    /** Ô vuông trống cỡ lớn như trong sách. */
    private static final String RAW_BOX = "`<w:r><w:rPr><w:sz w:val=\"36\"/></w:rPr><w:t xml:space=\"preserve\"> ☐ </w:t></w:r>`{=openxml}";

    private BookMarkdown() {
    }

    private static final Pattern HTML_TABLE = Pattern.compile("(?is)<table\\b.*?</table>");
    private static final Pattern TABLE_PLACEHOLDER = Pattern.compile("^⟦BANG(\\d+)⟧$");

    /**
     * @param htmlTableConverter chuyển một bảng HTML (có rowspan/colspan) thành grid table Markdown của pandoc,
     *                           vì bảng | a | b | không gộp ô được còn pandoc bỏ qua HTML khi xuất Word
     */
    static String prepare(String markdown, UnaryOperator<String> htmlTableConverter) {
        markdown = fixBareExponents(markdown);
        // Tách bảng HTML ra trước, giữ chỗ bằng placeholder để xử lý dòng không đụng vào
        List<String> tables = new ArrayList<>();
        Matcher tm = HTML_TABLE.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (tm.find()) {
            tm.appendReplacement(sb, Matcher.quoteReplacement("\n⟦BANG" + tables.size() + "⟧\n"));
            tables.add(tm.group());
        }
        tm.appendTail(sb);
        markdown = sb.toString();

        List<String> out = new ArrayList<>();
        boolean inMathBlock = false;
        boolean prevTable = false;

        for (String line : markdown.split("\\r?\\n", -1)) {
            String trimmed = line.strip();

            if (inMathBlock) {
                out.add(line);
                inMathBlock = count(trimmed, "$$") % 2 == 0;
                continue;
            }
            if (trimmed.isEmpty()) {
                continue;
            }

            boolean table = trimmed.startsWith("|");
            // Mỗi dòng sách thành một đoạn riêng (dòng trống ngăn cách), trừ các dòng liền nhau của cùng một bảng
            if (!out.isEmpty() && !(table && prevTable)) {
                out.add("");
            }
            prevTable = table;

            Matcher placeholder = TABLE_PLACEHOLDER.matcher(trimmed);
            if (placeholder.matches()) {
                out.add(htmlTableConverter.apply(tables.get(Integer.parseInt(placeholder.group(1)))).strip());
            } else if (trimmed.startsWith("$$") && count(trimmed, "$$") % 2 == 1) {
                inMathBlock = true;
                out.add(trimmed);
            } else if (table || trimmed.startsWith(":::") || trimmed.startsWith("![")) {
                out.add(trimmed);
            } else {
                out.add(prepareTextLine(trimmed));
            }
        }
        addMissingTableSeparators(out);  // chỉ bảng |...| (kết quả OCR cũ); grid table nằm gọn trong một phần tử
        return String.join("\n", out) + "\n";
    }

    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$");

    /** Gemini đôi khi bỏ dòng | :---: | sau dòng đầu → pandoc không nhận là bảng. Thêm lại (căn giữa như sách). */
    private static void addMissingTableSeparators(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            boolean start = lines.get(i).startsWith("|") && (i == 0 || !lines.get(i - 1).startsWith("|"));
            if (!start) {
                continue;
            }
            boolean hasSeparator = i + 1 < lines.size() && TABLE_SEPARATOR.matcher(lines.get(i + 1).strip()).matches();
            if (!hasSeparator) {
                String header = lines.get(i).strip();
                int columns = header.replaceAll("^\\||\\|$", "").split("\\|", -1).length;
                lines.add(i + 1, "|" + " :---: |".repeat(columns));
            }
        }
    }

    private static String prepareTextLine(String line) {
        boolean center = false;
        String headingPrefix = "";
        Matcher m = CENTER_MARKER.matcher(line);
        if (m.find()) {
            center = true;
            headingPrefix = m.group(1) == null ? "" : m.group(1).strip() + " ";
            line = line.substring(m.end());
        } else if (line.startsWith("#")) {
            int i = line.indexOf(' ');
            if (i > 0 && line.substring(0, i).chars().allMatch(c -> c == '#')) {
                headingPrefix = line.substring(0, i + 1);
                line = line.substring(i + 1).strip();
            }
        }

        line = replaceBoxes(line);
        // <u>...</u> là HTML, pandoc bỏ khi xuất Word → dùng cú pháp gạch chân của pandoc
        line = UNDERLINE.matcher(line).replaceAll("[$1]{.underline}");

        List<String> cells = splitColumns(line);
        if (cells.size() > 1) {
            line = String.join(RAW_TAB, cells.stream().map(BookMarkdown::escapeLeadingDash).toList());
        } else {
            line = escapeLeadingDash(line);
        }

        String tokens = (center ? CENTER_TOKEN : "")
                + (cells.size() > 1 ? COLUMNS_TOKEN_PREFIX + cells.size() + "⟧" : "");
        return headingPrefix + tokens + line;
    }

    /** Số mũ không có ngoặc nhọn: ^ hoặc _ theo sau nhiều chữ số. */
    private static final Pattern BARE_EXPONENT = Pattern.compile("(?<!\\\\)([\\^_])(\\d)(?=\\d)");

    /**
     * Trong LaTeX, "hm^215m^2" nghĩa là hm² 15m² (^ chỉ lấy MỘT ký tự), nhưng pandoc lại đưa cả "215" lên mũ.
     * Thêm ngoặc cho đúng nghĩa LaTeX: ^215 → ^{2}15.
     */
    private static String fixBareExponents(String markdown) {
        return BARE_EXPONENT.matcher(markdown).replaceAll("$1{$2}");
    }

    /** Dấu "-" đầu dòng trong sách là chữ, không phải danh sách → escape để Word không biến thành chấm tròn. */
    private static String escapeLeadingDash(String s) {
        return s.startsWith("- ") ? "\\" + s : s;
    }

    private static String replaceBoxes(String line) {
        line = BOX_MARKER.matcher(line).replaceAll(Matcher.quoteReplacement(RAW_BOX));
        // \square còn nằm trong một công thức lớn hơn → ký tự ô vuông to thay cho ◻ rất nhỏ của Word
        return BOX_IN_MATH.matcher(line).replaceAll(Matcher.quoteReplacement("\\text{☐}"));
    }

    /** Tách các mục xếp cột: theo [[TAB]], hoặc (kết quả cũ) theo khoảng trắng rộng nằm ngoài công thức. */
    private static List<String> splitColumns(String line) {
        List<String> cells = new ArrayList<>();
        if (TAB_MARKER.matcher(line).find()) {
            for (String c : TAB_MARKER.split(line)) {
                if (!c.isBlank()) {
                    cells.add(c.strip());
                }
            }
            return cells;
        }

        // Chỉ xét khoảng trắng ngoài $...$
        StringBuilder current = new StringBuilder();
        String[] segments = line.split("\\$", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                current.append('$');
            }
            if (i % 2 == 1) {
                current.append(segments[i]);
                continue;
            }
            String[] parts = WIDE_GAP.split(segments[i], -1);
            current.append(parts[0]);
            for (int p = 1; p < parts.length; p++) {
                cells.add(current.toString().strip());
                current.setLength(0);
                current.append(parts[p]);
            }
        }
        cells.add(current.toString().strip());
        cells.removeIf(String::isBlank);
        return cells;
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }
}
