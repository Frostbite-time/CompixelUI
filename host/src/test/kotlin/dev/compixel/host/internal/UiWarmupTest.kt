package dev.compixel.host.internal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import dev.compixel.ui.UiDesign
import dev.compixel.ui.theme.SchemeOwner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UiWarmupTest {
    @Test
    fun `composes the content inside the design`() {
        val decorated = AtomicInteger()
        val composed = AtomicInteger()
        val design =
            object : UiDesign {
                override val owner: SchemeOwner
                    get() = error("not used")

                @Composable
                override fun Decorate(content: @Composable () -> Unit) {
                    SideEffect { decorated.incrementAndGet() }
                    content()
                }

                override fun preview(): @Composable () -> Unit = {}
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
