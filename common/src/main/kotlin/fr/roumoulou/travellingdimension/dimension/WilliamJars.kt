// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.VersionParsingException
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.name

/**
 * Les jars WWOO du dossier `worldgen/` : la reconnaissance, le contrôle de version et le choix, chapitres 4.3 et 4.4 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Un jar WWOO se reconnaît à son contenu, pas à son nom : un `fabric.mod.json` à sa racine dont l'`id` vaut `wwoo`, et le
 * datapack `resources/wwoo_main`, que WWOO charge de là. Un jar reconnu n'est accepté que si son `depends.minecraft` accepte la
 * version du jeu : les registres changent de dossier d'une version à l'autre, et un datapack hors version fait échouer leur
 * chargement. Le jar n'est que lu.
 */
object WilliamJars {

    /** L'identifiant du mod WWOO. */
    const val MOD_ID = "wwoo"

    /** Le datapack de WWOO dans son jar. */
    const val DATAPACK = "resources/wwoo_main"

    private const val METADATA = "fabric.mod.json"

    /** Ce que valent les fichiers [deposits] du dossier, lus un par un, pour le jeu de version [game]. */
    fun read(deposits: List<Path>, game: Version): Reading = Reading(deposits.map { inspect(it, game) }, game.friendlyString)

    /** Ce que vaut [file] pour le jeu de version [game]. Jamais fatal : un fichier qui ne se lit pas n'est pas un jar WWOO. */
    fun inspect(file: Path, game: Version): WilliamJar {
        val metadata = try {
            ZipFile(file.toFile()).use { archive ->
                val entry = archive.getEntry(METADATA) ?: return WilliamJar.Foreign(file, "no $METADATA at its root")
                val parsed = try {
                    Json.parseToJsonElement(archive.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)) as? JsonObject
                } catch (e: SerializationException) {
                    null
                } ?: return WilliamJar.Foreign(file, "its $METADATA is not a JSON object")
                val id = text(parsed["id"])
                if (id != MOD_ID) return WilliamJar.Foreign(file, "it is the mod '${id ?: "?"}'")
                if (archive.entries().asSequence().none { it.name.startsWith("$DATAPACK/data/") }) return WilliamJar.Foreign(file, "no $DATAPACK/data in it")
                parsed
            }
        } catch (e: IOException) {
            return WilliamJar.Foreign(file, "not a readable archive")
        }

        val versionText = text(metadata["version"]) ?: return WilliamJar.Unreadable(file, "no version in its $METADATA")
        val version = try {
            Version.parse(versionText)
        } catch (e: VersionParsingException) {
            return WilliamJar.Unreadable(file, "its version '$versionText' is not readable")
        }

        val predicates = minecraftPredicates(metadata)
        if (predicates.isEmpty()) return WilliamJar.Unreadable(file, "no depends.minecraft in its $METADATA")
        val declared = predicates.joinToString(" || ")
        val accepts = try {
            predicates.any { VersionPredicate.parse(it).test(game) }
        } catch (e: VersionParsingException) {
            return WilliamJar.Unreadable(file, "its depends.minecraft '$declared' is not readable")
        }
        return if (accepts) WilliamJar.Accepted(file, version) else WilliamJar.WrongVersion(file, version, declared)
    }

    /** Les prédicats de `depends.minecraft` : un seul, ou une liste dont un suffit. */
    private fun minecraftPredicates(metadata: JsonObject): List<String> = when (val declared = (metadata["depends"] as? JsonObject)?.get("minecraft")) {
        is JsonArray -> declared.mapNotNull(::text)
        else -> listOfNotNull(text(declared))
    }

    private fun text(json: JsonElement?): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Ce que le dossier donne à la copie William : chaque fichier lu, dans l'ordre de leurs noms, pour le jeu de version [game]. */
    class Reading(val jars: List<WilliamJar>, private val game: String) {

        /** Les jars WWOO acceptés. */
        val accepted: List<WilliamJar.Accepted> = jars.filterIsInstance<WilliamJar.Accepted>()

        /** Le jar que le mod prend : la plus haute version de WWOO parmi les acceptés, et le premier par son nom à version égale. */
        val taken: WilliamJar.Accepted? = accepted.reduceOrNull { best, next -> if (next.version > best.version) next else best }

        /** Les jars WWOO refusés, chacun avec sa raison : le log en donne une ligne par jar. */
        val refused: List<WilliamJarRefusal> = jars.mapNotNull(::refusalOf)

        /**
         * Ce que la résolution dit quand aucun jar n'est pris : au plus un jar d'une autre version du jeu, celui de la plus haute
         * version de WWOO, puis un jar illisible, le premier. Le log garde le détail, fichier par fichier.
         */
        val reported: List<WilliamJarRefusal> =
            if (taken != null) emptyList()
            else listOfNotNull(
                jars.filterIsInstance<WilliamJar.WrongVersion>().reduceOrNull { best, next -> if (next.version > best.version) next else best },
                jars.filterIsInstance<WilliamJar.Unreadable>().firstOrNull(),
            ).mapNotNull(::refusalOf)

        private fun refusalOf(jar: WilliamJar): WilliamJarRefusal? = when (jar) {
            is WilliamJar.WrongVersion -> WilliamJarRefusal.WrongVersion(jar.file.name, jar.declared, game)
            is WilliamJar.Unreadable -> WilliamJarRefusal.Unreadable(jar.file.name, jar.reason)
            is WilliamJar.Accepted, is WilliamJar.Foreign -> null
        }
    }
}

/** Un fichier du dossier `worldgen/`, tel que le mod le lit. */
sealed interface WilliamJar {

    val file: Path

    /** Un jar WWOO de version [version], fait pour la version du jeu : il peut donner la copie William. */
    data class Accepted(override val file: Path, val version: Version) : WilliamJar

    /** Un jar WWOO de version [version] dont le `depends.minecraft`, [declared], n'accepte pas la version du jeu. */
    data class WrongVersion(override val file: Path, val version: Version, val declared: String) : WilliamJar

    /** Un jar WWOO dont le `fabric.mod.json` ne dit pas ce qu'il faut pour le contrôler : [reason]. */
    data class Unreadable(override val file: Path, val reason: String) : WilliamJar

    /** Un fichier qui n'est pas un jar WWOO, pour [reason] : il est ignoré. */
    data class Foreign(override val file: Path, val reason: String) : WilliamJar
}
