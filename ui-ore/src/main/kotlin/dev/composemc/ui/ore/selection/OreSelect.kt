package dev.composemc.ui.ore.selection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.composemc.ui.ore.button.OreButton
import dev.composemc.ui.ore.button.OreButtonStyle
import dev.composemc.ui.ore.display.OreText
import dev.composemc.ui.ore.overlay.OreMenu
import dev.composemc.ui.ore.overlay.OreMenuItem

/** Controlled, select-only dropdown. Option values and labels are supplied without Minecraft types. */
@Composable
fun <T> OreSelect(
    options: List<T>,
    selected: T?,
    onSelectionChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Select…",
    optionLabel: (T) -> String = { it.toString() },
    optionEnabled: (T) -> Boolean = { true },
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(enabled, options.isEmpty()) { if (!enabled || options.isEmpty()) expanded = false }
    Box(modifier.onSizeChanged { anchorWidth = it.width }) {
        OreButton(
            { expanded = !expanded },
            Modifier.fillMaxWidth()
                .semantics { contentDescription = selected?.let(optionLabel) ?: placeholder }
                .onPreviewKeyEvent { event ->
                    if (
                        enabled &&
                            options.isNotEmpty() &&
                            event.type == KeyEventType.KeyDown &&
                            event.key in listOf(Key.DirectionDown, Key.DirectionUp)
                    ) {
                        expanded = true
                        true
                    } else false
                },
            enabled = enabled && options.isNotEmpty(),
            style = OreButtonStyle.Secondary,
        ) {
            OreText(
                selected?.let(optionLabel) ?: placeholder,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OreText("▾")
        }
        OreMenu(
            expanded && enabled,
            { expanded = false },
            options.mapIndexed { index, option ->
                OreMenuItem(index.toString(), optionLabel(option), optionEnabled(option), option == selected) {
                    onSelectionChange(option)
                }
            },
            modifier = Modifier.width(with(density) { anchorWidth.toDp() }),
            initialIndex = options.indexOf(selected).coerceAtLeast(0),
        )
    }
}
