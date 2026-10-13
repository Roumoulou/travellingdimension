// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le renommage d'un identifiant par une copie : le tableau du chapitre 5.1 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Une chaîne en donne une autre, sans type du jeu : où le moteur l'applique se voit à l'étage 1.
 */
class WorldgenCopyTest {

    @Test
    @DisplayName("un identifiant minecraft: passe sous l'espace du mod, dans le dossier de la copie")
    fun `identifiant minecraft`() {
        assertEquals("travellingdimension:vanilla/plains", WorldgenCopy.VANILLA.renamed("minecraft:plains"))
        assertEquals("travellingdimension:vanilla/overworld/caves/noodle", WorldgenCopy.VANILLA.renamed("minecraft:overworld/caves/noodle"))
        assertEquals("travellingdimension:wwoo/plains", WorldgenCopy.WILLIAM.renamed("minecraft:plains"))

        // Sans espace de noms, un identifiant est minecraft:, comme dans le jeu.
        assertEquals("travellingdimension:vanilla/plains", WorldgenCopy.VANILLA.renamed("plains"))
    }

    @Test
    @DisplayName("un identifiant d'un autre espace de noms garde cet espace en tête de son chemin")
    fun `autre espace de noms`() {
        assertEquals("travellingdimension:wwoo/othermod/tree/oak", WorldgenCopy.WILLIAM.renamed("othermod:tree/oak"))
        assertEquals("travellingdimension:vanilla/othermod/hills", WorldgenCopy.VANILLA.renamed("othermod:hills"))

        // Le même chemin sous deux espaces donne deux identifiants : aucun n'écrase l'autre.
        assertEquals("wwoo/plains", WorldgenCopy.WILLIAM.renamedPath("minecraft", "plains"))
        assertEquals("wwoo/othermod/plains", WorldgenCopy.WILLIAM.renamedPath("othermod", "plains"))
    }

    @Test
    @DisplayName("une copie écrit sous le préfixe que la résolution cherche dans les registres")
    fun `prefixes de la resolution`() {
        assertEquals(WorldgenResolver.VANILLA_COPY + "large_biomes", WorldgenCopy.VANILLA.renamed("minecraft:large_biomes"))
        assertEquals(WorldgenResolver.WILLIAM_COPY + "plains", WorldgenCopy.WILLIAM.renamed("minecraft:plains"))
    }
}
