package fr.roumoulou.travellingdimension

import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le garde de l'étage 1 : il éprouve le harnais lui-même, pas le mod.
 *
 * `fabric-loader-junit` installe le chargeur Fabric par un écouteur de session de la
 * plateforme JUnit, ce qui rend les registres de Minecraft accessibles sans serveur. Le jour
 * où une montée de version casse cet amorçage, c'est ce fichier qui le dira.
 *
 * **Ce que l'étage 1 ne donne PAS** : les mixins. Le chargeur Knot ne s'installe pas ici, les
 * classes du jeu viennent du chargeur d'application, non transformées. Mesuré : `PortalShape`
 * chargée depuis un test ne porte aucune méthode de synthèse du mixin. Tout ce qui passe par
 * un mixin se vérifie en jeu ou par lecture du bytecode.
 */
class GameBootstrapTest {

    @Test
    @DisplayName("le jeu s'amorce et le registre des blocs répond")
    fun `le registre des blocs repond`() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        assertEquals(
            "minecraft:amethyst_block",
            BuiltInRegistries.BLOCK.getKey(Blocks.AMETHYST_BLOCK).toString(),
        )
    }
}
