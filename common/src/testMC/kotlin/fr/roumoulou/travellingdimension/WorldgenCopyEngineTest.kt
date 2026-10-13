// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import com.google.gson.JsonParser
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyEngine
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyReport
import fr.roumoulou.travellingdimension.gameversion.GameVersion
import net.minecraft.SharedConstants
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * L'élagage du moteur des copies, sur une source écrite par le test : le chapitre 5.4 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Le datapack vanilla ne fait rien élaguer ([VanillaCopyTest]). Cette source porte donc ce qu'il faut pour le voir : une feature
 * d'un type que le jeu ne connaît pas, la placed feature qui la pose, et un biome qui liste cette dernière. Elle vit sous un espace
 * de noms à elle, `sample:`, ce qui éprouve du même coup le renommage d'un autre espace que `minecraft:`.
 */
class WorldgenCopyEngineTest {

    private companion object {
        const val MOD = TravellingDimension.MOD_ID

        /** Une feature sans réglage, que les trois versions servies lisent. */
        const val FEATURE = """{ "type": "minecraft:void_start_platform", "config": {} }"""

        /** Une feature d'un type absent du jeu : son codec la refuse. */
        const val UNKNOWN_FEATURE = """{ "type": "sample:unknown_type", "config": {} }"""

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @TempDir
    lateinit var scratch: Path

    private val source: Path get() = scratch.resolve("source")
    private val copy: Path get() = scratch.resolve("copy")

    /** Le dossier du registre des features : `worldgen/configured_feature` jusqu'en 26.2, `worldgen/feature` en 26.3. */
    private val featureFolder: String
        get() = WorldgenCopyEngine.generationRegistries(GameVersion.bridge.worldRegistries).map { Registries.elementsDirPath(it.key()) }
            .single { it == "worldgen/configured_feature" || it == "worldgen/feature" }

    @Test
    @DisplayName("un fichier que le codec refuse n'est pas écrit, celui qui le référence part avec lui, et la liste du biome raccourcit")
    fun `elagage`() {
        write("sample", "$featureFolder/good.json", FEATURE)
        write("sample", "$featureFolder/broken.json", UNKNOWN_FEATURE)
        write("sample", "worldgen/placed_feature/good.json", placed("sample:good"))
        write("sample", "worldgen/placed_feature/broken.json", placed("sample:broken"))
        // Une référence vers un élément que la source ne porte pas : elle reste telle quelle.
        write("sample", "worldgen/placed_feature/elsewhere.json", placed("minecraft:void_start_platform"))
        write("minecraft", "worldgen/biome/plains.json", biome("""[ [ "sample:good", "sample:broken" ], [ "sample:broken" ], [ "sample:elsewhere" ] ]"""))

        val report = fabricate()

        assertEquals(
            sortedMapOf(
                "worldgen/biome" to WorldgenCopyReport.Count(written = 1, pruned = 0),
                featureFolder to WorldgenCopyReport.Count(written = 1, pruned = 1),
                "worldgen/placed_feature" to WorldgenCopyReport.Count(written = 2, pruned = 1),
            ),
            report.registries,
        )
        assertEquals(setOf("$featureFolder sample:broken", "worldgen/placed_feature sample:broken"), report.refusals.keys)
        assertEquals("references the pruned $featureFolder sample:broken", report.refusals["worldgen/placed_feature sample:broken"])

        assertTrue(copied("$featureFolder/wwoo/sample/good.json").exists())
        assertFalse(copied("$featureFolder/wwoo/sample/broken.json").exists())
        assertFalse(copied("worldgen/placed_feature/wwoo/sample/broken.json").exists())
        assertEquals("$MOD:wwoo/sample/good", json(copied("worldgen/placed_feature/wwoo/sample/good.json")).get("feature").asString)
        assertEquals("minecraft:void_start_platform", json(copied("worldgen/placed_feature/wwoo/sample/elsewhere.json")).get("feature").asString)

        // Le biome reste, sous le chemin d'un identifiant minecraft:, et sa liste a perdu la placed feature élaguée.
        val features = json(copied("worldgen/biome/wwoo/plains.json")).getAsJsonArray("features").map { step -> step.asJsonArray.map { it.asString } }
        assertEquals(listOf(listOf("$MOD:wwoo/sample/good"), emptyList(), listOf("$MOD:wwoo/sample/elsewhere")), features)
    }

    @Test
    @DisplayName("un biome copié entre dans les tags de biomes minecraft: du datapack vanilla où son original est listé")
    fun `tags de biomes`() {
        write("minecraft", "worldgen/biome/plains.json", biome("[]"))
        write("sample", "worldgen/biome/plateau.json", biome("[]"))

        val report = fabricate()

        val villages = json(copy.resolve("data/minecraft/tags/worldgen/biome/has_structure/village_plains.json"))
        assertFalse(villages.get("replace").asBoolean)
        assertEquals(listOf("$MOD:wwoo/plains"), villages.getAsJsonArray("values").map { it.asString })
        assertTrue(report.biomeTags > 1, "minecraft:plains est dans plusieurs tags de biomes vanilla")

        // Un biome que le datapack vanilla ne connaît pas n'est listé dans aucun de ses tags.
        val everywhere = Files.walk(copy.resolve("data/minecraft")).use { files -> files.filter { Files.isRegularFile(it) }.map { it.readText() }.toList() }
        assertTrue(everywhere.none { "plateau" in it })
    }

    /** Fabrique la copie de la source du test, avec les tags de biomes du datapack vanilla de la version. */
    private fun fabricate(): WorldgenCopyReport {
        val bridge = GameVersion.bridge
        val pack = PathPackResources(PackLocationInfo("sample", Component.literal("sample"), PackSource.BUILT_IN, Optional.empty()), source)
        return WorldgenCopyEngine(WorldgenCopy.WILLIAM, pack, WorldgenCopyEngine.generationRegistries(bridge.worldRegistries), biomeTags = bridge.vanillaDatapack()).fabricate(copy)
    }

    private fun write(namespace: String, path: String, content: String) {
        val file = source.resolve("data/$namespace/$path")
        Files.createDirectories(file.parent)
        file.writeText(content)
    }

    private fun copied(path: String): Path = copy.resolve("data/$MOD/$path")

    private fun json(file: Path) = JsonParser.parseString(file.readText()).asJsonObject

    private fun placed(feature: String): String = """{ "feature": "$feature", "placement": [] }"""

    /** Un biome que les trois versions servies lisent : `spawners` et `spawn_costs` sont requis jusqu'en 26.2, et ignorés en 26.3. */
    private fun biome(features: String): String = """
        {
          "has_precipitation": false,
          "temperature": 0.5,
          "downfall": 0.5,
          "effects": { "water_color": "#3f76e4" },
          "spawners": { "ambient": [], "axolotls": [], "creature": [], "misc": [], "monster": [], "underground_water_creature": [], "water_ambient": [], "water_creature": [] },
          "spawn_costs": {},
          "carvers": [],
          "features": $features
        }
    """.trimIndent()
}
