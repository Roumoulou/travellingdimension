// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.WorldgenMode
import java.util.SortedMap

/**
 * Ce que le log dit du terrain de VOYAGE : la ligne d'une copie fabriquée, la ligne des datapacks
 * du mod, la ligne « Travel dimension active », et la ligne de chaque message du repli.
 *
 * En anglais, dans le code : le serveur intégré d'un client traduit dans la langue de ce client,
 * et un log se lit au milieu de traces anglaises. Aucun type du jeu n'y entre, pour que la ligne
 * s'éprouve à l'étage 0.
 */
object WorldgenReport {

    /**
     * La ligne d'une copie fabriquée en [millis] millisecondes, écrite au chargement du mod : ce que
     * [report] compte d'écrit et d'élagué, puis registre par registre, dans l'ordre de leurs
     * dossiers, et les fichiers de tag de biomes.
     */
    fun copyLine(copy: WorldgenCopy, report: WorldgenCopyReport, millis: Long): String {
        val byRegistry = report.registries.entries.joinToString(", ") { (folder, count) -> "$folder ${count.written}/${count.pruned}" }
        return "Worldgen copy '${copy.folder}' made in $millis ms: ${report.written} file(s) written, ${report.pruned} pruned " +
            "(written/pruned by registry: $byRegistry), ${report.biomeTags} biome tag file(s)"
    }

    /**
     * La ligne des datapacks du mod, écrite quand une copie est préparée : ceux que le serveur a
     * sélectionnés ([selected]), puis ceux de [prepared] qui manquent à l'appel. Une copie
     * préparée et absente dit qu'un dépôt de datapacks n'a pas reçu la source du mod.
     */
    fun datapacksLine(prepared: List<String>, selected: List<String>): String {
        val missing = prepared - selected.toSet()
        val loaded = if (selected.isEmpty()) "none" else selected.joinToString(", ")
        val absent = if (missing.isEmpty()) "" else " (prepared but not selected: ${missing.joinToString(", ")})"
        return "Worldgen datapacks selected by the server: $loaded$absent"
    }

    /**
     * La ligne « Travel dimension active » : [mode], [terrain] (le réglage de bruit du générateur,
     * ou sa classe), les biomes de VOYAGE comptés par espace de noms, dans l'ordre de
     * [biomeCounts], puis Terralith et WWOO quand [detection] les porte.
     */
    fun activeLine(mode: WorldgenMode, terrain: String, biomeCounts: Map<String, Int>, detection: WorldgenDetection): String {
        val counts = biomeCounts.entries.joinToString(", ") { (group, count) -> "$group=$count" }
        val detected = listOfNotNull("Terralith".takeIf { detection.terralithLoaded }, "WWOO".takeIf { detection.wwooInstalled })
        val naming = if (detected.isEmpty()) "" else ", detected: ${detected.joinToString(", ")}"
        return "Travel dimension active: mode ${mode.name.lowercase()}, $terrain, ${biomeCounts.values.sum()} biome(s) ($counts)$naming"
    }

    /** La ligne de log de [notice], écrite au niveau `WARN` quand `logFallback` est actif. */
    fun warning(notice: WorldgenNotice): String = when (notice) {
        is WorldgenNotice.WilliamNoSource -> "Worldgen: worldgen=william but WWOO is not installed and its jar is not in '${notice.worldgenFolder}': falling back to the vanilla copy"
        WorldgenNotice.VanillaCopyFailed -> "Worldgen: the vanilla copy is not loaded: the travel dimension follows the Overworld, with large biomes"
        WorldgenNotice.TectonicMissing -> "Worldgen: worldgen=tectonic but the Tectonic mod is not loaded: falling back to the vanilla copy"
        is WorldgenNotice.CustomUnknown -> "Worldgen: worldgen=custom but '${notice.identifier}' is not in the registries: falling back to the vanilla copy"
        is WorldgenNotice.GeneratorFailed -> "Worldgen: the terrain could not be built (${notice.reason}): falling back"
        WorldgenNotice.DimensionMissing -> "The travel dimension '${TravellingDimension.MOD_ID}:travel' does not exist on this server"
    }
}

/**
 * Ce qu'une fabrication a écrit et élagué : le compte du chapitre 5.4 de la spécification.
 *
 * [registries] compte par dossier de registre (`worldgen/biome`), [biomeTags] les fichiers de tag de biomes écrits. [refusals]
 * donne, pour chaque élément élagué (`worldgen/feature minecraft:oak`), la raison : le refus du codec du jeu, ou l'élément élagué
 * qu'il référence.
 */
data class WorldgenCopyReport(val registries: SortedMap<String, Count>, val biomeTags: Int, val refusals: Map<String, String>) {

    val written: Int
        get() = registries.values.sumOf { it.written }

    val pruned: Int
        get() = registries.values.sumOf { it.pruned }

    /** Les fichiers d'un registre : ceux que la copie porte, et ceux que le moteur a élagués. */
    data class Count(val written: Int, val pruned: Int)
}
