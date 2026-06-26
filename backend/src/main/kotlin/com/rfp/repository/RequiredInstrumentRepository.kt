package com.rfp.repository

import com.rfp.domain.RequiredInstrument
import org.springframework.data.jpa.repository.JpaRepository

interface RequiredInstrumentRepository : JpaRepository<RequiredInstrument, Long> {
    fun findByRfpRequestId(rfpRequestId: Long): List<RequiredInstrument>
}
