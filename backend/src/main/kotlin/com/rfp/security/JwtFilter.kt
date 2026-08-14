package com.rfp.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtFilter(private val jwtUtil: JwtUtil) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val claim = jwtUtil.validateToken(header.removePrefix("Bearer "))
            if (claim != null) {
                val (userId, role) = claim
                val auth = UsernamePasswordAuthenticationToken(
                    userId, null, listOf(SimpleGrantedAuthority("ROLE_$role"))
                )
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        chain.doFilter(request, response)
    }
}
