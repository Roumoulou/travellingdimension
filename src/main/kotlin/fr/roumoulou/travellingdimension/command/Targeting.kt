// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.command

import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * La visée, commune aux commandes qui désignent un portail en le regardant : `/where cible`,
 * `/tdzones` et `/tdlock`.
 */
internal object Targeting {

    /** Portée du rayon de visée, large : on veut pouvoir désigner un portail d'en face. */
    const val REACH = 32.0

    /**
     * Le bloc que le joueur regarde, ou `null` avec un message d'échec déjà envoyé, sous les
     * clés `<keyPrefix>.needs_player` et `<keyPrefix>.no_target` de la commande appelante.
     */
    fun targetedBlock(source: CommandSourceStack, keyPrefix: String): BlockPos? {
        val player = source.entity as? ServerPlayer ?: run {
            source.sendFailure(Component.translatable("$keyPrefix.needs_player"))
            return null
        }

        val hit = player.pick(REACH, 0f, false)
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) {
            source.sendFailure(Component.translatable("$keyPrefix.no_target", REACH.toInt()))
            return null
        }
        return hit.blockPos
    }

    /**
     * L'ancre du portail de VOYAGE complet que [pos] ou le bloc du dessus désigne : le geste
     * naturel vise le bloc de cadre du bas, et le milieu du portail est juste au-dessus. Un
     * cadre éteint n'a pas d'ancre.
     */
    fun travelAnchorAround(level: ServerLevel, pos: BlockPos): BlockPos? =
        (TravelPortalPlacer.completePortalContaining(level, pos)
            ?: TravelPortalPlacer.completePortalContaining(level, pos.above()))?.centre
}
