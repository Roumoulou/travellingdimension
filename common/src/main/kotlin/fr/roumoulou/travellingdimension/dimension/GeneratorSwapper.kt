// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import com.mojang.datafixers.util.Pair
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

/**
 * Construit le générateur de VOYAGE à la création des mondes, appelé par le mixin de
 * `MinecraftServer.createLevels` : le terrain que [WorldgenSelector.select] retient remplace le
 * générateur du `LevelStem`. Le registre des dimensions reste intact.
 *
 * Jamais fatal : une construction qui lève descend d'un maillon du repli
 * ([WorldgenSelector.descend]), jusqu'au `LevelStem` d'origine, celui du JSON embarqué.
 */
object GeneratorSwapper {

    @JvmStatic
    fun swapTravelGenerator(server: MinecraftServer, original: LevelStem): LevelStem {
        val registries = server.registryAccess()
        return build(registries, original, WorldgenSelector.select(registries))
    }

    private fun build(registries: RegistryAccess, original: LevelStem, selection: WorldgenSelector.Selection): LevelStem {
        val terrain = selection.resolution.terrain as? Terrain.Noise ?: return original
        return try {
            val settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                .get(ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.parse(terrain.noiseSettings)))
                .orElseThrow { IllegalArgumentException("noise settings '${terrain.noiseSettings}' not found") }
            LevelStem(original.type(), NoiseBasedChunkGenerator(biomeSource(registries, terrain.biomes), settings))
        } catch (e: Exception) {
            build(registries, original, WorldgenSelector.descend(selection, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun biomeSource(registries: RegistryAccess, choice: BiomeChoice): BiomeSource = when (choice) {
        is BiomeChoice.Preset -> {
            val preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .get(ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, Identifier.parse(choice.id)))
                .orElseThrow { IllegalArgumentException("biome preset '${choice.id}' not found") }
            MultiNoiseBiomeSource.createFromPreset(preset)
        }

        is BiomeChoice.VanillaLayout -> vanillaLayout(registries, choice.prefixes)
    }

    /**
     * La disposition des biomes de l'OVERWORLD vanilla, celle que le jeu code en dur : le preset
     * `minecraft:overworld` du registre, lui, appartient à qui le remplace. Chaque
     * `minecraft:<biome>` se prend sous le premier préfixe de [prefixes] où il existe.
     */
    private fun vanillaLayout(registries: RegistryAccess, prefixes: List<String>): BiomeSource {
        val layout = MultiNoiseBiomeSourceParameterList.knownPresets()[MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD]
            ?: throw IllegalStateException("hard-coded overworld preset not found")
        val biomes = registries.lookupOrThrow(Registries.BIOME)

        val entries: List<Pair<Climate.ParameterPoint, Holder<Biome>>> = layout.values().map { entry ->
            val path = entry.second.identifier().path
            val holder: Holder<Biome> = prefixes.firstNotNullOfOrNull { prefix -> biomes.get(ResourceKey.create(Registries.BIOME, Identifier.parse(prefix + path))).orElse(null) }
                ?: throw IllegalStateException("biome '$path' not found under ${prefixes.joinToString(" or ")}")
            Pair.of(entry.first, holder)
        }
        return MultiNoiseBiomeSource.createFromList(Climate.ParameterList(entries))
    }
}
