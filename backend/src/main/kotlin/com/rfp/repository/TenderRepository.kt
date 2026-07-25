package com.rfp.repository

import com.rfp.domain.Tender
import org.springframework.data.jpa.repository.JpaRepository

interface TenderRepository : JpaRepository<Tender, Long>
