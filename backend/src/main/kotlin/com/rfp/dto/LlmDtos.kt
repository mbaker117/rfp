package com.rfp.dto

import com.rfp.domain.enums.MatchStatus
import java.math.BigDecimal

data class ExtractedRequirement(
    val rawText: String,
    val name: String,
    val quantity: Int?,
    val specs: Map<String, String>
)

data class ScrapedInstrument(
    val description: String,
    val normalizedName: String,
    val manualLink: String?,
    val price: BigDecimal?,
    val currency: String = "JOD"
)

data class MatchResult(
    val matchedInstrumentId: Long?,
    val score: Int,
    val reason: String,
    val status: MatchStatus
)
