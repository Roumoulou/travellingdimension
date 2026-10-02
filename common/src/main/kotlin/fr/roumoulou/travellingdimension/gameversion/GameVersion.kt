// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion

import java.util.ServiceLoader

/**
 * Le pont vers la version du jeu que sert ce jar.
 *
 * Chaque module de version déclare son implémentation de [GameVersionBridge] dans
 * `META-INF/services/fr.roumoulou.travellingdimension.gameversion.GameVersionBridge`, et le jar d'une version n'en embarque qu'une.
 * Le chargement a lieu au premier accès, quand les blocs s'enregistrent à l'initialisation du mod.
 */
object GameVersion {

    val bridge: GameVersionBridge by lazy { load() }

    private fun load(): GameVersionBridge {
        val bridges = ServiceLoader.load(GameVersionBridge::class.java, GameVersionBridge::class.java.classLoader).toList()
        return bridges.singleOrNull()
            ?: error("Expected exactly one GameVersionBridge from a game version module (mc-<version>), found ${bridges.size}: ${bridges.map { it.javaClass.name }}")
    }
}
