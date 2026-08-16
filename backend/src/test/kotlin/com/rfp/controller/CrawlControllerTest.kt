package com.rfp.controller

import com.ninjasquad.springmockk.MockkBean
import com.rfp.domain.CrawlRun
import com.rfp.domain.CrawlRunStatus
import com.rfp.domain.Supplier
import com.rfp.repository.CrawlRunRepository
import com.rfp.security.JwtUtil
import com.rfp.service.crawl.CrawlCoordinator
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

@WebMvcTest(CrawlController::class)
class CrawlControllerTest {

    /** Force method-level security on in the WebMvcTest slice so that
     *  @PreAuthorize("hasRole('ADMIN')") on CrawlController is enforced. */
    @TestConfiguration
    @EnableMethodSecurity
    class MethodSecurityConfig

    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var runRepo: CrawlRunRepository

    @MockkBean
    lateinit var coordinator: CrawlCoordinator

    @MockkBean
    lateinit var jwtUtil: JwtUtil

    private val supplier = Supplier(id = 1L, name = "TestSupplier", officialWebsite = "https://test.com")

    private val activeRun = CrawlRun(
        id = 41L,
        supplier = supplier,
        status = CrawlRunStatus.CRAWLING,
        configJson = "{}",
        discoveredUrlCount = 5300
    )

    private val completedRun = CrawlRun(
        id = 99L,
        supplier = supplier,
        status = CrawlRunStatus.COMPLETE,
        configJson = "{}"
    )

    // -----------------------------------------------------------------------
    // GET /crawl-runs/{runId}
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `GET crawl-run returns detail with counts and completeness`() {
        every { runRepo.findById(41L) } returns Optional.of(activeRun)

        mvc.perform(get("/crawl-runs/41"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CRAWLING"))
            .andExpect(jsonPath("$.counts.discovered").value(5300))
            .andExpect(jsonPath("$.completeness.canReconcile").value(false))
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `GET returns 404 for unknown run`() {
        every { runRepo.findById(999L) } returns Optional.empty()

        mvc.perform(get("/crawl-runs/999"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `unauthenticated request returns 401`() {
        mvc.perform(get("/crawl-runs/41"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `non-admin user returns 403`() {
        mvc.perform(get("/crawl-runs/41"))
            .andExpect(status().isForbidden)
    }

    // -----------------------------------------------------------------------
    // POST /crawl-runs/{runId}/cancel
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `cancel returns 202 for active run`() {
        every { runRepo.findById(41L) } returns Optional.of(activeRun)
        every { coordinator.cancel(41L) } just Runs

        mvc.perform(post("/crawl-runs/41/cancel").with(csrf()))
            .andExpect(status().isAccepted)
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `cancel returns 409 for terminal run`() {
        every { runRepo.findById(99L) } returns Optional.of(completedRun)

        mvc.perform(post("/crawl-runs/99/cancel").with(csrf()))
            .andExpect(status().isConflict)
    }

    // -----------------------------------------------------------------------
    // POST /crawl-runs/{runId}/resume
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `resume returns 202 for active run`() {
        every { runRepo.findById(41L) } returns Optional.of(activeRun)
        every { coordinator.resume(41L) } just Runs

        mvc.perform(post("/crawl-runs/41/resume").with(csrf()))
            .andExpect(status().isAccepted)
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `resume returns 409 for complete run`() {
        every { runRepo.findById(99L) } returns Optional.of(completedRun)

        mvc.perform(post("/crawl-runs/99/resume").with(csrf()))
            .andExpect(status().isConflict)
    }

    // -----------------------------------------------------------------------
    // POST /crawl-runs/{runId}/retry-failed
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `retry-failed returns 202 for failed run`() {
        val failedRun = CrawlRun(id = 55L, supplier = supplier, status = CrawlRunStatus.FAILED, configJson = "{}")
        every { runRepo.findById(55L) } returns Optional.of(failedRun)
        every { coordinator.retryFailed(55L) } just Runs

        mvc.perform(post("/crawl-runs/55/retry-failed").with(csrf()))
            .andExpect(status().isAccepted)
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `retry-failed returns 409 for complete run`() {
        every { runRepo.findById(99L) } returns Optional.of(completedRun)

        mvc.perform(post("/crawl-runs/99/retry-failed").with(csrf()))
            .andExpect(status().isConflict)
    }
}
