package com.kachat.app.repository

import com.kachat.app.repository.AppSettingsRepository.Companion.DEFAULT_INDEXER_URL
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultIndexerTest {
    @Test
    fun `every endpoint defaults to indexer kachat app`() {
        assertEquals("https://indexer.kachat.app", DEFAULT_INDEXER_URL)
        assertEquals(DEFAULT_INDEXER_URL, AppSettingsRepository.DEFAULT_KAPOST_INDEXER_URL)
        assertEquals(DEFAULT_INDEXER_URL, AppSettingsRepository.DEFAULT_BROADCAST_INDEXER_URL)
        assertEquals(DEFAULT_INDEXER_URL, AppSettingsRepository.DEFAULT_PUSH_INDEXER_URL)
        assertEquals(DEFAULT_INDEXER_URL, AppSettingsRepository.DEFAULT_TRANSLATION_SERVICE_URL)
    }
}
