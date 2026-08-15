package com.rfp.service.crawl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xwpf.usermodel.BodyElementType
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.Writer
import java.net.URI
import java.time.Duration
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import javax.swing.text.rtf.RTFEditorKit

class CatalogDocumentParser(
    maxDocumentBytes: Int = 10 * 1024 * 1024,
    maxPages: Int = 200,
    maxSheets: Int = 100,
    maxSections: Int = 2_000,
    maxExpandedBytes: Long = 50L * 1024 * 1024,
    maxArchiveEntryBytes: Long = 16L * 1024 * 1024,
    maxArchiveEntries: Int = 2_000,
    maxTextCharacters: Long = 2_000_000,
    maxRows: Long = 100_000,
    maxCells: Long = 1_000_000,
    maxParagraphs: Long = 100_000,
    maxTableRows: Long = 100_000,
    maxXmlDepth: Int = 128,
    maxXmlElements: Long = 1_000_000,
    maxRuns: Long = 1_000_000,
    maximumDuration: Duration = Duration.ofSeconds(20),
    workerMaxHeapMegabytes: Int = 128,
    maxWorkerOutputBytes: Long = 16L * 1024 * 1024,
    workerAdmissionTimeout: Duration = Duration.ofSeconds(2),
) {
    private val worker = CatalogDocumentWorkerClient(
        settings = CatalogDocumentParserSettings(
            maxDocumentBytes,
            maxPages,
            maxSheets,
            maxSections,
            maxExpandedBytes,
            maxArchiveEntryBytes,
            maxArchiveEntries,
            maxTextCharacters,
            maxRows,
            maxCells,
            maxParagraphs,
            maxTableRows,
            maxXmlDepth,
            maxXmlElements,
            maxRuns,
        ),
        maximumDuration = maximumDuration,
        workerMaxHeapMegabytes = workerMaxHeapMegabytes,
        maxWorkerOutputBytes = maxWorkerOutputBytes,
        admissionTimeout = workerAdmissionTimeout,
    )

    fun parse(bytes: ByteArray, contentType: String, sourceUrl: URI): ParsedDocument =
        worker.parse(bytes, contentType, sourceUrl)

    fun parse(bytes: ByteArray, contentType: String, document: DiscoveredDocument): ParsedDocument =
        worker.parse(bytes, contentType, document.uri, document.linkedProductIdentity)
}

internal class CatalogDocumentParserCore(
    private val settings: CatalogDocumentParserSettings,
    private val nanoTimeSource: NanoTimeSource = SystemNanoTimeSource,
) {
    private val maxDocumentBytes = settings.maxDocumentBytes
    private val maxPages = settings.maxPages
    private val maxSheets = settings.maxSheets
    private val maxSections = settings.maxSections

    fun parse(
        bytes: ByteArray,
        contentType: String,
        sourceUrl: URI,
        maximumDuration: Duration,
    ): ParsedDocument {
        if (bytes.size > maxDocumentBytes) reject(DocumentRejectionReason.OVERSIZED)
        val deadline = DeadlineBudget.start(maximumDuration, nanoTimeSource)
        val limits = DocumentResourceLimits(
            settings.maxExpandedBytes,
            settings.maxArchiveEntryBytes,
            settings.maxArchiveEntries,
            settings.maxTextCharacters,
            settings.maxRows,
            settings.maxCells,
            settings.maxParagraphs,
            settings.maxTableRows,
            settings.maxXmlDepth,
            settings.maxXmlElements,
            settings.maxRuns,
        )
        return try {
            val format = CatalogDocumentPreflight(limits, deadline).inspect(bytes, contentType, sourceUrl)
            val budget = ParseWorkBudget(limits, deadline)
            val parsed = when (format) {
                CatalogDocumentFormat.PDF -> parsePdf(bytes, sourceUrl, budget)
                CatalogDocumentFormat.DOCX -> parseDocx(bytes, sourceUrl, budget)
                CatalogDocumentFormat.DOC -> parseDoc(bytes, sourceUrl, budget)
                CatalogDocumentFormat.XLS, CatalogDocumentFormat.XLSX -> parseWorkbook(bytes, sourceUrl, budget)
                CatalogDocumentFormat.TEXT -> parseText(bytes, contentType, sourceUrl, budget)
                CatalogDocumentFormat.HTML, CatalogDocumentFormat.XHTML ->
                    parseHtml(bytes, contentType, sourceUrl, budget)
            }
            budget.checkTime()
            ParsedDocument(sourceUrl, format.mediaType, parsed)
        } catch (rejection: CatalogDocumentRejectedException) {
            throw rejection
        } catch (encrypted: InvalidPasswordException) {
            reject(DocumentRejectionReason.ENCRYPTED, encrypted)
        } catch (encrypted: EncryptedDocumentException) {
            reject(DocumentRejectionReason.ENCRYPTED, encrypted)
        } catch (exception: Exception) {
            reject(DocumentRejectionReason.MALFORMED, exception)
        }
    }

    private fun parsePdf(
        bytes: ByteArray,
        sourceUrl: URI,
        budget: ParseWorkBudget,
    ): List<DocumentFragment> = Loader.loadPDF(bytes).use { document ->
        budget.checkTime()
        if (document.isEncrypted) reject(DocumentRejectionReason.ENCRYPTED)
        if (document.numberOfPages > maxPages) reject(DocumentRejectionReason.PAGE_LIMIT_EXCEEDED)
        inspectPdfStreams(document, budget)
        (1..document.numberOfPages).mapNotNull { page ->
            budget.checkTime()
            val writer = BoundedTextWriter(budget)
            PDFTextStripper().apply {
                startPage = page
                endPage = page
            }.writeText(document, writer)
            val text = writer.toString().trim()
            text.takeIf(String::isNotEmpty)?.let {
                DocumentFragment(it, DocumentProvenance(sourceUrl, page = page))
            }
        }
    }

    private fun inspectPdfStreams(document: PDDocument, budget: ParseWorkBudget) {
        val pending = ArrayDeque<COSBase>()
        pending.add(document.document.trailer)
        document.document.xrefTable.keys.forEach { key -> pending.add(document.document.getObjectFromPool(key)) }
        val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val buffer = ByteArray(8192)
        while (pending.isNotEmpty()) {
            budget.checkTime()
            val candidate = pending.removeLast()
            val value = if (candidate is COSObject) candidate.getObject() ?: continue else candidate
            if (!visited.add(value)) continue
            when (value) {
                is COSStream -> {
                    var streamBytes = 0L
                    value.createInputStream().use { decoded ->
                        while (true) {
                            val read = decoded.read(buffer)
                            if (read < 0) break
                            streamBytes += read
                            budget.addExpandedBytes(read, streamBytes)
                        }
                    }
                    value.values.forEach(pending::add)
                }
                is COSDictionary -> value.values.forEach(pending::add)
                is COSArray -> value.forEach(pending::add)
            }
        }
    }

    private fun parseDocx(bytes: ByteArray, sourceUrl: URI, budget: ParseWorkBudget): List<DocumentFragment> {
        if (isEncryptedOffice(bytes)) reject(DocumentRejectionReason.ENCRYPTED)
        return XWPFDocument(ByteArrayInputStream(bytes)).use { document ->
            budget.checkTime()
            val fragments = mutableListOf<DocumentFragment>()
            var section = "Document"
            var sectionText = mutableListOf<String>()
            var headingCount = 0
            fun flush() {
                sectionText.joinToString("\n").trim().takeIf(String::isNotEmpty)?.let {
                    if (fragments.size >= maxSections) reject(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED)
                    fragments += DocumentFragment(it, DocumentProvenance(sourceUrl, section = section))
                }
                sectionText = mutableListOf()
            }
            document.bodyElements.forEach { element ->
                when (element.elementType) {
                    BodyElementType.PARAGRAPH -> {
                        budget.addParagraphs()
                        val paragraph = element as XWPFParagraph
                        val text = paragraph.text.trim()
                        budget.addTextCharacters(text.length)
                        if (text.isNotEmpty()) {
                            val isHeading = paragraph.style?.startsWith("Heading", ignoreCase = true) == true ||
                                paragraph.styleID?.startsWith("Heading", ignoreCase = true) == true
                            if (isHeading) {
                                headingCount++
                                if (headingCount > maxSections) reject(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED)
                                flush()
                                section = text
                            } else {
                                sectionText += text
                            }
                        }
                    }
                    BodyElementType.TABLE -> {
                        val table = element as XWPFTable
                        table.rows.forEach { row ->
                            budget.addTableRows()
                            budget.addCells(row.tableCells.size)
                            val text = row.tableCells.joinToString("\t") { it.text.trim() }
                            budget.addTextCharacters(text.length)
                            sectionText += text
                        }
                    }
                    else -> Unit
                }
            }
            flush()
            fragments
        }
    }

    private fun parseDoc(bytes: ByteArray, sourceUrl: URI, budget: ParseWorkBudget): List<DocumentFragment> {
        val text = if (bytes.take(5).toByteArray().toString(Charsets.US_ASCII).startsWith("{\\rtf")) {
            val kit = RTFEditorKit()
            val document = kit.createDefaultDocument()
            ByteArrayInputStream(bytes).use { kit.read(it, document, 0) }
            document.getText(0, document.length).also { budget.addTextCharacters(it.length) }
        } else {
            HWPFDocument(ByteArrayInputStream(bytes)).use { document ->
                budget.checkTime()
                buildString {
                    val range = document.range
                    repeat(range.numParagraphs()) { index ->
                        budget.addParagraphs()
                        val paragraph = range.getParagraph(index).text().trimEnd('\r', '\u0007')
                        budget.addTextCharacters(paragraph.length)
                        if (paragraph.isNotBlank()) appendLine(paragraph)
                    }
                }
            }
        }.trim()
        return text.takeIf(String::isNotEmpty)?.let {
            listOf(DocumentFragment(it, DocumentProvenance(sourceUrl, section = "Document")))
        }.orEmpty()
    }

    private fun parseWorkbook(bytes: ByteArray, sourceUrl: URI, budget: ParseWorkBudget): List<DocumentFragment> =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            budget.checkTime()
            if (workbook.numberOfSheets > maxSheets) reject(DocumentRejectionReason.SHEET_LIMIT_EXCEEDED)
            val formatter = DataFormatter(Locale.ROOT).apply {
                setUseCachedValuesForFormulaCells(true)
            }
            (0 until workbook.numberOfSheets).mapNotNull { index ->
                val sheet = workbook.getSheetAt(index)
                val text = buildString {
                    sheet.forEach { row ->
                        budget.addRows()
                        val values = row.map {
                            budget.addCells()
                            formatter.formatCellValue(it).trim().also { value -> budget.addTextCharacters(value.length) }
                        }
                        if (values.any(String::isNotEmpty)) appendLine(values.joinToString("\t"))
                    }
                }.trim()
                text.takeIf(String::isNotEmpty)?.let {
                    DocumentFragment(it, DocumentProvenance(sourceUrl, sheet = sheet.sheetName))
                }
            }
        }

    private fun parseText(
        bytes: ByteArray,
        contentType: String,
        sourceUrl: URI,
        budget: ParseWorkBudget,
    ): List<DocumentFragment> {
        val text = decodeTextStrict(bytes, contentType).trim()
        budget.addTextCharacters(text.length)
        return text.takeIf(String::isNotEmpty)?.let {
            listOf(DocumentFragment(it, DocumentProvenance(sourceUrl, section = "Document")))
        }.orEmpty()
    }

    private fun parseHtml(
        bytes: ByteArray,
        contentType: String,
        sourceUrl: URI,
        budget: ParseWorkBudget,
    ): List<DocumentFragment> {
        val decoded = decodeTextStrict(bytes, contentType)
        budget.addTextCharacters(decoded.length)
        val document = Jsoup.parse(decoded, sourceUrl.toString())
        budget.checkTime()
        val fragments = mutableListOf<DocumentFragment>()
        var section = "Document"
        val sectionText = mutableListOf<String>()
        fun flush() {
            sectionText.joinToString("\n").trim().takeIf(String::isNotEmpty)?.let {
                if (fragments.size >= maxSections) reject(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED)
                fragments += DocumentFragment(it, DocumentProvenance(sourceUrl, section = section))
            }
            sectionText.clear()
        }
        fun walk(element: Element) {
            budget.checkTime()
            if (element.tagName() in NON_VISIBLE_TAGS) return
            if (element.tagName() in HEADING_TAGS) {
                flush()
                section = element.text().trim().ifEmpty { "Document" }
                sectionText += element.text().trim()
                return
            }
            val semanticLabel = if (element.tagName() in SEMANTIC_CONTAINERS) {
                element.attr("aria-label").trim().ifEmpty { null }
            } else {
                null
            }
            val previousSection = section
            if (semanticLabel != null) {
                flush()
                section = semanticLabel
            }
            element.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> node.text().trim().takeIf(String::isNotEmpty)?.let(sectionText::add)
                    is Element -> walk(node)
                }
            }
            if (semanticLabel != null || element.tagName() in SEMANTIC_CONTAINERS) {
                flush()
                section = previousSection
            }
        }
        document.body().childNodes().forEach { node ->
            when (node) {
                is TextNode -> node.text().trim().takeIf(String::isNotEmpty)?.let(sectionText::add)
                is Element -> walk(node)
            }
        }
        flush()
        if (fragments.size > maxSections) reject(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED)
        return fragments
    }

    private fun reject(reason: DocumentRejectionReason, cause: Throwable? = null): Nothing =
        throw CatalogDocumentRejectedException(reason, cause)

    private class BoundedTextWriter(private val budget: ParseWorkBudget) : Writer() {
        private val output = StringBuilder()

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            budget.addTextCharacters(length)
            output.append(buffer, offset, length)
        }

        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = output.toString()
    }

    private fun isEncryptedOffice(bytes: ByteArray): Boolean {
        if (FileMagic.valueOf(ByteArrayInputStream(bytes)) != FileMagic.OLE2) return false
        return runCatching {
            POIFSFileSystem(ByteArrayInputStream(bytes)).use { fileSystem ->
                fileSystem.root.hasEntry("EncryptedPackage") || fileSystem.root.hasEntry("EncryptionInfo")
            }
        }.getOrDefault(false)
    }

    private companion object {
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val SEMANTIC_CONTAINERS = setOf("section", "article", "main", "nav", "aside")
        val NON_VISIBLE_TAGS = setOf("script", "style", "noscript", "template")
    }
}
