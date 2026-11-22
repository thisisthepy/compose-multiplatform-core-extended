package org.thisisthepy.compose.adaptive

import org.thisisthepy.compose.designsystem.ColorRole
import org.thisisthepy.compose.designsystem.DesignSystemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveDesignSystemTest {

    @Test
    fun fr14_11_5_adaptive_x_seven_systems_table_has_no_empty_cell() {
        val expectedCount = AdaptiveComponentTable.coreComponents.size * Layer1Library.entries.size
        assertEquals(expectedCount, AdaptiveComponentTable.cells.size)
        for (component in AdaptiveComponentTable.coreComponents) {
            for (library in Layer1Library.entries) {
                val cell = assertNotNull(
                    AdaptiveComponentTable.cell(component, library),
                    "missing cell for ($component, $library)",
                )
                assertTrue(cell.layer1Target.isNotBlank(), "blank Layer 1 target for ($component, $library)")
                assertTrue(cell.adaptiveSubstitute.isNotBlank(), "blank Layer 2 substitute for ($component, $library)")
            }
        }
    }

    @Test
    fun fr14_11_6_non_standard_cells_and_substitutes_are_complete() {
        val nonStandard = AdaptiveComponentTable.nonStandardCells()
        assertTrue(nonStandard.isNotEmpty(), "expected non-standard Layer 1 cells to be recorded")
        for (cell in nonStandard) {
            assertTrue(
                !cell.guidelineUrl.isNullOrBlank() && cell.guidelineUrl.startsWith("https://"),
                "non-standard cell (${cell.component}, ${cell.library}) must cite an official guideline URL",
            )
            assertTrue(
                cell.adaptiveSubstitute.isNotBlank() && cell.adaptiveSubstitute != cell.layer1Target,
                "non-standard cell (${cell.component}, ${cell.library}) must name a distinct platform substitute",
            )
        }
    }

    @Test
    fun fr14_11_5_adaptive_resolves_every_design_system_in_both_schemes() {
        val defaultSystem = platformDesignSystem(darkTheme = false)
        assertEquals(platformDefaultDesignSystemId(), defaultSystem.id)
        for (id in DesignSystemId.entries) {
            val light = id.resolve(darkTheme = false)
            val dark = id.resolve(darkTheme = true)
            assertEquals(id, light.id)
            assertEquals(id, dark.id)
            assertEquals(false, light.isDark)
            assertEquals(true, dark.isDark)
            assertTrue(light.color(ColorRole.Surface) != dark.color(ColorRole.Surface))
        }
    }
}
