// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenPreparation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ce que le mod prépare à son chargement : le tableau du chapitre 3.1 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`, un test par ligne.
 *
 * La préparation ne prend aucun type du jeu : ces tests vivent dans le source set **pur**. La
 * présence d'une copie sur le disque et sa déclaration au jeu s'éprouvent aux étages 1 et 2.
 */
class WorldgenPreparationTest {

    private companion object {
        val VANILLA_COPY_ONLY = setOf(WorldgenCopy.VANILLA)
    }

    @Test
    @DisplayName("terralith : le mod ne prépare rien, quoi qui soit installé ou déposé")
    fun `terralith ne prepare rien`() {
        for (wwooModLoaded in listOf(false, true)) {
            for (williamJarAccepted in listOf(false, true)) {
                assertEquals(emptySet<WorldgenCopy>(), WorldgenPreparation.copiesFor(WorldgenMode.TERRALITH, wwooModLoaded, williamJarAccepted))
            }
        }
    }

    @Test
    @DisplayName("vanilla, tectonic et custom : la copie vanilla, jamais la copie William")
    fun `copie vanilla seule`() {
        for (mode in listOf(WorldgenMode.VANILLA, WorldgenMode.TECTONIC, WorldgenMode.CUSTOM)) {
            assertEquals(VANILLA_COPY_ONLY, WorldgenPreparation.copiesFor(mode, wwooModLoaded = false, williamJarAccepted = false), mode.name)
            // Un jar accepté dans le dossier ne sert qu'au mode william.
            assertEquals(VANILLA_COPY_ONLY, WorldgenPreparation.copiesFor(mode, wwooModLoaded = false, williamJarAccepted = true), mode.name)
        }
    }

    @Test
    @DisplayName("william : la copie vanilla, puis la copie William quand un jar du dossier est accepté")
    fun `william avec et sans jar`() {
        assertEquals(VANILLA_COPY_ONLY, WorldgenPreparation.copiesFor(WorldgenMode.WILLIAM, wwooModLoaded = false, williamJarAccepted = false))

        // L'ordre compte : la copie William référence la copie vanilla, déclarée avant elle.
        assertEquals(
            listOf(WorldgenCopy.VANILLA, WorldgenCopy.WILLIAM),
            WorldgenPreparation.copiesFor(WorldgenMode.WILLIAM, wwooModLoaded = false, williamJarAccepted = true).toList(),
        )
    }

    @Test
    @DisplayName("william, le mod wwoo chargé : la copie William n'est pas préparée, même avec un jar accepté")
    fun `wwoo installe prime`() {
        assertEquals(VANILLA_COPY_ONLY, WorldgenPreparation.copiesFor(WorldgenMode.WILLIAM, wwooModLoaded = true, williamJarAccepted = true))
        assertEquals(VANILLA_COPY_ONLY, WorldgenPreparation.copiesFor(WorldgenMode.WILLIAM, wwooModLoaded = true, williamJarAccepted = false))
    }

    @Test
    @DisplayName("le datapack d'une copie porte l'identifiant du mod et son dossier")
    fun `identifiant du datapack`() {
        assertEquals("travellingdimension/vanilla", WorldgenCopy.VANILLA.packId)
        assertEquals("travellingdimension/wwoo", WorldgenCopy.WILLIAM.packId)
    }
}
