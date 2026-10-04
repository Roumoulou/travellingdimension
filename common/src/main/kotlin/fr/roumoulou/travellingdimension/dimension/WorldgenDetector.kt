// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.config.TravelConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biomes

/**
 * La détection de ce qui est installé et de ce que le mod a chargé : les critères du chapitre 3.3
 * de `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Elle lit les mods chargés et les registres : elle s'appelle à la création des mondes, registres
 * chargés, et s'éprouve à l'étage 2.
 */
object WorldgenDetector {

    /** L'espace de noms sous lequel WWOO range ses features. */
    private const val WWOO_FEATURES = "wythers"

    /** Ce qui est détecté dans [registries], et les deux identifiants de `custom` que porte [config]. */
    fun detect(registries: RegistryAccess, config: TravelConfig): WorldgenDetection {
        val mods = FabricLoader.getInstance()
        return WorldgenDetection(
            terralithLoaded = mods.isModLoaded("terralith"),
            tectonicLoaded = mods.isModLoaded("tectonic"),
            wwooInstalled = mods.isModLoaded("wwoo") || plainsCarryWwooFeatures(registries),
            vanillaCopyLoaded = isKnown(registries, Registries.NOISE_SETTINGS, WorldgenResolver.VANILLA_COPY + "overworld"),
            williamCopyLoaded = registries.lookupOrThrow(Registries.BIOME).listElementIds()
                .anyMatch { it.identifier().toString().startsWith(WorldgenResolver.WILLIAM_COPY) },
            customNoiseSettingsKnown = isKnown(registries, Registries.NOISE_SETTINGS, config.customNoiseSettings),
            customBiomePresetKnown = isKnown(registries, Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, config.customBiomePreset),
        )
    }

    /**
     * WWOO installé, mod ou datapack, se reconnaît à ce que `minecraft:plains` porte une feature
     * `wythers:` : il remplace ce biome dans les trois versions, et la copie William range tout
     * sous `travellingdimension:wwoo/`, features comprises.
     */
    private fun plainsCarryWwooFeatures(registries: RegistryAccess): Boolean =
        registries.lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS)
            .map { plains ->
                plains.value().generationSettings.features().any { step ->
                    step.any { feature -> feature.unwrapKey().map { it.identifier().namespace == WWOO_FEATURES }.orElse(false) }
                }
            }
            .orElse(false)

    private fun <T : Any> isKnown(registries: RegistryAccess, registry: ResourceKey<out Registry<T>>, raw: String): Boolean {
        val identifier = Identifier.tryParse(raw) ?: return false
        return registries.lookupOrThrow(registry).get(ResourceKey.create(registry, identifier)).isPresent
    }
}
