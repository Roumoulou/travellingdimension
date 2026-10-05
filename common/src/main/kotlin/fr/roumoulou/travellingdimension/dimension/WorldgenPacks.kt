// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.server.packs.repository.ServerPacksSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.function.Consumer

/**
 * Les copies préparées et leur déclaration au jeu : le chapitre 6.1 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Une copie est un datapack du dossier du jeu, `travellingdimension/generated/<copie>/`, hors du
 * jar : Fabric API ne déclare un datapack que depuis l'intérieur d'un mod. [prepare] retient au
 * chargement du mod les copies demandées qui sont prêtes, et `PackRepositoryMixin` ajoute la source
 * du mod à tout dépôt de datapacks bâti autour de la source vanilla du jeu
 * ([withPreparedCopies]). Chaque copie y est un datapack requis : le dépôt la garde sélectionnée,
 * et le joueur ne peut pas la désactiver.
 */
object WorldgenPacks {

    /** Le préfixe de la clé de traduction du titre d'une copie, que suit son dossier. */
    private const val TITLE_KEY = "${TravellingDimension.MOD_ID}.datapack."

    /** Le dossier des copies, une par sous-dossier. */
    val GENERATED_FOLDER: Path = FabricLoader.getInstance().gameDir.resolve(TravellingDimension.MOD_ID).resolve("generated")

    /** Les copies préparées au chargement du mod et leur dossier, dans l'ordre où elles se déclarent. */
    @Volatile
    var prepared: Map<WorldgenCopy, Path> = emptyMap()
        private set

    /** Les copies que le garde-fou tient désactivées, chacune avec le fichier à supprimer pour la réessayer ([WorldgenCopyGuard]). */
    @Volatile
    var disabled: Map<WorldgenCopy, Path> = emptyMap()
        private set

    /** Requis, donc toujours sélectionné, au-dessus des autres datapacks. */
    private val ALWAYS_ACTIVE = PackSelectionConfig(true, Pack.Position.TOP, false)

    /** La source du mod : elle offre les copies de [prepared] à chaque rechargement d'un dépôt. */
    private val source = RepositorySource { consumer -> loadPacks(prepared, consumer) }

    /**
     * Retient, parmi [wanted], les copies dont le dossier porte un `pack.mcmeta` sous
     * [generatedFolder]. La copie vanilla y a été fabriquée ou reprise juste avant ([VanillaCopy]) ;
     * une copie dont la fabrication a échoué n'a pas de dossier, et n'est pas retenue. Une copie
     * de [disabledCopies] n'est jamais préparée, son dossier fût-il resté en place : le garde-fou
     * la retient, et la résolution le dira.
     */
    fun prepare(wanted: Set<WorldgenCopy>, generatedFolder: Path = GENERATED_FOLDER, disabledCopies: Set<WorldgenCopy> = emptySet()) {
        prepared = (wanted - disabledCopies).associateWith { generatedFolder.resolve(it.folder) }.filterValues { Files.isRegularFile(it.resolve("pack.mcmeta")) }
        disabled = disabledCopies.associateWith { generatedFolder.resolve(it.disabledFile) }
    }

    /**
     * Les sources d'un dépôt, avec celle du mod quand une copie est préparée et que [sources]
     * porte la source vanilla du jeu : un dépôt de resourcepacks ne la reçoit pas.
     */
    @JvmStatic
    fun withPreparedCopies(sources: Array<Any>): Array<Any> =
        if (prepared.isEmpty() || sources.none { it is ServerPacksSource }) sources else sources + source

    /** Les datapacks du mod que [repository] a sélectionnés, par identifiant. */
    fun selectedIn(repository: PackRepository): List<String> {
        val ours = WorldgenCopy.entries.map { it.packId }
        return repository.selectedIds.filter { it in ours }
    }

    private fun loadPacks(copies: Map<WorldgenCopy, Path>, consumer: Consumer<Pack>) {
        copies.forEach { (copy, directory) ->
            val location = PackLocationInfo(copy.packId, Component.translatable(TITLE_KEY + copy.folder), PackSource.BUILT_IN, Optional.empty())
            // Un pack.mcmeta illisible : le jeu le dit dans son log, et la copie n'est pas offerte.
            Pack.readMetaAndCreate(location, PathPackResources.PathResourcesSupplier(directory), PackType.SERVER_DATA, ALWAYS_ACTIVE)?.let { consumer.accept(it) }
        }
    }
}
