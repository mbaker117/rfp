package com.rfp.repository

import com.rfp.domain.TenderSupplier
import com.rfp.domain.TenderSupplierId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TenderSupplierRepository : JpaRepository<TenderSupplier, TenderSupplierId> {
    @Query("SELECT ts.supplier.id FROM TenderSupplier ts WHERE ts.tender.id = :tenderId")
    fun findSupplierIdsByTenderId(tenderId: Long): List<Long>
}
