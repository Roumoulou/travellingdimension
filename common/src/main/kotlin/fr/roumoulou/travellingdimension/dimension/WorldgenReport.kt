// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.WorldgenMode

/**
 * Ce que le log dit du terrain de VOYAGE : la ligne « Travel dimension active », et la ligne de
 * chaque message du repli.
 *
 * En anglais, dans le code : le serveur intégré d'un client traduit dans la langue de ce client,
 * et un log se lit au milieu de traces anglaises. Aucun type du jeu n'y entre, pour que la ligne
 * s'éprouve à l'étage 0.
 */
object WorldgenReport {

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
