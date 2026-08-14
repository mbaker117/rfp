package com.rfp.service.crawl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import javax.swing.text.rtf.RTFEditorKit

class CatalogDocumentParser(
    private val maxDocumentBytes: Int = 10 * 1024 * 1024,
    private val maxPages: Int = 200,
    private val maxSheets: Int = 100,
    private val maxSections: Int = 2_000,
) {
    init {
        require(maxDocumentBytes > 0)
        require(maxPages > 0)
        require(maxSheets > 0)
        require(maxSections > 0)
    }

    fun parse(bytes: ByteArray, contentType: String, sourceUrl: URI): ParsedDocument {
        if (bytes.size > maxDocumentBytes) reject(DocumentRejectionReason.OVERSIZED)
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val fragments = try {
            when (mediaType) {
                PDF -> parsePdf(bytes, sourceUrl)
                DOCX -> parseDocx(bytes, sourceUrl)
                DOC -> parseDoc(bytes, sourceUrl)
                XLS, XLSX -> parseWorkbook(bytes, sourceUrl)
                TEXT -> parseText(bytes, contentType, sourceUrl)
                HTML, XHTML -> parseHtml(bytes, sourceUrl)
                else -> reject(DocumentRejectionReason.UNSUPPORTED)
            }
        } catch (rejection: CatalogDocumentRejectedException) {
            throw rejection
        } catch (encrypted: InvalidPasswordException) {
            reject(DocumentRejectionReason.ENCRYPTED, encrypted)
        } catch (encrypted: EncryptedDocumentException) {
            reject(DocumentRejectionReason.ENCRYPTED, encrypted)
        } catch (exception: Exception) {
            reject(DocumentRejectionReason.MALFORMED, exception)
        }
        if (fragments.size > maxSections) reject(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED)
        return ParsedDocument(sourceUrl, mediaType, fragments)
    }

    private fun parsePdf(bytes: ByteArray, sourceUrl: URI): List<DocumentFragment> = Loader.loadPDF(bytes).use { document ->
        if (document.isEncrypted) reject(DocumentRejectionReason.ENCRYPTED)
        if (document.numberOfPages > maxPages) reject(DocumentRejectionReason.PAGE_LIMIT_EXCEEDED)
        (1..document.numberOfPages).mapNotNull { page ->
            val text = PDFTextStripper().apply {
                startPage = page
                endPage = page
            }.getText(document).trim()
            text.takeIf(String::isNotEmpty)?.let {
                DocumentFragment(it, DocumentProvenance(sourceUrl, page = page))
            }
        }
    }

    private fun parseDocx(bytes: ByteArray, sourceUrl: URI): List<DocumentFragment> {
        if (isEncryptedOffice(bytes)) reject(DocumentRejectionReason.ENCRYPTED)
        return XWPFDocument(ByteArrayInputStream(bytes)).use { document ->
            val fragments = mutableListOf<DocumentFragment>()
            var section = "Document"
            var sectionText = mutableListOf<String>()
            fun flush() {
                sectionText.joinToString("\n").trim().takeIf(String::isNotEmpty)?.let {
                    fragments += DocumentFragment(it, DocumentProvenance(sourceUrl, section = section))
                }
                sectionText = mutableListOf()
            }
            document.paragraphs.forEachIndexed { index, paragraph ->
                val text = paragraph.text.trim()
                if (text.isEmpty()) return@forEachIndexed
                val isHeading = paragraph.style?.startsWith("Heading", ignoreCase = true) == true ||
                    paragraph.styleID?.startsWith("Heading", ignoreCase = true) == true
                if (isHeading) {
                    flush()
                    section = text
                } else {
                    sectionText += text
                }
                if (index + fragments.size > maxSections) reject(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED)
            }
            document.tables.forEach { table ->
                table.rows.forEach { row ->
                    sectionText += row.tableCells.joinToString("\t") { it.text.trim() }
                }
            }
            flush()
            fragments
        }
    }

    private fun parseDoc(bytes: ByteArray, sourceUrl: URI): List<DocumentFragment> {
        val text = if (bytes.take(5).toByteArray().toString(Charsets.US_ASCII).startsWith("{\\rtf")) {
            val kit = RTFEditorKit()
            val document = kit.createDefaultDocument()
            ByteArrayInputStream(bytes).use { kit.read(it, document, 0) }
            document.getText(0, document.length)
        } else {
            WordExtractor(ByteArrayInputStream(bytes)).use { it.text }
        }.trim()
        return text.takeIf(String::isNotEmpty)?.let {
            listOf(DocumentFragment(it, DocumentProvenance(sourceUrl, section = "Document")))
        }.orEmpty()
    }

    private fun parseWorkbook(bytes: ByteArray, sourceUrl: URI): List<DocumentFragment> =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            if (workbook.numberOfSheets > maxSheets) reject(DocumentRejectionReason.SHEET_LIMIT_EXCEEDED)
            val formatter = DataFormatter(Locale.ROOT)
            (0 until workbook.numberOfSheets).mapNotNull { index ->
                val sheet = workbook.getSheetAt(index)
                val text = buildString {
                    sheet.forEach { row ->
                        val values = row.map { formatter.formatCellValue(it).trim() }
                        if (values.any(String::isNotEmpty)) appendLine(values.joinToString("\t"))
                    }
                }.trim()
                text.takeIf(String::isNotEmpty)?.let {
                    DocumentFragment(it, DocumentProvenance(sourceUrl, sheet = sheet.sheetName))
                }
            }
        }

    private fun parseText(bytes: ByteArray, contentType: String, sourceUrl: URI): List<DocumentFragment> {
        val charset = contentType.substringAfter("charset=", "UTF-8").substringBefore(';').trim()
            .let { runCatching { Charset.forName(it) }.getOrDefault(Charsets.UTF_8) }
        val text = bytes.toString(charset).trim()
        return text.takeIf(String::isNotEmpty)?.let {
            listOf(DocumentFragment(it, DocumentProvenance(sourceUrl, section = "Document")))
        }.orEmpty()
    }

    private fun parseHtml(bytes: ByteArray, sourceUrl: URI): List<DocumentFragment> {
        val document = Jsoup.parse(bytes.toString(Charsets.UTF_8), sourceUrl.toString())
        val headings = document.select("h1, h2, h3, h4, h5, h6")
        if (headings.size > maxSections) reject(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED)
        if (headings.isEmpty()) {
            return document.body().text().trim().takeIf(String::isNotEmpty)?.let {
                listOf(DocumentFragment(it, DocumentProvenance(sourceUrl, section = "Document")))
            }.orEmpty()
        }
        return headings.mapNotNull { heading ->
            val body = buildString {
                var sibling = heading.nextElementSibling()
                while (sibling != null && sibling.tagName() !in HEADING_TAGS) {
                    if (heading.tagName() != "h1" || sibling.tagName() !in CONTAINER_TAGS) appendLine(sibling.text())
                    sibling = sibling.nextElementSibling()
                }
            }.trim()
            val text = listOf(heading.text().trim(), body).filter(String::isNotEmpty).joinToString("\n")
            text.takeIf(String::isNotEmpty)?.let {
                DocumentFragment(it, DocumentProvenance(sourceUrl, section = heading.text().trim()))
            }
        }
    }

    private fun reject(reason: DocumentRejectionReason, cause: Throwable? = null): Nothing =
        throw CatalogDocumentRejectedException(reason, cause)

    private fun isEncryptedOffice(bytes: ByteArray): Boolean {
        if (FileMagic.valueOf(ByteArrayInputStream(bytes)) != FileMagic.OLE2) return false
        return runCatching {
            POIFSFileSystem(ByteArrayInputStream(bytes)).use { fileSystem ->
                fileSystem.root.hasEntry("EncryptedPackage") || fileSystem.root.hasEntry("EncryptionInfo")
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val PDF = "application/pdf"
        const val DOC = "application/msword"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLS = "application/vnd.ms-excel"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val TEXT = "text/plain"
        const val HTML = "text/html"
        const val XHTML = "application/xhtml+xml"
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val CONTAINER_TAGS = setOf("section", "article", "main", "nav", "aside")
    }
}
