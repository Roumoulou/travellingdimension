// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import com.google.gson.JsonParser
import fr.roumoulou.travellingdimension.dimension.MediumBiomes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La dérivation de la taille intermédiaire : le chapitre 5.7 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`, sur des arbres JSON écrits par le
 * test.
 *
 * Une transformation d'arbre, sans type du jeu : ce qu'elle fait de la vraie copie vanilla, et ce
 * que les codecs du jeu en disent, se voit à l'étage 1.
 */
class MediumBiomesTest {

    private companion object {
        val REDONE = setOf("continents", "erosion", "depth")
        val ENLARGED = setOf("travellingdimension:vanilla/continentalness", "travellingdimension:vanilla/erosion")
    }

    @Test
    @DisplayName("une référence à une fonction refaite change de dossier, en clé comme en valeur, et les autres restent")
    fun `references renommees`() {
        val source = JsonParser.parseString(
            """
            {
              "noise_router": {
                "continents": "travellingdimension:vanilla/overworld/continents",
                "ridges": "travellingdimension:vanilla/overworld/ridges",
                "depth": "travellingdimension:vanilla/overworld/depth"
              },
              "material_rule": "travellingdimension:vanilla/overworld",
              "spawn_target": [
                { "travellingdimension:vanilla/overworld/erosion": [-1.0, 1.0], "travellingdimension:vanilla/overworld/ridges": [0.16, 1.0] }
              ],
              "caves": "travellingdimension:vanilla/overworld/caves/noodle",
              "minecraft": "minecraft:overworld/continents"
            }
            """
        )
        val expected = JsonParser.parseString(
            """
            {
              "noise_router": {
                "continents": "travellingdimension:vanilla/overworld_medium_biomes/continents",
                "ridges": "travellingdimension:vanilla/overworld/ridges",
                "depth": "travellingdimension:vanilla/overworld_medium_biomes/depth"
              },
              "material_rule": "travellingdimension:vanilla/overworld",
              "spawn_target": [
                { "travellingdimension:vanilla/overworld_medium_biomes/erosion": [-1.0, 1.0], "travellingdimension:vanilla/overworld/ridges": [0.16, 1.0] }
              ],
              "caves": "travellingdimension:vanilla/overworld/caves/noodle",
              "minecraft": "minecraft:overworld/continents"
            }
            """
        )

        assertEquals(expected, MediumBiomes.renamed(source, REDONE))
    }

    @Test
    @DisplayName("un bruit à jumeau _large se lit à un xz_scale divisé par deux, par noise ou shifted_noise, où qu'il soit dans l'arbre, et les autres bruits gardent le leur")
    fun `xz_scale divise par deux`() {
        val source = JsonParser.parseString(
            """
            {
              "input": {
                "noise": "travellingdimension:vanilla/continentalness",
                "xz_scale": 0.25,
                "y_scale": 0.0,
                "shift_x": "travellingdimension:vanilla/shift_x",
                "type": "minecraft:noise"
              },
              "jagged": { "noise": "travellingdimension:vanilla/jagged", "xz_scale": 1500.0, "y_scale": 0.0, "type": "minecraft:noise" },
              "other": { "noise": "travellingdimension:vanilla/erosion", "xz_scale": 0.25, "type": "minecraft:shifted_noise" },
              "plain": { "noise": "travellingdimension:vanilla/erosion", "xz_scale": 0.25, "type": "minecraft:other" },
              "type": "minecraft:cache"
            }
            """
        )
        val expected = JsonParser.parseString(
            """
            {
              "input": {
                "noise": "travellingdimension:vanilla/continentalness",
                "xz_scale": 0.125,
                "y_scale": 0.0,
                "shift_x": "travellingdimension:vanilla/shift_x",
                "type": "minecraft:noise"
              },
              "jagged": { "noise": "travellingdimension:vanilla/jagged", "xz_scale": 1500.0, "y_scale": 0.0, "type": "minecraft:noise" },
              "other": { "noise": "travellingdimension:vanilla/erosion", "xz_scale": 0.125, "type": "minecraft:shifted_noise" },
              "plain": { "noise": "travellingdimension:vanilla/erosion", "xz_scale": 0.25, "type": "minecraft:other" },
              "type": "minecraft:cache"
            }
            """
        )

        assertEquals(expected, MediumBiomes.halved(source, ENLARGED))
        // Sans bruit à jumeau, rien ne change.
        assertEquals(source, MediumBiomes.halved(source, emptySet()))
    }

    @Test
    @DisplayName("le réglage de bruit dérivé et son dossier portent le nom que la résolution cherche")
    fun `noms de la resolution`() {
        assertEquals("medium_biomes", MediumBiomes.NOISE_SETTINGS)
        assertEquals("overworld_medium_biomes", MediumBiomes.FOLDER)
        assertEquals("overworld_large_biomes", MediumBiomes.LARGE_FOLDER)
    }
}
