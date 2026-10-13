// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.gameversion.GameVersion
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import java.nio.file.Path

/**
 * La copie vanilla : le datapack que le mod fabrique à partir du datapack vanilla du jeu, sous `travellingdimension:vanilla/`.
 *
 * Elle prend les réglages de bruit `overworld` et `large_biomes`, les biomes de la disposition que le jeu code en dur, et les
 * autres registres de génération en entier (chapitre 5.2 de la spécification). Sa source est le datapack vanilla lu seul : ce
 * qu'un mod ou un datapack remplace sous `minecraft:` n'y est pas.
 */
object VanillaCopy {

    private const val NOISE_SETTINGS_FOLDER = "worldgen/noise_settings"
    private const val BIOME_FOLDER = "worldgen/biome"

    /** Les deux réglages de bruit que la résolution référence, selon `largeBiomes`. */
    private val NOISE_SETTINGS = setOf("overworld", "large_biomes")

    /** Ce qui fabrique la copie vanilla, et sa clé : la version du mod et celle du jeu. */
    fun source(): WorldgenCopyMaker.Source = WorldgenCopyMaker.Source(WorldgenCopy.VANILLA, WorldgenCopyMaker.key(), ::fabricate)

    /**
     * Rend prête la copie vanilla seule sous [generated], et dit ce qu'il en est ([WorldgenCopyMaker.prepare]). Jamais fatal :
     * une fabrication qui lève laisse le dossier sans copie, et la résolution descendra d'un maillon, avec le message
     * `vanilla_copy_failed`.
     */
    fun prepare(generated: Path = WorldgenPacks.GENERATED_FOLDER, reuse: Boolean = !FabricLoader.getInstance().isDevelopmentEnvironment): WorldgenCopyMaker.Readiness =
        WorldgenCopyMaker.prepare(generated, listOf(source()), reuse).getValue(WorldgenCopy.VANILLA)

    /** Fabrique la copie vanilla dans [target] et rend son compte. */
    fun fabricate(target: Path): WorldgenCopyReport {
        val bridge = GameVersion.bridge
        val layout = layoutBiomes()
        val engine = WorldgenCopyEngine(WorldgenCopy.VANILLA, bridge.vanillaDatapack(), WorldgenCopyEngine.generationRegistries(bridge.worldRegistries)) { folder, element ->
            when (folder) {
                NOISE_SETTINGS_FOLDER -> element.path in NOISE_SETTINGS
                BIOME_FOLDER -> element in layout
                else -> true
            }
        }
        return engine.fabricate(target)
    }

    /** Les biomes de la disposition de l'OVERWORLD vanilla, celle que [GeneratorSwapper] donne à VOYAGE. */
    fun layoutBiomes(): Set<Identifier> {
        val layout = MultiNoiseBiomeSourceParameterList.knownPresets()[MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD]
            ?: throw IllegalStateException("hard-coded overworld preset not found")
        return layout.values().mapTo(LinkedHashSet()) { it.second.identifier() }
    }
}
