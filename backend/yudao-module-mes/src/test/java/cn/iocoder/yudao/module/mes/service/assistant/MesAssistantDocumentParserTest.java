package cn.iocoder.yudao.module.mes.service.assistant;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class MesAssistantDocumentParserTest {
    private final MesAssistantDocumentParser parser = new MesAssistantDocumentParser();

    @Test void markdownChunksAreBoundedAndTraceBackToExactSourceOffsetsAndHashes() {
        String source = "# 设备点检\n\n发现异常必须停机并通知维修。\n\n" + "点检记录应如实填写。".repeat(160) + "\n# 质量放行\n首件检验合格后方可放行。";
        var parsed = parser.parse("点检.md", source.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.chunks().size() > 2);
        assertTrue(parsed.chunks().stream().anyMatch(chunk -> chunk.heading().equals("质量放行")));
        for (var chunk : parsed.chunks()) {
            assertFalse(chunk.text().isBlank()); assertTrue(chunk.text().length() <= 900);
            assertEquals(chunk.text(), parsed.text().substring(chunk.startOffset(), chunk.endOffset()));
            assertEquals(MesAssistantDocumentParser.sha256(chunk.text().getBytes(StandardCharsets.UTF_8)), chunk.hash());
        }
    }

    @Test void docxIsReallyParsedAndExternalEntitiesAreRejected() throws Exception {
        byte[] document = docx("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>工艺确认后开机</w:t></w:r></w:p></w:body></w:document>");
        assertEquals("工艺确认后开机\n", parser.parse("工艺.docx", document).text());
        byte[] hostile = docx("<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///not-a-real-secret'>]><document><t>&x;</t></document>");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("bad.docx", hostile));
    }

    @Test void pdfTextKeepsRealPageNumbersAndScannedPagesAreNotInvented() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            for (String line : new String[]{"Inspection must finish before release.", "Stop the equipment when an alarm occurs."}) {
                var page = new PDPage(); pdf.addPage(page);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700); stream.showText(line); stream.endText();
                }
            }
            pdf.save(output);
        }
        var parsed = parser.parse("equipment.pdf", output.toByteArray());
        assertEquals(2, parsed.chunks().size());
        assertEquals(1, parsed.chunks().get(0).pageNumber()); assertEquals(2, parsed.chunks().get(1).pageNumber());
        assertTrue(parsed.chunks().get(1).text().contains("alarm"));
        for (var chunk : parsed.chunks()) assertEquals(chunk.text(), parsed.text().substring(chunk.startOffset(), chunk.endOffset()));
        var blank = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(blank); }
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("scan.pdf", blank.toByteArray()));
    }

    @Test void rejectsMalformedUnsupportedEmptyAndOversizedDocuments() {
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("bad.txt", new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("empty.txt", new byte[0]));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("large.txt", new byte[MesAssistantDocumentParser.MAX_FILE_BYTES + 1]));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("script.html", "<script>bad()</script>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("binary.txt", new byte[]{0, 1, 2}));
    }

    @Test void aCompressedNonDocumentEntryCannotBypassTheDecompressionLimit() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("ignored-bomb.bin"));
            for (int index = 0; index < 9 * 1024; index++) zip.write(new byte[1024]);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document><t>should not be reached</t></document>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        assertTrue(output.size() < MesAssistantDocumentParser.MAX_FILE_BYTES);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> parser.parse("bomb.docx", output.toByteArray()));
    }

    private static byte[] docx(String xml) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) { zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        return output.toByteArray();
    }
}
