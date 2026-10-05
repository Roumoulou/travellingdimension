// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyReport
import fr.roumoulou.travellingdimension.dimension.WorldgenDetection
import fr.roumoulou.travellingdimension.dimension.WorldgenReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La ligne « Travel dimension active », celle d'une copie fabriquée et celle des datapacks du mod,
 * ce que le log dit du terrain de VOYAGE.
 *
 * Elles se composent sans type du jeu : le générateur, ses biomes et le dépôt de datapacks se
 * lisent à l'étage 2, le moteur des copies à l'étage 1, les lignes s'éprouvent ici.
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

    @Test
    @DisplayName("la ligne d'une copie fabriquée compte ce qui est écrit et élagué, registre par registre")
    fun `ligne d'une copie`() {
        val report = WorldgenCopyReport(
            registries = sortedMapOf("worldgen/placed_feature" to WorldgenCopyReport.Count(written = 271, pruned = 2), "worldgen/biome" to WorldgenCopyReport.Count(written = 56, pruned = 0)),
            biomeTags = 67,
            refusals = mapOf("worldgen/placed_feature minecraft:a" to "refused", "worldgen/placed_feature minecraft:b" to "references the pruned worldgen/feature minecraft:c"),
        )

        assertEquals(327, report.written)
        assertEquals(2, report.pruned)
        assertEquals(
            "Worldgen copy 'vanilla' made in 840 ms: 327 file(s) written, 2 pruned (written/pruned by registry: worldgen/biome 56/0, worldgen/placed_feature 271/2), 67 biome tag file(s)",
            WorldgenReport.copyLine(WorldgenCopy.VANILLA, report, millis = 840),
        )
    }

    @Test
    @DisplayName("la ligne des datapacks dit ceux que le serveur a sélectionnés, et nomme une copie préparée qui manque")
    fun `ligne des datapacks`() {
        val vanilla = "travellingdimension/vanilla"
        val william = "travellingdimension/wwoo"

        assertEquals(
            "Worldgen datapacks selected by the server: travellingdimension/vanilla, travellingdimension/wwoo",
            WorldgenReport.datapacksLine(prepared = listOf(vanilla, william), selected = listOf(vanilla, william)),
        )

        // Une copie préparée que le serveur n'a pas : son dépôt de datapacks n'a pas reçu la source du mod.
        assertEquals(
            "Worldgen datapacks selected by the server: travellingdimension/vanilla (prepared but not selected: travellingdimension/wwoo)",
            WorldgenReport.datapacksLine(prepared = listOf(vanilla, william), selected = listOf(vanilla)),
        )
        assertEquals(
            "Worldgen datapacks selected by the server: none (prepared but not selected: travellingdimension/vanilla)",
            WorldgenReport.datapacksLine(prepared = listOf(vanilla), selected = emptyList()),
        )
    }
}
