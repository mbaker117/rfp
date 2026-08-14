package com.rfp.controller

import com.ninjasquad.springmockk.MockkBean
import com.rfp.repository.AttributeDefRepository
import com.rfp.repository.ProductPriceRepository
import com.rfp.repository.ProductRepository
import com.rfp.repository.TenderLineRepository
import com.rfp.repository.TenderSupplierRepository
import com.rfp.security.JwtUtil
import com.rfp.service.ProposalDto
import com.rfp.service.ProposalService
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(ProposalController::class)
class ProposalControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @MockkBean lateinit var proposalService: ProposalService
    @MockkBean lateinit var jwtUtil: JwtUtil
    @MockkBean lateinit var tenderLineRepo: TenderLineRepository
    @MockkBean lateinit var productRepo: ProductRepository
    @MockkBean lateinit var productPriceRepo: ProductPriceRepository
    @MockkBean lateinit var attrDefRepo: AttributeDefRepository
    @MockkBean lateinit var tenderSupplierRepo: TenderSupplierRepository

    @Test
    @WithMockUser
    fun `POST proposals triggers generation and returns generating status`() {
        every { proposalService.generateProposals(1L) } just Runs

        mvc.perform(post("/rfp/1/proposals").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("generating"))
    }

    @Test
    @WithMockUser
    fun `GET proposals returns list`() {
        every { proposalService.getProposals(1L) } returns listOf(
            ProposalDto(
                id = 1L, variant = "PERFECT", status = "READY",
                acceptanceRate = java.math.BigDecimal("89.00"),
                matchScore = java.math.BigDecimal("94.00"),
                isComplete = true, lines = emptyList()
            )
        )

        mvc.perform(get("/rfp/1/proposals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].variant").value("PERFECT"))
            .andExpect(jsonPath("$[0].status").value("READY"))
            .andExpect(jsonPath("$[0].acceptanceRate").value(89.0))
    }

    @Test
    @WithMockUser
    fun `PATCH override returns ok`() {
        every { proposalService.overrideLine(1L, 5L, 42L) } just Runs

        mvc.perform(
            patch("/rfp/1/proposals/1/lines/5")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"productId": 42}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))
    }
}
