package com.rfp.repository

import com.rfp.domain.ProductPrice
import org.springframework.data.jpa.repository.JpaRepository

interface ProductPriceRepository : JpaRepository<ProductPrice, Long>
