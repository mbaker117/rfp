package com.rfp.controller

import com.rfp.domain.AppUser
import com.rfp.repository.AppUserRepository
import com.rfp.security.JwtUtil
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthControllerTest {

    private val userRepo = mockk<AppUserRepository>()
    private val jwtUtil = mockk<JwtUtil>()
    private val controller = AuthController(userRepo, jwtUtil)

    @Test
    fun `register persists user and login returns token`() {
        every { userRepo.findByUsername("alice") } returns null
        every { userRepo.save(any()) } answers {
            val u = firstArg<AppUser>()
            u.copy(id = 1L)
        }
        every { jwtUtil.generateToken(1L) } returns "tok123"

        val resp = controller.register(AuthRequest("alice", "secret"))
        assertThat(resp.statusCode.value()).isEqualTo(200)
        assertThat(resp.body?.token).isEqualTo("tok123")
    }

    @Test
    fun `register returns 400 when username taken`() {
        every { userRepo.findByUsername("alice") } returns AppUser(id = 1L, username = "alice", passwordHash = "hash")
        val resp = controller.register(AuthRequest("alice", "secret"))
        assertThat(resp.statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `login returns 401 when user not found`() {
        every { userRepo.findByUsername("ghost") } returns null
        val resp = controller.login(AuthRequest("ghost", "pass"))
        assertThat(resp.statusCode.value()).isEqualTo(401)
    }
}
