package com.rfp.service

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream

class UnsupportedFileTypeException(type: String) : RuntimeException("Unsupported file type: $type")

@Service
class DocumentParsingService {
    fun extractText(bytes: ByteArray, fileType: String): String = when (fileType.lowercase()) {
        "pdf" -> parsePdf(bytes)
        "docx", "doc" -> parseWord(bytes)
        "xlsx", "xls" -> parseExcel(bytes)
        else -> throw UnsupportedFileTypeException(fileType)
    }

    private fun parsePdf(bytes: ByteArray): String =
        // Form feed after each page lets CatalogChunker keep pages whole.
        Loader.loadPDF(bytes).use { PDFTextStripper().apply { pageEnd = "\u000C" }.getText(it) }

    private fun parseWord(bytes: ByteArray): String =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.paragraphs.joinToString("\n") { it.text }
        }

    private fun parseExcel(bytes: ByteArray): String =
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            buildString {
                wb.forEach { sheet ->
                    sheet.forEach { row ->
                        row.forEach { cell -> append(cell.toString()).append('\t') }
                        append('\n')
                    }
                }
            }
        }
}
