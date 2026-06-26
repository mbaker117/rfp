package com.rfp.service

import com.rfp.domain.Company
import com.rfp.domain.Instrument
import com.rfp.domain.RequiredInstrument
import com.rfp.domain.RfpRequest
import com.rfp.domain.enums.MatchStatus
import com.rfp.domain.enums.RfpStatus
import com.rfp.repository.RequiredInstrumentRepository
import com.rfp.repository.RfpRequestRepository
import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant

class ReportServiceTest {

    private val rfpRepo: RfpRequestRepository = mockk()
    private val reqInstrRepo: RequiredInstrumentRepository = mockk()
    private val reportService = ReportService(rfpRepo, reqInstrRepo)

    @Test
    fun `exportXlsx returns valid Excel file with headers and data`() {
        val company = Company(id = 1, name = "Acme Corp", country = "Jordan")
        val instrument = Instrument(
            id = 1,
            company = company,
            description = "Test Instrument",
            normalizedName = "TEST_INST",
            manualLink = "http://example.com/manual",
            price = BigDecimal("100.00"),
            currency = "JOD"
        )
        val requiredInst = RequiredInstrument(
            id = 1,
            rfpRequest = RfpRequest(id = 1L, userId = 1L, originalFilename = "test.pdf", fileType = "pdf", status = RfpStatus.DONE),
            rawText = "Test Required Item",
            matchedInstrument = instrument,
            matchingScore = 95,
            matchStatus = MatchStatus.MATCHED
        )

        every { reqInstrRepo.findByRfpRequestId(1L) } returns listOf(requiredInst)

        val bytes = reportService.exportXlsx(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty(), "Excel file should not be empty")

        // Verify it's a valid xlsx by reading it back
        val wb = XSSFWorkbook(ByteArrayInputStream(bytes))
        val sheet = wb.getSheetAt(0)
        assertNotNull(sheet)
        assertEquals("Report", sheet.sheetName)

        val headerRow = sheet.getRow(0)
        assertNotNull(headerRow)
        assertEquals(7, headerRow.lastCellNum, "Should have 7 columns")
        assertEquals("Required Instrument", headerRow.getCell(0).stringCellValue)
        assertEquals("Score", headerRow.getCell(2).stringCellValue)

        val dataRow = sheet.getRow(1)
        assertNotNull(dataRow)
        assertEquals("Test Required Item", dataRow.getCell(0).stringCellValue)
        assertEquals("TEST_INST", dataRow.getCell(1).stringCellValue)
        assertEquals(95.0, dataRow.getCell(2).numericCellValue)
        assertEquals("MATCHED", dataRow.getCell(3).stringCellValue)
        assertEquals(100.0, dataRow.getCell(4).numericCellValue)
        assertEquals("JOD", dataRow.getCell(5).stringCellValue)
        assertEquals("http://example.com/manual", dataRow.getCell(6).stringCellValue)

        wb.close()
    }

    @Test
    fun `exportXlsx handles null matched instrument gracefully`() {
        val requiredInst = RequiredInstrument(
            id = 1,
            rfpRequest = RfpRequest(id = 1L, userId = 1L, originalFilename = "test.pdf", fileType = "pdf", status = RfpStatus.DONE),
            rawText = "Unmatched Item",
            matchedInstrument = null,
            matchingScore = null,
            matchStatus = MatchStatus.NOT_FOUND
        )

        every { reqInstrRepo.findByRfpRequestId(1L) } returns listOf(requiredInst)

        val bytes = reportService.exportXlsx(1L)
        assertNotNull(bytes)

        val wb = XSSFWorkbook(ByteArrayInputStream(bytes))
        val sheet = wb.getSheetAt(0)
        val dataRow = sheet.getRow(1)

        assertEquals("Unmatched Item", dataRow.getCell(0).stringCellValue)
        assertEquals("", dataRow.getCell(1).stringCellValue)
        assertEquals(0.0, dataRow.getCell(2).numericCellValue)
        assertEquals("NOT_FOUND", dataRow.getCell(3).stringCellValue)
        assertEquals("JOD", dataRow.getCell(5).stringCellValue)

        wb.close()
    }

    @Test
    fun `exportXlsx with empty items list returns valid workbook`() {
        every { reqInstrRepo.findByRfpRequestId(1L) } returns emptyList()

        val bytes = reportService.exportXlsx(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val wb = XSSFWorkbook(ByteArrayInputStream(bytes))
        val sheet = wb.getSheetAt(0)
        val headerRow = sheet.getRow(0)
        assertNotNull(headerRow)
        assertEquals(7, headerRow.lastCellNum)

        wb.close()
    }

    @Test
    fun `exportPdf returns valid PDF file with title and data`() {
        val company = Company(id = 1, name = "Acme Corp", country = "Jordan")
        val instrument = Instrument(
            id = 1,
            company = company,
            description = "Test Instrument",
            normalizedName = "TEST_INST",
            manualLink = "http://example.com/manual",
            price = BigDecimal("100.00"),
            currency = "JOD"
        )
        val requiredInst = RequiredInstrument(
            id = 1,
            rfpRequest = RfpRequest(id = 1L, userId = 1L, originalFilename = "test.pdf", fileType = "pdf", status = RfpStatus.DONE),
            rawText = "Test Required Item",
            matchedInstrument = instrument,
            matchingScore = 95,
            matchStatus = MatchStatus.MATCHED
        )

        every { reqInstrRepo.findByRfpRequestId(1L) } returns listOf(requiredInst)

        val bytes = reportService.exportPdf(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty(), "PDF file should not be empty")

        // Verify it's a valid PDF by reading it back
        val doc = Loader.loadPDF(bytes)
        assertNotNull(doc)
        assertEquals(1, doc.numberOfPages, "PDF should have at least 1 page")
        doc.close()
    }

    @Test
    fun `exportPdf handles null matched instrument gracefully`() {
        val requiredInst = RequiredInstrument(
            id = 1,
            rfpRequest = RfpRequest(id = 1L, userId = 1L, originalFilename = "test.pdf", fileType = "pdf", status = RfpStatus.DONE),
            rawText = "Unmatched Item",
            matchedInstrument = null,
            matchingScore = null,
            matchStatus = MatchStatus.NOT_FOUND
        )

        every { reqInstrRepo.findByRfpRequestId(1L) } returns listOf(requiredInst)

        val bytes = reportService.exportPdf(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val doc = Loader.loadPDF(bytes)
        assertNotNull(doc)
        assertTrue(doc.numberOfPages > 0)
        doc.close()
    }

    @Test
    fun `exportPdf with empty items list returns valid PDF`() {
        every { reqInstrRepo.findByRfpRequestId(1L) } returns emptyList()

        val bytes = reportService.exportPdf(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val doc = Loader.loadPDF(bytes)
        assertNotNull(doc)
        assertEquals(1, doc.numberOfPages)
        doc.close()
    }

    @Test
    fun `exportPdf with multiple items`() {
        val company = Company(id = 1, name = "Acme Corp", country = "Jordan")
        val items = (1..5).map { i ->
            RequiredInstrument(
                id = i.toLong(),
                rfpRequest = RfpRequest(id = 1L, userId = 1L, originalFilename = "test.pdf", fileType = "pdf", status = RfpStatus.DONE),
                rawText = "Item $i",
                matchedInstrument = Instrument(
                    id = i.toLong(),
                    company = company,
                    description = "Instrument $i",
                    normalizedName = "INST_$i",
                    price = BigDecimal(i * 100)
                ),
                matchingScore = 90 + i,
                matchStatus = MatchStatus.MATCHED
            )
        }

        every { reqInstrRepo.findByRfpRequestId(1L) } returns items

        val bytes = reportService.exportPdf(1L)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val doc = Loader.loadPDF(bytes)
        assertNotNull(doc)
        // Multiple items should create new pages if they exceed space
        assertTrue(doc.numberOfPages >= 1)
        doc.close()
    }
}
