package dev.compixel.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import dev.compixel.ui.UiDesign
import dev.compixel.ui.theme.ThemeId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UiWarmupTest {
    @Test
    fun composesTheContentInsideTheDesign() {
        val decorated = AtomicInteger()
        val composed = AtomicInteger()
        val design =
            object : UiDesign {
                @Composable
                override fun Decorate(theme: ThemeId, content: @Composable () -> Unit) {
                    SideEffect { decorated.incrementAndGet() }
                    content()
                }
            }
        UiWarmup.warmUp(
            design,
            {
                SideEffect { composed.incrementAndGet() }
                Box(Modifier.fillMaxSize())
            },
            frames = 5,
        )
        assertTrue(decorated.get() > 0, "The design did not decorate the warm-up")
        assertTrue(composed.get() > 0, "The warm-up content was not composed")
    }
}
