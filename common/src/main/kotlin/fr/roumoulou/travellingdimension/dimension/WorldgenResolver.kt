// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.WorldgenMode

/**
 * La résolution du terrain de VOYAGE : la table de décision de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`, chapitres 2 et 7.
 *
 * Elle reçoit la configuration et ce qui est détecté, et rend ce qu'il faut construire, avec les
 * messages du repli. Aucun type du jeu n'y entre, pour que chaque cas de la table soit un test de
 * l'étage 0 : la lecture des registres et la construction du générateur vivent à côté.
 *
 * La chaîne de repli a trois maillons : le terrain du mode, la copie vanilla à la taille de
 * `largeBiomes`, le `LevelStem` du JSON embarqué.
 */
object WorldgenResolver {

    /** Le préfixe de tout identifiant de la copie vanilla. */
    const val VANILLA_COPY = "${TravellingDimension.MOD_ID}:vanilla/"

    /** Le préfixe de tout identifiant de la copie William. */
    const val WILLIAM_COPY = "${TravellingDimension.MOD_ID}:wwoo/"

    private const val MINECRAFT = "minecraft:"
    private const val OVERWORLD = "minecraft:overworld"

    /**
     * Le terrain de VOYAGE pour [config], d'après [detection]. [worldgenFolder] est le dossier où
     * le joueur dépose le jar de WWOO : le message `william_no_source` le donne.
     */
    fun resolve(config: TravelConfig, detection: WorldgenDetection, worldgenFolder: String): WorldgenResolution = when (config.worldgen) {
        // Jamais un repli : ces identifiants existent avec ou sans Terralith.
        WorldgenMode.TERRALITH -> WorldgenResolution(Terrain.Noise(MINECRAFT + sizeOf(config), BiomeChoice.Preset(OVERWORLD)))
        WorldgenMode.VANILLA -> vanillaCopy(config, detection)
        WorldgenMode.WILLIAM -> william(config, detection, worldgenFolder)
        WorldgenMode.TECTONIC ->
            if (detection.tectonicLoaded) WorldgenResolution(Terrain.Noise(OVERWORLD, BiomeChoice.Preset(OVERWORLD)))
            else vanillaCopy(config, detection, notices = listOf(WorldgenNotice.TectonicMissing))
        WorldgenMode.CUSTOM -> custom(config, detection)
    }

    /**
     * Le maillon suivant quand la construction de [failed] a levé pour [reason] : la copie
     * vanilla, ou le `LevelStem` du JSON embarqué quand c'est elle qui vient d'échouer.
     * `generator_failed` s'ajoute aux messages de [failed].
     */
    fun fallback(config: TravelConfig, detection: WorldgenDetection, failed: WorldgenResolution, reason: String): WorldgenResolution {
        val notices = failed.notices + WorldgenNotice.GeneratorFailed(reason)
        val next = vanillaCopy(config, detection, notices = notices)
        return if (next.terrain == failed.terrain) WorldgenResolution(Terrain.EmbeddedStem, notices) else next
    }

    /** Le relief de la copie vanilla, et les biomes de William : WWOO installé d'abord, la copie William à défaut, la copie vanilla en repli. */
    private fun william(config: TravelConfig, detection: WorldgenDetection, worldgenFolder: String): WorldgenResolution = when {
        // WWOO installé a remplacé les biomes minecraft: du registre : la disposition vanilla les garde.
        detection.wwooInstalled -> vanillaCopy(config, detection, BiomeChoice.VanillaLayout(listOf(MINECRAFT)))
        detection.williamCopyLoaded -> vanillaCopy(config, detection, BiomeChoice.VanillaLayout(listOf(WILLIAM_COPY, VANILLA_COPY)))
        // Le jar est là, c'est le garde-fou qui retient sa copie : le message dit comment réessayer, pas où déposer le jar.
        WorldgenCopy.WILLIAM in detection.disabledCopies -> vanillaCopy(config, detection, notices = disabled(detection, WorldgenCopy.WILLIAM))
        else -> vanillaCopy(config, detection, notices = listOf(WorldgenNotice.WilliamNoSource(worldgenFolder)))
    }

    private fun custom(config: TravelConfig, detection: WorldgenDetection): WorldgenResolution {
        val unknown = buildList<WorldgenNotice> {
            if (!detection.customNoiseSettingsKnown) add(WorldgenNotice.CustomUnknown(config.customNoiseSettings))
            if (!detection.customBiomePresetKnown) add(WorldgenNotice.CustomUnknown(config.customBiomePreset))
        }.distinct()
        return if (unknown.isEmpty()) WorldgenResolution(Terrain.Noise(config.customNoiseSettings, BiomeChoice.Preset(config.customBiomePreset)))
        else vanillaCopy(config, detection, notices = unknown)
    }

    /**
     * La copie vanilla à la taille de `largeBiomes`, avec [biomes], ou le dernier maillon quand
     * elle n'est pas chargée : le `LevelStem` du JSON embarqué, et `vanilla_copy_failed` après
     * [notices]. Quand c'est le garde-fou qui la retient, `copy_disabled` le dit d'abord : la
     * cause, puis la conséquence.
     */
    private fun vanillaCopy(
        config: TravelConfig,
        detection: WorldgenDetection,
        biomes: BiomeChoice = BiomeChoice.VanillaLayout(listOf(VANILLA_COPY)),
        notices: List<WorldgenNotice> = emptyList(),
    ): WorldgenResolution =
        if (detection.vanillaCopyLoaded) WorldgenResolution(Terrain.Noise(VANILLA_COPY + sizeOf(config), biomes), notices)
        else WorldgenResolution(Terrain.EmbeddedStem, notices + disabled(detection, WorldgenCopy.VANILLA) + WorldgenNotice.VanillaCopyFailed)

    /** `copy_disabled` pour [copy] quand le garde-fou la tient désactivée, rien sinon. */
    private fun disabled(detection: WorldgenDetection, copy: WorldgenCopy): List<WorldgenNotice> =
        listOfNotNull(detection.disabledCopies[copy]?.let { file -> WorldgenNotice.CopyDisabled(copy.title, file) })

    private fun sizeOf(config: TravelConfig): String = if (config.largeBiomes) "large_biomes" else "overworld"
}

/** Ce que le mod détecte de l'installation : les critères du chapitre 3.3 de la spécification, les copies que le garde-fou retient, et les deux identifiants de `custom`. */
data class WorldgenDetection(

    /** Le mod `terralith` est chargé. Aucun mode ne s'en sert pour résoudre : `terralith` suit l'OVERWORLD avec ou sans lui. */
    val terralithLoaded: Boolean,

    /** Le mod `tectonic` est chargé. */
    val tectonicLoaded: Boolean,

    /** WWOO est installé, en mod ou en datapack : le mod `wwoo` est chargé, ou `minecraft:plains` porte une feature `wythers:`. */
    val wwooInstalled: Boolean,

    /** La copie vanilla est chargée : le réglage de bruit `travellingdimension:vanilla/overworld` est dans le registre. */
    val vanillaCopyLoaded: Boolean,

    /** La copie William est chargée : un biome `travellingdimension:wwoo/...` est dans le registre. */
    val williamCopyLoaded: Boolean,

    /**
     * Les copies que le garde-fou tient désactivées, chacune avec le fichier à supprimer pour la réessayer : elles ne sont ni
     * déclarées ni chargées ([WorldgenCopyGuard]).
     */
    val disabledCopies: Map<WorldgenCopy, String>,

    /** `customNoiseSettings` est un réglage de bruit du registre. */
    val customNoiseSettingsKnown: Boolean,

    /** `customBiomePreset` est un preset de biomes du registre. */
    val customBiomePresetKnown: Boolean,
)

/** Ce que la résolution rend : le terrain à construire, et les messages du repli, dans l'ordre où la chaîne a descendu. */
data class WorldgenResolution(val terrain: Terrain, val notices: List<WorldgenNotice> = emptyList())

/** Le terrain de VOYAGE. */
sealed interface Terrain {

    /** Le dernier maillon du repli : le `LevelStem` du JSON embarqué, laissé tel quel. VOYAGE suit l'OVERWORLD, en large biomes. */
    data object EmbeddedStem : Terrain

    /** Un générateur de bruit à construire : son réglage de bruit, par identifiant, et ses biomes. */
    data class Noise(val noiseSettings: String, val biomes: BiomeChoice) : Terrain
}

/** D'où viennent les biomes d'un [Terrain.Noise]. */
sealed interface BiomeChoice {

    /** Un preset de biomes du registre, par identifiant : ce qu'un mod ou un datapack y met atteint VOYAGE. */
    data class Preset(val id: String) : BiomeChoice

    /**
     * La disposition vanilla, celle que le jeu code en dur. Chaque `minecraft:<biome>` s'y cherche
     * sous [prefixes], dans l'ordre : pour la copie William, `travellingdimension:wwoo/<biome>`
     * puis `travellingdimension:vanilla/<biome>`.
     */
    data class VanillaLayout(val prefixes: List<String>) : BiomeChoice
}

/**
 * Un message du repli, par sa clé de traduction et ses arguments : les opérateurs le reçoivent en
 * composant traduisible, à chaque connexion. Sa ligne de log est dans [WorldgenReport.warning].
 */
sealed class WorldgenNotice(name: String, val arguments: List<String> = emptyList()) {

    val key: String = "${TravellingDimension.MOD_ID}.worldgen.$name"

    /** `william` sans WWOO installé ni copie William. [worldgenFolder] est le dossier où déposer le jar. */
    data class WilliamNoSource(val worldgenFolder: String) : WorldgenNotice("william_no_source", listOf(worldgenFolder))

    /** Le garde-fou tient la copie [copy] désactivée : elle a fait échouer un chargement des registres. Supprimer [file] la fait réessayer. */
    data class CopyDisabled(val copy: String, val file: String) : WorldgenNotice("copy_disabled", listOf(copy, file))

    /** La copie vanilla n'est pas chargée : VOYAGE suit l'OVERWORLD. */
    data object VanillaCopyFailed : WorldgenNotice("vanilla_copy_failed")

    /** `tectonic` sans le mod Tectonic. */
    data object TectonicMissing : WorldgenNotice("tectonic_missing")

    /** `custom` : [identifier] n'est pas dans les registres. */
    data class CustomUnknown(val identifier: String) : WorldgenNotice("custom_unknown", listOf(identifier))

    /** La construction d'un terrain a levé pour [reason], et la chaîne a descendu d'un maillon. */
    data class GeneratorFailed(val reason: String) : WorldgenNotice("generator_failed", listOf(reason))

    /** VOYAGE n'existe pas sur le serveur. */
    data object DimensionMissing : WorldgenNotice("dimension_missing")
}
