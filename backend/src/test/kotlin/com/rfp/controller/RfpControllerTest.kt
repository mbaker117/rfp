package com.rfp.controller

import com.ninjasquad.springmockk.MockkBean
import com.rfp.domain.*
import com.rfp.repository.MatchResultRepository
import com.rfp.repository.ProposalLineRepository
import com.rfp.repository.ProposalRepository
import com.rfp.repository.SupplierRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.repository.TenderRepository
import com.rfp.repository.TenderSupplierRepository
import com.rfp.security.JwtUtil
import com.rfp.service.MatchingEngineService
import com.rfp.service.ReportService
import com.rfp.service.TenderExtractionService
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.Optional

@WebMvcTest(RfpController::class)
class RfpControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @MockkBean lateinit var tenderRepo: TenderRepository
    @MockkBean lateinit var tenderLineRepo: TenderLineRepository
    @MockkBean lateinit var matchResultRepo: MatchResultRepository
    @MockkBean lateinit var extractionService: TenderExtractionService
    @MockkBean lateinit var matchingService: MatchingEngineService
    @MockkBean lateinit var reportService: ReportService
    @MockkBean lateinit var proposalRepo: ProposalRepository
    @MockkBean lateinit var proposalLineRepo: ProposalLineRepository
    @MockkBean lateinit var tenderSupplierRepo: TenderSupplierRepository
    @MockkBean lateinit var supplierRepo: SupplierRepository
    @MockkBean lateinit var jwtUtil: JwtUtil

    private val tender = Tender(id = 1L, userId = 1L, filename = "test.pdf", fileType = "pdf", status = "done")

    @Test
    @WithMockUser(username = "1")
    fun `GET report returns 200 with rfpId and empty items`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { tenderLineRepo.findByTenderId(1L) } returns emptyList()
        every { matchResultRepo.findByLineTenderId(1L) } returns emptyList()

        mvc.perform(get("/rfp/1/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rfpId").value(1))
            .andExpect(jsonPath("$.items").isArray)
    }

    @Test
    @WithMockUser(username = "1")
    fun `GET report returns 404 when tender not found`() {
        every { tenderRepo.findById(99L) } returns Optional.empty()

        mvc.perform(get("/rfp/99/report"))
            .andExpect(status().isNotFound)
    }

    @Test
    @WithMockUser(username = "1")
    fun `POST match returns jobId`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { proposalRepo.findByTenderId(1L) } returns emptyList()
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { matchResultRepo.deleteByLineTenderId(1L) } just Runs
        every { tenderRepo.save(any()) } answers { firstArg() }
        every { matchingService.matchAsync(1L) } just Runs

        mvc.perform(post("/rfp/1/match").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value("rfp-1-match"))
    }

    @Test
    @WithMockUser(username = "1")
    fun `GET report export xlsx returns attachment`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        val xlsxBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        every { reportService.exportXlsx(1L, null) } returns xlsxBytes

        mvc.perform(get("/rfp/1/report/export").param("format", "xlsx"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", "attachment; filename=report-1.xlsx"))
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }

    @Test
    @WithMockUser(username = "1")
    fun `GET report export pdf returns attachment`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        every { reportService.exportPdf(1L, null) } returns pdfBytes

        mvc.perform(get("/rfp/1/report/export").param("format", "pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", "attachment; filename=report-1.pdf"))
            .andExpect(header().string("Content-Type", "application/pdf"))
    }

    @Test
    @WithMockUser(username = "1")
    fun `GET report export with invalid format returns 400`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        mvc.perform(get("/rfp/1/report/export").param("format", "docx"))
            .andExpect(status().isBadRequest)
    }

    // --- New tests for Task 1 ---

    @Test
    @WithMockUser(username = "1")
    fun `GET rfp returns list of user tenders`() {
        every { tenderRepo.findByUserIdOrderByCreatedAtDesc(1L) } returns listOf(tender)
        every { tenderLineRepo.findByTenderId(1L) } returns emptyList()
        every { proposalRepo.findByTenderId(1L) } returns emptyList()
        every { tenderSupplierRepo.findSupplierIdsByTenderId(1L) } returns listOf(42L)

        mvc.perform(get("/rfp"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].filename").value("test.pdf"))
            .andExpect(jsonPath("$[0].supplierIds[0]").value(42))
            .andExpect(jsonPath("$[0].lineCount").value(0))
            .andExpect(jsonPath("$[0].proposalCount").value(0))
    }

    @Test
    @WithMockUser(username = "2")
    fun `GET rfp returns empty list for user with no tenders`() {
        every { tenderRepo.findByUserIdOrderByCreatedAtDesc(2L) } returns emptyList()

        mvc.perform(get("/rfp"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    @WithMockUser(username = "1")
    fun `DELETE rfp returns 204 for owner`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { proposalRepo.findByTenderId(1L) } returns emptyList()
        every { proposalLineRepo.deleteByProposalId(any()) } just Runs
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { matchResultRepo.deleteByLineTenderId(1L) } just Runs
        every { tenderSupplierRepo.deleteByTenderId(1L) } just Runs
        every { tenderLineRepo.deleteByTenderId(1L) } just Runs
        every { tenderRepo.deleteById(1L) } just Runs

        mvc.perform(delete("/rfp/1").with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    @WithMockUser(username = "99")
    fun `DELETE rfp returns 403 for non-owner`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)

        mvc.perform(delete("/rfp/1").with(csrf()))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "99")
    fun `GET report returns 403 for non-owner`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)

        mvc.perform(get("/rfp/1/report"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "99")
    fun `GET report export returns 403 for non-owner`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)

        mvc.perform(get("/rfp/1/report/export").param("format", "pdf"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "1")
    fun `POST match with supplierIds body updates suppliers and returns jobId`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { tenderSupplierRepo.deleteByTenderId(1L) } just Runs
        every { tenderSupplierRepo.save(any()) } answers { firstArg() }
        every { supplierRepo.getReferenceById(any()) } returns mockk(relaxed = true)
        every { proposalRepo.findByTenderId(1L) } returns emptyList()
        every { proposalLineRepo.deleteByProposalId(any()) } just Runs
        every { proposalRepo.deleteByTenderId(1L) } just Runs
        every { matchResultRepo.deleteByLineTenderId(1L) } just Runs
        every { tenderRepo.save(any()) } answers { firstArg() }
        every { matchingService.matchAsync(1L) } just Runs

        mvc.perform(
            post("/rfp/1/match").with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"supplierIds":[5,6]}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value("rfp-1-match"))

        io.mockk.verify(exactly = 1) { tenderSupplierRepo.deleteByTenderId(1L) }
        io.mockk.verify(atLeast = 1) { tenderSupplierRepo.save(any()) }
    }
}
