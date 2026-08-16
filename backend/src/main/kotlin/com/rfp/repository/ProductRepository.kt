package com.rfp.repository

import com.rfp.domain.Product
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

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
    fun findBySupplierIdAndIdentityKey(supplierId: Long, identityKey: String): Product?

    @Query(
        value = """
            SELECT p FROM Product p
            JOIN FETCH p.supplier
            LEFT JOIN FETCH p.productClass
            WHERE (:q IS NULL OR :q = ''
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.mpn,'')) LIKE LOWER(CONCAT('%', :q, '%')))
        """,
        countQuery = """
            SELECT COUNT(p) FROM Product p
            WHERE (:q IS NULL OR :q = ''
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.mpn,'')) LIKE LOWER(CONCAT('%', :q, '%')))
        """
    )
    fun searchByNameOrMpn(q: String?, pageable: Pageable): Page<Product>

    @Query(
        value = """
            SELECT p FROM Product p
            JOIN FETCH p.supplier
            LEFT JOIN FETCH p.productClass
            WHERE p.supplier.id = :supplierId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.mpn,'')) LIKE LOWER(CONCAT('%', :q, '%')))
        """,
        countQuery = """
            SELECT COUNT(p) FROM Product p
            WHERE p.supplier.id = :supplierId
              AND (:q IS NULL OR :q = ''
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.mpn,'')) LIKE LOWER(CONCAT('%', :q, '%')))
        """
    )
    fun searchBySupplierAndNameOrMpn(supplierId: Long, q: String?, pageable: Pageable): Page<Product>
}
