package com.rfp.exception

import com.rfp.service.LlmException
import com.rfp.service.UnsupportedFileTypeException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
class GlobalExceptionHandler(
    @Value("\${spring.servlet.multipart.max-file-size:1GB}") private val maxFileSize: String = "1GB"
) {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "Bad request")))

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(404).body(mapOf("error" to (ex.message ?: "Not found")))

    @ExceptionHandler(UnsupportedFileTypeException::class)
    fun handleUnsupportedFileType(ex: UnsupportedFileTypeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "Unsupported file type")))

    @ExceptionHandler(LlmException::class)
    fun handleLlmError(ex: LlmException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("error" to (ex.message ?: "LLM service error")))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(ex: MaxUploadSizeExceededException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(mapOf("error" to "File is too large. The maximum upload size is $maxFileSize."))
}
