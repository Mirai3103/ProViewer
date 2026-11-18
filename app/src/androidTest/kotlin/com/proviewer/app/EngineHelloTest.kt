package com.proviewer.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.proviewer_core.engineVersion

class EngineHelloTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun engine_version_is_exposed_to_kotlin() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(engineVersion()))
    }

    @Test
    fun main_screen_shows_engine_version() {
        composeRule.onNodeWithTag("engine-version")
            .assertTextContains(engineVersion(), substring = true)
    }
}
