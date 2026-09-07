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
        val uneCase = PortalCoordinates.distanceSquared(BlockPos(1, 64, 0), ideal, true, ratio)
        val quaranteEnHaut = PortalCoordinates.distanceSquared(BlockPos(0, 104, 0), ideal, true, ratio)

        // 1 case = 16 blocs d'OVERWORLD de marche, 40 blocs d'altitude = 40 blocs.
        assertEquals(256.0, uneCase)
        assertEquals(1600.0, quaranteEnHaut)
        assertTrue(
            uneCase < quaranteEnHaut,
            "une case ($uneCase) devrait coûter moins que 40 blocs ($quaranteEnHaut)",
        )
    }

    @Test
    @DisplayName("à colonne égale, seul le Y départage : c'est l'étage le plus proche qui gagne")
    fun `a colonne egale le Y departage`() {
        val ideal = BlockPos(5, 64, 5)
        val proche = PortalCoordinates.distanceSquared(BlockPos(5, 70, 5), ideal, true, ratio)
        val loin = PortalCoordinates.distanceSquared(BlockPos(5, 120, 5), ideal, true, ratio)
        assertTrue(proche < loin, "l'étage proche ($proche) devrait battre l'étage loin ($loin)")
    }

    @Test
    @DisplayName("le poids vertical pénalise l'altitude sans jamais toucher l'horizontal")
    fun `poids vertical`() {
        val ideal = BlockPos(0, 64, 0)
        val neutre = PortalCoordinates.distanceSquared(BlockPos(0, 74, 0), ideal, false, ratio, 1.0)
        val penalise = PortalCoordinates.distanceSquared(BlockPos(0, 74, 0), ideal, false, ratio, 3.0)
        assertTrue(penalise > neutre, "le poids 3.0 ($penalise) devrait pénaliser plus que 1.0 ($neutre)")

        val horizontal1 = PortalCoordinates.distanceSquared(BlockPos(10, 64, 0), ideal, false, ratio, 1.0)
        val horizontal3 = PortalCoordinates.distanceSquared(BlockPos(10, 64, 0), ideal, false, ratio, 3.0)
        assertEquals(horizontal1, horizontal3)
    }
}
