package com.rfp.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class UnitNormalizationServiceTest {

    @Test
    fun `converts kV to V`() {
        val jdbc = mockk<JdbcTemplate>()
        every {
            jdbc.queryForList(any<String>(), eq("kV"), eq("V"))
        } returns listOf(mapOf("factor" to 1000.0, "addend" to 0.0))
        val service = UnitNormalizationService(jdbc)
        assertThat(service.normalize(5.0, "kV", "V")).isEqualTo(5000.0)
    }

    @Test
    fun `returns value unchanged when units match`() {
        val jdbc = mockk<JdbcTemplate>()
        val service = UnitNormalizationService(jdbc)
        assertThat(service.normalize(230.0, "V", "V")).isEqualTo(230.0)
    }
}
