package com.rfp.security

import com.rfp.security.JwtUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JwtUtilTest {
    private val jwtUtil = JwtUtil("test-secret-key-that-is-32-chars-long!!")

    @Test
    fun `generateToken produces token that validates to same userId`() {
        val token = jwtUtil.generateToken(42L)
        val extracted = jwtUtil.validateToken(token)
        assertEquals(42L, extracted)
    }

    @Test
    fun `validateToken returns null for tampered token`() {
        val token = jwtUtil.generateToken(1L)
        val tampered = token.dropLast(5) + "XXXXX"
        assertNull(jwtUtil.validateToken(tampered))
    }
}
