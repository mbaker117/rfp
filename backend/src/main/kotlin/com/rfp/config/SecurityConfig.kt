package com.rfp.config

import com.rfp.security.JwtFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(private val jwtFilter: JwtFilter) {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .cors { it.configurationSource(corsConfigurationSource()) }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(AntPathRequestMatcher("/auth/**")).permitAll()
            // Use AntPathRequestMatcher to bypass Spring MVC handler-mapping checks.
            // This ensures the rules apply even in @WebMvcTest contexts where not all
            // controllers are loaded.
            it.requestMatchers(AntPathRequestMatcher("/crawl-runs/**")).hasRole("ADMIN")
            it.requestMatchers(AntPathRequestMatcher("/admin/**")).hasRole("ADMIN")
            it.anyRequest().authenticated()
        }
        .exceptionHandling { exceptions ->
            // Return 401 for unauthenticated requests (not the default 403).
            exceptions.authenticationEntryPoint { _, response, _ ->
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
            }
        }
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
        .build()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = listOf("http://localhost:3000")
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Authorization", "Content-Type")
        config.allowCredentials = true
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
