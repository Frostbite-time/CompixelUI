package dev.composemc.development

import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

/** Compatibility helpers for deterministic development probes on the 26.x input API. */
internal fun Screen.mouseClicked(x: Double, y: Double, button: Int, modifiers: Int = 0): Boolean =
    mouseClicked(MouseButtonEvent(x, y, MouseButtonInfo(button, modifiers)), false)

internal fun Screen.mouseReleased(x: Double, y: Double, button: Int, modifiers: Int = 0): Boolean =
    mouseReleased(MouseButtonEvent(x, y, MouseButtonInfo(button, modifiers)))

internal fun Screen.keyPressed(key: Int, keycode: Int = 0, modifiers: Int = 0): Boolean =
    keyPressed(
        KeyEvent(
            key,
            if (keycode != 0) keycode
            else org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(key, modifiers.toShort(), false),
            modifiers,
        )
    )

internal fun Screen.keyReleased(key: Int, keycode: Int = 0, modifiers: Int = 0): Boolean =
    keyReleased(
        KeyEvent(
            key,
            if (keycode != 0) keycode
            else org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(key, modifiers.toShort(), false),
            modifiers,
        )
    )

internal fun Screen.charTyped(character: Char, @Suppress("UNUSED_PARAMETER") modifiers: Int = 0): Boolean =
    charTyped(CharacterEvent(character.code))
