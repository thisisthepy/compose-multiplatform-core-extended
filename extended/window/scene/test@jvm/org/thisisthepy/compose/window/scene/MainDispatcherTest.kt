package org.thisisthepy.compose.window.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.internal.ExtendedMainDispatcher
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers

class MainDispatcherTest {
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun the_host_dispatcher_becomes_compose_main_dispatcher_on_the_jvm() {
        val before = ExtendedMainDispatcher.override
        try {
            ExtendedMainDispatcher.override = null
            val dispatcher = Dispatchers.Unconfined
            useAsComposeMainDispatcher(dispatcher)
            assertSame(dispatcher, ExtendedMainDispatcher.override)
        } finally {
            ExtendedMainDispatcher.override = before
        }
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun an_embedder_dispatcher_set_before_the_host_is_kept() {
        val before = ExtendedMainDispatcher.override
        try {
            val own = Dispatchers.Default
            ExtendedMainDispatcher.override = own
            useAsComposeMainDispatcher(Dispatchers.Unconfined)
            assertSame(own, ExtendedMainDispatcher.override)
        } finally {
            ExtendedMainDispatcher.override = before
        }
    }
}
