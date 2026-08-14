package com.rfp.service.crawl

import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

internal data class DocumentResourceLimits(
    val maxExpandedBytes: Long,
    val maxArchiveEntryBytes: Long,
    val maxArchiveEntries: Int,
    val maxTextCharacters: Long,
    val maxRows: Long,
    val maxCells: Long,
    val maxParagraphs: Long,
    val maxTableRows: Long,
    val maxXmlDepth: Int,
    val maxXmlElements: Long,
    val maxRuns: Long,
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
    private var xmlElements = 0L
    private var runs = 0L

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

    fun checkXmlDepth(depth: Int) {
        checkTime()
        if (depth > limits.maxXmlDepth) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }

    fun addXmlElement() {
        checkTime()
        xmlElements = addExactOrReject(xmlElements, 1)
        if (xmlElements > limits.maxXmlElements) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
    }

    fun addRuns() {
        checkTime()
        runs = addExactOrReject(runs, 1)
        if (runs > limits.maxRuns) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
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
            FileMagic.PDF -> CatalogDocumentFormat.PDF
            FileMagic.OLE2 -> inspectOle(bytes, declared ?: extension)
            FileMagic.OOXML -> inspectZip(bytes)
            else -> when {
                bytes.startsWithAscii("{\\rtf") -> CatalogDocumentFormat.DOC
                declared in TEXT_FORMATS -> declared
                extension in TEXT_FORMATS -> extension
                else -> null
            }
        }
        if (declared == null && !CatalogDocumentFormatDetector.isGenericMediaType(contentType) &&
            (extension != null || magic != null)
        ) {
            reject(DocumentRejectionReason.TYPE_MISMATCH)
        }
        val candidates = listOfNotNull(declared, extension, magic).toSet()
        if (candidates.isEmpty()) reject(DocumentRejectionReason.UNSUPPORTED)
        if (candidates.size > 1) reject(DocumentRejectionReason.TYPE_MISMATCH)
        return candidates.single().also { format ->
            if (format in TEXT_FORMATS) decodeTextStrict(bytes, contentType)
        }
    }

    private fun inspectZip(bytes: ByteArray): CatalogDocumentFormat {
        val budget = ParseWorkBudget(limits, deadline)
        var entryCount = 0
        var documentFormat: CatalogDocumentFormat? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                budget.checkTime()
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > limits.maxArchiveEntries) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
                var entryBytes = 0L
                val normalizedName = entry.name.lowercase()
                val retain = normalizedName.endsWith(".xml") || normalizedName.endsWith(".rels")
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
                        entry.name == "[Content_Types].xml" -> documentFormat = inspectContentTypes(xml, budget)
                        else -> inspectXml(
                            xml,
                            budget,
                            countWord = normalizedName.startsWith("word/"),
                            countSheet = normalizedName.startsWith("xl/worksheets/"),
                        )
                    }
                }
                zip.closeEntry()
            }
        }
        return documentFormat ?: reject(DocumentRejectionReason.MALFORMED)
    }

    private fun inspectContentTypes(xml: ByteArray, budget: ParseWorkBudget): CatalogDocumentFormat {
        val declaredTypes = mutableSetOf<String>()
        inspectXml(xml, budget) { reader ->
            if (reader.namespaceURI == CONTENT_TYPES_NAMESPACE &&
                (reader.localName == "Default" || reader.localName == "Override")
            ) {
                reader.getAttributeValue(null, "ContentType")?.trim()?.takeIf(String::isNotEmpty)?.let(declaredTypes::add)
            }
        }
        val formats = buildSet {
            if (WORD_MAIN_CONTENT_TYPE in declaredTypes) add(CatalogDocumentFormat.DOCX)
            if (SHEET_MAIN_CONTENT_TYPE in declaredTypes) add(CatalogDocumentFormat.XLSX)
        }
        if (formats.isEmpty()) reject(DocumentRejectionReason.UNSUPPORTED)
        if (formats.size > 1) reject(DocumentRejectionReason.TYPE_MISMATCH)
        return formats.single()
    }

    private fun inspectXml(
        xml: ByteArray,
        budget: ParseWorkBudget,
        countWord: Boolean = false,
        countSheet: Boolean = false,
        onStartElement: (XMLStreamReader) -> Unit = {},
    ) {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty("javax.xml.stream.isSupportingExternalEntities", false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(xml))
        var depth = 0
        try {
            while (reader.hasNext()) {
                budget.checkTime()
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        budget.checkXmlDepth(depth)
                        budget.addXmlElement()
                        when {
                            countWord && reader.localName == "p" -> budget.addParagraphs()
                            countWord && reader.localName == "tr" -> budget.addTableRows()
                            countWord && reader.localName == "tc" -> budget.addCells()
                            countWord && reader.localName == "r" -> budget.addRuns()
                            countSheet && reader.localName == "row" -> budget.addRows()
                            countSheet && reader.localName == "c" -> budget.addCells()
                        }
                        onStartElement(reader)
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
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

    private fun ByteArray.startsWithAscii(value: String): Boolean =
        size >= value.length && value.indices.all { this[it] == value[it].code.toByte() }

    private companion object {
        val TEXT_FORMATS = setOf(CatalogDocumentFormat.TEXT, CatalogDocumentFormat.HTML, CatalogDocumentFormat.XHTML)
        const val WORD_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        const val SHEET_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
        const val CONTENT_TYPES_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/content-types"
    }
}

private fun reject(reason: DocumentRejectionReason, cause: Throwable? = null): Nothing =
    throw CatalogDocumentRejectedException(reason, cause)

internal fun decodeTextStrict(bytes: ByteArray, contentType: String): String {
    val charset = contentType.substringAfter("charset=", "UTF-8").substringBefore(';').trim()
        .let { runCatching { Charset.forName(it) }.getOrDefault(Charsets.UTF_8) }
    val decoded = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: Exception) {
        reject(DocumentRejectionReason.TYPE_MISMATCH, exception)
    }
    if (decoded.any { character ->
            character == '\u0000' || character.code in 0x01..0x1f && character !in ALLOWED_TEXT_CONTROLS
        }
    ) {
        reject(DocumentRejectionReason.TYPE_MISMATCH)
    }
    return decoded
}

private val ALLOWED_TEXT_CONTROLS = setOf('\t', '\n', '\r', '\u000c')
