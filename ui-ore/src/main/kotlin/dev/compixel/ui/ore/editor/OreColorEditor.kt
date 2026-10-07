package dev.compixel.ui.ore.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.compixel.ui.ore.button.OreButton
import dev.compixel.ui.ore.button.OreButtonStyle
import dev.compixel.ui.ore.display.OreGlyph
import dev.compixel.ui.ore.display.OreIcon
import dev.compixel.ui.ore.display.OreText
import dev.compixel.ui.ore.input.OreColorPicker
import dev.compixel.ui.ore.input.OreTextField
import dev.compixel.ui.ore.layout.OrePanel
import dev.compixel.ui.ore.navigation.OreListItem
import dev.compixel.ui.ore.overlay.OreDialog
import dev.compixel.ui.ore.scroll.OreScrollbar
import dev.compixel.ui.ore.selection.OreCheckbox
import dev.compixel.ui.ore.selection.OreRadioButton
import dev.compixel.ui.ore.selection.OreSelect
import dev.compixel.ui.ore.theme.OreColors
import dev.compixel.ui.ore.theme.OreTheme
import dev.compixel.ui.theme.ColorHex
import dev.compixel.ui.theme.ColorRole
import dev.compixel.ui.theme.ColorValues
import dev.compixel.ui.theme.LocalColorEditor
import dev.compixel.ui.theme.LocalSchemes
import dev.compixel.ui.theme.SchemeEditing
import dev.compixel.ui.theme.SchemeOwner

/** The color editor's words in the player's language, which its host provides. */
class OreColorEditorText(
    val title: String,
    val close: String,
    /** Scheme names by path; a scheme without one shows its path. */
    val schemes: Map<String, String>,
    /** Group and color names by key; one without a name shows its key. */
    val groups: Map<String, String>,
    val colors: Map<String, String>,
    /** The heading of the colors that follow others, such as hover shades. */
    val derived: String,
    /** "Follows %s", for a derived color that follows its rule. */
    val follows: String,
    val restore: String,
    val restoreAll: String,
    val export: String,
    /** "Replace %s", exporting the colors over the scheme they change. */
    val exportReplace: String,
    val exportNew: String,
    val exportName: String,
    val exportDefault: String,
    /** Where the exported pack goes and how others use it. */
    val exportHint: String,
    val cancel: String,
    val plane: String,
    val hue: String,
    val alpha: String,
)

/** What the player asked to export: [scheme]'s colors, as a replacement or as a new scheme [name], maybe as default. */
data class OreColorExport(val scheme: String, val name: String, val asNew: Boolean, val makeDefault: Boolean)

/**
 * CompixelUI's color editor for [owner]'s schemes. The player chooses a scheme, picks a color from the list and changes
 * it with the picker; every change goes through [editing] and so reaches every screen of [owner] at once, including
 * [preview], content in the owner's own design shown beside the colors. Colors that follow others, such as hover
 * shades, are listed apart; changing one makes it the player's own until restored. [onExport] writes a resource pack
 * and returns what to tell the player.
 */
@Composable
fun OreColorEditor(
    owner: SchemeOwner,
    editing: SchemeEditing,
    text: OreColorEditorText,
    onExport: (OreColorExport) -> String,
    onClose: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val schemes = LocalSchemes.current
    val scheme = schemes.selected(owner)
    val values = schemes.colors(owner, scheme)
    val edited = schemes.edits(owner, scheme).keys
    val colors = OreTheme.colors
    var role by remember(owner) { mutableStateOf(owner.schema.roles.first()) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun name(role: ColorRole) = text.colors[role.key] ?: role.key
    // The editor takes the whole screen, so the preview and the colors get all the room there is.
    OrePanel(
        text.title,
        Modifier.fillMaxSize(),
        onClose = onClose,
        closeLabel = text.close,
        footer = {
            OreText(
                message.orEmpty(),
                Modifier.weight(1f),
                color = colors[OreColors.mutedText],
                style = OreTheme.typography.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            OreButton(
                text.restoreAll,
                { editing.restore(owner, scheme) },
                enabled = edited.isNotEmpty(),
                style = OreButtonStyle.Secondary,
            )
            OreButton(text.export, { exporting = true })
        },
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                // The preview is not a screen of its own: it offers no way into another editor.
                CompositionLocalProvider(LocalColorEditor provides null, content = preview)
            }
            Column(Modifier.width(224.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OreSelect(
                    schemes.paths(owner),
                    scheme,
                    { editing.select(owner, it) },
                    Modifier.fillMaxWidth(),
                    optionLabel = { text.schemes[it] ?: it },
                )
                ColorList(owner, values, edited, role, { role = it }, text, Modifier.weight(1f).fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        OreText(name(role), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (values.follows(role))
                            OreText(
                                text.follows.format(role.follows.joinToString(", ") { name(it) }),
                                color = colors[OreColors.mutedText],
                                style = OreTheme.typography.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                    }
                    OreButton(
                        text.restore,
                        { editing.edit(owner, scheme, role, null) },
                        enabled = role in edited,
                        style = OreButtonStyle.Secondary,
                    )
                }
                // A new color starts the picker over, without the previous color's remembered hue.
                key(role) {
                    OreColorPicker(
                        values[role],
                        { editing.edit(owner, scheme, role, it) },
                        Modifier.fillMaxWidth(),
                        planeLabel = text.plane,
                        hueLabel = text.hue,
                        alphaLabel = text.alpha,
                    )
                }
            }
        }
    }
    if (exporting) {
        ExportDialog(text.schemes[scheme] ?: scheme, text, { exporting = false }) { name, asNew, makeDefault ->
            exporting = false
            message = onExport(OreColorExport(scheme, name, asNew, makeDefault))
        }
    }
}

/** The schema's base colors by group, then on request the colors that follow others. */
@Composable
private fun ColorList(
    owner: SchemeOwner,
    values: ColorValues,
    edited: Set<ColorRole>,
    selected: ColorRole,
    onSelect: (ColorRole) -> Unit,
    text: OreColorEditorText,
    modifier: Modifier,
) {
    var showDerived by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val (derived, base) = owner.schema.roles.partition { it.derived }
    @Composable
    fun groups(roles: List<ColorRole>) {
        for ((group, members) in roles.groupBy { it.group }) {
            OreText(
                text.groups[group] ?: group,
                Modifier.padding(start = 2.dp, top = 3.dp),
                color = OreTheme.colors[OreColors.mutedText],
                style = OreTheme.typography.caption,
            )
            for (role in members) ColorRow(role, values, role in edited, role == selected, text) { onSelect(role) }
        }
    }
    Box(modifier) {
        Column(Modifier.fillMaxSize().padding(end = 7.dp).verticalScroll(scroll)) {
            groups(base)
            if (derived.isNotEmpty()) {
                OreListItem(false, { showDerived = !showDerived }, Modifier.fillMaxWidth(), true, 16.dp) {
                    OreIcon(if (showDerived) OreGlyph.ChevronDown else OreGlyph.ChevronRight, Modifier.size(7.dp))
                    OreText(text.derived, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (showDerived) groups(derived)
            }
        }
        OreScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun ColorRow(
    role: ColorRole,
    values: ColorValues,
    edited: Boolean,
    selected: Boolean,
    text: OreColorEditorText,
    onClick: () -> Unit,
) {
    val colors = OreTheme.colors
    val color = values[role]
    OreListItem(selected, onClick, Modifier.fillMaxWidth(), true, 16.dp) {
        Swatch(color, Modifier.size(9.dp))
        OreText(text.colors[role.key] ?: role.key, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The player's own colors are marked; restoring one removes the mark.
        if (edited) Box(Modifier.size(3.dp).background(colors[OreColors.primary]))
        OreText(ColorHex.format(color), color = colors[OreColors.mutedText], style = OreTheme.typography.caption)
    }
}

/** A color over a checkerboard, so transparent colors read as such. */
@Composable
private fun Swatch(color: Color, modifier: Modifier) {
    val edge = OreTheme.colors[OreColors.edge]
    Canvas(modifier) {
        val half = Size(size.width / 2, size.height / 2)
        drawRect(Color.White)
        drawRect(CHECKER, Offset.Zero, half)
        drawRect(CHECKER, Offset(half.width, half.height), half)
        drawRect(color)
        drawRect(edge, style = Stroke(1.dp.toPx()))
    }
}

private val CHECKER = Color(0xFFBFBFBF)

@Composable
private fun ExportDialog(
    schemeName: String,
    text: OreColorEditorText,
    onDismiss: () -> Unit,
    onExport: (name: String, asNew: Boolean, makeDefault: Boolean) -> Unit,
) {
    var asNew by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(schemeName) }
    var makeDefault by remember { mutableStateOf(false) }
    OreDialog(
        text.export,
        onDismiss,
        closeLabel = text.close,
        buttons = {
            OreButton(
                text.export,
                { onExport(name.trim(), asNew, makeDefault) },
                Modifier.fillMaxWidth(),
                name.isNotBlank(),
            )
            OreButton(text.cancel, onDismiss, Modifier.fillMaxWidth(), style = OreButtonStyle.Secondary)
        },
    ) {
        OreRadioButton(!asNew, { asNew = false }, label = text.exportReplace.format(schemeName))
        OreRadioButton(asNew, { asNew = true }, label = text.exportNew)
        OreTextField(name, { name = it }, Modifier.fillMaxWidth(), label = text.exportName)
        OreCheckbox(makeDefault, { makeDefault = it }, label = text.exportDefault)
        OreText(
            text.exportHint,
            color = OreTheme.colors[OreColors.mutedText],
            style = OreTheme.typography.caption,
        )
    }
}
