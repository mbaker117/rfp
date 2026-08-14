package com.rfp.security

import com.rfp.security.JwtUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JwtUtilTest {
    private val jwtUtil = JwtUtil("test-secret-key-that-is-32-chars-long!!")

    @Test
    fun `generateToken produces token that validates to same userId and role`() {
        val token = jwtUtil.generateToken(42L, "ADMIN")
        val extracted = jwtUtil.validateToken(token)
        assertEquals(Pair(42L, "ADMIN"), extracted)
    }

    @Test
    fun `validateToken returns null for tampered token`() {
        val token = jwtUtil.generateToken(1L, "USER")
        val tampered = token.dropLast(5) + "XXXXX"
        assertNull(jwtUtil.validateToken(tampered))
    }
}
