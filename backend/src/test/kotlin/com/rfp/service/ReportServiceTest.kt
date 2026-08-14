package com.rfp.service

import com.rfp.domain.*
import com.rfp.repository.MatchResultRepository
import com.rfp.repository.ProposalLineRepository
import com.rfp.repository.ProposalRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.repository.TenderRepository
import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.util.Optional

class ReportServiceTest {

    private val tenderRepo = mockk<TenderRepository>()
    private val tenderLineRepo = mockk<TenderLineRepository>()
    private val matchResultRepo = mockk<MatchResultRepository>()
    private val proposalRepo = mockk<ProposalRepository>()
    private val proposalLineRepo = mockk<ProposalLineRepository>()
    private val service = ReportService(tenderRepo, tenderLineRepo, matchResultRepo, proposalRepo, proposalLineRepo)

    private val supplier = Supplier(id = 1, name = "Acme Corp", country = "Jordan")
    private val tender = Tender(id = 1L, userId = 1L, filename = "test.pdf", fileType = "pdf", status = "done")

    private fun makeLine(id: Long, desc: String) =
        TenderLine(id = id, tender = tender, rawText = desc, description = desc)

    private fun makeResult(line: TenderLine, productName: String? = null, score: Int = 90): MatchResult {
        val product = productName?.let { Product(id = 1L, supplier = supplier, name = it, source = "scrape") }
        return MatchResult(
            id = line.id,
            line = line,
            product = product,
            matchType = if (product != null) "spec" else null,
            score = score,
            status = if (score >= 80) "matched" else if (score >= 40) "partial" else "not_found"
        )
    }

    @Test
    fun `exportXlsx returns valid Excel file with headers`() {
        val line = makeLine(1L, "Digital Oscilloscope 200MHz")
        val result = makeResult(line, "Oscilloscope 200MHz", 95)
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { matchResultRepo.findByLineTenderId(1L) } returns listOf(result)

        val bytes = service.exportXlsx(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val wb = XSSFWorkbook(ByteArrayInputStream(bytes))
        val sheet = wb.getSheetAt(0)
        assertNotNull(sheet)
        assertEquals("Report", sheet.sheetName)
        val header = sheet.getRow(0)
        assertNotNull(header)
        assertEquals("Line", header.getCell(0).stringCellValue)
        assertEquals("Score", header.getCell(6).stringCellValue)
        wb.close()
    }

    @Test
    fun `exportXlsx with no match leaves product columns empty`() {
        val line = makeLine(1L, "Unmatched Item")
        val result = makeResult(line, null, 0)
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { matchResultRepo.findByLineTenderId(1L) } returns listOf(result)

        val bytes = service.exportXlsx(1L)
        val wb = XSSFWorkbook(ByteArrayInputStream(bytes))
        val row = wb.getSheetAt(0).getRow(1)
        assertEquals("", row.getCell(3).stringCellValue)  // Matched Product
        assertEquals("not_found", row.getCell(7).stringCellValue)
        wb.close()
    }

    @Test
    fun `exportXlsx with empty results returns valid workbook with headers only`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { matchResultRepo.findByLineTenderId(1L) } returns emptyList()

        val bytes = service.exportXlsx(1L)
        val wb = XSSFWorkbook(ByteArrayInputStream(bytes))
        val sheet = wb.getSheetAt(0)
        assertNotNull(sheet.getRow(0))
        assertNull(sheet.getRow(1))
        wb.close()
    }

    @Test
    fun `exportXlsx throws when tender not found`() {
        every { tenderRepo.findById(99L) } returns Optional.empty()
        val ex = assertThrows<NoSuchElementException> { service.exportXlsx(99L) }
        assertTrue(ex.message?.contains("99") == true)
    }

    @Test
    fun `exportXlsx with proposalId uses proposal selected products`() {
        val proposalId = 10L
        val line = makeLine(1L, "Digital Oscilloscope 200MHz")
        val result = makeResult(line, "Oscilloscope 200MHz", 95)
        val selectedProduct = Product(id = 2L, supplier = supplier, name = "Proposal Product", source = "scrape")
        val proposal = Proposal(id = proposalId, tender = tender, variant = "A")
        val proposalLine = ProposalLine(
            id = 1L,
            proposal = proposal,
            line = line,
            selectedProduct = selectedProduct
        )
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { proposalLineRepo.findByProposalId(proposalId) } returns listOf(proposalLine)
        every { matchResultRepo.findByLineTenderId(1L) } returns listOf(result)

        val bytes = service.exportXlsx(tenderId = 1L, proposalId = proposalId)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val wb = XSSFWorkbook(ByteArrayInputStream(bytes))
        val sheet = wb.getSheetAt(0)
        val dataRow = sheet.getRow(1)
        assertEquals("Proposal Product", dataRow.getCell(3).stringCellValue)
        wb.close()
    }

    @Test
    fun `exportPdf returns valid PDF with at least one page`() {
        val line = makeLine(1L, "Test Instrument")
        val result = makeResult(line, "Matched Instrument", 90)
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { matchResultRepo.findByLineTenderId(1L) } returns listOf(result)

        val bytes = service.exportPdf(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())
        val doc = Loader.loadPDF(bytes)
        assertTrue(doc.numberOfPages >= 1)
        doc.close()
    }

    @Test
    fun `exportPdf with empty results returns single-page PDF`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { matchResultRepo.findByLineTenderId(1L) } returns emptyList()

        val bytes = service.exportPdf(1L)
        val doc = Loader.loadPDF(bytes)
        assertEquals(1, doc.numberOfPages)
        doc.close()
    }

    @Test
    fun `exportPdf with 50 items generates multiple pages`() {
        val results = (1..50).map { i ->
            makeResult(makeLine(i.toLong(), "Item $i"), "Product $i", 90)
        }
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { matchResultRepo.findByLineTenderId(1L) } returns results

        val bytes = service.exportPdf(1L)
        val doc = Loader.loadPDF(bytes)
        assertTrue(doc.numberOfPages >= 2, "50 items should span 2+ pages, got ${doc.numberOfPages}")
        doc.close()
    }

    @Test
    fun `exportPdf throws when tender not found`() {
        every { tenderRepo.findById(99L) } returns Optional.empty()
        val ex = assertThrows<NoSuchElementException> { service.exportPdf(99L) }
        assertTrue(ex.message?.contains("99") == true)
    }
}
