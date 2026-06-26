package com.rfp.controller

import com.ninjasquad.springmockk.MockkBean
import com.rfp.domain.RfpRequest
import com.rfp.domain.enums.RfpStatus
import com.rfp.repository.RequiredInstrumentRepository
import com.rfp.repository.RfpRequestRepository
import com.rfp.security.JwtUtil
import com.rfp.service.DocumentParsingService
import com.rfp.service.LlmService
import com.rfp.service.MatchingService
import com.rfp.service.ReportService
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.Optional

@WebMvcTest(RfpController::class)
class RfpControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @MockkBean lateinit var rfpRepo: RfpRequestRepository
    @MockkBean lateinit var reqInstrRepo: RequiredInstrumentRepository
    @MockkBean lateinit var parsingService: DocumentParsingService
    @MockkBean lateinit var llmService: LlmService
    @MockkBean lateinit var matchingService: MatchingService
    @MockkBean lateinit var reportService: ReportService
    @MockkBean lateinit var jwtUtil: JwtUtil

    @Test
    @WithMockUser
    fun `GET report returns 200 with rfpId and empty items for existing rfp`() {
        every { rfpRepo.findById(1L) } returns Optional.of(
            RfpRequest(id = 1L, userId = 1L, originalFilename = "test.pdf", fileType = "pdf", status = RfpStatus.DONE)
        )
        every { reqInstrRepo.findByRfpRequestId(1L) } returns emptyList()

        mvc.perform(get("/rfp/1/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rfpId").value(1))
            .andExpect(jsonPath("$.items").isArray)
    }

    @Test
    @WithMockUser
    fun `GET report returns 404 when rfp not found`() {
        every { rfpRepo.findById(99L) } returns Optional.empty()

        mvc.perform(get("/rfp/99/report"))
            .andExpect(status().isNotFound)
    }

    @Test
    @WithMockUser
    fun `POST match returns jobId`() {
        every { rfpRepo.findById(1L) } returns Optional.of(
            RfpRequest(id = 1L, userId = 1L, originalFilename = "test.pdf", fileType = "pdf", status = RfpStatus.UPLOADED)
        )
        every { matchingService.matchAsync(1L, listOf(10L, 20L)) } just Runs

        mvc.perform(
            post("/rfp/1/match")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"companyIds":[10,20]}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value("rfp-1-match"))
    }

    @Test
    @WithMockUser
    fun `GET report export with xlsx format returns 200 with file attachment`() {
        val xlsxContent = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK.. - ZIP signature
        every { reportService.exportXlsx(1L) } returns xlsxContent

        mvc.perform(get("/rfp/1/report/export").param("format", "xlsx"))
            .andExpect(status().isOk)
            .andExpect(header().exists("Content-Disposition"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=report-1.xlsx"))
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(content().bytes(xlsxContent))
    }

    @Test
    @WithMockUser
    fun `GET report export with pdf format returns 200 with file attachment`() {
        val pdfContent = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
        every { reportService.exportPdf(1L) } returns pdfContent

        mvc.perform(get("/rfp/1/report/export").param("format", "pdf"))
            .andExpect(status().isOk)
            .andExpect(header().exists("Content-Disposition"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=report-1.pdf"))
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(content().bytes(pdfContent))
    }

    @Test
    @WithMockUser
    fun `GET report export with invalid format returns 400`() {
        mvc.perform(get("/rfp/1/report/export").param("format", "invalid"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @WithMockUser
    fun `GET report export with uppercase format is case-insensitive`() {
        val xlsxContent = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        every { reportService.exportXlsx(1L) } returns xlsxContent

        mvc.perform(get("/rfp/1/report/export").param("format", "XLSX"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", "attachment; filename=report-1.xlsx"))
    }
}
