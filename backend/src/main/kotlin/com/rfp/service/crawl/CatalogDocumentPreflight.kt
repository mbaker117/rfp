package com.rfp.service.crawl

import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

internal data class DocumentResourceLimits(
    val maxExpandedBytes: Long,
    val maxArchiveEntryBytes: Long,
    val maxArchiveEntries: Int,
    val maxTextCharacters: Long,
    val maxRows: Long,
    val maxCells: Long,
    val maxParagraphs: Long,
    val maxTableRows: Long,
)

internal class ParseWorkBudget(
    private val limits: DocumentResourceLimits,
    private val deadline: DeadlineBudget,
) {
    private var expandedBytes = 0L
    private var textCharacters = 0L
    private var rows = 0L
    private var cells = 0L
    private var paragraphs = 0L
    private var tableRows = 0L

    fun checkTime() {
        if (deadline.isExpired()) reject(DocumentRejectionReason.TIME_LIMIT_EXCEEDED)
    }

    fun addExpandedBytes(count: Int, entryBytes: Long? = null) {
        checkTime()
        expandedBytes = addExactOrReject(expandedBytes, count.toLong())
        if (expandedBytes > limits.maxExpandedBytes || entryBytes != null && entryBytes > limits.maxArchiveEntryBytes) {
            reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
        }
    }

    fun addTextCharacters(count: Int) {
        checkTime()
        textCharacters = addExactOrReject(textCharacters, count.toLong())
        if (textCharacters > limits.maxTextCharacters) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }

    fun addRows(count: Int = 1) {
        checkTime()
        rows = addExactOrReject(rows, count.toLong())
        if (rows > limits.maxRows) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }

    fun addCells(count: Int = 1) {
        checkTime()
        cells = addExactOrReject(cells, count.toLong())
        if (cells > limits.maxCells) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }

    fun addParagraphs(count: Int = 1) {
        checkTime()
        paragraphs = addExactOrReject(paragraphs, count.toLong())
        if (paragraphs > limits.maxParagraphs) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }

    fun addTableRows(count: Int = 1) {
        checkTime()
        tableRows = addExactOrReject(tableRows, count.toLong())
        if (tableRows > limits.maxTableRows) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }

    private fun addExactOrReject(current: Long, added: Long): Long = try {
        Math.addExact(current, added)
    } catch (_: ArithmeticException) {
        reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }
}

internal class CatalogDocumentPreflight(
    private val limits: DocumentResourceLimits,
    private val deadline: DeadlineBudget,
) {
    fun inspect(bytes: ByteArray, contentType: String, sourceUrl: URI): CatalogDocumentFormat {
        val declared = CatalogDocumentFormatDetector.fromMediaType(contentType)
        val extension = CatalogDocumentFormatDetector.fromUrl(sourceUrl)
        val magic = when (FileMagic.valueOf(ByteArrayInputStream(bytes))) {
            FileMagic.PDF -> inspectPdf(bytes).let { CatalogDocumentFormat.PDF }
            FileMagic.OLE2 -> inspectOle(bytes, declared ?: extension)
            FileMagic.OOXML -> inspectZip(bytes)
            else -> when {
                bytes.startsWithAscii("{\\rtf") -> CatalogDocumentFormat.DOC
                declared in TEXT_FORMATS -> declared
                extension in TEXT_FORMATS -> extension
                else -> null
            }
        }
        val candidates = listOfNotNull(declared, extension, magic).toSet()
        if (candidates.isEmpty()) reject(DocumentRejectionReason.UNSUPPORTED)
        if (candidates.size > 1) reject(DocumentRejectionReason.TYPE_MISMATCH)
        return candidates.single()
    }

    private fun inspectZip(bytes: ByteArray): CatalogDocumentFormat {
        val budget = ParseWorkBudget(limits, deadline)
        var entryCount = 0
        var contentTypes: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                budget.checkTime()
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > limits.maxArchiveEntries) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
                var entryBytes = 0L
                val retain = entry.name == "[Content_Types].xml" ||
                    entry.name == "word/document.xml" ||
                    entry.name.startsWith("xl/worksheets/") ||
                    entry.name == "xl/sharedStrings.xml"
                val retained = if (retain) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    budget.addExpandedBytes(read, entryBytes)
                    retained?.write(buffer, 0, read)
                }
                retained?.toByteArray()?.let { xml ->
                    when {
                        entry.name == "[Content_Types].xml" -> contentTypes = xml.toString(Charsets.UTF_8)
                        entry.name == "word/document.xml" -> inspectXml(xml, budget, countWord = true)
                        entry.name.startsWith("xl/worksheets/") -> inspectXml(xml, budget, countSheet = true)
                        entry.name == "xl/sharedStrings.xml" -> inspectXml(xml, budget)
                    }
                }
                zip.closeEntry()
            }
        }
        val types = contentTypes ?: reject(DocumentRejectionReason.MALFORMED)
        return when {
            WORD_MAIN_CONTENT_TYPE in types -> CatalogDocumentFormat.DOCX
            SHEET_MAIN_CONTENT_TYPE in types -> CatalogDocumentFormat.XLSX
            else -> reject(DocumentRejectionReason.UNSUPPORTED)
        }
    }

    private fun inspectXml(xml: ByteArray, budget: ParseWorkBudget, countWord: Boolean = false, countSheet: Boolean = false) {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        }
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(xml))
        try {
            while (reader.hasNext()) {
                budget.checkTime()
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when {
                        countWord && reader.localName == "p" -> budget.addParagraphs()
                        countWord && reader.localName == "tr" -> budget.addTableRows()
                        countSheet && reader.localName == "row" -> budget.addRows()
                        countSheet && reader.localName == "c" -> budget.addCells()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> budget.addTextCharacters(reader.textLength)
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun inspectOle(bytes: ByteArray, fallback: CatalogDocumentFormat?): CatalogDocumentFormat {
        ParseWorkBudget(limits, deadline).checkTime()
        return POIFSFileSystem(ByteArrayInputStream(bytes)).use { fileSystem ->
            when {
                fileSystem.root.hasEntry("WordDocument") -> CatalogDocumentFormat.DOC
                fileSystem.root.hasEntry("Workbook") || fileSystem.root.hasEntry("Book") -> CatalogDocumentFormat.XLS
                fileSystem.root.hasEntry("EncryptedPackage") || fileSystem.root.hasEntry("EncryptionInfo") ->
                    fallback ?: reject(DocumentRejectionReason.ENCRYPTED)
                else -> reject(DocumentRejectionReason.UNSUPPORTED)
            }
        }
    }

    private fun inspectPdf(bytes: ByteArray) {
        if (bytes.indexOfAscii("/Encrypt", 0) >= 0) reject(DocumentRejectionReason.ENCRYPTED)
        val budget = ParseWorkBudget(limits, deadline)
        var cursor = 0
        while (true) {
            val streamToken = bytes.indexOfAscii("stream", cursor)
            if (streamToken < 0) return
            val dataStart = bytes.afterPdfStreamLineBreak(streamToken + 6)
            if (dataStart == null) {
                cursor = streamToken + 6
                continue
            }
            val end = bytes.indexOfAscii("endstream", dataStart)
            if (end < 0) reject(DocumentRejectionReason.MALFORMED)
            val dictionaryStart = bytes.lastIndexOfAscii("<<", streamToken, 2048)
            val dictionary = if (dictionaryStart >= 0) {
                bytes.copyOfRange(dictionaryStart, streamToken).toString(Charsets.ISO_8859_1)
            } else {
                ""
            }
            val encodedLength = end - dataStart
            if ("FlateDecode" in dictionary || "/Fl" in dictionary) {
                InflaterInputStream(ByteArrayInputStream(bytes, dataStart, encodedLength)).use { inflated ->
                    val buffer = ByteArray(8192)
                    var streamBytes = 0L
                    while (true) {
                        val read = inflated.read(buffer)
                        if (read < 0) break
                        streamBytes += read
                        budget.addExpandedBytes(read, streamBytes)
                    }
                }
            } else {
                budget.addExpandedBytes(encodedLength, encodedLength.toLong())
            }
            cursor = end + 9
        }
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean =
        size >= value.length && value.indices.all { this[it] == value[it].code.toByte() }

    private fun ByteArray.indexOfAscii(value: String, start: Int): Int {
        if (value.isEmpty()) return start.coerceAtMost(size)
        outer@ for (index in start.coerceAtLeast(0)..(size - value.length)) {
            for (offset in value.indices) if (this[index + offset] != value[offset].code.toByte()) continue@outer
            return index
        }
        return -1
    }

    private fun ByteArray.lastIndexOfAscii(value: String, before: Int, window: Int): Int {
        val minimum = (before - window).coerceAtLeast(0)
        for (index in (before - value.length) downTo minimum) {
            if (value.indices.all { this[index + it] == value[it].code.toByte() }) return index
        }
        return -1
    }

    private fun ByteArray.afterPdfStreamLineBreak(index: Int): Int? = when {
        index < size && this[index] == '\n'.code.toByte() -> index + 1
        index + 1 < size && this[index] == '\r'.code.toByte() && this[index + 1] == '\n'.code.toByte() -> index + 2
        index < size && this[index] == '\r'.code.toByte() -> index + 1
        else -> null
    }

    private companion object {
        val TEXT_FORMATS = setOf(CatalogDocumentFormat.TEXT, CatalogDocumentFormat.HTML, CatalogDocumentFormat.XHTML)
        const val WORD_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        const val SHEET_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    }
}

private fun reject(reason: DocumentRejectionReason, cause: Throwable? = null): Nothing =
    throw CatalogDocumentRejectedException(reason, cause)
