package dev.compixel.ui.ore.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.display.OreText

@Composable
fun OreTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OreButton(
        onClick,
        modifier.semantics { this.selected = selected },
        enabled,
        if (selected) OreButtonStyle.Primary else OreButtonStyle.Quiet,
        role = Role.Tab,
    ) {
        OreText(text)
    }
}
