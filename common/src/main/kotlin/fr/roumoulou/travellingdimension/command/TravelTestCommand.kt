// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.portal.PortalCoordinates
import fr.roumoulou.travellingdimension.portal.PortalFrame
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * `/tdtest` : compter et nettoyer les portails, réservé aux opérateurs (niveau 2).
 *
 * Le comportement des portails se juge mal à l'œil : la transformation divise, donc
 * plusieurs portails de l'OVERWORLD peuvent partager une destination, et il est facile de
 * croire à un bug là où c'est la règle. Ces deux sous-commandes servent à **mesurer** au
 * lieu de deviner.
 *
 * - `scan [rayon]` compte les portails réellement présents autour de soi, avec pour chacun
 *   son ancre, sa taille, sa couleur et l'endroit où il mène ;
 * - `clear [rayon]` efface portails et cadres pour recommencer une expérience.
 *
 * Les deux ne touchent QUE les chunks déjà chargés : une commande de diagnostic ne doit pas
 * générer du terrain en le mesurant. Sans joueur à proximité il n'y a donc rien à voir, et
 * c'est normal.
 */
object TravelTestCommand {

    /** Le préfixe des messages de cette commande. */
    private const val KEYS = "commands.travellingdimension.test"

    private const val DEFAULT_RADIUS = 48

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("tdtest")
                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                    .then(
                        Commands.literal("scan")
                            .executes { ctx -> scan(ctx.source, DEFAULT_RADIUS) }
                            .then(
                                Commands.argument("rayon", IntegerArgumentType.integer(1, 256))
                                    .executes { ctx -> scan(ctx.source, IntegerArgumentType.getInteger(ctx, "rayon")) }
                            )
                    )
                    .then(
                        Commands.literal("clear")
                            .executes { ctx -> clear(ctx.source, DEFAULT_RADIUS) }
                            .then(
                                Commands.argument("rayon", IntegerArgumentType.integer(1, 256))
                                    .executes { ctx -> clear(ctx.source, IntegerArgumentType.getInteger(ctx, "rayon")) }
                            )
                    )
            )
        }
    }

    private fun scan(source: CommandSourceStack, radius: Int): Int {
        val level = source.level
        val here = BlockPos.containing(source.position)
        val config = ConfigManager.current

        val portals = TravelPortalPlacer.portalsIn(
            level, TravelPortalPlacer.Box.around(here, radius), level.minY, level.maxY
        ).sortedWith(compareBy({ it.centre.x }, { it.centre.z }, { it.centre.y }))

        if (portals.isEmpty()) {
            source.sendSuccess({
                Component.translatable("$KEYS.scan.none", radius)
                    .withStyle(ChatFormatting.GRAY)
            }, false)
            return 1
        }

        source.sendSuccess({
            Component.translatable("$KEYS.scan.count", portals.size, radius)
                .withStyle(ChatFormatting.AQUA)
        }, false)

        val inTravel = level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL
        val destKey = if (inTravel) Level.OVERWORLD else TravelDimensionKeys.TRAVEL_LEVEL
        val destLevel = source.server.getLevel(destKey)

        portals.forEach { rect ->
            source.sendSuccess({
                if (rect.tint.isLink) {
                    Component.translatable(
                        "$KEYS.scan.portal_tinted",
                        rect.centre.toShortString(), rect.width, rect.height, rect.axis.toString(), rect.tint.serializedName
                    )
                } else {
                    Component.translatable("$KEYS.scan.portal", rect.centre.toShortString(), rect.width, rect.height, rect.axis.toString())
                }
            }, false)

            if (destLevel == null) return@forEach
            val convert: (Int) -> Int =
                if (inTravel) { coord -> PortalCoordinates.travelToOverworld(coord, config.ratio) }
                else { coord -> PortalCoordinates.overworldToTravel(coord, config.ratio) }

            val ideal = TravelPortalPlacer.idealPoint(
                destLevel, rect.centre, rect.height, config.platformDepth, convert
            )
            // Même garde que la résolution : couleurs coupées, l'aperçu les ignore aussi.
            val linked = if (config.portalTints && rect.tint.isLink) {
                TravelPortalPlacer.findNearest(destLevel, ideal, !inTravel, config, rect.tint)
            } else null
            val actual = linked ?: TravelPortalPlacer.findNearest(destLevel, ideal, !inTravel, config)

            source.sendSuccess({
                if (actual == null) {
                    Component.translatable("$KEYS.scan.creates", ideal.toShortString())
                        .withStyle(ChatFormatting.GRAY)
                } else {
                    Component.translatable("$KEYS.scan.arrives", ideal.toShortString(), actual.centre.toShortString())
                        .withStyle(ChatFormatting.GOLD)
                }
            }, false)
        }
        return portals.size
    }

    /**
     * Efface les blocs de portail ET les blocs de cadre du rayon donné.
     *
     * Le cadre part avec le portail : laisser des cadres debout ferait rallumer au premier
     * igniter des portails qu'on croyait effacés, et fausserait l'expérience suivante.
     */
    private fun clear(source: CommandSourceStack, radius: Int): Int {
        val level = source.level
        val here = BlockPos.containing(source.position)
        val air = Blocks.AIR.defaultBlockState()

        var removed = 0
        var skippedChunks = 0
        val seenChunks = HashSet<Long>()

        BlockPos.betweenClosed(
            here.offset(-radius, -radius, -radius),
            here.offset(radius, radius, radius),
        ).forEach { pos ->
            val chunkX = pos.x shr 4
            val chunkZ = pos.z shr 4
            if (!level.chunkSource.hasChunk(chunkX, chunkZ)) {
                if (seenChunks.add(chunkPosKey(chunkX, chunkZ))) skippedChunks++
                return@forEach
            }
            if (pos.y < level.minY || pos.y > level.maxY) return@forEach

            val state = level.getBlockState(pos)
            if (state.`is`(ModBlocks.TRAVEL_PORTAL) || PortalFrame.matches(state)) {
                level.setBlock(pos.immutable(), air, 2)
                removed++
            }
        }

        source.sendSuccess({
            Component.translatable("$KEYS.clear.done", removed, radius)
                .withStyle(ChatFormatting.GREEN)
        }, false)
        if (skippedChunks > 0) {
            source.sendSuccess({
                Component.translatable("$KEYS.clear.skipped", skippedChunks)
                    .withStyle(ChatFormatting.GRAY)
            }, false)
        }
        return removed
    }

    private fun chunkPosKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
