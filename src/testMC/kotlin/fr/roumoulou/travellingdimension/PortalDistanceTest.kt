package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.portal.PortalCoordinates
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La distance qui départage les portails candidats.
 *
 * **Pourquoi ce fichier n'est pas dans le source set pur** : la signature prend des
 * [BlockPos]. Ce n'est qu'un triplet d'entiers, aucun amorçage n'est nécessaire, mais le
 * type appartient au jeu et le source set pur ne le voit pas.
 *
 * La règle éprouvée ici : **la distance se mesure toujours en blocs d'OVERWORLD**. Dans
 * VOYAGE un bloc horizontal en vaut `ratio`, un bloc vertical en vaut un. Les additionner
 * tels quels reviendrait à additionner des kilomètres et des centimètres.
 */
class PortalDistanceTest {

    private val ratio = 16

    @Test
    @DisplayName("dans l'OVERWORLD la distance est l'euclidienne ordinaire")
    fun `distance dans l OVERWORLD`() {
        val d = PortalCoordinates.distanceSquared(
            BlockPos(3, 4, 0), BlockPos(0, 0, 0), inTravel = false, ratio = ratio
        )
        assertEquals(25.0, d)
    }

    @Test
    @DisplayName("dans VOYAGE un bloc horizontal vaut `ratio` blocs, un bloc vertical en vaut un")
    fun `distance dans VOYAGE`() {
        val horizontal = PortalCoordinates.distanceSquared(
            BlockPos(1, 0, 0), BlockPos(0, 0, 0), inTravel = true, ratio = ratio
        )
        val vertical = PortalCoordinates.distanceSquared(
            BlockPos(0, 1, 0), BlockPos(0, 0, 0), inTravel = true, ratio = ratio
        )
        assertEquals(256.0, horizontal)
        assertEquals(1.0, vertical)
    }

    @Test
    @DisplayName("un portail à une case bat un portail 40 blocs plus haut, parce qu'il coûte moins de trajet")
    fun `une case bat quarante blocs de haut`() {
        val ideal = BlockPos(0, 64, 0)
        val oneCell = PortalCoordinates.distanceSquared(BlockPos(1, 64, 0), ideal, true, ratio)
        val fortyUp = PortalCoordinates.distanceSquared(BlockPos(0, 104, 0), ideal, true, ratio)

        // 1 case = 16 blocs d'OVERWORLD de marche, 40 blocs d'altitude = 40 blocs.
        assertEquals(256.0, oneCell)
        assertEquals(1600.0, fortyUp)
        assertTrue(
            oneCell < fortyUp,
            "une case ($oneCell) devrait coûter moins que 40 blocs ($fortyUp)",
        )
    }

    @Test
    @DisplayName("à colonne égale, seul le Y départage : c'est l'étage le plus proche qui gagne")
    fun `a colonne egale le Y departage`() {
        val ideal = BlockPos(5, 64, 5)
        val near = PortalCoordinates.distanceSquared(BlockPos(5, 70, 5), ideal, true, ratio)
        val far = PortalCoordinates.distanceSquared(BlockPos(5, 120, 5), ideal, true, ratio)
        assertTrue(near < far, "l'étage proche ($near) devrait battre l'étage loin ($far)")
    }

    @Test
    @DisplayName("le poids vertical pénalise l'altitude sans jamais toucher l'horizontal")
    fun `poids vertical`() {
        val ideal = BlockPos(0, 64, 0)
        val neutral = PortalCoordinates.distanceSquared(BlockPos(0, 74, 0), ideal, false, ratio, 1.0)
        val penalized = PortalCoordinates.distanceSquared(BlockPos(0, 74, 0), ideal, false, ratio, 3.0)
        assertTrue(penalized > neutral, "le poids 3.0 ($penalized) devrait pénaliser plus que 1.0 ($neutral)")

        val horizontalWeight1 = PortalCoordinates.distanceSquared(BlockPos(10, 64, 0), ideal, false, ratio, 1.0)
        val horizontalWeight3 = PortalCoordinates.distanceSquared(BlockPos(10, 64, 0), ideal, false, ratio, 3.0)
        assertEquals(horizontalWeight1, horizontalWeight3)
    }
}
