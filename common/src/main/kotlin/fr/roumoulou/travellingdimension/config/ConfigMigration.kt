// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * La migration de `config.json` d'une version à l'autre, avant l'ouverture du store : la clé `largeBiomes`, un booléen jusqu'à la
 * 2.10.0, devient `biomeSize`, `true` donnant `large` et `false` donnant `normal`, à la même place dans le fichier. Le reste ne
 * bouge pas, et le fichier est réécrit d'un seul renommage.
 *
 * Elle ne touche qu'un fichier qui porte `largeBiomes` et pas `biomeSize` : un fichier qui porte les deux n'est pas migré, et
 * `largeBiomes` l'arrête comme toute clé inconnue, par le store. Aucun type du jeu n'y entre : l'arbre s'éprouve à l'étage 0, le
 * fichier à l'étage 1.
 */
object ConfigMigration {

    private const val OLD_KEY = "largeBiomes"
    private const val NEW_KEY = "biomeSize"
    private const val BOM = "﻿"

    /**
     * Migre [file] s'il y a lieu, et rend ce qui a été fait, pour le log, ou `null` quand rien ne l'était. Lève quand le fichier
     * ne se lit pas comme du JSON ou ne s'écrit pas : le store dira alors ce qu'il en est.
     */
    fun migrate(file: Path): String? {
        if (!Files.isRegularFile(file)) return null
        val root = ModJson.json.parseToJsonElement(Files.readString(file).removePrefix(BOM)) as? JsonObject ?: return null
        val migrated = migrated(root) ?: return null
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temporary, ModJson.json.encodeToString(JsonObject.serializer(), migrated) + "\n")
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return "$OLD_KEY ${root.getValue(OLD_KEY)} became $NEW_KEY ${migrated.getValue(NEW_KEY)}"
    }

    /**
     * [root] migré, la clé remplacée à sa place, ou `null` quand rien n'est à migrer : pas de `largeBiomes`, un `biomeSize` déjà
     * là, ou un `largeBiomes` qui n'est pas un booléen, que le store refusera.
     */
    fun migrated(root: JsonObject): JsonObject? {
        if (OLD_KEY !in root || NEW_KEY in root) return null
        val large = (root.getValue(OLD_KEY) as? JsonPrimitive)?.booleanOrNull ?: return null
        val size = ModJson.json.encodeToJsonElement(BiomeSize.serializer(), if (large) BiomeSize.LARGE else BiomeSize.NORMAL)
        return JsonObject(root.entries.associate { (key, value) -> if (key == OLD_KEY) NEW_KEY to size else key to value })
    }
}
