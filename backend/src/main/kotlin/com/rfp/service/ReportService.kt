package com.rfp.service

import com.rfp.repository.RequiredInstrumentRepository
import com.rfp.repository.RfpRequestRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

@Service
class ReportService(
    private val rfpRepo: RfpRequestRepository,
    private val reqInstrRepo: RequiredInstrumentRepository
) {
    fun exportXlsx(rfpId: Long): ByteArray {
        rfpRepo.findById(rfpId).orElseThrow { NoSuchElementException("RFP $rfpId not found") }
        val items = reqInstrRepo.findByRfpRequestId(rfpId)
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Report")
            val header = sheet.createRow(0)
            listOf("Required Instrument", "Matched", "Score", "Status", "Price", "Currency", "Manual Link")
                .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
            items.forEachIndexed { idx, item ->
                val row = sheet.createRow(idx + 1)
                row.createCell(0).setCellValue(item.rawText)
                row.createCell(1).setCellValue(item.matchedInstrument?.normalizedName ?: "")
                row.createCell(2).setCellValue(item.matchingScore?.toDouble() ?: 0.0)
                row.createCell(3).setCellValue(item.matchStatus?.name ?: "")
                val priceCell = row.createCell(4)
                item.matchedInstrument?.price?.toDouble()?.let { priceCell.setCellValue(it) }
                row.createCell(5).setCellValue(item.matchedInstrument?.currency ?: "JOD")
                row.createCell(6).setCellValue(item.matchedInstrument?.manualLink ?: "")
            }
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    fun exportPdf(rfpId: Long): ByteArray {
        rfpRepo.findById(rfpId).orElseThrow { NoSuchElementException("RFP $rfpId not found") }
        val items = reqInstrRepo.findByRfpRequestId(rfpId)
        val doc = PDDocument()
        try {
            // Load Arabic-capable font with Latin fallback
            val arabicFont: PDFont = try {
                val fontStream = javaClass.getResourceAsStream("/fonts/NotoSansArabic-Regular.ttf")
                if (fontStream != null) PDType0Font.load(doc, fontStream)
                else PDType1Font(Standard14Fonts.FontName.HELVETICA)
            } catch (e: Exception) {
                PDType1Font(Standard14Fonts.FontName.HELVETICA)
            }
            val font: PDFont = arabicFont
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

            var page = PDPage()
            doc.addPage(page)
            var stream = PDPageContentStream(doc, page)
            var y = 750f

            stream.beginText()
            stream.setFont(bold, 14f)
            stream.newLineAtOffset(50f, y)
            stream.showText("RFP Matching Report #$rfpId")
            stream.endText()
            y -= 30f

            items.forEach { item ->
                if (y < 60f) {
                    stream.close()
                    page = PDPage()
                    doc.addPage(page)
                    stream = PDPageContentStream(doc, page)
                    y = 750f
                }
                stream.beginText()
                stream.setFont(font, 9f)
                stream.newLineAtOffset(50f, y)
                val line = "${item.rawText.take(40).padEnd(40)} | " +
                    "${(item.matchedInstrument?.normalizedName?.take(30) ?: "NOT FOUND").padEnd(30)} | " +
                    "${item.matchingScore ?: 0}/100"
                stream.showText(line)
                stream.endText()
                y -= 18f
            }

            stream.close()
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }
}
