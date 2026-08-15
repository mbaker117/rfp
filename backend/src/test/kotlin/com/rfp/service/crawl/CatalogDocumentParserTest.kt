package com.rfp.service.crawl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
    fun `parses genuine OLE2 DOC specifications in English and Arabic`() {
        val bytes = resourceBytes("DMM-1000-manual.doc")

        assertThat(FileMagic.valueOf(ByteArrayInputStream(bytes))).isEqualTo(FileMagic.OLE2)
        assertThat(bytes.take(8).map { it.toInt() and 0xff })
            .containsExactly(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        assertThat(WordExtractor(ByteArrayInputStream(bytes)).use { it.text }).contains(
            "Accuracy 0.5%",
            "المواصفات",
            "مدى القياس ٦٠٠ فولت",
        )

        val parsed = parser.parse(bytes, "application/msword", source.resolve("manual.doc"))

        assertThat(parsed.fragments).hasSize(1)
        assertThat(parsed.fragments.single().provenance.section).isEqualTo("Document")
        assertThat(parsed.fragments.single().text).contains(
            "DMM-1000",
            "Accuracy 0.5%",
            "المواصفات",
            "مدى القياس ٦٠٠ فولت",
        )
    }

    @Test
    fun `rejects a malformed OLE2 Word document explicitly`() {
        val bytes = hexResourceBytes("DMM-1000-malformed-ole.hex")

        assertThat(FileMagic.valueOf(ByteArrayInputStream(bytes))).isEqualTo(FileMagic.OLE2)
        assertRejected(DocumentRejectionReason.MALFORMED) {
            parser.parse(bytes, "application/msword", source.resolve("malformed.doc"))
        }
    }

    @Test
    fun `rejects a password protected Word 97-2003 document explicitly`() {
        val bytes = resourceBytes("DMM-1000-protected.doc")

        assertThat(FileMagic.valueOf(ByteArrayInputStream(bytes))).isEqualTo(FileMagic.OLE2)
        assertRejected(DocumentRejectionReason.ENCRYPTED) {
            parser.parse(bytes, "application/msword", source.resolve("protected.doc"))
        }
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
    fun `keeps interleaved DOCX paragraphs and tables under their preceding heading`() {
        val parsed = parser.parse(
            interleavedDocx(),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            source.resolve("ordered.docx"),
        )

        assertThat(parsed.fragments.map { it.provenance.section }).containsExactly("Electrical", "Safety")
        assertThat(parsed.fragments[0].text).contains("Voltage 600 V", "Current 10 A")
        assertThat(parsed.fragments[0].text).doesNotContain("CAT III")
        assertThat(parsed.fragments[1].text).contains("CAT III")
    }

    @Test
    fun `counts populated DOCX sections instead of paragraphs for the section budget`() {
        val oneSection = CatalogDocumentParser(maxSections = 1).parse(
            docxWithParagraphsAndTableRows(paragraphs = 3, tableRows = 1),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            source.resolve("one-section.docx"),
        )

        assertThat(oneSection.fragments).hasSize(1)
        assertThat(oneSection.fragments.single().provenance.section).isEqualTo("Document")
        assertThat(oneSection.fragments.single().text).contains("paragraph", "row")
        assertRejected(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxSections = 1).parse(
                interleavedDocx(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("two-sections.docx"),
            )
        }
    }

    @Test
    fun `walks HTML semantic blocks once in document order including default and headingless sections`() {
        val html = """
            <html><body>
              <p>Preface once</p>
              <section>
                <h2>Specifications</h2>
                <p>Voltage 600 V</p>
                <div><p>Nested detail once</p></div>
              </section>
              <section aria-label="Safety notes"><p>CAT III once</p></section>
            </body></html>
        """.trimIndent().toByteArray()

        val parsed = parser.parse(html, "text/html", source.resolve("ordered.html"))

        assertThat(parsed.fragments.map { it.provenance.section })
            .containsExactly("Document", "Specifications", "Safety notes")
        assertThat(parsed.text.split("Preface once")).hasSize(2)
        assertThat(parsed.text.split("Voltage 600 V")).hasSize(2)
        assertThat(parsed.text.split("Nested detail once")).hasSize(2)
        assertThat(parsed.text.split("CAT III once")).hasSize(2)
    }

    @Test
    fun `counts the default HTML fragment in the section budget`() {
        val html = """
            <html><body>
              <p>Preface</p>
              <h2>Specifications</h2>
              <p>Voltage 600 V</p>
            </body></html>
        """.trimIndent().toByteArray()

        assertRejected(DocumentRejectionReason.SECTION_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxSections = 1).parse(
                html,
                "text/html",
                source.resolve("two-sections.html"),
            )
        }
    }

    @Test
    fun `preserves HTML mixed text around nested elements in document order`() {
        val html = """
            <html><body><div>before <strong>child</strong> after</div></body></html>
        """.trimIndent().toByteArray()

        val parsed = parser.parse(html, "text/html", source.resolve("mixed.html"))

        assertThat(parsed.text.replace(Regex("\\s+"), " ")).isEqualTo("before child after")
    }

    @Test
    fun `preserves direct body text around child elements in document order`() {
        val html = "<html><body>before<div>child</div>after</body></html>".toByteArray()

        val parsed = parser.parse(html, "text/html", source.resolve("body-mixed.html"))

        assertThat(parsed.text.replace(Regex("\\s+"), " ")).isEqualTo("before child after")
    }

    @Test
    fun `uses cached displayed formula values without evaluating external workbook links`() {
        val parsed = parser.parse(
            workbookWithCachedExternalFormula(),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            source.resolve("formula.xlsx"),
        )

        assertThat(parsed.text).contains("2")
        assertThat(parsed.text).doesNotContain("[1]External!A1", "#REF!")
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

    @Test
    fun `rejects OOXML archives whose aggregate or single entry expansion exceeds preflight budgets`() {
        val expanded = highExpansionDocx(16 * 1024)

        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxExpandedBytes = 1024, maxArchiveEntryBytes = 32 * 1024).parse(
                expanded,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("expanded.docx"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxExpandedBytes = 32 * 1024, maxArchiveEntryBytes = 1024).parse(
                expanded,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("expanded.docx"),
            )
        }
    }

    @Test
    fun `parses OOXML content type declarations using their XML encoding`() {
        val utf16Docx = rewriteZipEntry(
            resourceBytes("DMM-1000-manual.docx"),
            "[Content_Types].xml",
        ) { xml ->
            xml.toString(Charsets.UTF_8)
                .replace("UTF-8", "UTF-16", ignoreCase = true)
                .toByteArray(Charsets.UTF_16)
        }

        val parsed = parser.parse(
            utf16Docx,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            source.resolve("utf16.docx"),
        )

        assertThat(parsed.text).contains("Accuracy 0.5%", "مدى القياس ٦٠٠ فولت")
    }

    @Test
    fun `does not accept OOXML content types mentioned only in comments`() {
        val spoofed = zipEntries(
            mapOf(
                "[Content_Types].xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <!-- application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml -->
                      <Default Extension="xml" ContentType="application/xml"/>
                    </Types>
                """.trimIndent().toByteArray(),
                "word/document.xml" to """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>
                """.trimIndent().toByteArray(),
            ),
        )

        assertRejected(DocumentRejectionReason.UNSUPPORTED) {
            parser.parse(
                spoofed,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("spoofed.docx"),
            )
        }
    }

    @Test
    fun `rejects excessive XML depth in otherwise ignored OOXML relationships`() {
        val deeplyNestedRelationship = addZipEntry(
            resourceBytes("DMM-1000-manual.docx"),
            "custom/_rels/hostile.rels",
            ("<n>".repeat(24) + "value" + "</n>".repeat(24)).toByteArray(),
        )

        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxXmlDepth = 16).parse(
                deeplyNestedRelationship,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("deep.docx"),
            )
        }
    }

    @Test
    fun `rejects XML element and text budgets in otherwise ignored OOXML parts`() {
        val manyElements = addZipEntry(
            resourceBytes("DMM-1000-manual.docx"),
            "custom/hostile.xml",
            ("<root>" + "<item/>".repeat(1_000) + "</root>").toByteArray(),
        )
        val longText = addZipEntry(
            resourceBytes("DMM-1000-manual.docx"),
            "custom/hostile.xml",
            ("<root>" + "x".repeat(2_000) + "</root>").toByteArray(),
        )

        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxXmlElements = 200).parse(
                manyElements,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("elements.docx"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxTextCharacters = 1_000).parse(
                longText,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("text.docx"),
            )
        }
    }

    @Test
    fun `rejects DOCX run and table cell budgets during archive preflight`() {
        val docx = docxWithRunsAndCells(runs = 2, cells = 2)

        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxRuns = 1).parse(
                docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("runs.docx"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxCells = 1).parse(
                docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("cells.docx"),
            )
        }
    }

    @Test
    fun `rejects high expansion PDF streams and oversized single page text`() {
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxExpandedBytes = 1024).parse(
                highExpansionPdf(16 * 1024),
                "application/pdf",
                source.resolve("expanded.pdf"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxTextCharacters = 100).parse(
                pdfWithText("DMM-1000 " + "measurement ".repeat(100)),
                "application/pdf",
                source.resolve("one-large-page.pdf"),
            )
        }
    }

    @Test
    fun `applies PDF expansion budgets after complete filter chains`() {
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxExpandedBytes = 1_024).parse(
                chainedFilterPdf(16 * 1_024),
                "application/pdf",
                source.resolve("filter-chain.pdf"),
            )
        }
    }

    @Test
    fun `accepts valid PDF streams containing literal stream delimiter text`() {
        val parsed = parser.parse(
            pdfWithLiteralStreamTokens(),
            "application/pdf",
            source.resolve("literal-token.pdf"),
        )

        assertThat(parsed.contentType).isEqualTo("application/pdf")
    }

    @Test
    fun `rejects oversized single sheets paragraphs tables and plain text incrementally`() {
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxRows = 1).parse(
                workbookWithRows(2, 1),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                source.resolve("rows.xlsx"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxCells = 1).parse(
                workbookWithRows(1, 2),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                source.resolve("cells.xlsx"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxParagraphs = 1).parse(
                docxWithParagraphsAndTableRows(paragraphs = 2, tableRows = 0),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("paragraphs.docx"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxTableRows = 1).parse(
                docxWithParagraphsAndTableRows(paragraphs = 0, tableRows = 2),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("table.docx"),
            )
        }
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxTextCharacters = 5).parse(
                "123456".toByteArray(),
                "text/plain",
                source.resolve("large.txt"),
            )
        }
    }

    @Test
    fun `rejects type mismatches before parsing and infers octet stream from matching URL and magic`() {
        assertRejected(DocumentRejectionReason.TYPE_MISMATCH) {
            parser.parse(
                resourceBytes("DMM-1000-manual.pdf"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("manual.docx"),
            )
        }
        assertRejected(DocumentRejectionReason.TYPE_MISMATCH) {
            parser.parse(
                resourceBytes("DMM-1000-manual.docx"),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                source.resolve("manual.xlsx"),
            )
        }

        val inferred = parser.parse(
            resourceBytes("DMM-1000-manual.pdf"),
            "application/octet-stream",
            source.resolve("manual.pdf"),
        )

        assertThat(inferred.contentType).isEqualTo("application/pdf")
        assertThat(inferred.text).contains("DMM-1000")
    }

    @Test
    fun `rejects contradictory unknown media types even when extension and magic agree`() {
        assertRejected(DocumentRejectionReason.TYPE_MISMATCH) {
            parser.parse(
                resourceBytes("DMM-1000-manual.pdf"),
                "image/png",
                source.resolve("manual.pdf"),
            )
        }
    }

    @Test
    fun `rejects binary bytes declared as HTML`() {
        val binary = byteArrayOf(0, 1, 2, 3, 0x7f, 0, 0x80.toByte(), 0xff.toByte())

        assertRejected(DocumentRejectionReason.TYPE_MISMATCH) {
            parser.parse(binary, "text/html", source.resolve("manual.html"))
        }
    }

    @Test
    fun `forcibly bounds parsing with an absolute worker deadline`() {
        val started = System.nanoTime()
        assertRejected(DocumentRejectionReason.TIME_LIMIT_EXCEEDED) {
            CatalogDocumentParser(
                maximumDuration = Duration.ofMillis(1),
            ).parse(
                resourceBytes("DMM-1000-manual.docx"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("deadline.docx"),
            )
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5))
    }

    @Test
    fun `rejects worker output beyond the parent response bound`() {
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(maxWorkerOutputBytes = 128).parse(
                "x".repeat(1_000).toByteArray(),
                "text/plain",
                source.resolve("output.txt"),
            )
        }
    }

    @Test
    fun `contains parser heap exhaustion inside the worker process`() {
        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            CatalogDocumentParser(
                maxDocumentBytes = 2 * 1024 * 1024,
                maxExpandedBytes = 64L * 1024 * 1024,
                maxArchiveEntryBytes = 64L * 1024 * 1024,
                maxTextCharacters = 64L * 1024 * 1024,
                maxWorkerOutputBytes = 64L * 1024 * 1024,
                workerMaxHeapMegabytes = 16,
            ).parse(
                docxWithLargeText(24 * 1024 * 1024),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source.resolve("heap.docx"),
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

    private fun interleavedDocx(): ByteArray {
        val document = XWPFDocument()
        return document.use {
            it.createParagraph().apply { style = "Heading1" }.createRun().setText("Electrical")
            it.createParagraph().createRun().setText("Voltage 600 V")
            it.createTable(1, 1).getRow(0).getCell(0).text = "Current 10 A"
            it.createParagraph().apply { style = "Heading1" }.createRun().setText("Safety")
            it.createTable(1, 1).getRow(0).getCell(0).text = "CAT III"
            ByteArrayOutputStream().also(it::write).toByteArray()
        }
    }

    private fun workbookWithCachedExternalFormula(): ByteArray {
        val initial = XSSFWorkbook().use { workbook ->
            val cell = workbook.createSheet("Formula").createRow(0).createCell(0)
            cell.cellFormula = "1+1"
            workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(cell)
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(initial)).use { input ->
            ZipOutputStream(output).use { zip ->
                generateSequence(input::getNextEntry).forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    val bytes = input.readAllBytes()
                    if (entry.name == "xl/worksheets/sheet1.xml") {
                        val xml = bytes.toString(Charsets.UTF_8).replace("<f>1+1</f>", "<f>[1]External!A1</f>")
                        zip.write(xml.toByteArray())
                    } else {
                        zip.write(bytes)
                    }
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun highExpansionDocx(characters: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("x".repeat(characters).toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun rewriteZipEntry(bytes: ByteArray, name: String, transform: (ByteArray) -> ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            ZipOutputStream(output).use { zip ->
                generateSequence(input::getNextEntry).forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    val content = input.readAllBytes()
                    zip.write(if (entry.name == name) transform(content) else content)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun addZipEntry(bytes: ByteArray, name: String, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            ZipOutputStream(output).use { zip ->
                generateSequence(input::getNextEntry).forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(input.readAllBytes())
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun zipEntries(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun highExpansionPdf(characters: Int): ByteArray {
        val document = PDDocument()
        return document.use {
            val page = PDPage()
            it.addPage(page)
            val decoded = "%" + "x".repeat(characters)
            page.setContents(PDStream(it, ByteArrayInputStream(decoded.toByteArray()), COSName.FLATE_DECODE))
            ByteArrayOutputStream().also(it::save).toByteArray()
        }
    }

    private fun chainedFilterPdf(characters: Int): ByteArray {
        val document = PDDocument()
        return document.use {
            val page = PDPage()
            it.addPage(page)
            val filters = COSArray().apply {
                add(COSName.ASCII85_DECODE)
                add(COSName.FLATE_DECODE)
            }
            val decoded = "%" + "x".repeat(characters)
            page.setContents(PDStream(it, ByteArrayInputStream(decoded.toByteArray()), filters))
            ByteArrayOutputStream().also(it::save).toByteArray()
        }
    }

    private fun pdfWithLiteralStreamTokens(): ByteArray {
        val document = PDDocument()
        return document.use {
            val page = PDPage()
            it.addPage(page)
            val custom = PDStream(it, ByteArrayInputStream("prefix endstream stream suffix".toByteArray()))
            page.cosObject.setItem(COSName.getPDFName("CustomData"), custom.cosObject)
            ByteArrayOutputStream().also(it::save).toByteArray()
        }
    }

    private fun pdfWithText(text: String): ByteArray {
        val document = PDDocument()
        return document.use {
            val page = PDPage()
            it.addPage(page)
            PDPageContentStream(it, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 10f)
                stream.newLineAtOffset(20f, 700f)
                stream.showText(text)
                stream.endText()
            }
            ByteArrayOutputStream().also(it::save).toByteArray()
        }
    }

    private fun workbookWithRows(rows: Int, cells: Int): ByteArray = XSSFWorkbook().use { workbook ->
        val sheet = workbook.createSheet("Bounded")
        repeat(rows) { rowIndex ->
            val row = sheet.createRow(rowIndex)
            repeat(cells) { cellIndex -> row.createCell(cellIndex).setCellValue("value") }
        }
        ByteArrayOutputStream().also(workbook::write).toByteArray()
    }

    private fun docxWithParagraphsAndTableRows(paragraphs: Int, tableRows: Int): ByteArray = XWPFDocument().use { doc ->
        repeat(paragraphs) { doc.createParagraph().createRun().setText("paragraph") }
        if (tableRows > 0) {
            val table = doc.createTable(tableRows, 1)
            repeat(tableRows) { table.getRow(it).getCell(0).text = "row" }
        }
        ByteArrayOutputStream().also(doc::write).toByteArray()
    }

    private fun docxWithRunsAndCells(runs: Int, cells: Int): ByteArray = XWPFDocument().use { doc ->
        val paragraph = doc.createParagraph()
        repeat(runs) { paragraph.createRun().setText("run-$it") }
        if (cells > 0) {
            val table = doc.createTable(1, cells)
            repeat(cells) { table.getRow(0).getCell(it).text = "cell-$it" }
        }
        ByteArrayOutputStream().also(doc::write).toByteArray()
    }

    private fun resourceBytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/crawl/$name")) {
        "missing fixture $name"
    }.use { it.readAllBytes() }

    private fun hexResourceBytes(name: String): ByteArray = resourceBytes(name)
        .toString(Charsets.US_ASCII)
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun docxWithLargeText(characters: Int): ByteArray = rewriteZipEntry(
        resourceBytes("DMM-1000-manual.docx"),
        "word/document.xml",
    ) {
        """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>${"x".repeat(characters)}</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent().toByteArray()
    }
}
