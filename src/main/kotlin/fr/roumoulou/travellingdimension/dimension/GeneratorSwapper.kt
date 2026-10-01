// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import com.mojang.datafixers.util.Pair
import fr.roumoulou.travellingdimension.TravellingDimension
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
 * Remplace le générateur du LevelStem de la dimension de voyage au moment de la
 * création des mondes (appelé par le mixin sur MinecraftServer.createLevels).
 *
 * Deux façons de construire la source de biomes :
 * - preset par id (registre `multi_noise_biome_source_parameter_list`, datapacks inclus) ;
 * - remap WWOO : la disposition climatique VANILLA codée en dur (immunisée contre les
 *   overrides de datapacks type Terralith), où chaque biome `minecraft:X` écrasé par
 *   William est remplacé par notre copie `travellingdimension:wwoo/X`.
 *
 * Jamais fatal : la moindre erreur est loggée et le LevelStem d'origine
 * (JSON par défaut : vanilla large biomes) est conservé.
 */
object GeneratorSwapper {

    @JvmStatic
    fun swapTravelGenerator(server: MinecraftServer, original: LevelStem): LevelStem {
        val target = WorldgenSelector.swapTarget ?: return original

        return try {
            val access = server.registryAccess()

            val settingsHolder = access.lookup(Registries.NOISE_SETTINGS).orElseThrow()
                .get(ResourceKey.create(Registries.NOISE_SETTINGS, target.noiseSettings))
                .orElseThrow { IllegalArgumentException("noise settings '${target.noiseSettings}' introuvables") }

            val biomeSource = when {
                target.wwooRemap -> buildWwooRemappedSource(access)
                target.biomePreset != null -> {
                    val presetHolder = access.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).orElseThrow()
                        .get(ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, target.biomePreset))
                        .orElseThrow { IllegalArgumentException("biome preset '${target.biomePreset}' introuvable") }
                    MultiNoiseBiomeSource.createFromPreset(presetHolder)
                }
                else -> throw IllegalStateException("SwapTarget sans preset ni remap")
            }

            TravellingDimension.LOGGER.info(
                "Générateur de la dimension de voyage remplacé : {} (settings '{}')",
                target.label, target.noiseSettings
            )
            LevelStem(original.type(), NoiseBasedChunkGenerator(biomeSource, settingsHolder))
        } catch (e: Exception) {
            WorldgenSelector.noteFallback(
                "Impossible d'appliquer le worldgen '${target.label}' (${e.message}) : " +
                        "la dimension de voyage garde la génération vanilla (large biomes)."
            )
            original
        }
    }

    /**
     * Disposition climatique de l'Overworld VANILLA (preset codé en dur, PAS le registre,
     * donc insensible aux overrides globaux type Terralith), remappée vers les biomes
     * WWOO du pack embarqué : `minecraft:X` -> `travellingdimension:wwoo/X` quand la
     * copie existe, sinon biome vanilla conservé.
     */
    private fun buildWwooRemappedSource(access: RegistryAccess): BiomeSource {
        val vanillaLayout = MultiNoiseBiomeSourceParameterList.knownPresets()[MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD]
            ?: throw IllegalStateException("preset overworld codé en dur introuvable")

        val biomes = access.lookup(Registries.BIOME).orElseThrow()
        var remappedCount = 0

        val remapped: List<Pair<Climate.ParameterPoint, Holder<Biome>>> = vanillaLayout.values().map { entry ->
            val vanillaKey: ResourceKey<Biome> = entry.second
            val wwooId = Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "wwoo/" + vanillaKey.identifier().path)
            val wwooHolder = biomes.get(ResourceKey.create(Registries.BIOME, wwooId))
            val holder: Holder<Biome> =
                if (wwooHolder.isPresent) {
                    remappedCount++
                    wwooHolder.get()
                } else {
                    biomes.get(vanillaKey).orElseThrow {
                        IllegalStateException("biome vanilla '${vanillaKey.identifier()}' introuvable")
                    }
                }
            Pair.of(entry.first, holder)
        }

        if (remappedCount == 0) {
            throw IllegalStateException(
                "aucun biome travellingdimension:wwoo/* dans les registres — le pack wwoo_worldgen est-il chargé ?"
            )
        }
        TravellingDimension.LOGGER.info(
            "Biomes WWOO remappés dans la dimension de voyage : {} entrées sur {}",
            remappedCount, remapped.size
        )
        return MultiNoiseBiomeSource.createFromList(Climate.ParameterList(remapped))
    }
}
