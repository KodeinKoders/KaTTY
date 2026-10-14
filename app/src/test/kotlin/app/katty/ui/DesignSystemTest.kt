package app.katty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.katty.core.AgentState
import org.jetbrains.jewel.ui.component.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesignSystemTest {
    // Everything of the design system that is drawn here, in one image per theme, to compare with the design by eye.
    @Composable
    private fun Gallery() {
        Column(Modifier.fillMaxSize().background(Katty.colors.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("STATES")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentState.entries.forEach { StateGlyph(it) }
                AgentState.entries.forEach { StateGlyph(it, size = GlyphSize.SMALL) }
                StateCount(AgentState.NEEDS_INPUT, 1)
                StateCount(AgentState.WORKING, 3)
                CountPill(9)
            }
            SectionLabel("AGENTS")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentGlyph.entries.forEach { AgentTile(it, size = TileSize.HEADER) }
                AgentGlyph.entries.forEach { AgentTile(it, size = TileSize.LIST) }
                AgentGlyph.entries.forEach { AgentTile(it, size = TileSize.RAIL) }
            }
            SectionLabel("ICONS")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    KattyIcons.SidebarToggle, KattyIcons.Search, KattyIcons.Bell, KattyIcons.Menu, KattyIcons.ChevronDown, KattyIcons.ChevronRight,
                    KattyIcons.Plus, KattyIcons.Folder, KattyIcons.FolderPlus, KattyIcons.Kebab, KattyIcons.SplitRight, KattyIcons.SplitDown,
                    KattyIcons.Branch, KattyIcons.Worktree, KattyIcons.Maximize, KattyIcons.Restore, KattyIcons.Close, KattyIcons.Board,
                    KattyIcons.Check, KattyIcons.Pencil, KattyIcons.ArrowRight, KattyIcons.Palette, KattyIcons.Copy, KattyIcons.Restart,
                ).forEach { Icon(it, Katty.colors.text) }
            }
            SectionLabel("CONTROLS")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                KattyIconButton(KattyIcons.SplitRight, "Split right", onClick = {})
                KattyIconButton(KattyIcons.Maximize, "Maximize pane", onClick = {}, size = ButtonSize.COMPACT)
                Choice(listOf(ChoiceOption(0, "Auto"), ChoiceOption(1, "Horizontal"), ChoiceOption(2, "Vertical")), selected = 1, onSelect = {})
                CheckSetting("Font ligatures", checked = true, onChange = {})
                Shortcut("⌘ ⇧ J")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageBar(23)
                    UsageBar(41)
                    UsageBar(82)
                }
                Surface(Modifier.width(220.dp)) { Box(Modifier.padding(12.dp)) { Text("A card") } }
            }
        }
    }

    @Test
    fun drawsTheDesignSystemInTheLightTheme() {
        val rendered = render("design-system-light", 1100, 560) { Gallery() }

        assertTrue(rendered.count(0xF2F3F5) > 10_000, "The background of the window")
        // The amber of Needs input, the green of Done and the red of an error are in it.
        assertTrue(rendered.count(0xA85F00) > 20)
        assertTrue(rendered.count(0x1F7A3A) > 20)
        assertTrue(rendered.count(0xC93234) > 20)
    }

    @Test
    fun drawsTheDesignSystemInTheDarkTheme() {
        val rendered = render("design-system-dark", 1100, 560, dark = true) { Gallery() }

        assertTrue(rendered.count(0x1E1F22) > 10_000)
        assertTrue(rendered.count(0xE5A93D) > 20)
        assertTrue(rendered.count(0x6CC17D) > 20)
        assertTrue(rendered.count(0xF2767A) > 20)
    }

    @Test
    fun eachStateHasItsOwnGlyph() {
        val images = AgentState.entries.associateWith { state ->
            render("glyph-${state.name.lowercase()}", 40, 40) { Box(Modifier.fillMaxSize().background(Katty.colors.bg), Alignment.Center) { StateGlyph(state) } }.bytes().toList()
        }

        assertEquals(AgentState.entries.size, images.values.toSet().size)
    }

    @Test
    fun theWorkingRingSpinsUnlessTheSystemAsksForLessMotion() {
        // Two frames of one scene, a quarter of a turn apart.
        fun frames(reduced: Boolean): Pair<List<Byte>, List<Byte>> {
            val scene = ImageComposeScene(width = 40, height = 40, density = Density(2f)) {
                KattyTheme(dark = false, reducedMotion = reduced) {
                    Box(Modifier.fillMaxSize().background(Katty.colors.bg), Alignment.Center) { StateGlyph(AgentState.WORKING) }
                }
            }
            try {
                val first = scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes.toList()
                val second = scene.render(250_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes.toList()
                return first to second
            } finally {
                scene.close()
            }
        }

        val spinning = frames(reduced = false)
        val still = frames(reduced = true)

        assertNotEquals(spinning.first, spinning.second)
        assertEquals(still.first, still.second)
    }

    @Test
    fun theUsageBarWarnsFromEightyPercent() {
        val light = KattyColors.Light
        fun bar(percent: Int) = render("usage-$percent", 400, 60) { Box(Modifier.fillMaxWidth().padding(8.dp)) { UsageBar(percent, Modifier.fillMaxWidth()) } }

        val below = bar(79)
        val at = bar(80)

        assertTrue(below.count(0x2F6BE0) > 50 && below.count(0xA85F00) == 0)
        assertTrue(at.count(0xA85F00) > 50 && at.count(0x2F6BE0) == 0)
        assertEquals(0xA85F00, light.input.toArgbRgb())
    }

    private fun androidx.compose.ui.graphics.Color.toArgbRgb(): Int = (red * 255 + .5f).toInt() shl 16 or ((green * 255 + .5f).toInt() shl 8) or (blue * 255 + .5f).toInt()
}
