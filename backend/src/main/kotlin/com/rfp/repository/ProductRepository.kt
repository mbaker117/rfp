package com.rfp.repository

import com.rfp.domain.Product
import org.springframework.data.jpa.repository.JpaRepository

interface ProductRepository : JpaRepository<Product, Long> {
    fun findBySupplierIdInAndIsStaleAndNameIgnoreCase(
        supplierIds: List<Long>, isStale: Boolean, name: String
    ): List<Product>
    fun findBySupplierIdInAndIsStaleAndMpnIgnoreCase(
        supplierIds: List<Long>, isStale: Boolean, mpn: String
    ): List<Product>
    fun findBySupplierIdInAndIsStaleAndProductClassId(
        supplierIds: List<Long>, isStale: Boolean, classId: Long
    ): List<Product>
    fun findBySupplierIdAndMpnIgnoreCase(supplierId: Long, mpn: String): Product?
    fun findBySupplierIdAndNameIgnoreCase(supplierId: Long, name: String): Product?
    fun findBySupplierId(supplierId: Long): List<Product>
}
