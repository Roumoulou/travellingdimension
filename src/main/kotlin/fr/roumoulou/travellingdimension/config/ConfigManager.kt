// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.config

import fr.moulou.storify.core.BaseStore
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.transaction
import fr.moulou.storify.validation.ValidationException
import fr.moulou.storify.validation.ValidationResult
import fr.moulou.storify.validation.evaluate
import fr.roumoulou.travellingdimension.TravellingDimension
import java.nio.file.Path
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

/**
 * La configuration du mod, par un store Storify sur `config/travellingdimension/config.json`.
 *
 * Le fichier est du JSON **strict** ([ModJson]) : depuis que la config s'édite aussi depuis
 * l'écran en jeu (Mod Menu + Cloth Config), un enregistrement le réécrit entier, et des
 * commentaires n'y survivraient pas. Ils vivent dans la documentation,
 * `01-docs/user-docs/02-finalized/configuration.md`, qui décrit chaque réglage.
 *
 * Storify porte le fichier : la création depuis les défauts quand il manque, l'écriture
 * atomique (jamais de fichier tronqué, même en cas de crash en pleine écriture), un décodage
 * qui nomme le fichier et la ligne fautive, et la validation à l'ouverture par
 * [TravelConfigValidator]. Le store s'ouvre une fois pour toutes à l'initialisation de cet
 * objet : un fichier illisible ou invalide lève ici, l'exception remonte de `onInitialize`
 * jusqu'au rapport de crash, et le jeu ne démarre pas. La racine du store est [current]
 * elle-même, modifiée propriété par propriété sous son verrou.
 *
 * Invariant : une configuration est entière et valide, ou le jeu ne démarre pas.
 */
object ConfigManager {

    private val configFile: Path = TravellingDimension.CONFIG_DIRECTORY.resolve("config.json")

    /** Le store du fichier, ouvert et validé à l'initialisation de l'objet, voir sa KDoc. */
    private val store: BaseStore<TravelConfig> = StoreFactory.createFromConstructor<TravelConfig>(
        configFile.toString(), ModJson.format, ModJson.storeConfig(), TravelConfigValidator
    )

    /** Config active, la racine du store. Sur un serveur, c'est elle qui fait autorité pour tous les joueurs. */
    val current: TravelConfig get() = store.data

    /**
     * Le premier geste du mod : l'accès ouvre le store, et la ligne récapitulative dit ce qui
     * a été chargé. Le choix du générateur en dépend, d'où sa place en tête de `onInitialize`.
     */
    fun announce() = logCurrent()

    /**
     * Décode sans appliquer. Lève si le JSON est invalide ou porte une clé inconnue :
     * l'appelant décide quoi faire, le réseau refuse le paquet.
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
     * Remplace la config active par [config] et la persiste, ou la refuse entière : une valeur
     * hors bornes lève [ValidationException] avec tous les problèmes de la demande, et rien
     * n'est appliqué. Un échec d'écriture du fichier remonte aussi ; la config est alors
     * appliquée en mémoire et pas sur le disque, et l'appelant le dit.
     *
     * N'appelle volontairement PAS `WorldgenSelector.apply` : les réglages de génération
     * ne peuvent pas changer sous les pieds d'un monde déjà chargé (voir
     * [TravelConfig.needsRestartAgainst]).
     */
    fun apply(config: TravelConfig) {
        val verdict = TravelConfigValidator.evaluate(config)
        if (verdict is ValidationResult.Failure) throw ValidationException(verdict.errors)
        store.transaction { assignFrom(config) }
        store.saveImmediate()
        logCurrent()
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
