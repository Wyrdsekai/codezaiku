package org.codezaiku.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real documents, built in the test: a PDF written by PDFBox, office/ebook zips assembled by
 * hand. What these pin is detection BY BYTES (a PDF named .html is still a PDF) and that the
 * text comes out with paragraphs intact.
 */
class DocTextTest {

    private static byte[] zip(Map<String, String> entries) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bos)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    void pdfIsDetectedByMagicAndTextExtracted() throws Exception {
        var bos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Elements lost in subtitle translation");
                cs.newLineAtOffset(0, -20);
                cs.showText("Deference and forms of address are deleted.");
                cs.endText();
            }
            doc.getDocumentInformation().setTitle("Hosaka 2016");
            doc.save(bos);
        }
        // named .html on purpose: the bytes decide, never the name
        DocText.Doc d = DocText.convert(bos.toByteArray(), "https://example.org/paper.html");
        assertEquals("pdf", d.kind());
        assertEquals("Hosaka 2016", d.title());
        assertTrue(d.text().contains("Deference and forms of address"), d.text());
    }

    @Test
    void docxParagraphsSurvive() throws Exception {
        byte[] docx = zip(Map.of(
                "[Content_Types].xml", "<Types/>",
                "docProps/core.xml", "<cp:coreProperties><dc:title>Keigo notes</dc:title></cp:coreProperties>",
                "word/document.xml", "<w:document><w:body><w:p><w:r><w:t>First paragraph &amp; more.</w:t></w:r></w:p>"
                        + "<w:p><w:r><w:t>Second</w:t></w:r><w:tab/><w:r><w:t>tabbed.</w:t></w:r></w:p></w:body></w:document>"));
        DocText.Doc d = DocText.convert(docx, "notes.docx");
        assertEquals("docx", d.kind());
        assertEquals("Keigo notes", d.title());
        assertTrue(d.text().contains("First paragraph & more.\nSecond\ttabbed."), d.text());
    }

    @Test
    void pptxSlidesComeOutInNumericOrder() throws Exception {
        byte[] pptx = zip(Map.of(
                "ppt/slides/slide10.xml", "<p:sld><a:p><a:r><a:t>Tenth slide</a:t></a:r></a:p></p:sld>",
                "ppt/slides/slide2.xml", "<p:sld><a:p><a:r><a:t>Second slide</a:t></a:r></a:p></p:sld>",
                "ppt/slides/slide1.xml", "<p:sld><a:p><a:r><a:t>Title slide</a:t></a:r></a:p></p:sld>"));
        DocText.Doc d = DocText.convert(pptx, "deck.pptx");
        assertEquals("pptx", d.kind());
        int a = d.text().indexOf("Title slide"), b = d.text().indexOf("Second slide"), c = d.text().indexOf("Tenth slide");
        assertTrue(a < b && b < c, "1, 2, 10 — numeric, not lexical: " + d.text());
        assertEquals("Title slide", d.title());
    }

    @Test
    void epubChaptersFollowTheSpine() throws Exception {
        byte[] epub = zip(Map.of(
                "mimetype", "application/epub+zip",
                "OEBPS/content.opf", "<package><metadata><dc:title>Gilgamesh (trans. George)</dc:title></metadata>"
                        + "<manifest><item id=\"c2\" href=\"ch2.xhtml\"/><item id=\"c1\" href=\"ch1.xhtml\"/></manifest>"
                        + "<spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>",
                "OEBPS/ch2.xhtml", "<html><body><p>Tablet II text.</p></body></html>",
                "OEBPS/ch1.xhtml", "<html><body><p>Tablet I text.</p></body></html>"));
        DocText.Doc d = DocText.convert(epub, "gilgamesh.epub");
        assertEquals("epub", d.kind());
        assertEquals("Gilgamesh (trans. George)", d.title());
        assertTrue(d.text().indexOf("Tablet I text") < d.text().indexOf("Tablet II text"), d.text());
    }

    @Test
    void htmlAndPlainTextStillWork() {
        DocText.Doc h = DocText.convert("<html><head><title>T</title></head><body><p>Body here.</p></body></html>"
                .getBytes(StandardCharsets.UTF_8), "https://example.org/x");
        assertEquals("html", h.kind());
        assertEquals("T", h.title());
        assertTrue(h.text().contains("Body here."));
        DocText.Doc t = DocText.convert("just text\nmore".getBytes(StandardCharsets.UTF_8), "notes.txt");
        assertEquals("text", t.kind());
        assertEquals("just text\nmore", t.text());
    }

    @Test
    void aZipThatIsNoDocumentFallsThroughToText() throws Exception {
        byte[] z = zip(Map.of("random/file.xml", "<a>b</a>"));
        assertEquals("text", DocText.convert(z, "thing.zip").kind());
    }
}
