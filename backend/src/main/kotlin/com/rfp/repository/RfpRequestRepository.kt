package com.rfp.repository

import com.rfp.domain.RfpRequest
import org.springframework.data.jpa.repository.JpaRepository

interface RfpRequestRepository : JpaRepository<RfpRequest, Long>
