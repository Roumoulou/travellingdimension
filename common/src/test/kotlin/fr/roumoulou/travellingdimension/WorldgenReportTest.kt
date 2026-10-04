// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.WorldgenDetection
import fr.roumoulou.travellingdimension.dimension.WorldgenReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La ligne « Travel dimension active », ce que le log dit du terrain de VOYAGE.
 *
 * Elle se compose sans type du jeu : le générateur et ses biomes se lisent à l'étage 2, la ligne
 * s'éprouve ici.
 */
class WorldgenReportTest {

    private companion object {
        const val LARGE_BIOMES = "noise settings 'minecraft:large_biomes'"

        val NOTHING = WorldgenDetection(
            terralithLoaded = false,
            tectonicLoaded = false,
            wwooInstalled = false,
            vanillaCopyLoaded = false,
            williamCopyLoaded = false,
            customNoiseSettingsKnown = false,
            customBiomePresetKnown = false,
        )
    }

    @Test
    @DisplayName("la ligne nomme Terralith et WWOO quand ils sont détectés, et seulement alors")
    fun `ligne Travel dimension active`() {
        assertEquals(
            "Travel dimension active: mode terralith, noise settings 'minecraft:large_biomes', 146 biome(s) (minecraft=56, terralith=90), detected: Terralith",
            WorldgenReport.activeLine(WorldgenMode.TERRALITH, LARGE_BIOMES, sortedMapOf("minecraft" to 56, "terralith" to 90), NOTHING.copy(terralithLoaded = true)),
        )

        // Rien de détecté : la ligne ne nomme personne. Un datapack Terralith sans le mod est dans ce cas.
        assertEquals(
            "Travel dimension active: mode terralith, noise settings 'minecraft:large_biomes', 56 biome(s) (minecraft=56)",
            WorldgenReport.activeLine(WorldgenMode.TERRALITH, LARGE_BIOMES, sortedMapOf("minecraft" to 56), NOTHING),
        )

        // Tectonic et les copies se détectent aussi, la ligne ne nomme que Terralith et WWOO.
        val everything = NOTHING.copy(terralithLoaded = true, tectonicLoaded = true, wwooInstalled = true, vanillaCopyLoaded = true, williamCopyLoaded = true)
        assertEquals(
            "Travel dimension active: mode william, noise settings 'travellingdimension:vanilla/large_biomes', 56 biome(s) (minecraft=56), detected: Terralith, WWOO",
            WorldgenReport.activeLine(WorldgenMode.WILLIAM, "noise settings 'travellingdimension:vanilla/large_biomes'", sortedMapOf("minecraft" to 56), everything),
        )
    }
}
