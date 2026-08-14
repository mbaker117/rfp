package com.rfp.service.crawl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.openxml4j.opc.OPCPackage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

class CatalogDocumentParserTest {
    private val source = URI("https://example.com/manuals/DMM-1000")
    private val parser = CatalogDocumentParser(maxDocumentBytes = 2 * 1024 * 1024, maxPages = 10, maxSheets = 5)

    @Test
    fun `parses PDF pages as first class specification fragments`() {
        val parsed = parser.parse(resourceBytes("DMM-1000-manual.pdf"), "application/pdf", source.resolve("manual.pdf"))

        assertThat(parsed.sourceUrl).isEqualTo(source.resolve("manual.pdf"))
        assertThat(parsed.fragments).hasSize(2)
        assertThat(parsed.fragments[0].provenance.page).isEqualTo(1)
        assertThat(parsed.fragments[0].text).contains("DMM-1000", "600 V")
        assertThat(parsed.fragments[1].provenance.page).isEqualTo(2)
        assertThat(parsed.fragments[1].text).contains("CAT III")
    }

    @Test
    fun `parses DOCX sections with English and Arabic specifications`() {
        val parsed = parser.parse(
            resourceBytes("DMM-1000-manual.docx"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            source.resolve("manual.docx"),
        )

        assertThat(parsed.fragments.map { it.provenance.section }).contains("Specifications", "المواصفات")
        assertThat(parsed.text).contains("Accuracy 0.5%", "مدى القياس ٦٠٠ فولت")
    }

    @Test
    fun `parses legacy DOC specification sections`() {
        val parsed = parser.parse(resourceBytes("DMM-1000-manual.doc"), "application/msword", source.resolve("manual.doc"))

        assertThat(parsed.fragments).hasSize(1)
        assertThat(parsed.fragments.single().provenance.section).isEqualTo("Document")
        assertThat(parsed.fragments.single().text).contains("DMM-1000", "Accuracy 0.5%")
    }

    @Test
    fun `parses XLSX and XLS sheets with sheet provenance`() {
        val xlsx = parser.parse(
            resourceBytes("DMM-1000-specifications.xlsx"),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            source.resolve("specifications.xlsx"),
        )
        val xls = parser.parse(
            resourceBytes("DMM-1000-specifications.xls"),
            "application/vnd.ms-excel",
            source.resolve("specifications.xls"),
        )

        assertThat(xlsx.fragments.map { it.provenance.sheet }).containsExactly("Electrical", "المواصفات")
        assertThat(xlsx.text).contains("Voltage 600 V", "الدقة ٠٫٥٪")
        assertThat(xls.fragments.single().provenance.sheet).isEqualTo("Legacy Specs")
        assertThat(xls.text).contains("Current 10 A")
    }

    @Test
    fun `parses plain text and HTML with section provenance`() {
        val text = parser.parse(resourceBytes("manual.txt"), "text/plain; charset=UTF-8", source.resolve("manual.txt"))
        val html = parser.parse(resourceBytes("arabic-catalog.html"), "text/html", source.resolve("manual.html"))

        assertThat(text.fragments.single().provenance.section).isEqualTo("Document")
        assertThat(text.text).contains("Measurement range: 600 V", "تعليمات السلامة")
        assertThat(html.fragments.map { it.provenance.section }).containsExactly("دليل أجهزة القياس", "المواصفات", "السلامة")
        assertThat(html.text).contains("مدى القياس ٦٠٠ فولت")
    }

    @Test
    fun `rejects oversized unsupported encrypted and page or sheet limit documents explicitly`() {
        assertRejected(DocumentRejectionReason.OVERSIZED) {
            CatalogDocumentParser(maxDocumentBytes = 3).parse("large".toByteArray(), "text/plain", source)
        }
        assertRejected(DocumentRejectionReason.UNSUPPORTED) {
            parser.parse("zip".toByteArray(), "application/zip", source)
        }
        assertRejected(DocumentRejectionReason.PAGE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxPages = 1).parse(resourceBytes("DMM-1000-manual.pdf"), "application/pdf", source)
        }
        assertRejected(DocumentRejectionReason.SHEET_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxSheets = 1).parse(
                resourceBytes("DMM-1000-specifications.xlsx"),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                source,
            )
        }
        assertRejected(DocumentRejectionReason.ENCRYPTED) {
            parser.parse(encryptedPdf(), "application/pdf", source)
        }
        assertRejected(DocumentRejectionReason.ENCRYPTED) {
            parser.parse(
                encryptedDocx(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source,
            )
        }
    }

    private fun assertRejected(reason: DocumentRejectionReason, call: () -> Unit) {
        val thrown = org.junit.jupiter.api.assertThrows<CatalogDocumentRejectedException> { call() }
        assertThat(thrown.reason).isEqualTo(reason)
    }

    private fun encryptedPdf(): ByteArray {
        val document = Loader.loadPDF(resourceBytes("DMM-1000-manual.pdf"))
        return document.use {
            val policy = StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission())
            policy.encryptionKeyLength = 128
            it.protect(policy)
            ByteArrayOutputStream().also(it::save).toByteArray()
        }
    }

    private fun encryptedDocx(): ByteArray {
        val fileSystem = POIFSFileSystem()
        return fileSystem.use { fs ->
            val info = EncryptionInfo(EncryptionMode.agile)
            val encryptor = info.encryptor
            encryptor.confirmPassword("secret")
            OPCPackage.open(ByteArrayInputStream(resourceBytes("DMM-1000-manual.docx"))).use { pkg ->
                encryptor.getDataStream(fs).use(pkg::save)
            }
            ByteArrayOutputStream().also(fs::writeFilesystem).toByteArray()
        }
    }

    private fun resourceBytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/crawl/$name")) {
        "missing fixture $name"
    }.use { it.readAllBytes() }
}
