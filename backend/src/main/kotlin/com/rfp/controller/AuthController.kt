package com.rfp.controller

import com.rfp.domain.AppUser
import com.rfp.repository.AppUserRepository
import com.rfp.security.JwtUtil
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.bind.annotation.*

data class AuthRequest(val username: String, val password: String)
data class AuthResponse(val token: String)

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepo: AppUserRepository,
    private val jwtUtil: JwtUtil
) {
    private val encoder = BCryptPasswordEncoder()

    @PostMapping("/register")
    fun register(@RequestBody req: AuthRequest): ResponseEntity<AuthResponse> {
        if (userRepo.findByUsername(req.username) != null)
            return ResponseEntity.badRequest().build()
        val saved = userRepo.save(AppUser(username = req.username, passwordHash = encoder.encode(req.password)))
        return ResponseEntity.ok(AuthResponse(jwtUtil.generateToken(saved.id, saved.role)))
    }

    @PostMapping("/login")
    fun login(@RequestBody req: AuthRequest): ResponseEntity<AuthResponse> {
        val user = userRepo.findByUsername(req.username)
            ?: return ResponseEntity.status(401).build()
        if (!encoder.matches(req.password, user.passwordHash))
            return ResponseEntity.status(401).build()
        return ResponseEntity.ok(AuthResponse(jwtUtil.generateToken(user.id, user.role)))
    }
}
