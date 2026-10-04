// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.nether

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.portal.PortalGround
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import fr.roumoulou.travellingdimension.portal.forEachInBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.BlockUtil
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.structure.BoundingBox

/**
 * **Le portail du NETHER, créé au point idéal.**
 *
 * ## Ce que fait vanilla, et pourquoi on ne le garde pas
 *
 * `PortalForcer.createPortal` ne bâtit pas là où le calcul l'envoie : il balaie une large
 * zone autour du point converti à la recherche d'un endroit **jugé convenable**, un creux
 * assez dégagé pour loger un cadre, et se rabat sur un emplacement approximatif s'il n'en
 * trouve pas. Le portail peut donc naître très loin du point calculé, sans qu'on puisse ni
 * le prévoir ni l'expliquer. C'est la principale raison de la réputation d'imprévisibilité
 * du NETHER.
 *
 * Ici, le portail naît **exactement au point idéal**, et c'est le terrain qui s'adapte :
 * dégagement autour, plateforme dessous. La même règle que dans la dimension de VOYAGE, avec
 * les mêmes réglages, parce que c'est le même geste.
 *
 * ## Ce qui est conservé de vanilla
 *
 * Le cadre reste en **obsidienne**, l'intérieur en `minecraft:nether_portal`, et l'axe reste
 * celui du portail d'où l'on part. Le portail créé est donc un portail du NETHER ordinaire,
 * que le jeu reconnaît, indexe comme point d'intérêt et éteint normalement.
 *
 * ## La taille, elle, est celle du portail d'où l'on part
 *
 * Vanilla crée toujours un 2x3, la taille minimale, quelle que soit la porte franchie : une
 * arche de 9 de large répond par une fente de 2, et rien ne passe plus. C'est la règle de
 * VOYAGE qui est appliquée ici, un portail étant un élément entier
 * (`netherPortalCopySize`, défaut true). Coupé, la taille redevient le 2x3 de vanilla et
 * seul l'EMPLACEMENT change.
 *
 * La mesure passe par [NetherPortalGeometry.rectangleAt], donc par le calcul de Mojang
 * lui-même, lu dans le niveau de l'entité : à cet instant elle n'a pas encore bougé.
 *
 * ## Le point idéal, ramené dans le monde
 *
 * Le Y est clampé entre un plancher qui laisse la place à la dalle et un plafond qui tient
 * **sous le toit**. Le toit du NETHER, au-dessus de la bedrock, est un terrain de jeu de
 * joueur : on y bâtit son portail à la main si on veut, mais **le mod n'y crée jamais rien**.
 * Voir [PortalGround.underRoof], qui descend tant que le cadre mordrait dans l'indestructible,
 * la bedrock du NETHER étant bruitée sur plusieurs couches.
 */
object NetherPortalBuilder {

    /**
     * La taille de repli : celle que vanilla bâtit toujours, intérieur 2 de large et 3 de haut.
     * C'est ce qu'on pose dès qu'il manque quoi que ce soit pour mesurer la source.
     */
    private const val FALLBACK_WIDTH = 2
    private const val FALLBACK_HEIGHT = 3

    /**
     * Bâtit un portail du NETHER dont l'**ancre** tombe sur [target], et rend son rectangle
     * au format attendu par le jeu.
     *
     * L'ancre suit la convention du mod : rangée du bas, à `(largeur - 1) / 2` du coin
     * minimal. En largeur 2 c'est donc le bloc de plus petite coordonnée sur l'axe.
     *
     * [entity] et [entryPos] désignent le portail d'où l'on part, dont la taille est recopiée
     * quand `netherPortalCopySize` le demande. Les deux sont nullables pour que le
     * constructeur reste appelable sans contexte de voyage, auquel cas c'est le 2x3 de
     * vanilla qui est bâti.
     */
    fun build(
        level: ServerLevel,
        target: BlockPos,
        axis: Direction.Axis,
        entity: Entity? = null,
        entryPos: BlockPos? = null,
    ): BlockUtil.FoundRectangle {
        val config = ConfigManager.current
        val (width, height) = sourceSize(config, entity, entryPos)

        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        val perpendicular = if (axis == Direction.Axis.X) Direction.SOUTH else Direction.EAST

        /** Le coin minimal du cadre à une altitude donnée : l'ancre, ramenée sur l'axe. */
        fun cornerAt(atY: Int): BlockPos =
            BlockPos(target.x, atY, target.z).relative(along, -((width - 1) / 2))

        /** Le CADRE seul, coins compris : ce qui ne doit jamais mordre dans l'indestructible. */
        fun frameBoxAt(atY: Int): BoundingBox {
            val at = cornerAt(atY)
            val low = at.relative(along, -1).below()
            val high = at.relative(along, width).above(height)
            return BoundingBox(
                minOf(low.x, high.x), low.y, minOf(low.z, high.z),
                maxOf(low.x, high.x), high.y, maxOf(low.z, high.z),
            )
        }

        /** L'emprise que ce portail occuperait à une altitude donnée. */
        fun footprintAt(atY: Int): BoundingBox {
            val at = cornerAt(atY)
            val margin = maxOf(config.clearanceMargin, config.platformMargin)
            val low = at.relative(along, -1 - margin).relative(perpendicular, -margin)
                .below(maxOf(config.platformDepth, 1))
            val high = at.relative(along, width + margin).relative(perpendicular, margin)
                .above(height - 1 + config.clearanceHeight)
            return BoundingBox(
                minOf(low.x, high.x), minOf(low.y, high.y), minOf(low.z, high.z),
                maxOf(low.x, high.x), maxOf(low.y, high.y), maxOf(low.z, high.z),
            )
        }

        // Le plafond LOGIQUE, pas le plafond du monde : dans le NETHER la bedrock du toit est
        // à 127 alors que le monde monte plus haut, et vanilla s'arrête là aussi. Puis on
        // descend sous le toit pour de bon : JAMAIS de portail créé au-dessus de la bedrock,
        // cet étage-là n'appartient qu'aux joueurs qui y bâtissent à la main.
        val floor = level.minY + config.platformDepth + 1
        val logicalTop = minOf(level.maxY, level.minY + level.logicalHeight - 1) - height - 1
        val top = maxOf(floor, PortalGround.underRoof(level, logicalTop, floor, ::frameBoxAt))
        if (target.y > top) {
            TravellingDimension.LOGGER.info(
                "Ideal point at Y={}: brought back under the roof, anchor at Y={}", target.y, top
            )
        }

        val y = Mth.clamp(target.y, floor, top)
        val wanted = BlockPos(target.x, y, target.z)

        // ÉPARGNER CE QUI EST BÂTI, exactement comme dans VOYAGE et avec les mêmes réglages,
        // veto de la redstone compris : une installation refuse le terrain sans regarder la
        // fréquentation, et la recherche d'altitude porte alors sur toute la hauteur utile.
        var anchor = wanted
        val redstone = PortalGround.hasRedstoneWorks(level, footprintAt(wanted.y), config)
        if (PortalGround.refuses(level, footprintAt(wanted.y), config)) {
            val reach = if (redstone) top - floor else config.buildShiftMaxOffset
            val free = PortalGround.clearAltitude(level, config, wanted.y, floor, top, reach, ::footprintAt)

            if (free != null && free != wanted.y) {
                anchor = BlockPos(wanted.x, free, wanted.z)
                TravellingDimension.LOGGER.info(
                    "{} found at {}: Nether portal shifted to Y={} ({} blocks)",
                    if (redstone) "Redstone machine" else "Build",
                    wanted.toShortString(), free, free - wanted.y
                )
            } else if (free == null) {
                TravellingDimension.LOGGER.warn(
                    "{} found at {} and no free altitude within {} blocks: building in place",
                    if (redstone) "Redstone machine" else "Build",
                    wanted.toShortString(), reach
                )
            }
        }
        PortalGround.rescueContainers(level, footprintAt(anchor.y), anchor, config)

        val corner = cornerAt(anchor.y)

        val air = Blocks.AIR.defaultBlockState()
        val frame = Blocks.OBSIDIAN.defaultBlockState()
        val platform = TravelPortalPlacer.platformState(config)
        val portal = Blocks.NETHER_PORTAL.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_AXIS, axis)

        TravellingDimension.LOGGER.info(
            "Nether portal created at the ideal point, anchor at {} (axis {}, interior {}x{}) in {}",
            anchor.toShortString(), axis, width, height, level.dimension().identifier()
        )

        /**
         * Jamais touché : un portail voisin **allumé**, et l'indestructible.
         *
         * **L'obsidienne n'est PAS protégée, et c'est voulu.** Un portail du NETHER allumé ne
         * risque rien, puisqu'un portail trouvé à portée est rejoint au lieu d'être doublé, et
         * que le dégagement ne s'étend qu'à quelques blocs. Restent l'obsidienne d'un cadre non
         * allumé et l'obsidienne de décoration, que le dégagement efface. C'est la différence
         * assumée avec VOYAGE, où le bloc de cadre est protégé, et elle est écrite dans la
         * documentation plutôt que corrigée : protéger toute obsidienne empêcherait de dégager
         * proprement une arrivée posée dans une coulée.
         */
        fun isProtected(pos: BlockPos): Boolean {
            val state = level.getBlockState(pos)
            return state.`is`(Blocks.NETHER_PORTAL) || state.getDestroySpeed(level, pos) < 0f
        }

        // ── 1. Le dégagement, pour ne jamais sortir encastré dans la roche ni dans la lave.
        val cm = config.clearanceMargin
        forEachInBox(
            corner.relative(along, -1 - cm).relative(perpendicular, -cm),
            corner.relative(along, width + cm).relative(perpendicular, cm)
                .above(height - 1 + config.clearanceHeight),
        ) { pos ->
            val state = level.getBlockState(pos)
            val solid = !state.isAir
            val fluid = !state.fluidState.isEmpty
            if (!isProtected(pos) && (solid || (fluid && config.removeFluids))) {
                level.setBlock(pos, air, 2)
            }
        }

        // ── 2. La plateforme, qui ne remplit QUE les blocs remplaçables : une construction
        //       préexistante est préservée, quitte à ce que la dalle soit partielle.
        if (config.platformDepth > 0) {
            val pm = config.platformMargin
            forEachInBox(
                corner.relative(along, -1 - pm).relative(perpendicular, -pm).below(config.platformDepth),
                corner.relative(along, width + pm).relative(perpendicular, pm).below(1),
            ) { pos ->
                val state = level.getBlockState(pos)
                if (state.canBeReplaced() && !isProtected(pos)) level.setBlock(pos, platform, 2)
            }
        }

        // ── 3. Le cadre d'obsidienne, coins compris.
        forEachInBox(corner.relative(along, -1).below(), corner.relative(along, width).above(height)) { pos ->
            val da = (pos.x - corner.x) * along.stepX + (pos.z - corner.z) * along.stepZ
            val dy = pos.y - corner.y
            if (da == -1 || da == width || dy == -1 || dy == height) level.setBlock(pos, frame, 2)
        }

        // ── 4. L'intérieur (flag 18 comme vanilla : pas de re-validation pendant la pose).
        forEachInBox(corner, corner.relative(along, width - 1).above(height - 1)) { pos ->
            level.setBlock(pos, portal, 18)
        }

        return BlockUtil.FoundRectangle(corner.immutable(), width, height)
    }

    /**
     * **La taille à bâtir** : celle du portail d'où l'on part, ou celle de vanilla.
     *
     * Le portail source se mesure dans le niveau de l'ENTITÉ, qui n'a pas encore bougé quand
     * `getExitPortal` s'exécute, et par le calcul de Mojang lui-même
     * ([NetherPortalGeometry.rectangleAt]). Tout ce qui manque, réglage coupé, entité absente
     * ou bloc qui n'est plus un portail, ramène au 2x3 de vanilla : la taille est un confort,
     * jamais une condition pour que le voyage aboutisse.
     */
    private fun sourceSize(config: TravelConfig, entity: Entity?, entryPos: BlockPos?): Pair<Int, Int> {
        val vanilla = FALLBACK_WIDTH to FALLBACK_HEIGHT
        if (!config.netherPortalCopySize || entity == null || entryPos == null) return vanilla

        val rectangle = NetherPortalGeometry.rectangleAt(entity.level(), entryPos) ?: return vanilla
        // Les mêmes bornes que la détection de forme : une arche 1x1 franchie répond par une
        // arche 1x1, et une arche de 41 par une arche de 41, quand le réglage le permet.
        val width = Mth.clamp(rectangle.axis1Size, NetherPortalSizes.minWidth, NetherPortalSizes.max)
        val height = Mth.clamp(rectangle.axis2Size, NetherPortalSizes.minHeight, NetherPortalSizes.max)

        if (width != FALLBACK_WIDTH || height != FALLBACK_HEIGHT) {
            TravellingDimension.LOGGER.info(
                "Nether portal: the {}x{} size of the source portal is copied", width, height
            )
        }
        return width to height
    }

}
