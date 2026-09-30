package fr.roumoulou.travellingdimension.command

import com.mojang.brigadier.Command
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.portal.PortalLocks
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.Level
import kotlin.math.abs

/**
 * `/tdlock` : **verrouiller un portail**, ouverte à tous les joueurs.
 *
 * Un portail verrouillé **interdit d'allumer un autre portail dans son territoire**, c'est-à-dire
 * dans l'emprise où deux portails se disputeraient les mêmes voyageurs : 128 blocs dans
 * l'OVERWORLD, 8 blocs dans VOYAGE, qui sont le même carré de monde. Le joueur qui essaie
 * reçoit un message nommant le propriétaire et donnant les coordonnées, pour qu'il comprenne
 * au lieu de croire à un bug.
 *
 * ```
 * /tdlock                       verrouille le portail que tu regardes
 * /tdlock off                   retire le verrou du portail que tu regardes
 * /tdlock check <position>      le terrain est-il libre pour un portail ancré là ?
 * /tdlock at <position>         (opérateurs) verrouille le portail à cette position
 * /tdlock off at <position>     (opérateurs) retire le verrou à cette position
 * ```
 *
 * **Qui peut faire quoi.** N'importe qui verrouille un portail libre : c'est une réservation,
 * elle se prend en arrivant. Seuls le PROPRIÉTAIRE et les opérateurs la retirent, sinon elle
 * ne protégerait rien.
 *
 * `check` est ouverte à tous exprès : savoir avant de bâtir que le terrain est pris vaut mieux
 * que de l'apprendre en frottant son allume-portail sur un cadre qu'on vient de monter.
 *
 * Les formes par coordonnées sont réservées aux opérateurs. Elles servent à l'administration
 * et aux tests automatisés, un clic ne se scriptant pas.
 */
object LockCommand {

    /** Le préfixe des messages de cette commande. */
    private const val KEYS = "commands.travellingdimension.lock"

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("tdlock")
                    .executes { ctx -> Targeting.targetedBlock(ctx.source, KEYS)?.let { lock(ctx.source, it) } ?: 0 }
                    .then(
                        Commands.literal("off")
                            .executes { ctx -> Targeting.targetedBlock(ctx.source, KEYS)?.let { unlock(ctx.source, it) } ?: 0 }
                            .then(
                                Commands.literal("at")
                                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                                    .then(
                                        Commands.argument("position", BlockPosArgument.blockPos())
                                            .executes { ctx ->
                                                unlock(ctx.source, BlockPosArgument.getLoadedBlockPos(ctx, "position"))
                                            }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("at")
                            .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                            .then(
                                Commands.argument("position", BlockPosArgument.blockPos())
                                    .executes { ctx ->
                                        lock(ctx.source, BlockPosArgument.getLoadedBlockPos(ctx, "position"))
                                    }
                            )
                    )
                    .then(
                        Commands.literal("check")
                            .then(
                                Commands.argument("position", BlockPosArgument.blockPos())
                                    .executes { ctx ->
                                        check(ctx.source, BlockPosArgument.getLoadedBlockPos(ctx, "position"))
                                    }
                            )
                    )
            )
        }
    }

    // ─── Poser et retirer ────────────────────────────────────────────────────

    private fun lock(source: CommandSourceStack, target: BlockPos): Int {
        if (!ConfigManager.current.portalLocks) {
            source.sendFailure(Component.translatable("commands.travellingdimension.lock.disabled"))
            return 0
        }

        val level = source.level
        if (!isLinked(level)) {
            source.sendFailure(Component.translatable("commands.travellingdimension.lock.wrong_dimension"))
            return 0
        }

        val anchor = Targeting.travelAnchorAround(level, target) ?: run {
            source.sendFailure(Component.translatable("commands.travellingdimension.lock.no_portal"))
            return 0
        }

        PortalLocks.lockAt(level, anchor)?.let { existing ->
            source.sendFailure(
                Component.translatable(
                    "commands.travellingdimension.lock.already",
                    existing.ownerName, anchor.x, anchor.y, anchor.z
                )
            )
            return 0
        }

        // Depuis la console, il n'y a pas de joueur : le verrou est alors posé au nom de la
        // source, et seuls les opérateurs pourront le retirer. C'est le comportement voulu
        // pour un verrou administratif.
        val player = source.entity as? ServerPlayer
        val owner = player?.uuid ?: PortalLocks.CONSOLE_OWNER
        val ownerName = player?.gameProfile?.name ?: source.textName

        PortalLocks.lock(level, anchor, owner, ownerName)
        source.sendSuccess({
            Component.translatable(
                "commands.travellingdimension.lock.locked",
                anchor.x, anchor.y, anchor.z, PortalLocks.reach(level)
            ).withStyle(ChatFormatting.GREEN)
        }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun unlock(source: CommandSourceStack, target: BlockPos): Int {
        val level = source.level
        val anchor = Targeting.travelAnchorAround(level, target) ?: run {
            source.sendFailure(Component.translatable("commands.travellingdimension.lock.no_portal"))
            return 0
        }

        val existing = PortalLocks.lockAt(level, anchor) ?: run {
            source.sendFailure(Component.translatable("commands.travellingdimension.lock.not_locked"))
            return 0
        }

        // Le propriétaire, ou un opérateur. Sans cette garde, n'importe qui retirerait le
        // verrou d'un autre et la réservation ne protégerait rien.
        val player = source.entity as? ServerPlayer
        val isOwner = player != null && player.uuid == existing.owner
        val isOperator = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
        if (!isOwner && !isOperator) {
            source.sendFailure(
                Component.translatable("commands.travellingdimension.lock.not_yours", existing.ownerName)
            )
            return 0
        }

        PortalLocks.unlock(level, anchor)
        source.sendSuccess({
            Component.translatable(
                "commands.travellingdimension.lock.unlocked", anchor.x, anchor.y, anchor.z
            ).withStyle(ChatFormatting.GREEN)
        }, false)
        return Command.SINGLE_SUCCESS
    }

    /** Le terrain est-il libre pour un portail dont l'ancre serait [anchor] ? */
    private fun check(source: CommandSourceStack, anchor: BlockPos): Int {
        val level = source.level
        if (!isLinked(level)) {
            source.sendFailure(Component.translatable("commands.travellingdimension.lock.wrong_dimension"))
            return 0
        }

        val blocking = PortalLocks.blockingLock(level, anchor)
        source.sendSuccess({
            if (blocking == null) {
                Component.translatable(
                    "commands.travellingdimension.lock.check_free",
                    anchor.x, anchor.y, anchor.z, PortalLocks.reach(level)
                ).withStyle(ChatFormatting.GREEN)
            } else {
                val (lockedAt, lock) = blocking
                Component.translatable(
                    "commands.travellingdimension.lock.check_blocked",
                    lock.ownerName, lockedAt.x, lockedAt.y, lockedAt.z,
                    maxOf(abs(lockedAt.x - anchor.x), abs(lockedAt.z - anchor.z)),
                    PortalLocks.reach(level)
                ).withStyle(ChatFormatting.RED)
            }
        }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun isLinked(level: Level): Boolean =
        level.dimension() == Level.OVERWORLD || level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL
}
