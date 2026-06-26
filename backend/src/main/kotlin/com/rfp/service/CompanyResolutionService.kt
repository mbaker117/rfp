package com.rfp.service

import com.rfp.domain.Company
import com.rfp.repository.CompanyRepository
import com.rfp.repository.InstrumentRepository
import com.rfp.repository.ScrapeJobRepository
import org.springframework.stereotype.Service

@Service
class CompanyResolutionService(
    private val companyRepo: CompanyRepository,
    private val instrumentRepo: InstrumentRepository,
    private val scrapeJobRepo: ScrapeJobRepository,
    private val scrapeService: ScrapeService
) {
    fun resolveCompanies(names: List<String>): List<Company> {
        return names.map { name ->
            val company = companyRepo.findByNameIgnoreCase(name)
                ?: companyRepo.save(Company(name = name))
            val hasData = instrumentRepo.findByCompanyIdIn(listOf(company.id)).isNotEmpty()
            if (!hasData) scrapeService.enqueueScrapeJob(company.id)
            company
        }
    }
}
