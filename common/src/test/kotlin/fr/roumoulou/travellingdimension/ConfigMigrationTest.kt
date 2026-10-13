// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.ConfigMigration
import fr.roumoulou.travellingdimension.config.ModJson
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La migration de `largeBiomes` vers `biomeSize`, sur l'arbre JSON : le chapitre 5.7 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Sans type du jeu ni fichier : ce que le store fait du fichier migré se voit à l'étage 1.
 */
class ConfigMigrationTest {

    private fun parse(text: String): JsonObject = ModJson.json.parseToJsonElement(text) as JsonObject

    @Test
    @DisplayName("largeBiomes true devient biomeSize large, false devient normal, à la même place, et le reste ne bouge pas")
    fun `largeBiomes migre`() {
        assertEquals(
            parse("""{ "worldgen": "william", "ratio": 16, "biomeSize": "large", "seed": "424242" }"""),
            ConfigMigration.migrated(parse("""{ "worldgen": "william", "ratio": 16, "largeBiomes": true, "seed": "424242" }""")),
        )
        assertEquals(
            parse("""{ "biomeSize": "normal" }"""),
            ConfigMigration.migrated(parse("""{ "largeBiomes": false }""")),
        )
        // L'ordre des clés est celui du fichier : la clé migrée prend la place de l'ancienne.
        assertEquals(listOf("worldgen", "ratio", "biomeSize", "seed"), ConfigMigration.migrated(parse("""{ "worldgen": "william", "ratio": 16, "largeBiomes": true, "seed": "424242" }"""))!!.keys.toList())
    }

    @Test
    @DisplayName("rien à migrer : pas de largeBiomes, biomeSize déjà là, ou les deux clés, que le store refusera")
    fun `rien a migrer`() {
        assertNull(ConfigMigration.migrated(parse("""{ "worldgen": "william", "biomeSize": "medium" }""")))
        assertNull(ConfigMigration.migrated(parse("""{ "worldgen": "william" }""")))
        assertNull(ConfigMigration.migrated(parse("""{ "largeBiomes": true, "biomeSize": "normal" }""")))
        // Un largeBiomes qui n'est pas un booléen n'est pas corrigé : le store le refuse, avec sa ligne.
        assertNull(ConfigMigration.migrated(parse("""{ "largeBiomes": "yes" }""")))
    }
}
