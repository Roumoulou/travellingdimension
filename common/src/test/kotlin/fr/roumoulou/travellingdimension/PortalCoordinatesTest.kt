// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.portal.PortalCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La transformation de coordonnées et la portée symétrique : de l'arithmétique entière, et
 * rien d'autre.
 *
 * Ce fichier vit dans le source set **pur**, qui ne voit pas Minecraft. La distance passe par
 * `BlockPos` et vit donc dans `testMC`, voir `PortalDistanceTest`.
 *
 * Le deux-points et l'accent grave sont interdits dans un nom de fonction Kotlin, alors que
 * les intitulés du projet en portent : chaque test garde son nom complet en [DisplayName], et
 * la fonction ne porte qu'une étiquette courte.
 */
class PortalCoordinatesTest {

    private val ratio = 16

    // ── La transformation ────────────────────────────────────────────────────

    @Test
    @DisplayName("OVERWORLD vers VOYAGE : on garde la partie entière, jamais l'arrondi")
    fun `partie entiere`() {
        assertEquals(8, PortalCoordinates.overworldToTravel(143, ratio))
        assertEquals(1, PortalCoordinates.overworldToTravel(16, ratio))
        assertEquals(0, PortalCoordinates.overworldToTravel(0, ratio))
        assertEquals(36, PortalCoordinates.overworldToTravel(588, ratio))
        assertEquals(30, PortalCoordinates.overworldToTravel(490, ratio))
    }

    @Test
    @DisplayName("la division est PLANCHER en négatif : pas de case double autour de zéro")
    fun `division plancher en negatif`() {
        assertEquals(-1, PortalCoordinates.overworldToTravel(-1, ratio))
        assertEquals(-1, PortalCoordinates.overworldToTravel(-16, ratio))
        assertEquals(-2, PortalCoordinates.overworldToTravel(-17, ratio))
        assertEquals(-3, PortalCoordinates.overworldToTravel(-33, ratio))
        assertEquals(-8, PortalCoordinates.overworldToTravel(-128, ratio))
    }

    @Test
    @DisplayName("toutes les cases font exactement `ratio` blocs, y compris à cheval sur zéro")
    fun `toutes les cases font ratio blocs`() {
        (-64..64).groupBy { PortalCoordinates.overworldToTravel(it, ratio) }
            .filterKeys { it in -3..3 }
            .forEach { (cell, blocks) ->
                assertEquals(ratio, blocks.size, "la case $cell ne fait pas $ratio blocs")
            }
    }

    @Test
    @DisplayName("VOYAGE vers OVERWORLD : multiplication exacte")
    fun `multiplication exacte`() {
        assertEquals(528, PortalCoordinates.travelToOverworld(33, ratio))
        assertEquals(16, PortalCoordinates.travelToOverworld(1, ratio))
        assertEquals(-16, PortalCoordinates.travelToOverworld(-1, ratio))
        assertEquals(0, PortalCoordinates.travelToOverworld(0, ratio))
    }

    @Test
    @DisplayName("aller-retour depuis VOYAGE : exact, c'est le seul sens qui l'est")
    fun `aller retour depuis VOYAGE`() {
        (-40..40).forEach { cell ->
            assertEquals(
                cell,
                PortalCoordinates.overworldToTravel(
                    PortalCoordinates.travelToOverworld(cell, ratio), ratio
                ),
                "la case $cell ne revient pas sur elle-même",
            )
        }
    }

    @Test
    @DisplayName("le coin de case est le multiple inférieur")
    fun `coin de case`() {
        assertEquals(128, PortalCoordinates.cellCorner(143, ratio))
        assertEquals(16, PortalCoordinates.cellCorner(16, ratio))
        assertEquals(-16, PortalCoordinates.cellCorner(-1, ratio))
    }

    @Test
    @DisplayName("l'échelle horizontale : `ratio` dans VOYAGE, 1 dans l'OVERWORLD")
    fun `echelle horizontale`() {
        assertEquals(16, PortalCoordinates.horizontalScale(inTravel = true, ratio = ratio))
        assertEquals(1, PortalCoordinates.horizontalScale(inTravel = false, ratio = ratio))
    }

    // ── La portée symétrique ─────────────────────────────────────────────────

    @Test
    @DisplayName("le rayon de VOYAGE découle du rayon d'OVERWORLD")
    fun `rayon de VOYAGE`() {
        assertEquals(8, PortalCoordinates.symmetricTravelRadius(128, 16))
        assertEquals(16, PortalCoordinates.symmetricTravelRadius(128, 8))
        assertEquals(16, PortalCoordinates.symmetricTravelRadius(256, 16))
    }

    @Test
    @DisplayName("un rayon trop petit ne descend jamais sous 1")
    fun `plancher du rayon`() {
        assertEquals(1, PortalCoordinates.symmetricTravelRadius(4, 16))
    }
}
