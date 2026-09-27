package com.ocrbook.service;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabs;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Chỉnh file .docx pandoc vừa tạo cho giống trang sách: khổ A4, font Times New Roman, tiêu đề đậm xanh đậm,
 * bảng có kẻ ô, và xử lý các token {@link BookMarkdown} để lại (căn giữa, xếp cột).
 */
final class DocxLayout {

    private static final String FONT = "Times New Roman";
    private static final String HEADING_COLOR = "1F3864";

    // A4, lề 2cm (đơn vị twip)
    private static final int PAGE_WIDTH = 11906;
    private static final int PAGE_HEIGHT = 16838;
    private static final int MARGIN = 1134;
    private static final int TEXT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    private DocxLayout() {
    }

    static void apply(Path docx) throws IOException {
        XWPFDocument document;
        try (var in = Files.newInputStream(docx)) {
            document = new XWPFDocument(in);
        }
        try (document) {
            setPage(document);
            setStyles(document);
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                applyTokens(paragraph);
            }
            for (XWPFTable table : document.getTables()) {
                formatTable(table);
            }
            try (var out = Files.newOutputStream(docx)) {
                document.write(out);
            }
        }
    }

    private static void setPage(XWPFDocument document) {
        CTBody body = document.getDocument().getBody();
        CTSectPr sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz size = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        size.setW(BigInteger.valueOf(PAGE_WIDTH));
        size.setH(BigInteger.valueOf(PAGE_HEIGHT));
        CTPageMar mar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        mar.setTop(BigInteger.valueOf(MARGIN));
        mar.setBottom(BigInteger.valueOf(MARGIN));
        mar.setLeft(BigInteger.valueOf(MARGIN));
        mar.setRight(BigInteger.valueOf(MARGIN));
    }

    private static void setStyles(XWPFDocument document) {
        XWPFStyles styles = document.getStyles();
        if (styles == null) {
            return;
        }
        for (String id : List.of("Normal", "BodyText", "FirstParagraph", "Compact")) {
            XWPFStyle style = styles.getStyle(id);
            if (style != null) {
                setFont(style.getCTStyle(), 26, false, null);
                // Mỗi dòng sách là một đoạn → khoảng cách nhỏ giữa các đoạn, đủ chỗ cho phân số
                setSpacing(style.getCTStyle(), 0, 100);
            }
        }
        int[] headingSizes = {32, 28, 26, 26, 26, 26};
        for (int level = 1; level <= headingSizes.length; level++) {
            XWPFStyle style = styles.getStyle("Heading" + level);
            if (style != null) {
                setFont(style.getCTStyle(), headingSizes[level - 1], true, HEADING_COLOR);
                setSpacing(style.getCTStyle(), 200, 120);
            }
        }
        // Hình căn giữa / căn phải (custom-style do GeminiOcrService gán)
        setAlignment(styles.getStyleWithName(WordGeneratorService.FIGURE_CENTER_STYLE), STJc.CENTER);
        setAlignment(styles.getStyleWithName(WordGeneratorService.FIGURE_RIGHT_STYLE), STJc.RIGHT);
        XWPFStyle title = styles.getStyle("Title");
        if (title != null) {
            setFont(title.getCTStyle(), 36, true, HEADING_COLOR);
        }
    }

    private static void setAlignment(XWPFStyle style, STJc.Enum alignment) {
        if (style == null) {
            return;
        }
        CTPPrGeneral pPr = style.getCTStyle().isSetPPr() ? style.getCTStyle().getPPr() : style.getCTStyle().addNewPPr();
        (pPr.isSetJc() ? pPr.getJc() : pPr.addNewJc()).setVal(alignment);
    }

    private static void setFont(CTStyle style, int halfPoints, boolean bold, String color) {
        CTRPr rPr = style.isSetRPr() ? style.getRPr() : style.addNewRPr();
        CTFonts fonts = rPr.sizeOfRFontsArray() > 0 ? rPr.getRFontsArray(0) : rPr.addNewRFonts();
        // Bỏ font theo theme (Aptos...) để dùng đúng font sách
        if (fonts.isSetAsciiTheme()) fonts.unsetAsciiTheme();
        if (fonts.isSetHAnsiTheme()) fonts.unsetHAnsiTheme();
        if (fonts.isSetCstheme()) fonts.unsetCstheme();
        if (fonts.isSetEastAsiaTheme()) fonts.unsetEastAsiaTheme();
        fonts.setAscii(FONT);
        fonts.setHAnsi(FONT);
        fonts.setCs(FONT);
        fonts.setEastAsia(FONT);

        (rPr.sizeOfSzArray() > 0 ? rPr.getSzArray(0) : rPr.addNewSz()).setVal(BigInteger.valueOf(halfPoints));
        (rPr.sizeOfSzCsArray() > 0 ? rPr.getSzCsArray(0) : rPr.addNewSzCs()).setVal(BigInteger.valueOf(halfPoints));
        if (bold && rPr.sizeOfBArray() == 0) {
            rPr.addNewB();
        }
        if (color != null) {
            (rPr.sizeOfColorArray() > 0 ? rPr.getColorArray(0) : rPr.addNewColor()).setVal(color);
            // Tiêu đề pandoc mặc định có font nhẹ/khác màu theo theme → bỏ themeColor để màu trên có hiệu lực
            if (rPr.getColorArray(0).isSetThemeColor()) rPr.getColorArray(0).unsetThemeColor();
            if (rPr.getColorArray(0).isSetThemeShade()) rPr.getColorArray(0).unsetThemeShade();
        }
    }

    private static void setSpacing(CTStyle style, int before, int after) {
        CTPPrGeneral pPr = style.isSetPPr() ? style.getPPr() : style.addNewPPr();
        CTSpacing spacing = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        spacing.setBefore(BigInteger.valueOf(before));
        spacing.setAfter(BigInteger.valueOf(after));
    }

    /** Đọc và xoá token đầu đoạn ⟦GIUA⟧ / ⟦COTn⟧, áp căn giữa hoặc tab stop chia đều cột. */
    private static void applyTokens(XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (!text.contains("⟦")) {
            return;
        }
        if (text.contains(BookMarkdown.CENTER_TOKEN)) {
            paragraph.setAlignment(ParagraphAlignment.CENTER);
        }
        Matcher m = BookMarkdown.COLUMNS_TOKEN.matcher(text);
        if (m.find()) {
            setColumnTabs(paragraph, Integer.parseInt(m.group(1)));
        }
        for (XWPFRun run : paragraph.getRuns()) {
            String t = run.getText(0);
            if (t != null && t.contains("⟦")) {
                run.setText(BookMarkdown.COLUMNS_TOKEN.matcher(t.replace(BookMarkdown.CENTER_TOKEN, "")).replaceAll(""), 0);
            }
        }
    }

    private static void setColumnTabs(XWPFParagraph paragraph, int columns) {
        CTPPr pPr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTTabs tabs = pPr.isSetTabs() ? pPr.getTabs() : pPr.addNewTabs();
        for (int i = 1; i < columns; i++) {
            CTTabStop tab = tabs.addNewTab();
            tab.setVal(STTabJc.LEFT);
            tab.setPos(BigInteger.valueOf((long) TEXT_WIDTH * i / columns));
        }
    }

    /** Bảng trong sách có kẻ ô đầy đủ; style bảng mặc định của pandoc không có viền. */
    private static void formatTable(XWPFTable table) {
        table.setWidth("100%");
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph p : cell.getParagraphs()) {
                    applyTokens(p);
                }
            }
        }
    }
}
