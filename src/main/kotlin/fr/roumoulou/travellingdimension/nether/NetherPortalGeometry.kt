package fr.roumoulou.travellingdimension.nether

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.BlockUtil
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * Mesure d'un portail du NETHER vanilla.
 *
 * Aucune règle du mod ici : on reprend **le calcul de Mojang**, celui-là même que
 * `NetherPortalBlock.getExitPortal` utilise pour trouver le rectangle d'un portail
 * d'arrivée, avec ses mêmes bornes de 21 blocs et son même critère (des blocs de portail
 * d'état identique, donc de même axe). Un portail mesuré ici est donc exactement le
 * portail que le jeu voit.
 */
object NetherPortalGeometry {

    /** La borne de la mesure : 21 blocs d'intérieur dans les deux sens chez vanilla, jusqu'à 41 réglage actif. */
    private val MAX_SIZE: Int get() = NetherPortalSizes.max

    /**
     * Le rectangle de blocs de portail contenant [pos], ou `null` si ce n'est pas un
     * bloc de portail du Nether.
     */
    fun rectangleAt(level: LevelReader, pos: BlockPos): BlockUtil.FoundRectangle? {
        val state = level.getBlockState(pos)
        if (!state.`is`(Blocks.NETHER_PORTAL)) return null

        val axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS)
        return BlockUtil.getLargestRectangleAround(pos, axis, MAX_SIZE, Direction.Axis.Y, MAX_SIZE) { probe ->
            level.getBlockState(probe) == state
        }
    }

    /** L'axe du portail en [pos], ou `null` si ce n'est pas un bloc de portail du Nether. */
    fun axisAt(level: LevelReader, pos: BlockPos): Direction.Axis? {
        val state = level.getBlockState(pos)
        if (!state.`is`(Blocks.NETHER_PORTAL)) return null
        return state.getValue(BlockStateProperties.HORIZONTAL_AXIS)
    }

    /**
     * Le portail du NETHER auquel [pos] appartient : son bloc bas-milieu et son rectangle, ou
     * `null`. La mesure passe par le calcul de Mojang lui-même, donc ce qu'une commande
     * affiche est exactement ce que le jeu voit.
     */
    fun portalAt(level: LevelReader, pos: BlockPos): Pair<BlockPos, BlockUtil.FoundRectangle>? {
        val axis = axisAt(level, pos) ?: return null
        val rectangle = rectangleAt(level, pos) ?: return null
        return displayPos(rectangle, axis) to rectangle
    }

    /** Tous les blocs de portail du rectangle, pour poser la couleur sur le portail entier. */
    fun blocksOf(rectangle: BlockUtil.FoundRectangle, axis: Direction.Axis): List<BlockPos> {
        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        val blocks = ArrayList<BlockPos>(rectangle.axis1Size * rectangle.axis2Size)
        for (width in 0 until rectangle.axis1Size) {
            for (height in 0 until rectangle.axis2Size) {
                blocks.add(rectangle.minCorner.relative(along, width).above(height).immutable())
            }
        }
        return blocks
    }

    /**
     * Le bloc à annoncer au joueur pour désigner ce portail : le milieu de sa rangée du
     * bas, à `(largeur - 1) / 2` du coin minimal, comme l'ancre d'un portail de VOYAGE. Ici
     * ce point sert **seulement à l'affichage** : le calcul de Mojang travaille sur le
     * rectangle entier, jamais sur un bloc élu.
     */
    fun displayPos(rectangle: BlockUtil.FoundRectangle, axis: Direction.Axis): BlockPos {
        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        return rectangle.minCorner.relative(along, (rectangle.axis1Size - 1) / 2).immutable()
    }
}
