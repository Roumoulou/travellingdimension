// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Le dossier où le joueur dépose le jar de WWOO, `travellingdimension/worldgen/` dans le dossier du jeu : le chapitre 4.1 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Le mod le crée avec sa notice, quel que soit le mode. Il n'y écrit que cette notice, et n'y supprime jamais rien : ce qu'il
 * fabrique vit à côté, dans `generated/`. Ce que le joueur y a déposé se lit ensuite fichier par fichier ([WilliamJars]).
 *
 * Aucun type du jeu ni du chargeur n'y entre : le dossier se donne, et s'éprouve à l'étage 0.
 */
object WorldgenFolder {

    /** Le nom de la notice : le seul fichier du dossier qui n'est pas un dépôt du joueur. */
    const val NOTICE_FILE = "README.txt"

    /** Le texte de la notice, en anglais comme les logs : quel fichier déposer, où le trouver, et ce que le mod en fait. */
    val NOTICE: String = """
        Travelling Dimension: the worldgen folder
        =========================================

        Drop here the official jar of William Wythers' Overhauled Overworld (WWOO) for Fabric, as downloaded,
        for the version of Minecraft you play.

        Where to find it: https://modrinth.com/mod/wwoo

        With "worldgen": "william" in config/travellingdimension/config.json, the mod makes from this jar a copy of
        the WWOO biomes that only the travel dimension uses: the Overworld keeps its own terrain. The jar is read,
        never changed, and it does not go in the mods folder.

        - One jar is enough. Among several, the mod takes the highest version of WWOO made for the running version
          of Minecraft, and the game log names it.
        - A jar made for another version of Minecraft is refused, and a file that is not a WWOO jar is ignored:
          the game log says so.
        - When WWOO is installed as a mod, the travel dimension uses it, and this folder is not read.
        - The mod writes nothing here but this notice, and deletes nothing. What it makes lives next door, in
          generated, and is made again when the jar, the game or the mod changes.
    """.trimIndent() + "\n"

    /**
     * Crée [folder] et sa notice. La notice se réécrit quand elle manque ou qu'elle n'est plus celle de cette version du mod, et
     * rien d'autre n'est touché. Rend `true` quand elle vient d'être écrite.
     */
    fun ensure(folder: Path): Boolean {
        Files.createDirectories(folder)
        val notice = folder.resolve(NOTICE_FILE)
        if (Files.isRegularFile(notice) && Files.readString(notice) == NOTICE) return false
        Files.writeString(notice, NOTICE)
        return true
    }

    /** Ce que le joueur a déposé dans [folder] : ses fichiers, hors la notice et hors ses sous-dossiers, dans l'ordre de leurs noms. */
    fun deposits(folder: Path): List<Path> {
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { files -> files.filter { Files.isRegularFile(it) && it.name != NOTICE_FILE }.sorted(compareBy { it.name }).toList() }
    }
}
