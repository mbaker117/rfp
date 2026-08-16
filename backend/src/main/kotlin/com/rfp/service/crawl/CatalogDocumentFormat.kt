package com.rfp.service.crawl

import java.net.URI
import java.util.Locale

internal enum class CatalogDocumentFormat(
    val mediaType: String,
    val extensions: Set<String>,
) {
    PDF("application/pdf", setOf("pdf")),
    DOC("application/msword", setOf("doc")),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", setOf("docx")),
    XLS("application/vnd.ms-excel", setOf("xls")),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", setOf("xlsx")),
    TEXT("text/plain", setOf("txt")),
    HTML("text/html", setOf("html", "htm")),
    XHTML("application/xhtml+xml", setOf("xhtml")),
}

internal object CatalogDocumentFormatDetector {
    fun fromMediaType(contentType: String?): CatalogDocumentFormat? {
        val normalized = normalizedMediaType(contentType) ?: return null
        return CatalogDocumentFormat.entries.firstOrNull { it.mediaType == normalized }
    }

    fun normalizedMediaType(contentType: String?): String? = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.ifEmpty { null }

    fun isGenericMediaType(contentType: String?): Boolean =
        normalizedMediaType(contentType) in GENERIC_MEDIA_TYPES

    fun fromUrl(uri: URI): CatalogDocumentFormat? {
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return CatalogDocumentFormat.entries.firstOrNull { extension in it.extensions }
    }

    fun isDiscoverable(uri: URI, anchorType: String?, hints: String): Boolean {
        if (fromMediaType(anchorType) != null || fromUrl(uri) != null) return true
        val normalizedHints = hints.lowercase(Locale.ROOT)
        return DOCUMENT_HINTS.any(normalizedHints::contains)
    }

    private val DOCUMENT_HINTS = setOf(
        "manual",
        "datasheet",
        "data-sheet",
        "catalog",
        "specification",
        "spec-sheet",
    )

    private val GENERIC_MEDIA_TYPES = setOf(
        null,
        "application/octet-stream",
        "binary/octet-stream",
    )
}
