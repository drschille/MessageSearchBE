package org.themessagesearch.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HybridWeightsTest {
    @Test
    fun `accepts non-negative weights with at least one positive`() {
        val weights = HybridWeights(text = 0.2, vector = 0.8)
        assertEquals(0.2, weights.text)
        assertEquals(0.8, weights.vector)
    }

    @Test
    fun `rejects negative weights`() {
        assertFailsWith<IllegalArgumentException> { HybridWeights(text = -0.1, vector = 0.5) }
        assertFailsWith<IllegalArgumentException> { HybridWeights(text = 0.5, vector = -0.1) }
    }

    @Test
    fun `rejects zeroed weights`() {
        assertFailsWith<IllegalArgumentException> { HybridWeights(text = 0.0, vector = 0.0) }
    }
}
