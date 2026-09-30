package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.portal.PortalCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les bornes de la configuration et ses défauts.
 *
 * [TravelConfig] ne connaît pas Minecraft : aucun import du jeu dans ses 570 lignes. Ces
 * tests vivent donc dans le source set **pur**.
 *
 * L'invariant qui gouverne le tout : **une erreur de configuration est signalée, jamais
 * fatale**. `sanitized` corrige et rapporte, il ne lève pas.
 */
class TravelConfigTest {

    @Test
    @DisplayName("la config par défaut est symétrique, et une valeur brisée est corrigée")
    fun `symetrie de la config`() {
        val defaults = TravelConfig()
        assertEquals(
            PortalCoordinates.symmetricTravelRadius(defaults.searchRadiusOverworld, defaults.ratio),
            defaults.searchRadiusVoyage,
        )

        // Le contre-exemple du cahier des charges : 16 en VOYAGE au ratio 16 porte deux
        // fois plus loin à l'aller qu'au retour, donc un portail parasite naît.
        val problems = mutableListOf<String>()
        val fixed = TravelConfig(searchRadiusVoyage = 16).sanitized { problems.add(it) }
        assertEquals(8, fixed.searchRadiusVoyage)
        assertEquals(1, problems.size)
    }

    @Test
    @DisplayName("une configuration aberrante est corrigée, jamais fatale")
    fun `config aberrante corrigee`() {
        val problems = mutableListOf<String>()
        val fixed = TravelConfig(
            ratio = 0,
            searchRadiusOverworld = 99999,
            platformDepth = 99,
            clearanceHeight = -3,
            verticalWeight = -1.0,
            platformBlock = "",
            buildShiftMaxOffset = -5,
            rescueRadius = -1,
            inhabitedThreshold = -1L,
        ).sanitized { problems.add(it) }

        assertEquals(16, fixed.ratio)
        assertEquals(4096, fixed.searchRadiusOverworld)
        assertEquals(8, fixed.platformDepth)
        assertEquals(0, fixed.clearanceHeight)
        assertEquals(1.0, fixed.verticalWeight)
        assertEquals("minecraft:calcite", fixed.platformBlock)
        // Un rayon d'abri négatif faisait planter la création d'un portail : c'est la seule
        // valeur qui pouvait rendre une configuration fatale, et elle est bornée depuis.
        assertEquals(0, fixed.buildShiftMaxOffset)
        assertEquals(1, fixed.rescueRadius)
        assertEquals(0L, fixed.inhabitedThreshold)
        // Neuf valeurs corrigées, plus la symétrie de la portée recalculée derrière le rayon ramené à 4096.
        assertEquals(10, problems.size, "problèmes signalés : $problems")
    }

    @Test
    @DisplayName("les défauts du cahier des charges")
    fun `les defauts du cahier des charges`() {
        val defaults = TravelConfig()
        assertEquals(16, defaults.ratio)
        assertEquals(128, defaults.searchRadiusOverworld)
        assertEquals(8, defaults.searchRadiusVoyage)
        assertEquals(1.0, defaults.verticalWeight)
        assertEquals("minecraft:calcite", defaults.platformBlock)
        // Une seule couche, un seul bloc de débordement : la dalle sert à ne pas tomber en
        // sortant, pas à bâtir un socle.
        assertEquals(1, defaults.platformMargin)
        assertEquals(1, defaults.platformDepth)
        assertEquals(2, defaults.clearanceMargin)
        assertEquals(3, defaults.clearanceHeight)
        assertEquals("minecraft:amethyst_block", defaults.frameBlock)
        assertEquals(false, defaults.rememberEntryPortal)
        // Les deux systèmes de couleur se coupent séparément, et sont actifs par défaut.
        assertEquals(true, defaults.portalTints)
        assertEquals(true, defaults.netherPortalTints)
        // Les tailles hors vanilla sont COUPÉES par défaut : le mod ne change la règle des
        // portails chez personne sans qu'on l'ait demandé.
        assertEquals(false, defaults.portalFreeSize)
        assertEquals(false, defaults.netherPortalFreeSize)
        assertEquals(21, defaults.portalMaxSize)
        assertEquals(21, defaults.netherPortalMaxSize)
        // Une minute de présence cumulée, pas dix secondes : dix secondes s'accumulent en
        // traversant un chunk au galop.
        assertEquals(1200L, defaults.inhabitedThreshold)
        // La redstone a son propre veto, actif par défaut et indépendant du reste.
        assertEquals(8, defaults.redstoneVeto)
        assertEquals(29, defaults.redstoneBlocks.size)
    }

    @Test
    @DisplayName("la taille maximale d'un portail est bornée à 41")
    fun `taille maximale bornee a 41`() {
        val problems = mutableListOf<String>()
        val fixed = TravelConfig(portalMaxSize = 999, netherPortalMaxSize = 1)
            .sanitized { problems.add(it) }

        assertEquals(41, fixed.portalMaxSize)
        assertEquals(3, fixed.netherPortalMaxSize)
        assertEquals(2, problems.size)
    }
}
