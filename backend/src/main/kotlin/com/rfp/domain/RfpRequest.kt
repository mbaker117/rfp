package com.rfp.domain

import com.rfp.domain.enums.RfpStatus
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "rfp_request")
data class RfpRequest(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val userId: Long,
    val originalFilename: String,
    val fileType: String,
    @Enumerated(EnumType.STRING)
    var status: RfpStatus = RfpStatus.UPLOADED,
    val createdAt: Instant = Instant.now()
)
