package com.rfp.repository

import com.rfp.domain.ProductPriceHistory
import org.springframework.data.jpa.repository.JpaRepository

interface ProductPriceHistoryRepository : JpaRepository<ProductPriceHistory, Long>
