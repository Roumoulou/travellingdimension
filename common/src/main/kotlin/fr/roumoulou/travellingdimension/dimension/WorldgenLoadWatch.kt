// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import net.minecraft.core.registries.Registries
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.resources.ResourceManager
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Le témoin de chargement autour d'un chargement des registres : ce que `RegistryDataLoaderMixin` appelle, pour le chapitre 6.2 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Tout chargement des registres depuis les datapacks passe par `RegistryDataLoader.load` : le serveur dédié, le monde existant,
 * le serveur GameTest, et l'écran de création d'un monde, qui charge sans serveur. [watch] y pose le témoin de chaque copie
 * préparée que le chargement comprend, et le lève quand le chargement réussit. Quand il échoue, le témoin reste, sauf si les
 * erreurs que le jeu rapporte ([errorsReported]) innocentent la copie ([WorldgenCopyGuard.clears]).
 *
 * Le témoin ne vit que le temps du chargement : un écran de création quitté sans créer de monde ne laisse rien. Rien ici ne
 * lève : un témoin que le mod ne peut ni poser ni lever ne fait pas échouer le chargement du jeu.
 */
object WorldgenLoadWatch {

    /** Les copies dont un chargement des registres a réussi dans ce processus : leur témoin a été posé, puis levé. */
    val loaded: Set<WorldgenCopy>
        get() = succeeded.toSet()

    private val succeeded: MutableSet<WorldgenCopy> = ConcurrentHashMap.newKeySet()

    /** Les erreurs de chaque chargement en échec, par l'exception que le jeu en fait : elle seule relie un échec à ses erreurs. */
    private val reported: MutableMap<Throwable, List<LoadingError>> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Garde le chargement [loading] des registres [registries] depuis [resources] : pose le témoin des copies préparées qu'il
     * comprend, et rend un chargement qui le lève à sa fin. Un chargement sans copie du mod, ou d'autres registres que ceux du
     * monde, est rendu tel quel.
     */
    @JvmStatic
    fun <T> watch(loading: CompletableFuture<T>, resources: ResourceManager, registries: List<RegistryDataLoader.RegistryData<*>>): CompletableFuture<T> {
        val armed = try {
            copiesIn(resources, registries).filter { (copy, generated) -> arm(copy, generated) }
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Worldgen: the copies of a loading of the registries could not be read: {}", e.toString())
            emptyMap()
        }
        if (armed.isEmpty()) return loading
        return loading.whenComplete { _, failure -> armed.forEach { (copy, generated) -> conclude(copy, generated, failure) } }
    }

    /** Le jeu rapporte les erreurs d'un chargement des registres en échec, sous leurs clés [errors], et [failure] est l'exception qu'il en fait. */
    @JvmStatic
    fun errorsReported(failure: Throwable, errors: Collection<ResourceKey<*>>) {
        reported[failure] = errors.map { key ->
            // Une erreur rangée sous la clé d'un registre est celle du registre entier : elle ne nomme aucun élément.
            if (key.registry() == Registries.ROOT_REGISTRY_NAME) LoadingError(key.identifier().toString(), element = null)
            else LoadingError(key.registry().toString(), key.identifier().toString())
        }
    }

    /**
     * Les copies préparées que le chargement comprend, chacune avec son dossier `generated/` : celles dont le datapack est dans
     * [resources]. Aucune quand [registries] ne sont pas les registres du monde, ceux qui portent les biomes : les dimensions et
     * les registres rechargeables se chargent à part, sans rien d'une copie.
     */
    private fun copiesIn(resources: ResourceManager, registries: List<RegistryDataLoader.RegistryData<*>>): Map<WorldgenCopy, Path> {
        val prepared = WorldgenPacks.prepared
        if (prepared.isEmpty() || registries.none { it.key() == Registries.BIOME }) return emptyMap()

        val packs = resources.listPacks().map { it.packId() }.toList()
        return prepared.filterKeys { it.packId in packs }.mapValues { (_, folder) -> folder.parent }
    }

    /** Pose le témoin de [copy], et dit s'il est posé : une copie dont le témoin ne s'écrit pas n'est pas gardée. */
    private fun arm(copy: WorldgenCopy, generated: Path): Boolean =
        try {
            WorldgenCopyGuard.arm(generated, copy)
            true
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Worldgen: the loading witness of the {} copy could not be set: {}", copy.title, e.toString())
            false
        }

    /** La fin du chargement pour [copy] : son témoin se lève s'il a réussi ou si ses erreurs l'innocentent, et reste sinon. */
    private fun conclude(copy: WorldgenCopy, generated: Path, failure: Throwable?) {
        try {
            when {
                failure == null -> {
                    WorldgenCopyGuard.disarm(generated, copy)
                    succeeded += copy
                    TravellingDimension.LOGGER.debug("Worldgen: the registries loaded with the {} copy", copy.title)
                }

                WorldgenCopyGuard.clears(copy, errorsOf(failure)) -> {
                    WorldgenCopyGuard.disarm(generated, copy)
                    TravellingDimension.LOGGER.info("{}", WorldgenReport.failedLoadingLine(copy, cleared = true))
                }

                else -> TravellingDimension.LOGGER.warn("{}", WorldgenReport.failedLoadingLine(copy, cleared = false))
            }
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Worldgen: the loading witness of the {} copy could not be lifted: {}", copy.title, e.toString())
        }
    }

    /** Les erreurs que le jeu a rapportées pour [failure], à travers les exceptions qui l'enveloppent : aucune s'il n'en a pas rapporté. */
    private fun errorsOf(failure: Throwable): List<LoadingError> =
        generateSequence(failure) { it.cause }.take(8).firstNotNullOfOrNull { reported[it] } ?: emptyList()
}
