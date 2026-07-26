package com.rfp.service

import com.rfp.repository.MatchResultRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.repository.TenderRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

@Service
class ReportService(
    private val tenderRepo: TenderRepository,
    private val tenderLineRepo: TenderLineRepository,
    private val matchResultRepo: MatchResultRepository
) {
    fun exportXlsx(tenderId: Long): ByteArray {
        tenderRepo.findById(tenderId).orElseThrow { NoSuchElementException("Tender $tenderId not found") }
        val results = matchResultRepo.findByLineTenderId(tenderId)
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Report")
            val header = sheet.createRow(0)
            listOf("Line", "Description", "Qty", "Matched Product", "MPN",
                "Match Type", "Score", "Status", "Price", "Currency", "Alternatives Count")
                .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
            results.forEachIndexed { idx, r ->
                val row = sheet.createRow(idx + 1)
                row.createCell(0).setCellValue(r.line.lineNo ?: (idx + 1).toString())
                row.createCell(1).setCellValue(r.line.description ?: r.line.rawText)
                row.createCell(2).setCellValue(r.line.qty?.toDouble() ?: 0.0)
                row.createCell(3).setCellValue(r.product?.name ?: "")
                row.createCell(4).setCellValue(r.product?.mpn ?: "")
                row.createCell(5).setCellValue(r.matchType ?: "")
                row.createCell(6).setCellValue(r.score.toDouble())
                row.createCell(7).setCellValue(r.status)
                // Price column intentionally empty — filled by human
                row.createCell(8)
                row.createCell(9).setCellValue("JOD")
            }
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }

    fun exportPdf(tenderId: Long): ByteArray {
        tenderRepo.findById(tenderId).orElseThrow { NoSuchElementException("Tender $tenderId not found") }
        val results = matchResultRepo.findByLineTenderId(tenderId)
        val doc = PDDocument()
        try {
            val arabicFont = try {
                val stream = javaClass.getResourceAsStream("/fonts/NotoSansArabic-Regular.ttf")
                if (stream != null) PDType0Font.load(doc, stream)
                else PDType1Font(Standard14Fonts.FontName.HELVETICA)
            } catch (e: Exception) { PDType1Font(Standard14Fonts.FontName.HELVETICA) }
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

            var page = PDPage(); doc.addPage(page)
            var stream = PDPageContentStream(doc, page)
            var y = 750f

            stream.beginText(); stream.setFont(bold, 14f)
            stream.newLineAtOffset(50f, y)
            stream.showText("RFP Matching Report #$tenderId"); stream.endText(); y -= 30f

            results.forEach { r ->
                if (y < 60f) {
                    stream.close(); page = PDPage(); doc.addPage(page)
                    stream = PDPageContentStream(doc, page); y = 750f
                }
                stream.beginText(); stream.setFont(arabicFont, 9f)
                stream.newLineAtOffset(50f, y)
                val desc = (r.line.description ?: r.line.rawText).take(40).padEnd(40)
                val matched = (r.product?.name ?: "NOT FOUND").take(30).padEnd(30)
                stream.showText("$desc | $matched | ${r.score}/100 | ${r.status}")
                stream.endText(); y -= 18f
            }
            stream.close()
            val out = ByteArrayOutputStream(); doc.save(out); return out.toByteArray()
        } finally { doc.close() }
    }
}
