package com.rfp.controller

import com.ninjasquad.springmockk.MockkBean
import com.rfp.domain.*
import com.rfp.repository.MatchResultRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.repository.TenderRepository
import com.rfp.security.JwtUtil
import com.rfp.service.MatchingEngineService
import com.rfp.service.ReportService
import com.rfp.service.TenderExtractionService
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
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
    @MockkBean lateinit var jwtUtil: JwtUtil

    private val tender = Tender(id = 1L, userId = 1L, filename = "test.pdf", fileType = "pdf", status = "done")

    @Test
    @WithMockUser
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
    @WithMockUser
    fun `GET report returns 404 when tender not found`() {
        every { tenderRepo.findById(99L) } returns Optional.empty()

        mvc.perform(get("/rfp/99/report"))
            .andExpect(status().isNotFound)
    }

    @Test
    @WithMockUser
    fun `POST match returns jobId`() {
        every { tenderRepo.findById(1L) } returns Optional.of(tender)
        every { matchingService.matchAsync(1L) } just Runs

        mvc.perform(post("/rfp/1/match").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value("rfp-1-match"))
    }

    @Test
    @WithMockUser
    fun `GET report export xlsx returns attachment`() {
        val xlsxBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        every { reportService.exportXlsx(1L) } returns xlsxBytes

        mvc.perform(get("/rfp/1/report/export").param("format", "xlsx"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", "attachment; filename=report-1.xlsx"))
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }

    @Test
    @WithMockUser
    fun `GET report export pdf returns attachment`() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        every { reportService.exportPdf(1L) } returns pdfBytes

        mvc.perform(get("/rfp/1/report/export").param("format", "pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", "attachment; filename=report-1.pdf"))
            .andExpect(header().string("Content-Type", "application/pdf"))
    }

    @Test
    @WithMockUser
    fun `GET report export with invalid format returns 400`() {
        mvc.perform(get("/rfp/1/report/export").param("format", "docx"))
            .andExpect(status().isBadRequest)
    }
}
