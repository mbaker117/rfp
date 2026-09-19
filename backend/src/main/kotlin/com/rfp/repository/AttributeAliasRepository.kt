package com.rfp.repository

import com.rfp.domain.AttributeAlias
import org.springframework.data.jpa.repository.JpaRepository

interface AttributeAliasRepository : JpaRepository<AttributeAlias, Long> {
    fun findByClassId(classId: Long): List<AttributeAlias>
}
