package fr.roumoulou.travellingdimension.nether

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.portal.PortalTint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType

/**
 * `/tdnether` : lire et poser les couleurs des portails du NETHER, sans clic droit.
 *
 * Deux usages, et le second est le vrai motif de son existence :
 *
 * - **au jeu**, `info` répond à « où mène ce portail, au juste ? », que vanilla n'a jamais
 *   su dire. C'est le pendant de `/where cible` pour les portails ordinaires ;
 * - **au test**, `tint` permet d'éprouver toute la mécanique par la console, alors que le
 *   colorant demande un joueur et une main. Sans elle, la seule façon de vérifier un lien
 *   serait de jouer, donc de ne jamais rejouer le test.
 *
 * Réservée aux opérateurs (niveau 2), comme `/tdtest`.
 */
object NetherPortalCommand {

    private val TINT_NAMES: List<String> = PortalTint.entries.map { it.serializedName }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("tdnether")
                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                    .then(
                        Commands.literal("tint")
                            .then(
                                Commands.argument("couleur", StringArgumentType.word())
                                    .suggests { _, builder -> SharedSuggestionProvider.suggest(TINT_NAMES, builder) }
                                    .then(
                                        Commands.argument("position", BlockPosArgument.blockPos())
                                            .executes { ctx ->
                                                tint(
                                                    ctx.source,
                                                    BlockPosArgument.getLoadedBlockPos(ctx, "position"),
                                                    StringArgumentType.getString(ctx, "couleur"),
                                                )
                                            }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("info")
                            .then(
                                Commands.argument("position", BlockPosArgument.blockPos())
                                    .executes { ctx ->
                                        info(ctx.source, BlockPosArgument.getLoadedBlockPos(ctx, "position"))
                                    }
                            )
                    )
            )
        }
    }

    private fun tint(source: CommandSourceStack, pos: BlockPos, name: String): Int {
        val level = source.level
        val wanted = PortalTint.entries.firstOrNull { it.serializedName == name }
        if (wanted == null) {
            source.sendFailure(Component.literal("Couleur inconnue : $name (attendu : ${TINT_NAMES.joinToString(", ")})"))
            return 0
        }

        val axis = NetherPortalGeometry.axisAt(level, pos)
        val rectangle = NetherPortalGeometry.rectangleAt(level, pos)
        if (axis == null || rectangle == null) {
            source.sendFailure(Component.literal("Aucun bloc de portail du Nether en ${pos.toShortString()}"))
            return 0
        }

        val blocks = NetherPortalGeometry.blocksOf(rectangle, axis)
        NetherPortalTints.paint(level, blocks, wanted)
        source.sendSuccess({
            Component.literal(
                "Portail de ${rectangle.axis1Size}x${rectangle.axis2Size} en ${pos.toShortString()} : " +
                        if (wanted.isLink) "couleur ${wanted.serializedName}" else "couleur retirée"
            ).withStyle(ChatFormatting.GREEN)
        }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun info(source: CommandSourceStack, pos: BlockPos): Int {
        val level = source.level
        val axis = NetherPortalGeometry.axisAt(level, pos)
        val rectangle = NetherPortalGeometry.rectangleAt(level, pos)
        if (axis == null || rectangle == null) {
            source.sendFailure(Component.literal("Aucun bloc de portail du Nether en ${pos.toShortString()}"))
            return 0
        }

        val tint = NetherPortalTints.tintAt(level, pos)
        val centre = NetherPortalGeometry.displayPos(rectangle, axis)

        source.sendSuccess({
            Component.literal(
                "Portail du Nether : bas-milieu ${centre.toShortString()}, " +
                        "${rectangle.axis1Size}x${rectangle.axis2Size}, axe $axis, " +
                        "couleur ${tint.serializedName}"
            )
        }, false)

        if (!ConfigManager.current.netherPortalTints) {
            source.sendSuccess({
                Component.literal("  liens de couleur DÉSACTIVÉS (netherPortalTints)").withStyle(ChatFormatting.GRAY)
            }, false)
            return Command.SINGLE_SUCCESS
        }

        val destination = destinationLevel(level)
        if (destination == null) {
            source.sendSuccess({
                Component.literal("  aucun trajet depuis cette dimension").withStyle(ChatFormatting.GRAY)
            }, false)
            return Command.SINGLE_SUCCESS
        }

        val destinationIsNether = destination.dimension() == Level.NETHER
        val reach = NetherPortalLinks.reach(destinationIsNether)
        val target = convert(level, destination, centre)

        source.sendSuccess({
            Component.literal(
                "  vise ${target.toShortString()} dans ${destination.dimension().identifier()} " +
                        "(portée de vanilla : $reach blocs)"
            )
        }, false)

        if (!tint.isLink) {
            source.sendSuccess({
                Component.literal("  sans couleur : vanilla décide seul").withStyle(ChatFormatting.GRAY)
            }, false)
            return Command.SINGLE_SUCCESS
        }

        val partner = NetherPortalLinks.partnerFor(destination, target, tint, destinationIsNether)
        source.sendSuccess({
            if (partner == null) {
                Component.literal("  aucun portail ${tint.serializedName} à portée : vanilla décide seul")
                    .withStyle(ChatFormatting.GRAY)
            } else {
                Component.literal("  lien ${tint.serializedName} -> ${partner.toShortString()}")
                    .withStyle(ChatFormatting.GOLD)
            }
        }, false)
        return Command.SINGLE_SUCCESS
    }

    /** L'autre bout du trajet vanilla : Nether <-> Overworld, et rien d'autre. */
    private fun destinationLevel(level: ServerLevel): ServerLevel? = NetherPortalLinks.counterpart(level)

    /** La conversion de vanilla, à l'échelle des deux types de dimension. */
    private fun convert(from: ServerLevel, to: ServerLevel, pos: BlockPos): BlockPos =
        NetherPortalLinks.convert(from, to, pos)
}
