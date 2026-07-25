package com.rfp.repository

import com.rfp.domain.ProductClass
import org.springframework.data.jpa.repository.JpaRepository

interface ProductClassRepository : JpaRepository<ProductClass, Long> {
    fun findByNameIgnoreCase(name: String): ProductClass?
}
