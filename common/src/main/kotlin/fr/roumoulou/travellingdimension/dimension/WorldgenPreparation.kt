// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.WorldgenMode

/**
 * Ce que le mod prépare à son chargement, avant que les datapacks soient lus : le tableau du
 * chapitre 3.1 de `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Rien ne s'y décide : une copie préparée pourrait servir, et c'est la résolution, à la création
 * des mondes, qui dit si elle sert ([WorldgenResolver]). Aucun type du jeu n'y entre, pour que
 * chaque ligne du tableau soit un test de l'étage 0.
 */
object WorldgenPreparation {

    /**
     * Les copies que [mode] demande de préparer, dans l'ordre où elles se déclarent. La copie
     * William ne se prépare que si le mod `wwoo` n'est pas chargé ([wwooModLoaded]) et qu'un jar
     * du dossier est accepté ([williamJarAccepted]) : WWOO installé l'emporte sur le jar déposé.
     */
    fun copiesFor(mode: WorldgenMode, wwooModLoaded: Boolean, williamJarAccepted: Boolean): Set<WorldgenCopy> = when (mode) {
        WorldgenMode.TERRALITH -> emptySet()
        WorldgenMode.VANILLA, WorldgenMode.TECTONIC, WorldgenMode.CUSTOM -> setOf(WorldgenCopy.VANILLA)
        // La copie William référence la copie vanilla : elle ne se prépare jamais sans elle.
        WorldgenMode.WILLIAM -> if (!wwooModLoaded && williamJarAccepted) setOf(WorldgenCopy.VANILLA, WorldgenCopy.WILLIAM) else setOf(WorldgenCopy.VANILLA)
    }
}

/**
 * Une copie que le mod charge depuis le dossier du jeu : [folder] est son sous-dossier de `travellingdimension/generated/`, et
 * [title] le nom que les messages lui donnent, au lexique de `en_us.json`.
 */
enum class WorldgenCopy(val folder: String, val title: String) {

    /** La copie vanilla, sous `travellingdimension:vanilla/`. */
    VANILLA("vanilla", "vanilla"),

    /** La copie William, sous `travellingdimension:wwoo/`. */
    WILLIAM("wwoo", "William Wythers");

    private companion object {
        const val MINECRAFT = "minecraft"
    }

    /** L'identifiant du datapack de la copie dans le dépôt de datapacks du jeu, `travellingdimension/vanilla` pour la copie vanilla. */
    val packId: String = "${TravellingDimension.MOD_ID}/$folder"

    /** Le préfixe de tout identifiant que la copie écrit, `travellingdimension:vanilla/` pour la copie vanilla. */
    val elementPrefix: String = "${TravellingDimension.MOD_ID}:$folder/"

    /** Sous `generated/`, à côté du dossier de la copie : le fichier qui dit ce qui l'a fabriquée ([WorldgenCopyCache]). */
    val keyFile: String = "$folder.key.json"

    /** Sous `generated/` : le témoin de chargement de la copie ([WorldgenCopyGuard]). */
    val witnessFile: String = "$folder.loading"

    /** Sous `generated/` : le fichier qui tient la copie désactivée, et que le joueur supprime pour réessayer ([WorldgenCopyGuard]). */
    val disabledFile: String = "$folder.disabled"

    /**
     * L'identifiant que la copie écrit pour [identifier], le tableau du chapitre 5.1 de la spécification :
     * `minecraft:<chemin>` devient `travellingdimension:<copie>/<chemin>`, et `<autre espace>:<chemin>` devient
     * `travellingdimension:<copie>/<autre espace>/<chemin>`. Un identifiant sans espace de noms est `minecraft:`, comme dans le jeu.
     */
    fun renamed(identifier: String): String {
        val namespace = identifier.substringBefore(':', missingDelimiterValue = MINECRAFT)
        return "${TravellingDimension.MOD_ID}:${renamedPath(namespace, identifier.substringAfter(':'))}"
    }

    /** Le chemin, sous `travellingdimension:`, de l'identifiant que la copie écrit pour [namespace]`:`[path]. */
    fun renamedPath(namespace: String, path: String): String = if (namespace == MINECRAFT) "$folder/$path" else "$folder/$namespace/$path"
}
