package com.rfp.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "instrument_price_history")
data class InstrumentPriceHistory(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id")
    val instrument: Instrument,
    val price: BigDecimal,
    val currency: String = "JOD",
    val recordedAt: Instant = Instant.now()
)
