package me.rerere.ai.util

import me.rerere.ai.provider.Model
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpstreamContextRegistryTest {
    @After
    fun tearDown() {
        UpstreamContextRegistry.enabled = true
        UpstreamContextRegistry.clear()
    }

    @Test
    fun `extracts context length from provider error message`() {
        val errorBody = """{"object":"error","message":"The input (571415 tokens) is longer than the model's context length (524288 tokens).","type":"BadRequestError","code":400}"""
        val model = Model(modelId = "agnes-3.0-flash", contextLength = null)

        val extracted = UpstreamContextRegistry.recordFromError(
            modelId = model.modelId,
            errorText = errorBody,
        )

        assertEquals(524_288, extracted)
        assertEquals(524_288, UpstreamContextRegistry.resolveContextLength(model))
    }

    @Test
    fun `explicit model contextLength takes precedence over discovered value`() {
        val model = Model(modelId = "agnes-3.0-flash", contextLength = 128_000)
        UpstreamContextRegistry.record(
            modelId = model.modelId,
            contextLength = 524_288,
        )

        assertEquals(128_000, UpstreamContextRegistry.resolveContextLength(model))
    }

    @Test
    fun `returns null when neither static nor discovered context length exists`() {
        val model = Model(modelId = "unknown-model", contextLength = null)
        assertNull(UpstreamContextRegistry.resolveContextLength(model))
    }

    @Test
    fun `disabling discovery ignores recording and lookups and clear removes cached limits`() {
        val model = Model(modelId = "agnes-3.0-flash", contextLength = null)
        UpstreamContextRegistry.record(model.modelId, 256_000)
        assertEquals(256_000, UpstreamContextRegistry.resolveContextLength(model))

        UpstreamContextRegistry.enabled = false
        assertNull(UpstreamContextRegistry.getDiscoveredContextLength(model.modelId))
        assertNull(UpstreamContextRegistry.resolveContextLength(model))
        assertNull(
            UpstreamContextRegistry.recordFromError(
                modelId = "other-model",
                errorText = "maximum context length is 131072 tokens",
            )
        )

        UpstreamContextRegistry.enabled = true
        assertNull(UpstreamContextRegistry.getDiscoveredContextLength("other-model"))
        UpstreamContextRegistry.clear()
        assertNull(UpstreamContextRegistry.getDiscoveredContextLength(model.modelId))
    }
}
