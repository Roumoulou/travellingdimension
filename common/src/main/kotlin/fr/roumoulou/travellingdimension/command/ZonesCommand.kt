// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.command

import com.mojang.brigadier.Command
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.nether.NetherPortalGeometry
import fr.roumoulou.travellingdimension.nether.NetherPortalLinks
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * `/tdzones` : **voir l'emprise dans laquelle un portail cherche un partenaire**, ouverte à
 * tous les joueurs.
 *
 * ```
 * /tdzones        vise un portail : allume son emprise, ou l'éteint si c'était déjà lui
 * /tdzones off    éteint l'affichage
 * ```
 *
 * L'emprise est le carré centré sur l'ANCRE du portail, le bloc du milieu de sa rangée du bas,
 * celui sur lequel on marche en entrant. Elle vaut 128 blocs de chaque côté dans l'OVERWORLD et
 * 8 dans VOYAGE, qui sont le même carré de monde.
 *
 * Ouverte à tous, comme `/tdlock` et `/where` : c'est un affichage, il ne modifie ni ne sonde
 * rien. Comprendre où son portail va chercher est le premier besoin d'un joueur qui bâtit un
 * réseau, et lui refuser cette vue serait le laisser deviner.
 *
 * L'affichage est un interrupteur, il reste allumé jusqu'à ce qu'on l'éteigne ou qu'on se
 * déconnecte. Viser un AUTRE portail le déplace au lieu de l'éteindre : on compare deux
 * emprises en deux commandes.
 */
object ZonesCommand {

    /** Le préfixe des messages de cette commande. */
    private const val KEYS = "commands.travellingdimension.zones"

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("tdzones")
                    .executes { ctx -> toggle(ctx.source) }
                    .then(Commands.literal("off").executes { ctx -> off(ctx.source) })
            )
        }
    }

    private fun toggle(source: CommandSourceStack): Int {
        val player = source.entity as? ServerPlayer ?: run {
            source.sendFailure(Component.translatable("commands.travellingdimension.zones.needs_player"))
            return 0
        }
        val level = source.level
        if (!isLinked(level)) {
            source.sendFailure(Component.translatable("commands.travellingdimension.zones.wrong_dimension"))
            return 0
        }

        val pos = Targeting.targetedBlock(source, KEYS) ?: return 0

        // Un portail de VOYAGE ou un portail du NETHER : chacun a sa propre portée, et c'est
        // toute la difficulté que l'affichage sert à lever.
        val target = travelAround(level, pos) ?: netherAround(level, pos) ?: run {
            source.sendFailure(Component.translatable("commands.travellingdimension.zones.no_portal"))
            return 0
        }
        val (anchor, radius) = target

        // Le MÊME portail que celui déjà affiché : la commande fait interrupteur.
        val current = ZoneHighlight.watched(player)
        if (current != null && current.anchor == anchor && current.dimension == level.dimension()) {
            ZoneHighlight.stop(player)
            source.sendSuccess({
                Component.translatable("commands.travellingdimension.zones.off")
                    .withStyle(ChatFormatting.GRAY)
            }, false)
            return Command.SINGLE_SUCCESS
        }

        // L'emprise vient de la même source que la recherche elle-même : l'affichage ne peut
        // pas annoncer un territoire que le mod ne consulterait pas.
        val box = TravelPortalPlacer.Box.around(anchor, radius)
        ZoneHighlight.watch(player, ZoneHighlight.Watch(level.dimension(), anchor, box, radius))

        source.sendSuccess({
            Component.translatable(
                "commands.travellingdimension.zones.on",
                anchor.x, anchor.y, anchor.z,
                radius, box.minX, box.maxX, box.minZ, box.maxZ,
            ).withStyle(ChatFormatting.GREEN)
        }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun off(source: CommandSourceStack): Int {
        val player = source.entity as? ServerPlayer ?: run {
            source.sendFailure(Component.translatable("commands.travellingdimension.zones.needs_player"))
            return 0
        }
        if (!ZoneHighlight.stop(player)) {
            source.sendFailure(Component.translatable("commands.travellingdimension.zones.not_on"))
            return 0
        }
        source.sendSuccess({
            Component.translatable("commands.travellingdimension.zones.off").withStyle(ChatFormatting.GRAY)
        }, false)
        return Command.SINGLE_SUCCESS
    }

    /**
     * Un portail de VOYAGE : son ancre et la portée du mod, 128 blocs dans l'OVERWORLD et 8
     * dans VOYAGE. On regarde aussi le bloc du dessus, le geste naturel visant le cadre du bas.
     */
    private fun travelAround(level: ServerLevel, pos: BlockPos): Pair<BlockPos, Int>? {
        val anchor = Targeting.travelAnchorAround(level, pos) ?: return null
        return anchor to TravelPortalPlacer.searchRadius(level, ConfigManager.current)
    }

    /**
     * Un portail du NETHER : son bloc bas-milieu et la portée de **vanilla**, 16 blocs dans le
     * NETHER et 128 dans l'OVERWORLD, qui sont le même carré de monde au ratio 8.
     *
     * Ce n'est pas la portée du mod, et c'est précisément pour ça que l'affichage vaut le coup :
     * dans l'OVERWORLD, un portail de voyage et un portail du NETHER dessinent deux carrés de
     * 128 blocs qui ne veulent pas dire la même chose.
     */
    private fun netherAround(level: ServerLevel, pos: BlockPos): Pair<BlockPos, Int>? {
        val here = (NetherPortalGeometry.portalAt(level, pos) ?: NetherPortalGeometry.portalAt(level, pos.above()))?.first
            ?: return null
        return here to NetherPortalLinks.reach(level)
    }

    private fun isLinked(level: Level): Boolean =
        level.dimension() == Level.OVERWORLD ||
                level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL ||
                level.dimension() == Level.NETHER
}
