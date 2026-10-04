// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.WorldgenMode
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les défauts de la configuration et le rayon calculé.
 *
 * [TravelConfig] ne connaît pas Minecraft : ces tests vivent dans le source set **pur**. Les
 * bornes, elles, sont l'affaire de `TravelConfigValidator`, testé à l'étage 1 parce qu'il
 * vérifie la forme des identifiants avec `Identifier.tryParse`, une classe du jeu.
 */
class TravelConfigTest {

    private val json = Json { encodeDefaults = true }

    @Test
    @DisplayName("searchRadiusVoyage se déduit du rayon d'OVERWORLD et du ratio, et n'est jamais nul")
    fun `rayon de VOYAGE calcule`() {
        assertEquals(8, TravelConfig().searchRadiusVoyage)
        assertEquals(16, TravelConfig(searchRadiusOverworld = 256).searchRadiusVoyage)
        assertEquals(2, TravelConfig(ratio = 64).searchRadiusVoyage)
        // Partie entière, 100 / 16 -> 6, et jamais moins de 1.
        assertEquals(6, TravelConfig(searchRadiusOverworld = 100).searchRadiusVoyage)
        assertEquals(1, TravelConfig(searchRadiusOverworld = 1).searchRadiusVoyage)
    }

    @Test
    @DisplayName("searchRadiusVoyage n'est pas dans le fichier, searchRadiusOverworld y est")
    fun `rayon de VOYAGE hors du fichier`() {
        val encoded = json.encodeToString(TravelConfig.serializer(), TravelConfig())
        assertFalse(encoded.contains("searchRadiusVoyage"), encoded)
        assertTrue(encoded.contains("\"searchRadiusOverworld\":128"), encoded)
    }

    @Test
    @DisplayName("les défauts du cahier des charges")
    fun `les defauts du cahier des charges`() {
        val defaults = TravelConfig()
        assertEquals(16, defaults.ratio)
        assertEquals(128, defaults.searchRadiusOverworld)
        assertEquals(8, defaults.searchRadiusVoyage)
        assertEquals(1.0, defaults.verticalWeight)
        assertEquals("minecraft:calcite", defaults.platformBlock)
        // Une seule couche, un seul bloc de débordement : la dalle sert à ne pas tomber en
        // sortant, pas à bâtir un socle.
        assertEquals(1, defaults.platformMargin)
        assertEquals(1, defaults.platformDepth)
        assertEquals(2, defaults.clearanceMargin)
        assertEquals(3, defaults.clearanceHeight)
        assertEquals("minecraft:amethyst_block", defaults.frameBlock)
        assertEquals(false, defaults.rememberEntryPortal)
        // Les deux systèmes de couleur se coupent séparément, et sont actifs par défaut.
        assertEquals(true, defaults.portalTints)
        assertEquals(true, defaults.netherPortalTints)
        // Les tailles hors vanilla sont COUPÉES par défaut : le mod ne change la règle des
        // portails chez personne sans qu'on l'ait demandé.
        assertEquals(false, defaults.portalFreeSize)
        assertEquals(false, defaults.netherPortalFreeSize)
        assertEquals(21, defaults.portalMaxSize)
        assertEquals(21, defaults.netherPortalMaxSize)
        // Une minute de présence cumulée, pas dix secondes : dix secondes s'accumulent en
        // traversant un chunk au galop.
        assertEquals(1200L, defaults.inhabitedThreshold)
        // La redstone a son propre veto, actif par défaut et indépendant du reste.
        assertEquals(8, defaults.redstoneVeto)
        assertEquals(29, defaults.redstoneBlocks.size)
    }

    @Test
    @DisplayName("les défauts de la génération : terralith, large biomes")
    fun `les defauts de la generation`() {
        val defaults = TravelConfig()
        assertEquals(WorldgenMode.TERRALITH, defaults.worldgen)
        assertEquals(true, defaults.largeBiomes)
    }
}
