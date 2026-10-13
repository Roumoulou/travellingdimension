// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import fr.roumoulou.travellingdimension.TravellingDimension
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * La taille intermédiaire des biomes, `medium_biomes` : le chapitre 5.7 de `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Elle se dérive de la copie vanilla seule, par les fichiers qu'elle vient d'écrire, dans la même fabrication. Le jeu fait ses
 * `large_biomes` en refaisant quelques fonctions de densité d'`overworld/` sous `overworld_large_biomes/`, où ce qui lit un bruit
 * lit son jumeau `_large`, deux octaves plus bas : des biomes quatre fois plus larges. `medium_biomes` refait les mêmes fonctions
 * sous `overworld_medium_biomes/`, depuis celles d'`overworld/`, et tout ce qui lit un bruit à jumeau `_large` le lit à un
 * `xz_scale` divisé par deux : des biomes deux fois plus larges, sans écrire un bruit de plus. Le réglage de bruit `medium_biomes`
 * est celui d'`overworld`, ses références à ces fonctions renommées, et ses propres lectures de bruit divisées de même : avant
 * 26.3, la température et la végétation s'y lisent en ligne, par un `shifted_noise`.
 *
 * Les fonctions refaites et les bruits à jumeau se lisent dans la copie, version par version : douze fonctions en 26.3, sept
 * avant, et quatre bruits. Aucun type du jeu n'y entre : la transformation d'un arbre JSON s'éprouve à l'étage 0, ce qu'elle
 * écrit se décode par les codecs du jeu à l'étage 1, et se charge à l'étage 2.
 */
object MediumBiomes {

    /** Le réglage de bruit dérivé, sous `travellingdimension:vanilla/`. */
    const val NOISE_SETTINGS = "medium_biomes"

    /** Le réglage de bruit d'où il se dérive. */
    const val SOURCE_NOISE_SETTINGS = "overworld"

    /** Le dossier des fonctions de densité dérivées, sous `travellingdimension:vanilla/`. */
    const val FOLDER = "overworld_medium_biomes"

    /** Le dossier d'où elles se dérivent. */
    const val SOURCE_FOLDER = "overworld"

    /** Le dossier des fonctions que le jeu refait pour `large_biomes` : la liste de celles à refaire. */
    const val LARGE_FOLDER = "overworld_large_biomes"

    /** Le suffixe des bruits que le jeu refait pour `large_biomes` : un bruit qui a ce jumeau se lit à un `xz_scale` divisé par deux. */
    const val LARGE_SUFFIX = "_large"

    private const val NOISE_SETTINGS_FOLDER = "worldgen/noise_settings"
    private const val DENSITY_FUNCTION_FOLDER = "worldgen/density_function"
    private const val NOISE_FOLDER = "worldgen/noise"
    /** Les types de fonction de densité qui lisent un bruit à un `xz_scale` : `shifted_noise` porte les décalages avant 26.3, `noise` les porte depuis. */
    val NOISE_TYPES: Set<String> = setOf("minecraft:noise", "minecraft:shifted_noise")
    private const val XZ_SCALE = "xz_scale"
    private const val JSON = ".json"

    private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * Dérive la taille dans [target], la copie vanilla fabriquée, et rend les fichiers écrits par dossier de registre. Lève quand
     * la copie ne porte pas ce qu'il faut : l'appelant fabrique dans un dossier temporaire, qu'il jette.
     */
    fun derive(target: Path): Map<String, Int> {
        val data = target.resolve("data/${TravellingDimension.MOD_ID}")
        val functions = data.resolve("$DENSITY_FUNCTION_FOLDER/${WorldgenCopy.VANILLA.folder}")
        val redone = jsonNames(functions.resolve(LARGE_FOLDER))
        require(redone.isNotEmpty()) { "the vanilla copy has no $LARGE_FOLDER density function" }
        val enlarged = jsonNames(data.resolve("$NOISE_FOLDER/${WorldgenCopy.VANILLA.folder}")).filter { it.endsWith(LARGE_SUFFIX) }.mapTo(HashSet()) { WorldgenResolver.VANILLA_COPY + it.removeSuffix(LARGE_SUFFIX) }
        require(enlarged.isNotEmpty()) { "the vanilla copy has no $LARGE_SUFFIX noise" }

        val noiseSettings = data.resolve("$NOISE_SETTINGS_FOLDER/${WorldgenCopy.VANILLA.folder}")
        write(noiseSettings.resolve(NOISE_SETTINGS + JSON), halved(renamed(read(noiseSettings.resolve(SOURCE_NOISE_SETTINGS + JSON)), redone), enlarged))
        redone.forEach { name ->
            write(functions.resolve("$FOLDER/$name$JSON"), halved(renamed(read(functions.resolve("$SOURCE_FOLDER/$name$JSON")), redone), enlarged))
        }
        return mapOf(NOISE_SETTINGS_FOLDER to 1, DENSITY_FUNCTION_FOLDER to redone.size)
    }

    /** [json] où chaque identifiant `travellingdimension:vanilla/overworld/<nom>`, clé ou valeur, devient `travellingdimension:vanilla/overworld_medium_biomes/<nom>` quand `<nom>` est dans [redone]. */
    fun renamed(json: JsonElement, redone: Set<String>): JsonElement = when (json) {
        is JsonObject -> JsonObject().also { out -> json.entrySet().forEach { (key, value) -> out.add(renamedId(key, redone), renamed(value, redone)) } }
        is JsonArray -> JsonArray().also { out -> json.forEach { out.add(renamed(it, redone)) } }
        is JsonPrimitive -> if (json.isString) JsonPrimitive(renamedId(json.asString, redone)) else json
        else -> json
    }

    private fun renamedId(text: String, redone: Set<String>): String {
        val sourcePrefix = WorldgenResolver.VANILLA_COPY + "$SOURCE_FOLDER/"
        if (!text.startsWith(sourcePrefix)) return text
        val name = text.removePrefix(sourcePrefix)
        return if (name in redone) WorldgenResolver.VANILLA_COPY + "$FOLDER/$name" else text
    }

    /** [json] où chaque objet d'un type de [NOISE_TYPES] qui lit un bruit de [enlarged] le lit à un `xz_scale` divisé par deux. */
    fun halved(json: JsonElement, enlarged: Set<String>): JsonElement = when (json) {
        is JsonObject -> JsonObject().also { out ->
            val noise = text(json.get("noise"))
            val reads = text(json.get("type")) in NOISE_TYPES && noise != null && noise in enlarged
            json.entrySet().forEach { (key, value) -> out.add(key, if (reads && key == XZ_SCALE) JsonPrimitive(value.asDouble / 2) else halved(value, enlarged)) }
        }
        is JsonArray -> JsonArray().also { out -> json.forEach { out.add(halved(it, enlarged)) } }
        else -> json
    }

    private fun text(json: JsonElement?): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.asString

    /** Les noms des fichiers JSON de [folder], sans extension, triés ; aucun quand le dossier manque. */
    private fun jsonNames(folder: Path): Set<String> =
        if (!Files.isDirectory(folder)) emptySet()
        else folder.listDirectoryEntries().filter { it.isRegularFile() && it.extension == "json" }.mapTo(sortedSetOf()) { it.nameWithoutExtension }

    private fun read(file: Path): JsonElement = Files.newBufferedReader(file, Charsets.UTF_8).use { JsonParser.parseReader(it) }

    private fun write(file: Path, json: JsonElement) {
        Files.createDirectories(file.parent)
        Files.writeString(file, GSON.toJson(json) + "\n")
    }
}
