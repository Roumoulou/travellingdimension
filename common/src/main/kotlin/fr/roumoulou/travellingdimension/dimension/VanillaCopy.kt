// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
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
    private const val MINECRAFT = "minecraft"

    /** Les deux réglages de bruit que la résolution référence, selon `largeBiomes`. */
    private val NOISE_SETTINGS = setOf("overworld", "large_biomes")

    /**
     * Au chargement du mod : rend prête la copie vanilla sous [generated], reprise du cache ou fabriquée, et dit ce qu'il en est.
     * Sa clé est la version du mod et celle du jeu. Le garde-fou passe le premier ([WorldgenCopyGuard.review]) : une copie qu'il
     * tient désactivée n'est ni reprise ni fabriquée. Jamais fatal : une fabrication qui lève laisse le dossier sans copie, et la
     * résolution descendra d'un maillon, avec le message `vanilla_copy_failed`.
     *
     * Sans [reuse], la copie se refabrique à chaque lancement, et le garde-fou ne la retient pas. C'est le cas d'un environnement
     * de développement : le moteur y change sans que la version du mod bouge, et la clé ne le verrait pas.
     */
    fun prepare(generated: Path = WorldgenPacks.GENERATED_FOLDER, reuse: Boolean = !FabricLoader.getInstance().isDevelopmentEnvironment): Readiness {
        val loader = FabricLoader.getInstance()
        val copy = WorldgenCopy.VANILLA
        return try {
            val key = WorldgenCopyKey(modVersion = versionOf(loader, TravellingDimension.MOD_ID), gameVersion = versionOf(loader, MINECRAFT))

            val verdict = WorldgenCopyGuard.review(generated, copy, WorldgenCopyCache.textOf(key), fresh = !reuse)
            WorldgenReport.guardLine(copy, verdict, generated.resolve(copy.disabledFile).toString())?.let { line ->
                if (verdict == WorldgenCopyGuard.Verdict.DISABLED_NOW) TravellingDimension.LOGGER.warn("{}", line) else TravellingDimension.LOGGER.info("{}", line)
            }
            if (!verdict.declares) return Readiness.DISABLED

            val started = System.nanoTime()
            var report: WorldgenCopyReport? = null
            when (WorldgenCopyCache.prepare(generated, copy, key, reuse) { report = fabricate(it) }) {
                WorldgenCopyCache.Outcome.REUSED -> TravellingDimension.LOGGER.info("Worldgen copy '{}' reused from the cache", copy.folder)
                WorldgenCopyCache.Outcome.FABRICATED -> report?.let { made ->
                    TravellingDimension.LOGGER.info("{}", WorldgenReport.copyLine(copy, made, (System.nanoTime() - started) / 1_000_000))
                    made.refusals.forEach { (element, reason) -> TravellingDimension.LOGGER.debug("Worldgen copy '{}': pruned {}: {}", copy.folder, element, reason) }
                }
            }
            Readiness.READY
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Worldgen: the vanilla copy could not be made, and will not be loaded: {}", e.toString())
            Readiness.FAILED
        }
    }

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

    private fun versionOf(loader: FabricLoader, mod: String): String = loader.getModContainer(mod).orElseThrow().metadata.version.friendlyString

    /** Ce que [prepare] rend de la copie. */
    enum class Readiness {

        /** La copie est dans son dossier, reprise du cache ou fabriquée : elle se déclare au jeu. */
        READY,

        /** Le garde-fou la tient désactivée : elle n'est ni fabriquée ni déclarée. */
        DISABLED,

        /** Sa fabrication a levé : son dossier n'existe plus. */
        FAILED,
    }
}
