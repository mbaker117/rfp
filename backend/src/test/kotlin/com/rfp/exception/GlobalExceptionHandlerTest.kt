package com.rfp.exception

import com.rfp.service.LlmException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.multipart.MaxUploadSizeExceededException

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler(maxFileSize = "1GB")

    @Test
    fun `oversized upload returns 413 naming the limit`() {
        val res = handler.handleTooLarge(MaxUploadSizeExceededException(-1))

        assertThat(res.statusCode.value()).isEqualTo(413)
        assertThat(res.body!!["error"]).isEqualTo("File is too large. The maximum upload size is 1GB.")
    }

    @Test
    fun `llm error surfaces the provider message`() {
        val res = handler.handleLlmError(LlmException("LLM API error 400: Your credit balance is too low."))

        assertThat(res.statusCode.value()).isEqualTo(502)
        assertThat(res.body!!["error"]).isEqualTo("LLM API error 400: Your credit balance is too low.")
    }
}
