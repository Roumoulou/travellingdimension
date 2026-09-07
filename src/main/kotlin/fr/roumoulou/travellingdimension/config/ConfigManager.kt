package fr.roumoulou.travellingdimension.config

import fr.roumoulou.travellingdimension.TravellingDimension
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Chargement / sauvegarde de la configuration du mod.
 *
 * Le fichier `config/travellingdimension/config.json` est du JSON **nu** : depuis que
 * la config s'édite aussi depuis l'écran en jeu (Mod Menu + Cloth Config), un
 * enregistrement réécrit le fichier, et des commentaires n'y survivraient pas. Ils
 * vivent donc dans la documentation, `00-documentation/readme - Configuration.md`,
 * qui décrit chaque réglage.
 *
 * La lecture reste tolérante (`allowComments`) : un fichier commenté à la main, ou
 * hérité d'une version précédente du mod, se charge toujours sans broncher.
 *
 * Invariant : chaque erreur de configuration est loggée, jamais fatale.
 */
object ConfigManager {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true    // sinon un réglage laissé au défaut DISPARAÎT du fichier
        ignoreUnknownKeys = true // tolère les clés inconnues (anciennes versions, typos)
        allowComments = true     // un fichier commenté à la main reste lisible
        allowTrailingComma = true
        isLenient = true
    }

    private val configFile: Path = TravellingDimension.CONFIG_DIRECTORY.resolve("config.json")

    /** Config active. Sur un serveur, c'est elle qui fait autorité pour tous les joueurs. */
    var current: TravelConfig = TravelConfig()
        private set

    fun load() {
        current = try {
            if (configFile.exists()) {
                decode(configFile.readText())
            } else {
                TravellingDimension.LOGGER.info("Aucune config trouvée, création de {}", configFile)
                write(TravelConfig())
                TravelConfig()
            }
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error(
                "Config illisible ({}), utilisation des valeurs par défaut. Erreur : {}",
                configFile, e.message
            )
            TravelConfig()
        }.sanitized { problem -> TravellingDimension.LOGGER.warn("Config : {}", problem) }

        logCurrent()
    }

    /**
     * Décode sans appliquer. Lève si le JSON est invalide : l'appelant décide quoi
     * faire (le réseau refuse le paquet, le disque retombe sur les défauts).
     *
     * Le préfixe retiré est le BOM (U+FEFF) : Notepad et PowerShell 5.1 écrivent de
     * l'UTF-8 avec BOM, et kotlinx s'en étrangle.
     */
    fun decode(raw: String): TravelConfig =
        json.decodeFromString<TravelConfig>(raw.removePrefix("﻿"))

    /** Encode une config en JSON, pour l'écrire sur disque ou la transmettre au réseau. */
    fun encode(config: TravelConfig = current): String =
        json.encodeToString(TravelConfig.serializer(), config)

    /**
     * Remplace la config active et la persiste. Retourne la version réellement retenue,
     * qui peut différer de celle demandée (bornes corrigées par [TravelConfig.sanitized]) :
     * c'est cette version-là qu'il faut renvoyer aux clients.
     *
     * N'appelle volontairement PAS `WorldgenSelector.apply` : les réglages de génération
     * ne peuvent pas changer sous les pieds d'un monde déjà chargé (voir
     * [TravelConfig.needsRestartAgainst]).
     */
    fun apply(config: TravelConfig): TravelConfig {
        val sane = config.sanitized { problem -> TravellingDimension.LOGGER.warn("Config : {}", problem) }
        current = sane
        write(sane)
        logCurrent()
        return sane
    }

    private fun write(config: TravelConfig) {
        try {
            configFile.parent.createDirectories()
            configFile.writeText(encode(config) + "\n")
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error("Impossible d'écrire la config {} : {}", configFile, e.message)
        }
    }

    private fun logCurrent() {
        TravellingDimension.LOGGER.info(
            "Config : worldgen={}, ratio=1:{}, portée {} blocs (OVERWORLD) et {} blocs (VOYAGE), " +
                    "vertical={} poids={}, plateforme {}x{} en {}, dégagement {}x{}, structures={}, mobDensity={}",
            current.worldgen, current.ratio,
            current.searchRadiusOverworld, current.searchRadiusVoyage,
            current.verticalMode, current.verticalWeight,
            current.platformMargin, current.platformDepth, current.platformBlock,
            current.clearanceMargin, current.clearanceHeight,
            current.structures, current.mobDensity
        )
    }
}
