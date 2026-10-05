// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gametest

import com.google.gson.JsonElement
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenDetection
import fr.roumoulou.travellingdimension.dimension.WorldgenDetector
import fr.roumoulou.travellingdimension.dimension.WorldgenNotice
import fr.roumoulou.travellingdimension.dimension.WorldgenPacks
import fr.roumoulou.travellingdimension.dimension.WorldgenSelector
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.validation.DirectoryValidator
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * VOYAGE, la dimension elle-même : son type, son générateur, ce que la détection lit des registres, et les datapacks que le mod charge
 * pour elle.
 *
 * VOYAGE est une dimension de type OVERWORLD : son type de dimension dit ce que dit celui de
 * l'OVERWORLD, `coordinate_scale` mis à part, qui porte le ratio. Joué contre le jeu de chaque
 * module de version, à chaque build : quand une release ajoute ou change un attribut, l'écart
 * devient un échec qui le nomme, au lieu d'une enquête à la main.
 *
 * Les attributs se comparent à leur valeur effective, défaut compris, parce que c'est elle qui joue
 * en jeu : un attribut que `travel.json` ne déclare pas prend son défaut. Le `straw_bed_rule` de
 * 26.3 en est le cas : absent de VOYAGE, il y vaut la règle même que l'OVERWORLD déclare. Valeurs
 * et champs se comparent en JSON, par leurs codecs et les registres du serveur : certains types de
 * valeur n'ont pas d'égalité par valeur.
 *
 * Les tests de la génération tournent dans les deux runs de l'étage 2, et savent lequel par le nom que le build leur donne
 * ([GameTestRun]) : un run n'éprouve qu'un mode, parce que le générateur de VOYAGE se fige au lancement du jeu.
 */
class TravelDimensionGameTests {

    private companion object {
        /** La propriété système par laquelle le build nomme le run à ses tests (section 7 du plugin de version). */
        const val RUN_PROPERTY = "${TravellingDimension.MOD_ID}.gametest.run"

        /** Un `pack.mcmeta` de datapack que les trois versions servies lisent : leurs formats vont de 101 à 121. */
        const val PACK_MCMETA = """{ "pack": { "description": "A copy made by Travelling Dimension", "min_format": 101, "max_format": 121 } }"""

        /** Le biome du datapack témoin, que le run `gameTestVanilla` trouve à la place de la copie vanilla. */
        val WITNESS_BIOME: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "witness/void"))

        /** Ce que VOYAGE dit autrement que l'OVERWORLD, et lui seul : le ratio. */
        val INTENDED_DIFFERENCES = setOf("coordinate_scale")
    }

    /** Chaque champ du type de VOYAGE, et chaque attribut du registre, vaut ce qu'il vaut dans l'OVERWORLD. */
    @GameTest
    fun travelTypeMatchesTheOverworld(helper: GameTestHelper) {
        val registries = helper.level.registryAccess()
        val ops = registries.createSerializationContext(JsonOps.INSTANCE)
        val types = registries.lookupOrThrow(Registries.DIMENSION_TYPE)
        val overworld = types.getOrThrow(BuiltinDimensionTypes.OVERWORLD).value()
        val travel = types.getOrThrow(TravelDimensionKeys.TRAVEL_TYPE).value()
        val differences = mutableListOf<String>()

        val overworldFields = fields(overworld, ops)
        val travelFields = fields(travel, ops)
        (overworldFields.keys + travelFields.keys)
            .filter { it !in INTENDED_DIFFERENCES && overworldFields[it] != travelFields[it] }
            .forEach { differences += "$it : OVERWORLD ${overworldFields[it]}, VOYAGE ${travelFields[it]}" }

        BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.forEach { attribute ->
            val inOverworld = effective(overworld, attribute, ops)
            val inTravel = effective(travel, attribute, ops)
            if (inOverworld != inTravel) {
                differences += "${BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute)} : OVERWORLD $inOverworld, VOYAGE $inTravel"
            }
        }

        helper.assertTrue(differences.isEmpty(), "VOYAGE s'écarte de l'OVERWORLD : ${differences.joinToString(" ; ")}")
        helper.succeed()
    }

    /**
     * Le générateur de VOYAGE suit l'OVERWORLD dans les deux runs, sur un serveur sans mod de génération, où rien n'a remplacé ses
     * identifiants. Dans `gameTest`, c'est le terrain du mode `terralith`, et ce n'est pas un repli. Dans `gameTestVanilla`, c'est le
     * dernier maillon du repli : le datapack témoin ne porte pas le réglage de bruit de la copie vanilla, et `vanilla_copy_failed` le
     * dit. Les biomes se comptent par [WorldgenSelector.biomeCounts], le chemin de la ligne « Travel dimension active ».
     */
    @GameTest
    fun travelGeneratorFollowsTheOverworld(helper: GameTestHelper) {
        val run = currentRun(helper)
        helper.assertTrue(ConfigManager.current.largeBiomes, "les deux runs gardent largeBiomes à son défaut")

        val generator = Harness.travel(helper).chunkSource.generator
        val noise = generator as? NoiseBasedChunkGenerator
            ?: throw helper.assertionException("le générateur de VOYAGE n'est pas un générateur de bruit : ${generator.javaClass.simpleName}")
        val settings = noise.generatorSettings().unwrapKey().map { it.identifier().toString() }.orElse("sans clé de registre")
        helper.assertValueEqual(settings, "minecraft:large_biomes", "le réglage de bruit de VOYAGE")

        val counts = WorldgenSelector.biomeCounts(generator.biomeSource)
        helper.assertValueEqual(counts.keys.toSet(), setOf("minecraft"), "les espaces de noms des biomes de VOYAGE")

        val expectedNotices: List<String> = when (run) {
            // terralith sans Terralith n'est pas un repli : aucun message n'attend les opérateurs.
            GameTestRun.GAME_TEST -> emptyList()
            GameTestRun.GAME_TEST_VANILLA -> listOf(WorldgenNotice.VanillaCopyFailed.key)
        }
        helper.assertValueEqual(WorldgenSelector.notices.map { it.key }, expectedNotices, "les messages du repli dans le run ${run.runName}")
        helper.succeed()
    }

    /**
     * La détection sur les registres des deux runs, sans mod de génération : rien n'est installé, aucune copie n'est chargée, et les
     * deux identifiants par défaut de `custom` sont connus. Deux identifiants d'un mod absent ne le sont pas. Le datapack témoin du run
     * `gameTestVanilla` ne rend vrai aucun critère : il ne porte ni le réglage de bruit de la copie vanilla, ni un biome de la copie
     * William.
     */
    @GameTest
    fun detectionFindsNothingOnAVanillaServer(helper: GameTestHelper) {
        val registries = helper.level.registryAccess()
        val nothing = WorldgenDetection(
            terralithLoaded = false,
            tectonicLoaded = false,
            wwooInstalled = false,
            vanillaCopyLoaded = false,
            williamCopyLoaded = false,
            customNoiseSettingsKnown = true,
            customBiomePresetKnown = true,
        )
        helper.assertValueEqual(WorldgenDetector.detect(registries, TravelConfig()), nothing, "la détection sur un serveur sans mod de génération")

        val unknown = TravelConfig(customNoiseSettings = "othermod:hills", customBiomePreset = "othermod:layout")
        helper.assertValueEqual(
            WorldgenDetector.detect(registries, unknown),
            nothing.copy(customNoiseSettingsKnown = false, customBiomePresetKnown = false),
            "la détection de deux identifiants absents des registres",
        )
        helper.succeed()
    }

    /**
     * Ce que le serveur du run a chargé. Dans `gameTest`, aucune copie n'est préparée : aucun datapack du mod, et le biome témoin n'est
     * pas dans les registres. Dans `gameTestVanilla`, le datapack de `travellingdimension/generated/vanilla/` est préparé, sélectionné
     * et requis, et son biome est dans les registres : un datapack du dossier du jeu, hors du jar, y est entré.
     */
    @GameTest
    fun serverLoadsThePreparedCopies(helper: GameTestHelper) {
        val run = currentRun(helper)
        val expected: List<WorldgenCopy> = when (run) {
            GameTestRun.GAME_TEST -> emptyList()
            GameTestRun.GAME_TEST_VANILLA -> listOf(WorldgenCopy.VANILLA)
        }

        val repository = helper.level.server.packRepository
        helper.assertValueEqual(WorldgenPacks.prepared.keys.toList(), expected, "les copies préparées dans le run ${run.runName}")
        helper.assertValueEqual(WorldgenPacks.selectedIn(repository), expected.map { it.packId }, "les datapacks du mod sélectionnés par le serveur")
        expected.forEach { copy ->
            helper.assertTrue(repository.getPack(copy.packId)?.isRequired == true, "le datapack ${copy.packId} est requis")
        }

        val witnessLoaded = helper.level.registryAccess().lookupOrThrow(Registries.BIOME).get(WITNESS_BIOME).isPresent
        helper.assertValueEqual(witnessLoaded, run == GameTestRun.GAME_TEST_VANILLA, "le biome ${WITNESS_BIOME.identifier()} dans les registres")
        helper.succeed()
    }

    /**
     * Le mixin du dépôt de datapacks, dans les deux runs : une copie préparée entre dans tout dépôt bâti autour de la source vanilla du
     * jeu, par la fabrique de `ServerPacksSource` comme par le constructeur seul, la forme que prend l'écran de création d'un monde, et
     * elle y est requise.
     */
    @GameTest
    fun preparedCopiesEnterEveryDatapackRepository(helper: GameTestHelper) {
        val ownPrepared = WorldgenPacks.prepared.keys.toSet()
        val scratch = Files.createTempDirectory("travellingdimension-gametest")
        try {
            val generated = scratch.resolve("generated")
            Files.createDirectories(generated.resolve(WorldgenCopy.VANILLA.folder)).resolve("pack.mcmeta").writeText(PACK_MCMETA)
            WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA), generated)

            val validator = DirectoryValidator { true }
            val repositories = listOf(
                "par la fabrique" to ServerPacksSource.createPackRepository(scratch.resolve("datapacks"), validator),
                "par le constructeur seul" to PackRepository(ServerPacksSource(validator)),
            )
            for ((built, repository) in repositories) {
                repository.reload()
                repository.setSelected(listOf("vanilla"))
                helper.assertValueEqual(WorldgenPacks.selectedIn(repository), listOf(WorldgenCopy.VANILLA.packId), "les datapacks du mod dans un dépôt bâti $built")
                helper.assertTrue(repository.getPack(WorldgenCopy.VANILLA.packId)?.isRequired == true, "la copie vanilla est requise dans un dépôt bâti $built")
            }
        } finally {
            // Les copies préparées sont un état du processus : le run retrouve les siennes.
            WorldgenPacks.prepare(ownPrepared)
            scratch.toFile().deleteRecursively()
        }
        helper.succeed()
    }

    /**
     * Le run où le test tourne : le build le nomme par une propriété système, et sa configuration doit porter son mode. Un run qui
     * aurait perdu ses fixtures échoue ici, au lieu de passer pour l'autre.
     */
    private fun currentRun(helper: GameTestHelper): GameTestRun {
        val name = System.getProperty(RUN_PROPERTY)
        val run = GameTestRun.entries.firstOrNull { it.runName == name }
            ?: throw helper.assertionException("le run de l'étage 2 est inconnu : la propriété $RUN_PROPERTY vaut $name")
        helper.assertValueEqual(ConfigManager.current.worldgen, run.mode, "le mode de la configuration du run ${run.runName}")
        return run
    }

    /** Les champs d'un type de dimension en JSON, sauf ses attributs, qui se comparent à leur valeur effective. */
    private fun fields(type: DimensionType, ops: DynamicOps<JsonElement>): Map<String, JsonElement> =
        DimensionType.DIRECT_CODEC.encodeStart(ops, type).getOrThrow().asJsonObject.entrySet()
            .filter { it.key != "attributes" }
            .associate { it.key to it.value }

    /* Le registre rend des attributs de type inconnu : la valeur et le codec d'un même attribut vont pourtant ensemble. */
    @Suppress("UNCHECKED_CAST")
    private fun effective(type: DimensionType, attribute: EnvironmentAttribute<*>, ops: DynamicOps<JsonElement>): JsonElement =
        effectiveValue(type, attribute as EnvironmentAttribute<Any>, ops)

    /** La valeur que [attribute] prend dans [type], son défaut s'il n'y est pas déclaré, en JSON. */
    private fun <V : Any> effectiveValue(type: DimensionType, attribute: EnvironmentAttribute<V>, ops: DynamicOps<JsonElement>): JsonElement =
        attribute.valueCodec().encodeStart(ops, type.attributes().applyModifier(attribute, attribute.defaultValue())).getOrThrow()

    /** Les deux runs de l'étage 2 : leur nom dans le build, et le mode que leur configuration porte. */
    private enum class GameTestRun(val runName: String, val mode: WorldgenMode) {

        /** La configuration née des défauts, donc `terralith` : le chemin de tous les joueurs. */
        GAME_TEST("gameTest", WorldgenMode.TERRALITH),

        /** `worldgen` à `vanilla` par ses fixtures, et le datapack témoin à la place de la copie vanilla. */
        GAME_TEST_VANILLA("gameTestVanilla", WorldgenMode.VANILLA),
    }
}
