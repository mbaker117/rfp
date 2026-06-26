package com.rfp.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class DocumentParsingServiceTest {
    private val service = DocumentParsingService()

    @Test
    fun `extractText from PDF returns text content`() {
        val pdf = PDDocument().apply {
            val page = PDPage()
            addPage(page)
            PDPageContentStream(this, page).apply {
                beginText()
                setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                newLineAtOffset(100f, 700f)
                showText("Instrument ABC model X")
                endText()
                close()
            }
        }
        val out = ByteArrayOutputStream()
        pdf.save(out); pdf.close()
        val text = service.extractText(out.toByteArray(), "pdf")
        assertTrue(text.contains("Instrument ABC model X"))
    }

    @Test
    fun `extractText from DOCX returns text content`() {
        val doc = XWPFDocument()
        doc.createParagraph().createRun().setText("Oscilloscope 100MHz")
        val out = ByteArrayOutputStream(); doc.write(out); doc.close()
        val text = service.extractText(out.toByteArray(), "docx")
        assertTrue(text.contains("Oscilloscope 100MHz"))
    }

    @Test
    fun `extractText from XLSX returns cell values`() {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet()
        sheet.createRow(0).createCell(0).setCellValue("Signal Generator 1GHz")
        val out = ByteArrayOutputStream(); wb.write(out); wb.close()
        val text = service.extractText(out.toByteArray(), "xlsx")
        assertTrue(text.contains("Signal Generator 1GHz"))
    }

    @Test
    fun `extractText throws for unsupported type`() {
        assertThrows(UnsupportedFileTypeException::class.java) {
            service.extractText("hello".toByteArray(), "txt")
        }
    }
}
