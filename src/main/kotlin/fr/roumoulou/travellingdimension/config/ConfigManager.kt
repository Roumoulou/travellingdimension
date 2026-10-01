// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.config

import fr.moulou.storify.core.BaseStore
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.transaction
import fr.moulou.storify.encodeToPathAtomically
import fr.roumoulou.travellingdimension.TravellingDimension
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

/**
 * Chargement / sauvegarde de la configuration du mod, par un store Storify.
 *
 * Le fichier `config/travellingdimension/config.json` est du JSON **nu** : depuis que
 * la config s'édite aussi depuis l'écran en jeu (Mod Menu + Cloth Config), un
 * enregistrement réécrit le fichier, et des commentaires n'y survivraient pas. Ils
 * vivent donc dans la documentation, `01-docs/user-docs/02-finalized/configuration.md`,
 * qui décrit chaque réglage.
 *
 * La lecture reste tolérante ([ModJson]) : un fichier commenté à la main, ou hérité
 * d'une version précédente du mod, se charge toujours sans broncher.
 *
 * Storify porte le fichier : la création depuis les défauts au premier lancement,
 * l'écriture atomique (jamais de fichier tronqué, même en cas de crash en pleine
 * écriture), et un décodage qui nomme le fichier et la ligne fautive. Le mod garde ce qui
 * lui appartient : [TravelConfig.sanitized] ramène dans les bornes au lieu de refuser, et le
 * store est ouvert sans validation. La racine du store est [current] elle-même, modifiée
 * propriété par propriété sous son verrou.
 *
 * Invariant : chaque erreur de configuration est loggée, jamais fatale.
 */
object ConfigManager {

    private val configFile: Path = TravellingDimension.CONFIG_DIRECTORY.resolve("config.json")

    /** Le store du fichier, ou `null` tant que le fichier présent est illisible (voir [load]). */
    private var store: BaseStore<TravelConfig>? = null

    /** Config active. Sur un serveur, c'est elle qui fait autorité pour tous les joueurs. */
    @Volatile
    var current: TravelConfig = TravelConfig()
        private set

    fun load() {
        store?.close()
        if (!configFile.exists()) {
            TravellingDimension.LOGGER.info("Aucune config trouvée, création de {}", configFile)
        }
        val opened = open()
        store = opened

        // Corrigé en mémoire seulement : le fichier garde ce que l'utilisateur a écrit, et ne
        // se réécrit qu'à la prochaine modification en jeu.
        val loaded = opened?.data ?: TravelConfig()
        val sane = loaded.sanitized { problem -> TravellingDimension.LOGGER.warn("Config : {}", problem) }
        if (opened != null && sane != loaded) opened.transaction { assignFrom(sane) }
        current = opened?.data ?: sane

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
        ModJson.json.decodeFromString(TravelConfig.serializer(), raw.removePrefix("﻿"))

    /** Encode une config en JSON, pour la transmettre au réseau. */
    fun encode(config: TravelConfig = current): String =
        ModJson.json.encodeToString(TravelConfig.serializer(), config)

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
        val opened = store ?: reopen(sane)
        if (opened == null) {
            current = sane
        } else {
            opened.transaction { assignFrom(sane) }
            try {
                opened.saveImmediate()
            } catch (e: Exception) {
                TravellingDimension.LOGGER.error("Impossible d'écrire la config {} : {}", configFile, e.message)
            }
            current = opened.data
        }
        logCurrent()
        return current
    }

    /** Ouvre le store sur le fichier, créé depuis les défauts s'il manque ; `null` si le fichier présent est illisible. */
    private fun open(): BaseStore<TravelConfig>? = try {
        StoreFactory.createFromConstructor<TravelConfig>(configFile.toString(), ModJson.format, ModJson.storeConfig())
    } catch (e: Exception) {
        TravellingDimension.LOGGER.error("Config illisible, utilisation des valeurs par défaut. Erreur : {}", e.message)
        null
    }

    /**
     * Le fichier était illisible au chargement : la config demandée le remplace, écrite en
     * entier, puis le store s'ouvre dessus. C'est le geste qu'un enregistrement en jeu a
     * toujours fait sur un fichier cassé.
     */
    private fun reopen(config: TravelConfig): BaseStore<TravelConfig>? = try {
        ModJson.format.encodeToPathAtomically(TravelConfig.serializer(), config, configFile)
        open()?.also { store = it }
    } catch (e: Exception) {
        TravellingDimension.LOGGER.error("Impossible d'écrire la config {} : {}", configFile, e.message)
        null
    }

    /**
     * Recopie chaque propriété de [other] dans cette racine, sous le verrou du store (appelé
     * dans une transaction). Par réflexion, pour qu'un réglage ajouté demain soit recopié
     * sans qu'on ait à y penser : une liste écrite à la main l'oublierait en silence.
     */
    private fun TravelConfig.assignFrom(other: TravelConfig) {
        for (property in TravelConfig::class.memberProperties) {
            @Suppress("UNCHECKED_CAST")
            val mutable = property as? KMutableProperty1<TravelConfig, Any?> ?: continue
            mutable.set(this, mutable.get(other))
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
