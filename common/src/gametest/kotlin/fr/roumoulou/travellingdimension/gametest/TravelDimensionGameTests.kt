// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gametest

import com.google.gson.JsonElement
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.dimension.WorldgenSelector
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

/**
 * VOYAGE, la dimension elle-même : son type, et son générateur.
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
 */
class TravelDimensionGameTests {

    private companion object {
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
     * Le générateur de VOYAGE dans le run `gameTest`, né de la configuration par défaut : `terralith` suit l'OVERWORLD, et sur un
     * serveur sans mod de génération rien n'a remplacé ses identifiants. Les biomes se comptent par
     * [WorldgenSelector.biomeCounts], le chemin de la ligne « Travel dimension active ».
     */
    @GameTest
    fun travelGeneratorFollowsTheOverworld(helper: GameTestHelper) {
        val config = ConfigManager.current
        helper.assertTrue(config.worldgen == WorldgenMode.TERRALITH && config.largeBiomes, "le run gameTest naît de la configuration par défaut : terralith, large biomes")

        val generator = Harness.travel(helper).chunkSource.generator
        val noise = generator as? NoiseBasedChunkGenerator
            ?: throw helper.assertionException("le générateur de VOYAGE n'est pas un générateur de bruit : ${generator.javaClass.simpleName}")
        val settings = noise.generatorSettings().unwrapKey().map { it.identifier().toString() }.orElse("sans clé de registre")
        helper.assertValueEqual(settings, "minecraft:large_biomes", "le réglage de bruit de VOYAGE")

        val counts = WorldgenSelector.biomeCounts(generator.biomeSource)
        helper.assertValueEqual(counts.keys.toSet(), setOf("minecraft"), "les espaces de noms des biomes de VOYAGE")
        helper.succeed()
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
}
