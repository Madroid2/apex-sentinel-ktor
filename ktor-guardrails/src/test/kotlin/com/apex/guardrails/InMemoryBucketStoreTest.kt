package com.apex.guardrails

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryBucketStoreTest {
    @Test
    fun `rejects after capacity is consumed`() {
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val store = InMemoryBucketStore(clock)

        assertTrue(store.consume("tenant-a", 2, 1.0).allowed)
        assertTrue(store.consume("tenant-a", 2, 1.0).allowed)
        assertFalse(store.consume("tenant-a", 2, 1.0).allowed)
        assertTrue(store.consume("tenant-b", 2, 1.0).allowed)
    }
}
