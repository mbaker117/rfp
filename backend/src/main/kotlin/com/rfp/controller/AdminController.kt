package com.rfp.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/admin")
class AdminController {

    @PostMapping("/refresh")
    fun refresh(): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok(mapOf("status" to "refresh enqueued"))
}
