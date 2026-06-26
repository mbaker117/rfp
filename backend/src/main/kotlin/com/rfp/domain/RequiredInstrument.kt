package com.rfp.domain

import com.rfp.domain.enums.MatchStatus
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "required_instrument")
data class RequiredInstrument(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfp_request_id")
    val rfpRequest: RfpRequest,
    val rawText: String,
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var extractedSpec: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_instrument_id")
    var matchedInstrument: Instrument? = null,
    var matchingScore: Int? = null,
    @Enumerated(EnumType.STRING)
    var matchStatus: MatchStatus? = null,
    val createdAt: Instant = Instant.now()
)
