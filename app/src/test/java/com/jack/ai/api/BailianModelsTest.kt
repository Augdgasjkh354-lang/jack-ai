package com.jack.ai.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BailianModelsTest {
    @Test fun parsesModelIdsAndTotal() {
        val (models, total) = parseBailianModels("""{"output":{"total":102,"page_no":1,"models":[{"model":"qwen-plus","name":"千问"},{"model":"qwen-flash"}]}}""")
        assertEquals(listOf("qwen-plus", "qwen-flash"), models)
        assertEquals(102, total)
    }

    @Test fun rejectsInvalidResponse() {
        assertThrows(ApiException::class.java) { parseBailianModels("""{"error":{"message":"failed"}}""") }
    }
}
