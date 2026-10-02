// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
import java.util.function.Predicate

/**
 * Forme d'un portail de voyage : cadre rectangulaire vertical.
 *
 * Port de `net.minecraft.world.level.portal.PortalShape` (26.2), avec :
 * - cadre exclusivement dans le bloc configuré ([PortalFrame], améthyste par défaut :
 *   tout mélange rend le cadre invalide),
 * - blocs de portail [ModBlocks.TRAVEL_PORTAL] à l'intérieur,
 * - largeur intérieure de 1 à 21, **paire ou impaire**,
 * - hauteur intérieure de 3 à 21,
 * - et, quand `portalFreeSize` est actif, de **1x1** jusqu'à `portalMaxSize`.
 *
 * ## Toutes les largeurs sont acceptées
 *
 * Un portail 2x3, celui du NETHER, fonctionne ici tel quel. La position d'un portail est
 * son [centre], et cette notion est définie pour les deux parités : c'est le bloc de la
 * rangée du bas situé à `(largeur - 1) / 2` du bord minimal, en division entière. Largeur
 * impaire, c'est le bloc du milieu exactement ; largeur paire, celui de gauche des deux
 * blocs centraux, seul point d'arbitraire de la règle.
 *
 * L'ancre reste au CENTRE et jamais à un bord, et c'est ce qui compte : deux portails que
 * le joueur voit au même endroit ont des ancres voisines quelles que soient leurs tailles.
 * Un portail de 9 de large et un portail de 3 de large posés au même endroit ont la même
 * ancre ; avec une ancre au bord ils seraient séparés de 3 blocs, soit 48 blocs
 * d'OVERWORLD une fois la conversion faite.
 */
class TravelPortalShape private constructor(
    val axis: Direction.Axis,
    val numPortalBlocks: Int,
    private val rightDir: Direction,
    /** Coin bas de l'intérieur, côté opposé à [rightDir] (convention vanilla). */
    val bottomLeft: BlockPos,
    val width: Int,
    val height: Int,
) {

    companion object {

        /**
         * **Les bornes d'un portail de voyage**, lues dans la configuration à chaque mesure.
         *
         * Réglage `portalFreeSize` coupé, ce sont les valeurs historiques : de 1 à 21 de large,
         * de 3 à 21 de haut. Activé, la hauteur minimale tombe à 1, donc le **1x1** devient
         * possible, et le maximum devient `portalMaxSize`, jusqu'à 41.
         *
         * Ces bornes ne servent pas qu'à l'allumage : elles servent aussi à REVALIDER un
         * portail quand un bloc voisin change. Couper le réglage éteint donc les portails
         * devenus hors bornes, et c'est écrit dans la documentation.
         */
        private val free: Boolean get() = ConfigManager.current.portalFreeSize

        /** La largeur minimale : 1 dans tous les cas, un portail d'un bloc a toujours été permis. */
        const val MIN_WIDTH = 1

        val MAX_WIDTH: Int get() = if (free) ConfigManager.current.portalMaxSize else 21

        /** La hauteur minimale : 3 comme vanilla, 1 quand les tailles libres sont permises. */
        val MIN_HEIGHT: Int get() = if (free) 1 else 3

        val MAX_HEIGHT: Int get() = if (free) ConfigManager.current.portalMaxSize else 21

        /** Compteur mutable local (équivalent MutableInt de vanilla, en idiome Kotlin). */
        private class Counter(var value: Int = 0)

        /** Le cadre : uniquement le bloc configuré ([PortalFrame]). */
        private fun isFrame(state: BlockState): Boolean = PortalFrame.matches(state)

        /** L'intérieur : air ou blocs de portail de voyage déjà posés. */
        private fun isEmpty(state: BlockState): Boolean =
            state.isAir || state.`is`(ModBlocks.TRAVEL_PORTAL)

        /** Cherche un cadre valide et VIDE (pour l'allumage à l'igniter). */
        fun findEmptyPortalShape(level: LevelAccessor, pos: BlockPos, preferredAxis: Direction.Axis): Optional<TravelPortalShape> =
            findPortalShape(level, pos, { shape -> shape.isValid() && shape.numPortalBlocks == 0 }, preferredAxis)

        /** Cherche un cadre satisfaisant [isValid], en essayant les deux axes. */
        fun findPortalShape(
            level: LevelAccessor,
            pos: BlockPos,
            isValid: Predicate<TravelPortalShape>,
            preferredAxis: Direction.Axis,
        ): Optional<TravelPortalShape> {
            val firstTry = Optional.of(findAnyShape(level, pos, preferredAxis)).filter(isValid)
            if (firstTry.isPresent) return firstTry

            val otherAxis = if (preferredAxis == Direction.Axis.X) Direction.Axis.Z else Direction.Axis.X
            return Optional.of(findAnyShape(level, pos, otherAxis)).filter(isValid)
        }

        /** Mesure la forme présente autour de [pos] sur l'axe donné (peut être invalide). */
        fun findAnyShape(level: BlockGetter, pos: BlockPos, axis: Direction.Axis): TravelPortalShape {
            val rightDir = if (axis == Direction.Axis.X) Direction.WEST else Direction.SOUTH
            val bottomLeft = calculateBottomLeft(level, rightDir, pos)
                ?: return TravelPortalShape(axis, 0, rightDir, pos, 0, 0)

            val width = calculateWidth(level, bottomLeft, rightDir)
            if (width == 0) return TravelPortalShape(axis, 0, rightDir, bottomLeft, 0, 0)

            val portalBlockCount = Counter()
            val height = calculateHeight(level, bottomLeft, rightDir, width, portalBlockCount)
            return TravelPortalShape(axis, portalBlockCount.value, rightDir, bottomLeft, width, height)
        }

        private fun calculateBottomLeft(level: BlockGetter, rightDir: Direction, startPos: BlockPos): BlockPos? {
            var pos = startPos
            val minY = maxOf(level.minY, pos.y - MAX_HEIGHT)
            while (pos.y > minY && isEmpty(level.getBlockState(pos.below()))) {
                pos = pos.below()
            }
            val leftDir = rightDir.opposite
            val edge = getDistanceUntilEdgeAboveFrame(level, pos, leftDir) - 1
            return if (edge < 0) null else pos.relative(leftDir, edge)
        }

        private fun calculateWidth(level: BlockGetter, bottomLeft: BlockPos, rightDir: Direction): Int {
            val width = getDistanceUntilEdgeAboveFrame(level, bottomLeft, rightDir)
            return if (width in MIN_WIDTH..MAX_WIDTH) width else 0
        }

        private fun getDistanceUntilEdgeAboveFrame(level: BlockGetter, pos: BlockPos, direction: Direction): Int {
            val cursor = BlockPos.MutableBlockPos()
            for (distance in 0..MAX_WIDTH) {
                cursor.set(pos).move(direction, distance)
                val state = level.getBlockState(cursor)
                if (!isEmpty(state)) {
                    if (isFrame(state)) return distance
                    break
                }
                // Le sol sous chaque case intérieure doit être du cadre
                val below = level.getBlockState(cursor.move(Direction.DOWN))
                if (!isFrame(below)) break
            }
            return 0
        }

        private fun calculateHeight(
            level: BlockGetter,
            bottomLeft: BlockPos,
            rightDir: Direction,
            width: Int,
            portalBlockCount: Counter,
        ): Int {
            val cursor = BlockPos.MutableBlockPos()
            val height = getDistanceUntilTop(level, bottomLeft, rightDir, cursor, width, portalBlockCount)
            return if (height in MIN_HEIGHT..MAX_HEIGHT && hasTopFrame(level, bottomLeft, rightDir, cursor, width, height)) height else 0
        }

        private fun hasTopFrame(
            level: BlockGetter,
            bottomLeft: BlockPos,
            rightDir: Direction,
            cursor: BlockPos.MutableBlockPos,
            width: Int,
            height: Int,
        ): Boolean {
            for (i in 0 until width) {
                val framePos = cursor.set(bottomLeft).move(Direction.UP, height).move(rightDir, i)
                if (!isFrame(level.getBlockState(framePos))) return false
            }
            return true
        }

        private fun getDistanceUntilTop(
            level: BlockGetter,
            bottomLeft: BlockPos,
            rightDir: Direction,
            cursor: BlockPos.MutableBlockPos,
            width: Int,
            portalBlockCount: Counter,
        ): Int {
            for (height in 0 until MAX_HEIGHT) {
                // Montant gauche
                cursor.set(bottomLeft).move(Direction.UP, height).move(rightDir, -1)
                if (!isFrame(level.getBlockState(cursor))) return height

                // Montant droit
                cursor.set(bottomLeft).move(Direction.UP, height).move(rightDir, width)
                if (!isFrame(level.getBlockState(cursor))) return height

                // Intérieur : air ou portail
                for (i in 0 until width) {
                    cursor.set(bottomLeft).move(Direction.UP, height).move(rightDir, i)
                    val state = level.getBlockState(cursor)
                    if (!isEmpty(state)) return height
                    if (state.`is`(ModBlocks.TRAVEL_PORTAL)) portalBlockCount.value++
                }
            }
            return MAX_HEIGHT
        }
    }

    /** Largeur dans les bornes, quelle que soit sa parité. */
    fun hasValidWidth(): Boolean = width in MIN_WIDTH..MAX_WIDTH

    fun isValid(): Boolean = hasValidWidth() && height in MIN_HEIGHT..MAX_HEIGHT

    /** Cadre valide ET entièrement rempli de blocs de portail. */
    fun isComplete(): Boolean = isValid() && numPortalBlocks == width * height

    /** Remplit l'intérieur du cadre avec les blocs de portail (flag 18 comme vanilla). */
    fun createPortalBlocks(level: LevelAccessor) {
        val portalState = ModBlocks.TRAVEL_PORTAL.defaultBlockState().setValue(TravelPortalBlock.AXIS, axis)
        BlockPos.betweenClosed(
            bottomLeft,
            bottomLeft.relative(Direction.UP, height - 1).relative(rightDir, width - 1)
        ).forEach { pos -> level.setBlock(pos, portalState, 18) }
    }

    /** Coin minimal (coordonnées les plus basses) de l'intérieur du portail. */
    fun minCorner(): BlockPos =
        if (rightDir.axisDirection == Direction.AxisDirection.NEGATIVE) {
            bottomLeft.relative(rightDir, width - 1)
        } else {
            bottomLeft
        }

    /**
     * **L'ANCRE** du portail : le bloc de la rangée du bas situé à `(largeur - 1) / 2` du
     * coin minimal, en division entière.
     *
     * C'est la position canonique de tout le mod. C'est elle que la transformation de
     * coordonnées divise ou multiplie, c'est sur elle que se mesure la distance à un point
     * idéal, et c'est elle qu'on compare pour savoir si deux portails sont « le même ».
     *
     * L'ancre appartient au PORTAIL, pas au voyageur : deux entités qui traversent le même
     * portail par ses deux bords calculent le même point idéal et rejoignent la même
     * destination. Leur position d'entrée ne décide que de leur position DANS le portail
     * d'arrivée.
     *
     * Largeur impaire, c'est le bloc du milieu exactement, et un portail et son image
     * miroir ont donc la même ancre. Largeur paire, c'est celui de gauche des deux blocs
     * centraux : l'ancre reste au centre, ce qui est la propriété qui compte, mais le
     * miroir la décale d'un bloc.
     */
    fun centre(): BlockPos {
        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        return minCorner().relative(along, (width - 1) / 2)
    }
}
