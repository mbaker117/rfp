package com.rfp.domain

import jakarta.persistence.*

@Entity @Table(name = "app_user")
data class AppUser(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val username: String,
    val passwordHash: String,
    val role: String = "USER"   // USER, ADMIN
)
