package fr.roumoulou.travellingdimension.command

import com.mojang.brigadier.Command
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.portal.PortalLocks
import fr.roumoulou.travellingdimension.portal.TravelPortalBlock
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import fr.roumoulou.travellingdimension.portal.TravelPortalShape
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
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

    /** Portée du rayon de visée, large : on veut pouvoir désigner un portail d'en face. */
    private const val TARGET_REACH = 32.0

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("tdlock")
                    .executes { ctx -> targetedPortal(ctx.source)?.let { lock(ctx.source, it) } ?: 0 }
                    .then(
                        Commands.literal("off")
                            .executes { ctx -> targetedPortal(ctx.source)?.let { unlock(ctx.source, it) } ?: 0 }
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

        val anchor = anchorAround(level, target) ?: run {
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
        val anchor = anchorAround(level, target) ?: run {
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

    // ─── Viser ───────────────────────────────────────────────────────────────

    /**
     * La position du bloc regardé, ou `null` avec un message d'échec déjà envoyé.
     *
     * Le geste naturel vise le bloc de cadre du bas ; le milieu du portail est celui juste
     * au-dessus, et [anchorAround] inspecte les deux.
     */
    private fun targetedPortal(source: CommandSourceStack): BlockPos? {
        val player = source.entity as? ServerPlayer ?: run {
            source.sendFailure(Component.translatable("commands.travellingdimension.lock.needs_player"))
            return null
        }

        val hit = player.pick(TARGET_REACH, 0f, false)
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) {
            source.sendFailure(
                Component.translatable("commands.travellingdimension.lock.no_target", TARGET_REACH.toInt())
            )
            return null
        }
        return hit.blockPos
    }

    /**
     * L'ancre du portail COMPLET auquel [pos] appartient, en regardant aussi le bloc du
     * dessus. Un cadre éteint n'a pas d'ancre : réserver du terrain avec un cadre vide serait
     * trop facile.
     */
    private fun anchorAround(level: ServerLevel, pos: BlockPos): BlockPos? =
        anchorAt(level, pos) ?: anchorAt(level, pos.above())

    private fun anchorAt(level: ServerLevel, pos: BlockPos): BlockPos? {
        val state = level.getBlockState(pos)
        if (!state.`is`(ModBlocks.TRAVEL_PORTAL)) return null
        val axis = state.getOptionalValue(TravelPortalBlock.AXIS).orElse(Direction.Axis.X)
        val shape = TravelPortalShape.findAnyShape(level, pos, axis)
        if (!shape.isComplete()) return null
        return TravelPortalPlacer.completePortalAt(level, shape.centre())?.centre
    }

    private fun isLinked(level: Level): Boolean =
        level.dimension() == Level.OVERWORLD || level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL
}
