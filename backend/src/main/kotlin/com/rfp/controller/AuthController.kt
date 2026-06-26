package com.rfp.controller

import com.rfp.security.JwtUtil
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.bind.annotation.*

data class AuthRequest(val username: String, val password: String)
data class AuthResponse(val token: String)

@RestController
@RequestMapping("/auth")
class AuthController(private val jwtUtil: JwtUtil) {

    // In-memory stub — replace with UserRepository in production
    private val encoder = BCryptPasswordEncoder()
    private val users = mutableMapOf<String, Pair<Long, String>>() // username -> (id, hash)

    @PostMapping("/register")
    fun register(@RequestBody req: AuthRequest): ResponseEntity<AuthResponse> {
        if (users.containsKey(req.username)) return ResponseEntity.badRequest().build()
        val id = users.size + 1L
        users[req.username] = id to encoder.encode(req.password)
        return ResponseEntity.ok(AuthResponse(jwtUtil.generateToken(id)))
    }

    @PostMapping("/login")
    fun login(@RequestBody req: AuthRequest): ResponseEntity<AuthResponse> {
        val (id, hash) = users[req.username] ?: return ResponseEntity.status(401).build()
        if (!encoder.matches(req.password, hash)) return ResponseEntity.status(401).build()
        return ResponseEntity.ok(AuthResponse(jwtUtil.generateToken(id)))
    }
}
