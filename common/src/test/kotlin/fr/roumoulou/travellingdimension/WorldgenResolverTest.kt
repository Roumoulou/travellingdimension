// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.BiomeChoice
import fr.roumoulou.travellingdimension.dimension.Terrain
import fr.roumoulou.travellingdimension.dimension.WorldgenDetection
import fr.roumoulou.travellingdimension.dimension.WorldgenNotice
import fr.roumoulou.travellingdimension.dimension.WorldgenResolution
import fr.roumoulou.travellingdimension.dimension.WorldgenResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La résolution de la génération de VOYAGE : la table de décision de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`, un test par cas de ses
 * chapitres 2 et 7.
 *
 * La résolution ne prend aucun type du jeu : ces tests vivent dans le source set **pur**. La
 * détection, qui lit les mods chargés et les registres, s'éprouve à l'étage 2.
 */
class WorldgenResolverTest {

    private companion object {
        const val FOLDER = "game/travellingdimension/worldgen"

        /** Rien d'installé, rien de chargé, aucun identifiant connu. */
        val NOTHING = WorldgenDetection(
            terralithLoaded = false,
            tectonicLoaded = false,
            wwooInstalled = false,
            vanillaCopyLoaded = false,
            williamCopyLoaded = false,
            customNoiseSettingsKnown = false,
            customBiomePresetKnown = false,
        )

        val OVERWORLD_PRESET = BiomeChoice.Preset("minecraft:overworld")
        val VANILLA_COPY_BIOMES = BiomeChoice.VanillaLayout(listOf("travellingdimension:vanilla/"))
    }

    private fun resolve(config: TravelConfig, detection: WorldgenDetection): WorldgenResolution =
        WorldgenResolver.resolve(config, detection, FOLDER)

    // ── terralith ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("terralith : VOYAGE suit l'OVERWORLD à la taille de largeBiomes, aucun message")
    fun `terralith suit OVERWORLD`() {
        val detection = NOTHING.copy(terralithLoaded = true)

        assertEquals(
            WorldgenResolution(Terrain.Noise("minecraft:large_biomes", OVERWORLD_PRESET)),
            resolve(TravelConfig(worldgen = WorldgenMode.TERRALITH, largeBiomes = true), detection),
        )
        assertEquals(
            WorldgenResolution(Terrain.Noise("minecraft:overworld", OVERWORLD_PRESET)),
            resolve(TravelConfig(worldgen = WorldgenMode.TERRALITH, largeBiomes = false), detection),
        )
    }

    @Test
    @DisplayName("terralith sans Terralith : le même terrain, aucun message")
    fun `terralith sans Terralith`() {
        val config = TravelConfig(worldgen = WorldgenMode.TERRALITH)

        assertEquals(resolve(config, NOTHING.copy(terralithLoaded = true)), resolve(config, NOTHING))
        assertEquals(emptyList<WorldgenNotice>(), resolve(config, NOTHING).notices)
    }

    // ── vanilla ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("vanilla : le bruit et les biomes de la copie vanilla, à la taille de largeBiomes")
    fun `vanilla prend la copie vanilla`() {
        // Terralith installé : il remplace les identifiants minecraft:, pas ceux de la copie.
        val detection = NOTHING.copy(terralithLoaded = true, vanillaCopyLoaded = true)

        assertEquals(
            WorldgenResolution(Terrain.Noise("travellingdimension:vanilla/large_biomes", VANILLA_COPY_BIOMES)),
            resolve(TravelConfig(worldgen = WorldgenMode.VANILLA, largeBiomes = true), detection),
        )
        assertEquals(
            WorldgenResolution(Terrain.Noise("travellingdimension:vanilla/overworld", VANILLA_COPY_BIOMES)),
            resolve(TravelConfig(worldgen = WorldgenMode.VANILLA, largeBiomes = false), detection),
        )
    }

    @Test
    @DisplayName("vanilla sans copie vanilla : le JSON embarqué et vanilla_copy_failed")
    fun `vanilla sans copie vanilla`() {
        assertEquals(
            WorldgenResolution(Terrain.EmbeddedStem, listOf(WorldgenNotice.VanillaCopyFailed)),
            resolve(TravelConfig(worldgen = WorldgenMode.VANILLA), NOTHING),
        )
        assertEquals("travellingdimension.worldgen.vanilla_copy_failed", WorldgenNotice.VanillaCopyFailed.key)
        assertEquals(emptyList<String>(), WorldgenNotice.VanillaCopyFailed.arguments)
    }

    // ── william ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("william, copies vanilla et William chargées : le bruit de la copie vanilla, les biomes de William, aucun message")
    fun `william prend la copie William`() {
        val detection = NOTHING.copy(terralithLoaded = true, vanillaCopyLoaded = true, williamCopyLoaded = true)

        // Un biome que la copie William ne porte pas se prend dans la copie vanilla.
        assertEquals(
            WorldgenResolution(
                Terrain.Noise(
                    "travellingdimension:vanilla/large_biomes",
                    BiomeChoice.VanillaLayout(listOf("travellingdimension:wwoo/", "travellingdimension:vanilla/")),
                )
            ),
            resolve(TravelConfig(worldgen = WorldgenMode.WILLIAM, largeBiomes = true), detection),
        )
    }

    @Test
    @DisplayName("william, WWOO installé : les biomes minecraft: de la disposition vanilla, la copie William ne sert pas")
    fun `william avec WWOO installe`() {
        val detection = NOTHING.copy(wwooInstalled = true, vanillaCopyLoaded = true, williamCopyLoaded = true)

        assertEquals(
            WorldgenResolution(Terrain.Noise("travellingdimension:vanilla/large_biomes", BiomeChoice.VanillaLayout(listOf("minecraft:")))),
            resolve(TravelConfig(worldgen = WorldgenMode.WILLIAM, largeBiomes = true), detection),
        )
    }

    @Test
    @DisplayName("william sans source : la copie vanilla et william_no_source")
    fun `william sans source`() {
        val notice = WorldgenNotice.WilliamNoSource(FOLDER)

        assertEquals(
            WorldgenResolution(Terrain.Noise("travellingdimension:vanilla/overworld", VANILLA_COPY_BIOMES), listOf(notice)),
            resolve(TravelConfig(worldgen = WorldgenMode.WILLIAM, largeBiomes = false), NOTHING.copy(vanillaCopyLoaded = true)),
        )
        assertEquals("travellingdimension.worldgen.william_no_source", notice.key)
        assertEquals(listOf(FOLDER), notice.arguments)
    }

    @Test
    @DisplayName("william sans copie vanilla : le JSON embarqué, quelle que soit la source")
    fun `william sans copie vanilla`() {
        val config = TravelConfig(worldgen = WorldgenMode.WILLIAM)
        val embedded = WorldgenResolution(Terrain.EmbeddedStem, listOf(WorldgenNotice.VanillaCopyFailed))

        assertEquals(embedded, resolve(config, NOTHING.copy(wwooInstalled = true)))
        assertEquals(embedded, resolve(config, NOTHING.copy(williamCopyLoaded = true)))
        assertEquals(
            WorldgenResolution(Terrain.EmbeddedStem, listOf(WorldgenNotice.WilliamNoSource(FOLDER), WorldgenNotice.VanillaCopyFailed)),
            resolve(config, NOTHING),
        )
    }

    // ── tectonic ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tectonic avec Tectonic : minecraft:overworld, sans regarder largeBiomes")
    fun `tectonic avec Tectonic`() {
        val detection = NOTHING.copy(tectonicLoaded = true)
        val overworld = WorldgenResolution(Terrain.Noise("minecraft:overworld", OVERWORLD_PRESET))

        assertEquals(overworld, resolve(TravelConfig(worldgen = WorldgenMode.TECTONIC, largeBiomes = true), detection))
        assertEquals(overworld, resolve(TravelConfig(worldgen = WorldgenMode.TECTONIC, largeBiomes = false), detection))
    }

    @Test
    @DisplayName("tectonic sans Tectonic : la copie vanilla à la taille de largeBiomes, et tectonic_missing")
    fun `tectonic sans Tectonic`() {
        val detection = NOTHING.copy(vanillaCopyLoaded = true)
        val notices = listOf(WorldgenNotice.TectonicMissing)

        assertEquals(
            WorldgenResolution(Terrain.Noise("travellingdimension:vanilla/large_biomes", VANILLA_COPY_BIOMES), notices),
            resolve(TravelConfig(worldgen = WorldgenMode.TECTONIC, largeBiomes = true), detection),
        )
        assertEquals(
            WorldgenResolution(Terrain.Noise("travellingdimension:vanilla/overworld", VANILLA_COPY_BIOMES), notices),
            resolve(TravelConfig(worldgen = WorldgenMode.TECTONIC, largeBiomes = false), detection),
        )
        assertEquals("travellingdimension.worldgen.tectonic_missing", WorldgenNotice.TectonicMissing.key)
    }

    // ── custom ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("custom : les deux identifiants de la configuration, sans regarder largeBiomes")
    fun `custom avec identifiants connus`() {
        val detection = NOTHING.copy(customNoiseSettingsKnown = true, customBiomePresetKnown = true)
        val custom = WorldgenResolution(Terrain.Noise("minecraft:amplified", BiomeChoice.Preset("othermod:layout")))

        listOf(true, false).forEach { largeBiomes ->
            val config = TravelConfig(worldgen = WorldgenMode.CUSTOM, largeBiomes = largeBiomes, customNoiseSettings = "minecraft:amplified", customBiomePreset = "othermod:layout")
            assertEquals(custom, resolve(config, detection))
        }
    }

    @Test
    @DisplayName("custom, identifiant inconnu : la copie vanilla et custom_unknown, qui le nomme")
    fun `custom avec identifiant inconnu`() {
        val config = TravelConfig(worldgen = WorldgenMode.CUSTOM, largeBiomes = true, customNoiseSettings = "othermod:hills", customBiomePreset = "othermod:layout")
        val detection = NOTHING.copy(vanillaCopyLoaded = true)
        val vanillaCopy = Terrain.Noise("travellingdimension:vanilla/large_biomes", VANILLA_COPY_BIOMES)
        val unknownNoise = WorldgenNotice.CustomUnknown("othermod:hills")
        val unknownPreset = WorldgenNotice.CustomUnknown("othermod:layout")

        assertEquals(WorldgenResolution(vanillaCopy, listOf(unknownNoise)), resolve(config, detection.copy(customBiomePresetKnown = true)))
        assertEquals(WorldgenResolution(vanillaCopy, listOf(unknownPreset)), resolve(config, detection.copy(customNoiseSettingsKnown = true)))
        assertEquals(WorldgenResolution(vanillaCopy, listOf(unknownNoise, unknownPreset)), resolve(config, detection))
        assertEquals("travellingdimension.worldgen.custom_unknown", unknownNoise.key)
        assertEquals(listOf("othermod:hills"), unknownNoise.arguments)

        // Le même identifiant inconnu dans les deux réglages ne se dit qu'une fois.
        val twice = config.copy(customBiomePreset = "othermod:hills")
        assertEquals(WorldgenResolution(vanillaCopy, listOf(unknownNoise)), resolve(twice, detection))
    }

    // ── la chaîne de repli ───────────────────────────────────────────────────

    @Test
    @DisplayName("sans copie vanilla, un repli descend au JSON embarqué et ses messages coexistent")
    fun `repli sans copie vanilla`() {
        assertEquals(
            WorldgenResolution(Terrain.EmbeddedStem, listOf(WorldgenNotice.TectonicMissing, WorldgenNotice.VanillaCopyFailed)),
            resolve(TravelConfig(worldgen = WorldgenMode.TECTONIC), NOTHING),
        )
        assertEquals(
            WorldgenResolution(
                Terrain.EmbeddedStem,
                listOf(WorldgenNotice.CustomUnknown("othermod:hills"), WorldgenNotice.CustomUnknown("othermod:layout"), WorldgenNotice.VanillaCopyFailed),
            ),
            resolve(TravelConfig(worldgen = WorldgenMode.CUSTOM, customNoiseSettings = "othermod:hills", customBiomePreset = "othermod:layout"), NOTHING),
        )
    }

    @Test
    @DisplayName("une construction qui lève descend d'un maillon, et generator_failed en donne la raison")
    fun `construction qui leve`() {
        val failure = WorldgenNotice.GeneratorFailed("noise settings 'minecraft:large_biomes' not found")
        val vanillaCopy = Terrain.Noise("travellingdimension:vanilla/large_biomes", VANILLA_COPY_BIOMES)

        // Le terrain du mode échoue : la copie vanilla quand elle est chargée, le JSON embarqué sinon.
        val terralith = TravelConfig(worldgen = WorldgenMode.TERRALITH)
        val withCopy = NOTHING.copy(vanillaCopyLoaded = true)
        assertEquals(
            WorldgenResolution(vanillaCopy, listOf(failure)),
            WorldgenResolver.fallback(terralith, withCopy, resolve(terralith, withCopy), failure.reason),
        )
        assertEquals(
            WorldgenResolution(Terrain.EmbeddedStem, listOf(failure, WorldgenNotice.VanillaCopyFailed)),
            WorldgenResolver.fallback(terralith, NOTHING, resolve(terralith, NOTHING), failure.reason),
        )

        // La copie vanilla échoue à son tour : le JSON embarqué, et les deux échecs se disent.
        val william = TravelConfig(worldgen = WorldgenMode.WILLIAM)
        val bothCopies = withCopy.copy(williamCopyLoaded = true)
        val second = WorldgenResolver.fallback(william, bothCopies, resolve(william, bothCopies), failure.reason)
        assertEquals(WorldgenResolution(vanillaCopy, listOf(failure)), second)
        assertEquals(
            WorldgenResolution(Terrain.EmbeddedStem, listOf(failure, WorldgenNotice.GeneratorFailed("biome 'plains' not found"))),
            WorldgenResolver.fallback(william, bothCopies, second, "biome 'plains' not found"),
        )
        assertEquals("travellingdimension.worldgen.generator_failed", failure.key)
        assertEquals(listOf(failure.reason), failure.arguments)
    }
}
