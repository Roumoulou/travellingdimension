// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/**
 * La préparation des copies au chargement du mod, la même pour la copie vanilla et pour la copie William : le garde-fou
 * ([WorldgenCopyGuard]), puis le cache ([WorldgenCopyCache]), puis le compte rendu au log ([WorldgenReport]).
 *
 * Chaque copie y arrive par sa source : ce qui la fabrique, et la clé qui dit de quoi ([VanillaCopy.source],
 * [WilliamCopy.source]).
 */
object WorldgenCopyMaker {

    private const val MINECRAFT = "minecraft"

    /**
     * Au chargement du mod : rend prêtes sous [generated] les copies de [sources], dans leur ordre, chacune reprise du cache ou
     * fabriquée, et dit ce qu'il en est de chacune. Le garde-fou passe le premier, sur toutes les copies : une copie qu'il tient
     * désactivée n'est ni reprise ni fabriquée. Une copie ne se prépare que si celles qui la précèdent sont prêtes, parce qu'elle
     * les référence. Jamais fatal : une fabrication qui lève laisse le dossier sans cette copie.
     *
     * Sans [reuse], les copies se refabriquent à chaque lancement, et le garde-fou n'en retient aucune. C'est le cas d'un
     * environnement de développement : le moteur y change sans que la version du mod bouge, et la clé ne le verrait pas.
     */
    fun prepare(generated: Path, sources: List<Source>, reuse: Boolean = !FabricLoader.getInstance().isDevelopmentEnvironment): Map<WorldgenCopy, Readiness> {
        val verdicts = try {
            WorldgenCopyGuard.review(generated, sources.associate { it.copy to WorldgenCopyCache.textOf(it.key) }, fresh = !reuse)
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Worldgen: the copies could not be reviewed, and none will be loaded: {}", e.toString())
            return sources.associate { it.copy to Readiness.FAILED }
        }
        verdicts.forEach { (copy, verdict) ->
            // Une copie qui ne se prépare pas à ce lancement n'a de ligne que le jour où elle est désactivée.
            if (verdict != WorldgenCopyGuard.Verdict.DISABLED_NOW && sources.none { it.copy == copy }) return@forEach
            WorldgenReport.guardLine(copy, verdict, generated.resolve(copy.disabledFile).toString())?.let { line ->
                if (verdict == WorldgenCopyGuard.Verdict.DISABLED_NOW) TravellingDimension.LOGGER.warn("{}", line) else TravellingDimension.LOGGER.info("{}", line)
            }
        }

        val made = LinkedHashMap<WorldgenCopy, Readiness>()
        sources.forEach { source ->
            made[source.copy] = when {
                !verdicts.getValue(source.copy).declares -> Readiness.DISABLED
                made.values.any { it != Readiness.READY } -> Readiness.UNMADE
                else -> make(generated, source, reuse)
            }
        }
        return made
    }

    /** La clé d'une copie que ce mod fabrique dans ce jeu, à partir d'un jar d'empreinte [sourceSha256] quand elle a une source déposée. */
    fun key(sourceSha256: String? = null): WorldgenCopyKey {
        val loader = FabricLoader.getInstance()
        return WorldgenCopyKey(modVersion = versionOf(loader, TravellingDimension.MOD_ID), gameVersion = versionOf(loader, MINECRAFT), sourceSha256 = sourceSha256)
    }

    private fun make(generated: Path, source: Source, reuse: Boolean): Readiness {
        val copy = source.copy
        return try {
            val started = System.nanoTime()
            var report: WorldgenCopyReport? = null
            when (WorldgenCopyCache.prepare(generated, copy, source.key, reuse) { report = source.fabricate(it) }) {
                WorldgenCopyCache.Outcome.REUSED -> TravellingDimension.LOGGER.info("Worldgen copy '{}' reused from the cache", copy.folder)
                WorldgenCopyCache.Outcome.FABRICATED -> report?.let { made ->
                    TravellingDimension.LOGGER.info("{}", WorldgenReport.copyLine(copy, made, (System.nanoTime() - started) / 1_000_000))
                    made.refusals.forEach { (element, reason) -> TravellingDimension.LOGGER.debug("Worldgen copy '{}': pruned {}: {}", copy.folder, element, reason) }
                }
            }
            Readiness.READY
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("{}", WorldgenReport.failedCopyLine(copy, e.toString()))
            Readiness.FAILED
        }
    }

    private fun versionOf(loader: FabricLoader, mod: String): String = loader.getModContainer(mod).orElseThrow().metadata.version.friendlyString

    /** Ce qui fabrique la copie [copy] dans un dossier vide, et la clé [key] qui dit de quoi : la copie se refabrique quand elle change. */
    class Source(val copy: WorldgenCopy, val key: WorldgenCopyKey, val fabricate: (target: Path) -> WorldgenCopyReport)

    /** Ce que [prepare] rend d'une copie. */
    enum class Readiness {

        /** La copie est dans son dossier, reprise du cache ou fabriquée : elle se déclare au jeu. */
        READY,

        /** Le garde-fou la tient désactivée : elle n'est ni fabriquée ni déclarée. */
        DISABLED,

        /** Sa fabrication a levé : son dossier n'existe plus. */
        FAILED,

        /** Une copie qui la précède n'est pas prête : elle n'a pas été préparée. */
        UNMADE,
    }
}
