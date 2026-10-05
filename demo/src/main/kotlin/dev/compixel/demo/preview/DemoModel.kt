package dev.compixel.demo.preview

import androidx.compose.runtime.*
import dev.compixel.ui.theme.ThemeId

enum class Fixture {
    READY,
    EMPTY,
    LOADING,
    ERROR,
}

enum class DemoPage {
    Settings,
    Catalog,
    Buttons,
    Icons,
    Fields,
    Toggles,
    Sliders,
    Lists,
    Slots,
    Surfaces,
    Items,
    Selection,
    Menus,
    Numbers,
    Colors,
    Tree,
    Tooltips,
    Windows,
}

class DemoModel {
    var theme by mutableStateOf(ThemeId.Default)
    val itemBrowser = ItemBrowserModel()
    val bounds = mutableMapOf<String, androidx.compose.ui.geometry.Rect>()
    var locale by mutableStateOf("en_us")
    var query by mutableStateOf("")
    var enabled by mutableStateOf(true)
    var volume by mutableFloatStateOf(0.65f)
    var fixture by mutableStateOf(Fixture.READY)
    var count by mutableIntStateOf(1000)
    var dialog by mutableStateOf(false)
    var selected by mutableIntStateOf(0)
    var page by mutableStateOf(DemoPage.Settings)
    var intValue by mutableIntStateOf(20)
    var intUnbounded by mutableIntStateOf(20)
    var checked by mutableStateOf(true)
    var switched by mutableStateOf(true)
    var slider by mutableFloatStateOf(0.6f)
    var scrolledText by mutableStateOf("")
    var scrollPage by mutableIntStateOf(0)
    var scrollRows by mutableIntStateOf(4)
    var choice by mutableIntStateOf(2)
    var longValue by mutableLongStateOf(9_007_199_254_740_993L)
    var doubleValue by mutableDoubleStateOf(.25)
    var color by mutableStateOf(androidx.compose.ui.graphics.Color(0xCC3C8527))
    var menuAction by mutableStateOf("")
    var treeSelection by mutableStateOf<String?>(null)
    var expandedTree by mutableStateOf(setOf("world"))
    var largeTree by mutableStateOf(false)
    var mixedSelection by mutableStateOf(setOf(0, 2))
    var tooltipClicks by mutableIntStateOf(0)
    var windowOpen by mutableStateOf(true)
    val windowState = dev.compixel.ui.ore.overlay.OreWindowState()
}
