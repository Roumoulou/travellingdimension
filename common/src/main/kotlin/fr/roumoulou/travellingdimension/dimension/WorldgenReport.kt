// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.WorldgenMode
import java.util.SortedMap

/**
 * Ce que le log dit du terrain de VOYAGE : les lignes du dossier `worldgen/`, la ligne d'une copie
 * fabriquée, les lignes du garde-fou, la ligne des datapacks du mod, la ligne « Travel dimension
 * active », et la ligne de chaque message du repli.
 *
 * En anglais, dans le code : le serveur intégré d'un client traduit dans la langue de ce client,
 * et un log se lit au milieu de traces anglaises. Aucun type du jeu n'y entre, pour que la ligne
 * s'éprouve à l'étage 0.
 */
object WorldgenReport {

    /** La ligne d'un fichier du dossier `worldgen/` qui n'est pas un jar WWOO, pour [reason] : le mod l'ignore. */
    fun ignoredFileLine(file: String, reason: String): String = "Worldgen folder: '$file' is not a WWOO jar ($reason): ignored"

    /** La ligne d'un jar WWOO du dossier `worldgen/` que le mod refuse. */
    fun refusedJarLine(refusal: WilliamJarRefusal): String = when (refusal) {
        is WilliamJarRefusal.WrongVersion -> "Worldgen folder: the WWOO jar '${refusal.file}' is made for Minecraft ${refusal.declared}, not ${refusal.game}: refused"
        is WilliamJarRefusal.Unreadable -> "Worldgen folder: the WWOO jar '${refusal.file}' cannot be read (${refusal.reason}): refused"
    }

    /** La ligne du jar WWOO que le mod prend, de version [version] : la plus haute des [accepted] jars acceptés du dossier. */
    fun takenJarLine(file: String, version: String, accepted: Int): String {
        val among = if (accepted > 1) ", the highest version of $accepted accepted jars" else ""
        return "Worldgen folder: the WWOO jar '$file' (WWOO $version) is taken for the ${WorldgenCopy.WILLIAM.title} copy$among"
    }

    /**
     * La ligne d'une copie fabriquée en [millis] millisecondes, écrite au chargement du mod : ce que
     * [report] compte d'écrit et d'élagué, puis registre par registre, dans l'ordre de leurs
     * dossiers, les fichiers de tag de biomes, et ce que la source porte en propre quand elle en
     * porte : ses tags et ses gabarits NBT.
     */
    fun copyLine(copy: WorldgenCopy, report: WorldgenCopyReport, millis: Long): String {
        val byRegistry = report.registries.entries.joinToString(", ") { (folder, count) -> "$folder ${count.written}/${count.pruned}" }
        val own = if (report.tags + report.templates > 0) ", ${report.tags} tag file(s) and ${report.templates} structure template(s) of the source" else ""
        return "Worldgen copy '${copy.folder}' made in $millis ms: ${report.written} file(s) written, ${report.pruned} pruned " +
            "(written/pruned by registry: $byRegistry), ${report.biomeTags} biome tag file(s)$own"
    }

    /** La ligne d'une copie dont la fabrication a levé pour [reason] : son dossier n'existe plus, et elle ne se déclare pas. */
    fun failedCopyLine(copy: WorldgenCopy, reason: String): String = "Worldgen: the ${copy.title} copy could not be made, and will not be loaded: $reason"

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

    /**
     * La ligne du garde-fou pour [copy], écrite au chargement du mod, ou `null` quand [verdict] n'a rien à dire : la copie vient
     * d'être désactivée (au niveau `WARN`), elle le reste, ou sa clé a changé et elle est réessayée. [file] est le fichier à
     * supprimer pour la réessayer.
     */
    fun guardLine(copy: WorldgenCopy, verdict: WorldgenCopyGuard.Verdict, file: String): String? = when (verdict) {
        WorldgenCopyGuard.Verdict.ENABLED -> null
        WorldgenCopyGuard.Verdict.RETRIED -> "Worldgen: the ${copy.title} copy was disabled under another key: it is made again and tried again"
        WorldgenCopyGuard.Verdict.DISABLED_NOW -> "Worldgen: a loading of the registries with the ${copy.title} copy did not succeed at the previous launch: the copy is disabled. Delete '$file' and restart to try again"
        WorldgenCopyGuard.Verdict.DISABLED -> "Worldgen: the ${copy.title} copy stays disabled. Delete '$file' and restart to try again"
    }

    /**
     * La ligne d'un chargement des registres en échec, pour une copie qu'il comprenait : ses erreurs l'innocentent
     * ([cleared]), ou son témoin reste et elle sera désactivée au lancement suivant.
     */
    fun failedLoadingLine(copy: WorldgenCopy, cleared: Boolean): String =
        if (cleared) "Worldgen: the loading of the registries failed on elements that are not of the ${copy.title} copy: the copy stays enabled"
        else "Worldgen: a loading of the registries with the ${copy.title} copy failed: the copy is disabled at the next launch, unless a loading succeeds before"

    /** La ligne de log de [notice], écrite au niveau `WARN` quand `logFallback` est actif. */
    fun warning(notice: WorldgenNotice): String = when (notice) {
        is WorldgenNotice.WilliamNoSource -> "Worldgen: worldgen=william but WWOO is not installed and its jar is not in '${notice.worldgenFolder}': falling back to the vanilla copy"
        is WorldgenNotice.WilliamWrongVersion -> "Worldgen: worldgen=william but the WWOO jar '${notice.file}' is made for Minecraft ${notice.declared}, not ${notice.game}: falling back to the vanilla copy"
        is WorldgenNotice.WilliamUnreadable -> "Worldgen: worldgen=william but the WWOO jar '${notice.file}' could not be read: falling back to the vanilla copy"
        is WorldgenNotice.CopyDisabled -> "Worldgen: the ${notice.copy} copy made a loading of the registries fail and is disabled: delete '${notice.file}' and restart to try again"
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
 * qu'il référence. [tags] et [templates] comptent ce que la source porte en propre et que la copie reprend sous son espace de
 * noms : ses tags référencés et ses gabarits NBT, aucun pour la copie vanilla.
 */
data class WorldgenCopyReport(val registries: SortedMap<String, Count>, val biomeTags: Int, val refusals: Map<String, String>, val tags: Int = 0, val templates: Int = 0) {

    val written: Int
        get() = registries.values.sumOf { it.written }

    val pruned: Int
        get() = registries.values.sumOf { it.pruned }

    /** Les fichiers d'un registre : ceux que la copie porte, et ceux que le moteur a élagués. */
    data class Count(val written: Int, val pruned: Int)
}
