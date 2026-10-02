// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gametest

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.nether.NetherPortalGeometry
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.PortalShape

/**
 * Les portails du NETHER vanilla, là où seuls les mixins agissent : les bornes de taille de
 * `PortalShapeMixin`, et la création au point idéal de `NetherPortalBlockMixin`. C'est le seul
 * étage qui les voit : chargées depuis un test JUnit, les classes de Mojang ne portent aucun mixin.
 */
class NetherPortalGameTests {

    /** `netherPortalFreeSize` : coupé, le 1x1 est refusé par les bornes de Mojang ; actif, il s'allume. */
    @GameTest(maxTicks = 400)
    fun freeSizeOpensTheOneByOneFrame(helper: GameTestHelper) {
        val level = helper.level
        val corner = Harness.sector(6)
        Harness.forceAround(level, corner, 1, true)
        Harness.buildFrame(level, corner, Direction.Axis.X, 1, 1, Blocks.OBSIDIAN.defaultBlockState())

        val before = ConfigManager.current.copy()
        try {
            ConfigManager.apply(before.copy(netherPortalFreeSize = false))
            helper.assertFalse(PortalShape.findEmptyPortalShape(level, corner, Direction.Axis.X).isPresent, "réglage coupé, un cadre 1x1 n'est pas un portail : les bornes de Mojang")

            ConfigManager.apply(before.copy(netherPortalFreeSize = true))
            val shape = PortalShape.findEmptyPortalShape(level, corner, Direction.Axis.X)
            helper.assertTrue(shape.isPresent, "réglage actif, le cadre 1x1 s'allume : les dix injections de PortalShapeMixin")
            shape.get().createPortalBlocks(level)
            helper.assertTrue(level.getBlockState(corner).`is`(Blocks.NETHER_PORTAL), "le bloc de portail du NETHER est posé")
        } finally {
            ConfigManager.apply(before)
        }

        Harness.forceAround(level, corner, 1, false)
        helper.succeed()
    }

    /** `netherPortalPlacement` et `netherPortalCopySize` : le portail naît à la coordonnée exacte, à la taille du portail source. */
    @GameTest(maxTicks = 6000)
    fun crossingBuildsTheNetherPortalAtIdealPoint(helper: GameTestHelper) {
        val level = helper.level
        val nether = Harness.nether(helper)
        // Le coin en X ≡ 1 modulo 8 : le cochon a beau bouger dans les trois blocs de l'intérieur,
        // sa position divisée par 8 tombe toujours sur la même case. Vanilla convertit la position
        // de l'entité, pas l'ancre.
        val corner = Harness.sector(7).east(1)
        Harness.forceAround(level, corner, 1, true)

        Harness.buildFrame(level, corner, Direction.Axis.X, 3, 3, Blocks.OBSIDIAN.defaultBlockState())
        PortalShape.findEmptyPortalShape(level, corner, Direction.Axis.X)
            .orElseThrow { helper.assertionException("cadre d'obsidienne 3x3 refusé en ${corner.toShortString()}") }
            .createPortalBlocks(level)
        val anchor = corner.east(1)
        val expected = BlockPos(Math.floorDiv(corner.x, 8), anchor.y, Math.floorDiv(corner.z, 8))

        val uuid = Harness.spawnPig(helper, level, anchor).uuid
        TravellingDimension.LOGGER.info("[gametest] NETHER : ancre {} (OVERWORLD), point idéal {} (NETHER)", anchor.toShortString(), expected.toShortString())

        helper.succeedWhen {
            Harness.entityIn(nether, uuid) ?: throw helper.assertionException("le voyageur n'est pas encore dans le NETHER")
            val rectangle = NetherPortalGeometry.rectangleAt(nether, expected)
                ?: throw helper.assertionException("aucun bloc de portail du NETHER à l'ancre attendue ${expected.toShortString()} (NETHER)")
            helper.assertValueEqual(rectangle.axis1Size, 3, "la largeur du portail source est recopiée")
            helper.assertValueEqual(rectangle.axis2Size, 3, "la hauteur du portail source est recopiée")
            helper.assertValueEqual(NetherPortalGeometry.displayPos(rectangle, Direction.Axis.X), expected, "bâti au point idéal, et non là où le jeu trouve de la place")
            Harness.forceAround(level, corner, 1, false)
        }
    }
}
