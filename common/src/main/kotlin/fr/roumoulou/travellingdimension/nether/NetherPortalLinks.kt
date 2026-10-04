// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.nether

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.portal.PortalTint
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.border.WorldBorder
import net.minecraft.world.level.dimension.DimensionType

/**
 * Les liens de couleur sur les portails du NETHER vanilla.
 *
 * ## Ce que ce fichier fait, et surtout ce qu'il ne fait pas
 *
 * Il ne calcule **aucune** destination. La conversion de coordonnées, la recherche d'un
 * emplacement, la création d'un portail, le placement de l'entité à l'arrivée : tout cela
 * reste le code de Mojang, appelé tel quel. Le mod n'intervient qu'en un seul point, et
 * seulement quand deux portails de la même couleur se répondent : il dit **lequel** des
 * portails que vanilla aurait de toute façon trouvés est retenu.
 *
 * ## La portée est celle de vanilla, et elle est déjà symétrique
 *
 * `PortalForcer.findClosestPortalPosition` cherche dans un carré de **16 blocs quand la
 * destination est le NETHER**, de **128 blocs quand c'est l'OVERWORLD**. Ces deux nombres
 * ne sont pas une asymétrie : avec le ratio 8 du Nether, 16 blocs (NETHER) et 128 blocs
 * (OVERWORLD) désignent le **même carré de monde**. La portée vaut donc 16 cases des deux
 * côtés, exactement comme l'exige l'invariant du mod :
 *
 * > *la couleur réordonne un choix, elle n'étend jamais une portée.*
 *
 * En reprenant ces bornes à l'identique, un portail coloré ne devient jamais joignable
 * depuis un endroit d'où vanilla ne l'aurait pas atteint : le lien ne fabrique aucun
 * raccourci, il départage des candidats.
 *
 * ## La réciprocité est gratuite ici
 *
 * Dans la dimension de voyage, il faut simuler l'aller d'un candidat pour vérifier qu'il
 * mène vraiment ici, sans quoi les liens marchent dans un sens et pas dans l'autre. Le
 * Nether n'en a pas besoin : un portail A (OVERWORLD) atteint B (NETHER) quand
 * `|A/8 - B| <= 16`, et B atteint A quand `|A - 8B| <= 128`, ce qui est la même inégalité.
 * La relation est symétrique par construction, à la partie entière près.
 */
object NetherPortalLinks {

    /**
     * **Le seul point d'intervention.** Le portail de destination que le lien de couleur
     * impose, ou `null` pour laisser vanilla chercher comme d'habitude.
     *
     * Appelé à la place de `PortalForcer.findClosestPortalPosition`, avec ses arguments :
     * la position visée après conversion, et le côté où l'on cherche. Rendre `null` ici
     * n'est pas un échec, c'est le cas NORMAL : sans couleur posée, le mod ne fait rien.
     */
    fun preferredExit(
        destLevel: ServerLevel,
        entity: Entity,
        entryPos: BlockPos,
        target: BlockPos,
        destIsNether: Boolean,
        border: WorldBorder,
    ): BlockPos? {
        if (!ConfigManager.current.netherPortalTints) return null

        // La couleur se lit sur le portail d'où l'on part, donc dans le niveau de l'entité.
        val sourceLevel = entity.level()
        val tint = NetherPortalTints.tintAt(sourceLevel, entryPos)
        if (!tint.isLink) return null

        val partner = partnerFor(destLevel, target, tint, destIsNether, border) ?: return null

        TravellingDimension.LOGGER.debug(
            "Nether portal: {} link from {} -> portal block {} in {}",
            tint, entryPos.toShortString(), partner.toShortString(), destLevel.dimension().identifier()
        )
        return partner
    }

    /**
     * Le portail de la couleur demandée le plus proche de [target], dans la portée.
     *
     * La recherche passe par les **points d'intérêt**, comme celle de vanilla : le jeu
     * indexe déjà chaque bloc de portail du Nether, il n'y a donc rien à balayer. Le
     * départage final reprend aussi celui de vanilla, la distance puis la hauteur, pour
     * qu'un lien de couleur se comporte comme un choix de vanilla et pas comme un autre
     * mécanisme greffé à côté.
     */
    fun partnerFor(
        level: ServerLevel,
        target: BlockPos,
        tint: PortalTint,
        destinationIsNether: Boolean,
        border: WorldBorder = level.worldBorder,
    ): BlockPos? {
        if (!tint.isLink) return null
        return search(level, target, destinationIsNether, border) { pos -> isTintedPortal(level, pos, tint) }
    }

    /**
     * Le bloc est-il un portail du Nether de cette couleur ?
     *
     * L'ordre compte : on vérifie d'abord que le BLOC est là. Une couleur oubliée dans un
     * chunk ne peut donc jamais faire revivre un portail détruit.
     */
    private fun isTintedPortal(level: ServerLevel, pos: BlockPos, tint: PortalTint): Boolean =
        level.getBlockState(pos).`is`(Blocks.NETHER_PORTAL) &&
                NetherPortalTints.tintAt(level, pos) == tint

    /**
     * **Le portail que vanilla retiendrait**, sans aucun filtre de couleur.
     *
     * C'est la réponse à « où vais-je arriver ? », et elle sert aux commandes qui affichent
     * l'aperçu. Elle passe par exactement le même chemin que la résolution, donc l'aperçu ne
     * peut pas annoncer autre chose que ce qui se passera.
     */
    fun nearest(
        level: ServerLevel,
        target: BlockPos,
        destinationIsNether: Boolean,
        border: WorldBorder = level.worldBorder,
    ): BlockPos? = search(level, target, destinationIsNether, border) { true }

    /**
     * Le balayage de vanilla : les points d'intérêt d'un carré, filtrés, puis départagés par
     * la distance **en trois dimensions** puis par la hauteur.
     *
     * Attention à ne pas confondre avec la règle de VOYAGE : ici la distance est brute, dans
     * les blocs de la dimension d'arrivée, et le Y y pèse autant qu'un écart horizontal. C'est
     * le classement de Mojang, on le reprend tel quel.
     */
    private inline fun search(
        level: ServerLevel,
        target: BlockPos,
        destinationIsNether: Boolean,
        border: WorldBorder,
        keep: (BlockPos) -> Boolean,
    ): BlockPos? {
        val radius = reach(destinationIsNether)
        val poi = level.poiManager
        poi.ensureLoadedAndValid(level, target, radius)

        return poi.getInSquare(
            { holder -> holder.`is`(PoiTypes.NETHER_PORTAL) },
            target, radius, PoiManager.Occupancy.ANY,
        ).toList()
            .map { record -> record.pos }
            .filter { pos -> border.isWithinBounds(pos) }
            .filter { pos -> level.getBlockState(pos).`is`(Blocks.NETHER_PORTAL) }
            .filter(keep)
            .minWithOrNull(compareBy({ pos -> pos.distSqr(target) }, { pos -> pos.y }))
    }

    /** Portée de vanilla, en blocs, du côté où l'on cherche. */
    fun reach(inNether: Boolean): Int = if (inNether) 16 else 128

    /** La même, pour une dimension donnée. */
    fun reach(level: ServerLevel): Int = reach(level.dimension() == Level.NETHER)

    /** La conversion de vanilla entre deux dimensions, à l'échelle de leurs types. */
    fun convert(from: ServerLevel, to: ServerLevel, pos: BlockPos): BlockPos {
        val scale = DimensionType.getTeleportationScale(from.dimensionType(), to.dimensionType())
        return to.worldBorder.clampToBounds(pos.x * scale, pos.y.toDouble(), pos.z * scale)
    }

    /** L'autre bout du trajet vanilla : NETHER vers OVERWORLD, et réciproquement. */
    fun counterpart(level: ServerLevel): ServerLevel? = when (level.dimension()) {
        Level.NETHER -> level.server.getLevel(Level.OVERWORLD)
        Level.OVERWORLD -> level.server.getLevel(Level.NETHER)
        else -> null
    }
}
