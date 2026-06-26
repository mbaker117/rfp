package com.rfp.repository

import com.rfp.domain.InstrumentPriceHistory
import org.springframework.data.jpa.repository.JpaRepository

interface InstrumentPriceHistoryRepository : JpaRepository<InstrumentPriceHistory, Long> {
    fun findByInstrumentId(instrumentId: Long): List<InstrumentPriceHistory>
}
