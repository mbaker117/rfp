package com.rfp.repository

import com.rfp.domain.AttributeDef
import org.springframework.data.jpa.repository.JpaRepository

interface AttributeDefRepository : JpaRepository<AttributeDef, Long> {
    fun findByProductClassId(classId: Long): List<AttributeDef>
}
