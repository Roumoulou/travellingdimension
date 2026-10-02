// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gametest

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.portal.PortalFrame
import fr.roumoulou.travellingdimension.portal.PortalLocks
import fr.roumoulou.travellingdimension.portal.PortalTint
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import fr.roumoulou.travellingdimension.portal.TravelPortalShape
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Les portails de VOYAGE dans un vrai serveur : la forme et l'ancre, puis les trois cas de
 * référence du vocabulaire du projet (rien à portée, un portail à portée, hors de portée), le
 * filtre de couleur et le verrou.
 *
 * Chaque test de traversée pose un cadre dans son secteur de l'OVERWORLD, l'allume, y lâche un
 * cochon et attend de le retrouver dans VOYAGE, puis lit ce que le mod a bâti à l'ancre attendue.
 * Le point idéal se recalcule ici de tête, comme le ferait un joueur : partie entière de l'ancre
 * divisée par le ratio, le Y inchangé.
 */
class TravelPortalGameTests {

    private val ratio: Int get() = ConfigManager.current.ratio

    /** Le point idéal d'une ancre de l'OVERWORLD : division plancher par le ratio, Y inchangé. */
    private fun idealOf(anchor: BlockPos): BlockPos =
        BlockPos(Math.floorDiv(anchor.x, ratio), anchor.y, Math.floorDiv(anchor.z, ratio))

    @GameTest(maxTicks = 400)
    fun frameShapesAndAnchors(helper: GameTestHelper) {
        val level = helper.level
        val sector = Harness.sector(0)
        Harness.forceAround(level, sector, 1, true)

        // Largeurs 1 à 5, hauteur 3, sur l'axe X : toutes valides, l'ancre à (largeur - 1) / 2 du coin minimal.
        for (width in 1..5) {
            val corner = sector.offset(0, 0, width * 8)
            Harness.buildFrame(level, corner, Direction.Axis.X, width, 3, PortalFrame.state)

            val shape = TravelPortalShape.findEmptyPortalShape(level, corner, Direction.Axis.X)
            helper.assertTrue(shape.isPresent, "un cadre de $width x 3 est un cadre valide")
            val found = shape.get()
            helper.assertValueEqual(found.width, width, "largeur mesurée")
            helper.assertValueEqual(found.height, 3, "hauteur mesurée")
            helper.assertValueEqual(found.centre(), corner.relative(Direction.EAST, (width - 1) / 2), "l'ancre est à (largeur - 1) / 2 du coin minimal")

            found.createPortalBlocks(level)
            helper.assertTrue(TravelPortalShape.findAnyShape(level, corner, Direction.Axis.X).isComplete(), "allumé, le portail de $width x 3 est complet")
            val retrieved = TravelPortalPlacer.completePortalAt(level, found.centre())
                ?: throw helper.assertionException("completePortalAt ne retrouve pas le portail de $width x 3 par son ancre")
            helper.assertValueEqual(retrieved.width, width, "completePortalAt le retrouve par son ancre")
        }

        // Un bloc étranger dans le cadre, et rien ne s'allume : le cadre n'accepte qu'un seul bloc.
        val mixed = sector.offset(0, 0, 64)
        Harness.buildFrame(level, mixed, Direction.Axis.X, 3, 3, PortalFrame.state)
        level.setBlock(mixed.relative(Direction.EAST, 3).above(1), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL)
        helper.assertFalse(TravelPortalShape.findEmptyPortalShape(level, mixed, Direction.Axis.X).isPresent, "un cadre mélangé n'est pas un cadre")

        // Un montant retiré éteint le portail en cascade.
        val lit = sector.offset(0, 0, 24)
        level.setBlock(lit.relative(Direction.WEST, 1).above(1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        helper.runAfterDelay(2) {
            helper.assertFalse(TravelPortalShape.findAnyShape(level, lit, Direction.Axis.X).isComplete(), "un montant retiré éteint le portail")
            Harness.forceAround(level, sector, 1, false)
            helper.succeed()
        }
    }

    /** Cas 1 du vocabulaire : rien à portée, le portail est créé au point idéal, à la taille et sur l'axe du portail source. */
    @GameTest(maxTicks = 6000)
    fun crossingBuildsAtIdealPoint(helper: GameTestHelper) {
        val level = helper.level
        val travel = Harness.travel(helper)
        val corner = Harness.sector(1)
        Harness.forceAround(level, corner, 1, true)

        Harness.buildFrame(level, corner, Direction.Axis.X, 3, 3, PortalFrame.state)
        val source = Harness.lightTravelPortal(helper, level, corner, Direction.Axis.X)
        val anchor = source.centre()
        val ideal = idealOf(anchor)
        helper.assertTrue(TravelPortalPlacer.findNearest(travel, ideal, true, ConfigManager.current) == null, "le secteur de VOYAGE est vierge")

        val uuid = Harness.spawnPig(helper, level, anchor).uuid
        TravellingDimension.LOGGER.info("[gametest] cas 1 : ancre {} (OVERWORLD), point idéal {} (VOYAGE)", anchor.toShortString(), ideal.toShortString())

        helper.succeedWhen {
            val arrived = Harness.entityIn(travel, uuid) ?: throw helper.assertionException("le voyageur n'est pas encore dans VOYAGE")
            val built = TravelPortalPlacer.completePortalAt(travel, ideal)
                ?: throw helper.assertionException("aucun portail complet au point idéal ${ideal.toShortString()} (VOYAGE)")
            helper.assertValueEqual(built.width, 3, "la largeur du portail source est recopiée")
            helper.assertValueEqual(built.height, 3, "la hauteur du portail source est recopiée")
            helper.assertValueEqual(built.axis, Direction.Axis.X, "l'axe du portail source est recopié")
            helper.assertTrue(arrived.blockPosition().distManhattan(ideal) <= 4, "le voyageur est sorti par ce portail, en ${arrived.blockPosition().toShortString()}")
            Harness.forceAround(level, corner, 1, false)
        }
    }

    /** Cas 2 : un portail à 6 blocs (VOYAGE) du point idéal est rejoint, et rien n'est créé. */
    @GameTest(maxTicks = 6000)
    fun crossingJoinsPortalInReach(helper: GameTestHelper) {
        val level = helper.level
        val travel = Harness.travel(helper)
        val corner = Harness.sector(2)
        Harness.forceAround(level, corner, 1, true)

        Harness.buildFrame(level, corner, Direction.Axis.X, 3, 3, PortalFrame.state)
        val source = Harness.lightTravelPortal(helper, level, corner, Direction.Axis.X)
        val ideal = idealOf(source.centre())

        // L'existant : ancre à 6 blocs (VOYAGE) du point idéal, soit 96 blocs (OVERWORLD), dans les 8 de portée.
        val existingCorner = ideal.offset(5, 0, 0)
        Harness.buildFrame(travel, existingCorner, Direction.Axis.X, 3, 3, PortalFrame.state)
        val existing = Harness.lightTravelPortal(helper, travel, existingCorner, Direction.Axis.X).centre()
        helper.assertValueEqual(existing, ideal.offset(6, 0, 0), "l'ancre de l'existant")

        val uuid = Harness.spawnPig(helper, level, source.centre()).uuid
        TravellingDimension.LOGGER.info("[gametest] cas 2 : point idéal {} (VOYAGE), existant en {} (VOYAGE)", ideal.toShortString(), existing.toShortString())

        helper.succeedWhen {
            val arrived = Harness.entityIn(travel, uuid) ?: throw helper.assertionException("le voyageur n'est pas encore dans VOYAGE")
            helper.assertTrue(TravelPortalPlacer.completePortalAt(travel, ideal) == null, "aucun portail créé au point idéal : l'existant l'emporte")
            helper.assertTrue(arrived.blockPosition().distManhattan(existing) <= 4, "le voyageur est sorti par l'existant, en ${arrived.blockPosition().toShortString()}")
            Harness.forceAround(level, corner, 1, false)
        }
    }

    /** Cas 3 : un portail à 12 blocs (VOYAGE) est hors de portée, un portail neuf est créé au point idéal. */
    @GameTest(maxTicks = 6000)
    fun crossingOutOfReachBuildsANewPortal(helper: GameTestHelper) {
        val level = helper.level
        val travel = Harness.travel(helper)
        val corner = Harness.sector(3)
        Harness.forceAround(level, corner, 1, true)

        Harness.buildFrame(level, corner, Direction.Axis.X, 3, 3, PortalFrame.state)
        val source = Harness.lightTravelPortal(helper, level, corner, Direction.Axis.X)
        val ideal = idealOf(source.centre())

        val farCorner = ideal.offset(11, 0, 0)
        Harness.buildFrame(travel, farCorner, Direction.Axis.X, 3, 3, PortalFrame.state)
        val far = Harness.lightTravelPortal(helper, travel, farCorner, Direction.Axis.X).centre()
        helper.assertValueEqual(far, ideal.offset(12, 0, 0), "l'ancre du portail hors de portée")

        val uuid = Harness.spawnPig(helper, level, source.centre()).uuid
        TravellingDimension.LOGGER.info("[gametest] cas 3 : point idéal {} (VOYAGE), portail hors de portée en {} (VOYAGE)", ideal.toShortString(), far.toShortString())

        helper.succeedWhen {
            val arrived = Harness.entityIn(travel, uuid) ?: throw helper.assertionException("le voyageur n'est pas encore dans VOYAGE")
            helper.assertTrue(TravelPortalPlacer.completePortalAt(travel, ideal) != null, "un portail neuf est créé au point idéal")
            helper.assertTrue(TravelPortalPlacer.completePortalAt(travel, far) != null, "le portail hors de portée est intact")
            helper.assertTrue(arrived.blockPosition().distManhattan(ideal) <= 4, "le voyageur est sorti par le portail neuf, en ${arrived.blockPosition().toShortString()}")
            Harness.forceAround(level, corner, 1, false)
        }
    }

    /** Rang 1 : la couleur filtre les candidats. Un portail neutre plus proche perd contre le portail de la même couleur. */
    @GameTest(maxTicks = 6000)
    fun dyeFiltersTheCandidates(helper: GameTestHelper) {
        val level = helper.level
        val travel = Harness.travel(helper)
        val corner = Harness.sector(4)
        Harness.forceAround(level, corner, 1, true)

        Harness.buildFrame(level, corner, Direction.Axis.X, 3, 3, PortalFrame.state)
        val source = Harness.lightTravelPortal(helper, level, corner, Direction.Axis.X)
        Harness.paint(level, source, PortalTint.RED)
        val ideal = idealOf(source.centre())

        // Le neutre à 2 blocs (VOYAGE), le rouge à 6 : sans couleur le neutre gagnerait.
        Harness.buildFrame(travel, ideal.offset(1, 0, 0), Direction.Axis.X, 3, 3, PortalFrame.state)
        val neutral = Harness.lightTravelPortal(helper, travel, ideal.offset(1, 0, 0), Direction.Axis.X).centre()
        Harness.buildFrame(travel, ideal.offset(5, 0, 0), Direction.Axis.X, 3, 3, PortalFrame.state)
        val redShape = Harness.lightTravelPortal(helper, travel, ideal.offset(5, 0, 0), Direction.Axis.X)
        Harness.paint(travel, redShape, PortalTint.RED)
        val red = redShape.centre()
        val unfiltered = TravelPortalPlacer.findNearest(travel, ideal, true, ConfigManager.current)
            ?: throw helper.assertionException("aucun candidat à portée du point idéal")
        helper.assertValueEqual(unfiltered.centre, neutral, "sans filtre, le neutre est le plus proche")
        val filtered = TravelPortalPlacer.findNearest(travel, ideal, true, ConfigManager.current, PortalTint.RED)
            ?: throw helper.assertionException("aucun candidat rouge à portée du point idéal")
        helper.assertValueEqual(filtered.centre, red, "filtré par le rouge, c'est le rouge")

        val uuid = Harness.spawnPig(helper, level, source.centre()).uuid
        TravellingDimension.LOGGER.info("[gametest] couleur : point idéal {} (VOYAGE), neutre en {}, rouge en {} (VOYAGE)", ideal.toShortString(), neutral.toShortString(), red.toShortString())

        helper.succeedWhen {
            val arrived = Harness.entityIn(travel, uuid) ?: throw helper.assertionException("le voyageur n'est pas encore dans VOYAGE")
            helper.assertTrue(arrived.blockPosition().distManhattan(red) <= 4, "le voyageur est sorti par le portail rouge, en ${arrived.blockPosition().toShortString()}")
            helper.assertTrue(TravelPortalPlacer.completePortalAt(travel, ideal) == null, "rien n'est créé au point idéal")
            Harness.forceAround(level, corner, 1, false)
        }
    }

    /** Un portail verrouillé interdit d'allumer à portée, 128 blocs (OVERWORLD), et rien au-delà. */
    @GameTest(maxTicks = 400)
    fun lockForbidsLightingWithinReach(helper: GameTestHelper) {
        val level = helper.level
        val corner = Harness.sector(5)
        Harness.forceAround(level, corner, 1, true)

        Harness.buildFrame(level, corner, Direction.Axis.X, 3, 3, PortalFrame.state)
        val anchor = Harness.lightTravelPortal(helper, level, corner, Direction.Axis.X).centre()
        val near = anchor.offset(100, 0, 0)
        val far = anchor.offset(200, 0, 0)

        helper.assertTrue(PortalLocks.lock(level, anchor, PortalLocks.CONSOLE_OWNER, "gametest"), "le verrou se pose")
        helper.assertTrue(PortalLocks.lockAt(level, anchor) != null, "le verrou se relit sur son ancre")
        helper.assertTrue(PortalLocks.blockingLock(level, near) != null, "à 100 blocs (OVERWORLD), le verrou interdit d'allumer")
        helper.assertTrue(PortalLocks.blockingLock(level, far) == null, "à 200 blocs (OVERWORLD), hors des 128 de portée, le terrain est libre")
        helper.assertTrue(PortalLocks.unlock(level, anchor), "le verrou se retire")
        helper.assertTrue(PortalLocks.blockingLock(level, near) == null, "sans verrou, le terrain est libre")

        Harness.forceAround(level, corner, 1, false)
        helper.succeed()
    }
}
