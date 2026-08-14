package com.rfp.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date

@Component
class JwtUtil(@Value("\${rfp.jwt.secret}") private val secret: String) {

    private val key = Keys.hmacShaKeyFor(secret.toByteArray())
    private val expiryMs = 24 * 60 * 60 * 1000L

    fun generateToken(userId: Long, role: String): String = Jwts.builder()
        .subject(userId.toString())
        .claim("role", role)
        .expiration(Date(System.currentTimeMillis() + expiryMs))
        .signWith(key)
        .compact()

    fun validateToken(token: String): Pair<Long, String>? = runCatching {
        val claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload
        val userId = claims.subject.toLong()
        val role = claims.get("role", String::class.java) ?: "USER"
        Pair(userId, role)
    }.getOrNull()
}
