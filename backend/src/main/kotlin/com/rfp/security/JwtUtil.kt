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

    fun generateToken(userId: Long): String = Jwts.builder()
        .subject(userId.toString())
        .expiration(Date(System.currentTimeMillis() + expiryMs))
        .signWith(key)
        .compact()

    fun validateToken(token: String): Long? = runCatching {
        Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload.subject.toLong()
    }.getOrNull()
}
