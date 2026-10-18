package cn.iocoder.yudao.module.mes.service.assistant;

import org.springframework.stereotype.Component;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** Bounded local parsing; uploaded markup is never executed and external entities are disabled. */
@Component
public class MesAssistantDocumentParser {
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 500_000;
    public record Chunk(int index, String heading, Integer pageNumber, int startOffset, int endOffset, String text, String hash) {}
    public record Parsed(String fileName, String type, String text, List<Chunk> chunks, String hash) {}

    public Parsed parse(String originalName, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw MesAssistantErrors.invalid("文件为空或超过5MB");
        }
        String fileName = originalName == null ? "" : originalName.replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (fileName.isBlank() || fileName.length() > 200) throw MesAssistantErrors.invalid("文件名无效或过长");
        int dot = fileName.lastIndexOf('.');
        String type = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        List<String> pages = new ArrayList<>();
        try {
            switch (type) {
                case "txt", "md", "csv" -> pages.add(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString());
                case "docx" -> pages.add(docx(bytes));
                case "pdf" -> {
                    try (var document = Loader.loadPDF(bytes)) {
                        if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                            throw MesAssistantErrors.invalid("加密或禁止提取文本的PDF无法建立知识索引");
                        if (document.getNumberOfPages() > 300) throw MesAssistantErrors.invalid("PDF最多300页");
                        var stripper = new PDFTextStripper();
                        int extractedLength = 0;
                        for (int page = 1; page <= document.getNumberOfPages(); page++) {
                            stripper.setStartPage(page); stripper.setEndPage(page);
                            String content = stripper.getText(document); extractedLength += content.length();
                            if (extractedLength > MAX_TEXT_CHARS) throw MesAssistantErrors.invalid("解析文本超过50万字符");
                            pages.add(content);
                        }
                    }
                }
                default -> throw MesAssistantErrors.invalid("仅支持UTF-8 TXT、MD、CSV、DOCX与文本型PDF");
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw MesAssistantErrors.invalid("文件无法解析，请检查格式、编码或PDF是否加密");
        }
        StringBuilder text = new StringBuilder();
        List<Chunk> chunks = new ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            String part = pages.get(page).replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
            if (part.indexOf('\0') >= 0 || text.length() + part.length() > MAX_TEXT_CHARS)
                throw MesAssistantErrors.invalid("解析文本异常或超过50万字符");
            if (!text.isEmpty()) text.append('\n');
            int offset = text.length(); text.append(part);
            chunkSection(part, offset, type.equals("pdf") ? page + 1 : null, chunks);
        }
        if (text.toString().isBlank() || chunks.isEmpty())
            throw MesAssistantErrors.invalid("未提取到可检索文本；扫描PDF请先进行OCR");
        if (chunks.size() > 1000) throw MesAssistantErrors.invalid("单文档分块超过1000，请拆分文件");
        return new Parsed(fileName, type, text.toString(), List.copyOf(chunks), sha256(bytes));
    }

    private static String docx(byte[] bytes) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries = 0; int decompressed = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 1000) throw MesAssistantErrors.invalid("DOCX包含过多文件条目");
                byte[] xml = zip.readNBytes(Math.min(8 * 1024 * 1024 + 1, 16 * 1024 * 1024 - decompressed + 1));
                decompressed += xml.length;
                if (xml.length > 8 * 1024 * 1024 || decompressed > 16 * 1024 * 1024)
                    throw MesAssistantErrors.invalid("DOCX解压内容过大");
                if (!entry.getName().equals("word/document.xml")) continue;
                var factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
                var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
                StringBuilder result = new StringBuilder(); boolean inText = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw MesAssistantErrors.invalid("不接受包含DTD的文档");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        inText = reader.getLocalName().equals("t");
                        if (reader.getLocalName().equals("br")) result.append('\n');
                        if (reader.getLocalName().equals("tab")) result.append('\t');
                    } else if (event == XMLStreamConstants.CHARACTERS && inText) result.append(reader.getText());
                    else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (reader.getLocalName().equals("t")) inText = false;
                        if (reader.getLocalName().equals("p")) result.append('\n');
                    }
                    if (result.length() > MAX_TEXT_CHARS) throw MesAssistantErrors.invalid("解析文本超过50万字符");
                }
                reader.close(); return result.toString();
            }
        }
        throw MesAssistantErrors.invalid("DOCX缺少正文内容");
    }

    private static void chunkSection(String text, int baseOffset, Integer pageNumber, List<Chunk> output) {
        String heading = "正文"; int sectionStart = 0; int cursor = 0;
        for (String line : text.split("\n", -1)) {
            if (line.matches("^#{1,6}\\s+.+")) {
                addChunks(text, sectionStart, cursor, heading, baseOffset, pageNumber, output);
                heading = line.replaceFirst("^#{1,6}\\s+", "").strip();
                if (heading.length() > 300) heading = heading.substring(0, 300);
                sectionStart = Math.min(text.length(), cursor + line.length() + 1);
            }
            cursor += line.length() + 1;
        }
        addChunks(text, sectionStart, text.length(), heading, baseOffset, pageNumber, output);
    }

    private static void addChunks(String text, int from, int until, String heading, int baseOffset,
                                  Integer pageNumber, List<Chunk> output) {
        int start = from;
        while (start < until) {
            while (start < until && Character.isWhitespace(text.charAt(start))) start++;
            if (start >= until) break;
            int end = Math.min(until, start + 900);
            if (end < until) {
                int boundary = text.lastIndexOf('\n', end);
                if (boundary > start + 450) end = boundary;
            }
            int boundaryEnd = end;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            String content = text.substring(start, end);
            output.add(new Chunk(output.size(), heading, pageNumber, baseOffset + start, baseOffset + end,
                    content, sha256(content.getBytes(StandardCharsets.UTF_8))));
            if (output.size() > 1000) throw MesAssistantErrors.invalid("单文档分块超过1000，请拆分文件");
            if (boundaryEnd >= until) break;
            start = Math.max(start + 1, end - 120);
        }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
