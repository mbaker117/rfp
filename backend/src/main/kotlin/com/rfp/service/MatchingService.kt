package com.rfp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rfp.domain.enums.RfpStatus
import com.rfp.dto.ExtractedRequirement
import com.rfp.repository.InstrumentRepository
import com.rfp.repository.RequiredInstrumentRepository
import com.rfp.repository.RfpRequestRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class MatchingService(
    private val rfpRepo: RfpRequestRepository,
    private val reqInstrRepo: RequiredInstrumentRepository,
    private val instrumentRepo: InstrumentRepository,
    private val llmService: LlmService
) {
    private val mapper = ObjectMapper()

    @Async("taskExecutor")
    open fun matchAsync(rfpId: Long, companyIds: List<Long>) {
        val rfp = rfpRepo.findById(rfpId).orElseThrow { NoSuchElementException("RFP $rfpId not found") }
        rfpRepo.save(rfp.copy(status = RfpStatus.MATCHING))
        try {
            val requirements = reqInstrRepo.findByRfpRequestId(rfpId)
            requirements.forEach { req ->
                val keyword = req.extractedSpec?.let {
                    runCatching {
                        val node = mapper.readTree(it)
                        node["name"]?.asText()?.takeIf { name -> name.isNotBlank() }
                    }.getOrNull()
                } ?: req.rawText.take(50)

                if (keyword.isNullOrBlank()) {
                    return@forEach
                }

                val candidates = instrumentRepo.findCandidates(companyIds, keyword)
                val extracted = ExtractedRequirement(
                    rawText = req.rawText,
                    name = keyword,
                    quantity = null,
                    specs = emptyMap()
                )
                val result = llmService.scoreMatch(extracted, candidates)
                reqInstrRepo.save(req.copy(
                    matchedInstrument = result.matchedInstrumentId?.let { id -> instrumentRepo.findById(id).orElse(null) },
                    matchingScore = result.score,
                    matchStatus = result.status
                ))
            }

            val updatedRfp = rfpRepo.findById(rfpId).orElseThrow { NoSuchElementException("RFP $rfpId not found") }
            rfpRepo.save(updatedRfp.copy(status = RfpStatus.DONE))
        } catch (e: Exception) {
            val failedRfp = rfpRepo.findById(rfpId).orElse(null)
            if (failedRfp != null) {
                rfpRepo.save(failedRfp.copy(status = RfpStatus.FAILED))
            }
            throw e
        }
    }
}
