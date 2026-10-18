package com.lingxi.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class DocumentLoader {
    private final TextTools textTools;
    private static final List<String> SUPPORTED = List.of(".pdf", ".docx", ".md", ".markdown", ".txt");

    public DocumentLoader(TextTools textTools) {
        this.textTools = textTools;
    }

    public static boolean isSupported(String filename) {
        return SUPPORTED.stream().anyMatch(ext -> filename.toLowerCase(Locale.ROOT).endsWith(ext));
    }

    public LoadedDocument load(Path path, String originalName) {
        String lower = originalName.toLowerCase(Locale.ROOT);
        if (!isSupported(lower)) {
            throw new DocumentLoadException("不支持的文件格式，请上传 PDF / DOCX / Markdown / TXT");
        }
        if (lower.endsWith(".pdf")) return loadPdf(path, originalName);
        if (lower.endsWith(".docx")) return loadDocx(path, originalName);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return loadMarkdown(path, originalName);
        return loadText(path, originalName);
    }

    private LoadedDocument loadPdf(Path path, String originalName) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            List<Block> blocks = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = textTools.clean(stripper.getText(document));
                if (!text.isBlank()) {
                    blocks.add(new Block(text, "第 " + page + " 页", page));
                }
            }
            if (blocks.isEmpty()) {
                throw new DocumentLoadException("未从 PDF 中提取到文本，可能是扫描版 PDF，请先进行 OCR");
            }
            String title = originalName.replaceFirst("(?i)\\.pdf$", "");
            try {
                if (document.getDocumentInformation() != null
                        && document.getDocumentInformation().getTitle() != null
                        && !document.getDocumentInformation().getTitle().isBlank()) {
                    title = document.getDocumentInformation().getTitle();
                }
            } catch (Exception ignored) {
            }
            return new LoadedDocument(truncate(title, 240), blocks, document.getNumberOfPages());
        } catch (DocumentLoadException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DocumentLoadException("PDF 解析失败：" + exc.getMessage());
        }
    }

    private LoadedDocument loadDocx(Path path, String originalName) {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(path))) {
            List<Block> blocks = new ArrayList<>();
            String title = originalName.replaceFirst("(?i)\\.docx$", "");
            String section = "正文";
            StringBuilder body = new StringBuilder();
            boolean titleSet = false;

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = textTools.clean(paragraph.getText());
                if (text.isBlank()) continue;
                String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase(Locale.ROOT);
                boolean heading = style.contains("heading") || style.contains("标题");
                if (heading) {
                    flushBlock(blocks, body, section);
                    section = text;
                    if (!titleSet) {
                        title = text;
                        titleSet = true;
                    }
                } else {
                    body.append(text).append('\n');
                }
            }
            flushBlock(blocks, body, section);

            int tableIndex = 1;
            for (XWPFTable table : document.getTables()) {
                StringBuilder tableText = new StringBuilder();
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = row.getTableCells().stream()
                            .map(cell -> textTools.clean(cell.getText()).replace('\n', ' '))
                            .toList();
                    if (cells.stream().anyMatch(cell -> !cell.isBlank())) {
                        tableText.append(String.join(" | ", cells)).append('\n');
                    }
                }
                if (!tableText.isEmpty()) blocks.add(new Block(tableText.toString().strip(), "表格 " + tableIndex++, null));
            }
            if (blocks.isEmpty()) throw new DocumentLoadException("文档内容为空");
            return new LoadedDocument(truncate(title, 240), blocks, 1);
        } catch (DocumentLoadException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DocumentLoadException("DOCX 解析失败：" + exc.getMessage());
        }
    }

    private void flushBlock(List<Block> blocks, StringBuilder body, String section) {
        String text = textTools.clean(body.toString());
        if (!text.isBlank()) blocks.add(new Block(text, section, null));
        body.setLength(0);
    }

    private LoadedDocument loadMarkdown(Path path, String originalName) {
        String text = textTools.clean(readText(path));
        if (text.isBlank()) throw new DocumentLoadException("文档内容为空");
        String title = originalName.replaceFirst("(?i)\\.(md|markdown)$", "");
        for (String line : text.lines().toList()) {
            if (line.startsWith("# ")) {
                title = line.substring(2).strip();
                break;
            }
        }
        String withoutFrontMatter = text.replaceFirst("(?s)^---\\n.*?\\n---\\n", "");
        List<Block> blocks = new ArrayList<>();
        for (TextTools.Section section : textTools.splitMarkdownSections(withoutFrontMatter)) {
            blocks.add(new Block(section.text(), section.title().isBlank() ? "正文" : section.title(), null));
        }
        if (blocks.isEmpty()) blocks.add(new Block(text, "正文", null));
        return new LoadedDocument(truncate(title, 240), blocks, 1);
    }

    private LoadedDocument loadText(Path path, String originalName) {
        String text = textTools.clean(readText(path));
        if (text.isBlank()) throw new DocumentLoadException("文档内容为空");
        String title = originalName.replaceFirst("(?i)\\.txt$", "");
        return new LoadedDocument(truncate(title, 240), List.of(new Block(text, "正文", null)), 1);
    }

    private String readText(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exc) {
            throw new DocumentLoadException("读取文件失败：" + exc.getMessage());
        }
        for (String encoding : List.of("UTF-8", "GB18030", "GBK", "ISO-8859-1")) {
            try {
                return new String(bytes, Charset.forName(encoding));
            } catch (Exception ignored) {
            }
        }
        throw new DocumentLoadException("无法识别文本编码，请使用 UTF-8 或 GBK");
    }

    private static String truncate(String text, int max) {
        if (text == null || text.isBlank()) return "未命名文档";
        return text.length() > max ? text.substring(0, max) : text;
    }

    public record Block(String text, String section, Integer page) {
    }

    public record LoadedDocument(String title, List<Block> blocks, int pageCount) {
        public long charCount() {
            return blocks.stream().mapToLong(block -> block.text().length()).sum();
        }
    }

    public static class DocumentLoadException extends RuntimeException {
        public DocumentLoadException(String message) {
            super(message);
        }
    }
}
