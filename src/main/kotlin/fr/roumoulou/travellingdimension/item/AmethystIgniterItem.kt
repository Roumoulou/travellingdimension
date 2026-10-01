// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.item

import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.portal.PortalCoordinates
import fr.roumoulou.travellingdimension.portal.PortalFrame
import fr.roumoulou.travellingdimension.portal.PortalLocks
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import fr.roumoulou.travellingdimension.portal.TravelPortalShape
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level

/**
 * Amethyst Igniter : l'item d'allumage du portail de VOYAGE.
 *
 * Il s'utilise comme un briquet : clic droit sur ou dans un cadre valide, et le portail
 * s'allume. Il ne fonctionne que dans l'OVERWORLD et dans VOYAGE.
 *
 * Toutes les tailles de cadre sont acceptées, largeur de 1 à 21 et hauteur de 3 à 21,
 * paires comprises : l'ancre d'un portail est définie pour toutes les largeurs.
 */
class AmethystIgniterItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player

        // La case visée : celle contre la face cliquée, comme le feu d'un briquet.
        val targetPos = context.clickedPos.relative(context.clickedFace)

        if (!isDimensionAllowed(level)) {
            if (level.isClientSide) {
                player?.sendOverlayMessage(
                    Component.translatable("item.travellingdimension.amethyst_igniter.wrong_dimension")
                )
            }
            return InteractionResult.FAIL
        }

        val shape = TravelPortalShape.findEmptyPortalShape(level, targetPos, Direction.Axis.X)
        if (shape.isEmpty) {
            // Le bloc du cadre étant configurable, le message le NOMME : écrire
            // « améthyste » en dur mentirait dès que le réglage change.
            if (level.isClientSide) {
                player?.sendOverlayMessage(
                    Component.translatable(
                        "item.travellingdimension.amethyst_igniter.no_frame",
                        PortalFrame.displayName()
                    )
                )
            }
            return InteractionResult.FAIL
        }

        // LE VERROU D'UN VOISIN. Un portail verrouillé réserve son territoire : on ne peut
        // pas en allumer un second dans son emprise. Le refus est explicite, parce qu'un
        // cadre qui ne s'allume pas sans rien dire se lit comme un bug du mod.
        if (level is ServerLevel) {
            val blocking = PortalLocks.blockingLock(level, shape.get().centre())
            if (blocking != null) {
                val (lockedAt, lock) = blocking
                val gap = maxOf(
                    kotlin.math.abs(lockedAt.x - shape.get().centre().x),
                    kotlin.math.abs(lockedAt.z - shape.get().centre().z),
                )
                player?.sendSystemMessage(
                    Component.translatable(
                        "item.travellingdimension.amethyst_igniter.locked_nearby",
                        lock.ownerName,
                        lockedAt.x, lockedAt.y, lockedAt.z,
                        gap, PortalLocks.reach(level),
                    )
                )
                return InteractionResult.FAIL
            }
        }

        if (!level.isClientSide) {
            val lit = shape.get()
            lit.createPortalBlocks(level)
            level.playSound(null, targetPos, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 0.6f, 1.2f)
            if (player != null) {
                context.itemInHand.hurtAndBreak(1, player, context.hand)
                announceDestination(level, lit.centre(), lit.height, player)
            }
        }
        return InteractionResult.SUCCESS
    }

    /**
     * Dire au joueur, au moment où il allume son portail, **où il va réellement arriver**.
     *
     * C'est le seul moment naturel pour le lui dire : à la traversée ce serait du bavardage
     * répété, et une fois le réseau bâti il est trop tard pour s'apercevoir que deux portails
     * partagent leur destination.
     *
     * Le message distingue **trois** cas, et c'est toute son utilité :
     * - aucun portail à portée du point idéal, donc ce portail créera le sien ;
     * - un portail existe déjà **pile sur le point idéal**, donc le calcul et le terrain
     *   s'accordent ;
     * - un portail existe déjà **ailleurs** à portée, donc c'est lui qu'on rejoindra, et le
     *   message donne ses coordonnées exactes plus le rappel que le colorant permet d'en
     *   choisir un autre.
     *
     * Le deuxième cas mérite son propre message : le confondre avec le premier reviendrait à
     * annoncer « rien n'est à portée » alors qu'un portail est là, ce qui est faux.
     */
    private fun announceDestination(level: Level, centre: BlockPos, height: Int, player: Player) {
        val server = (level as? ServerLevel)?.server ?: return
        val config = ConfigManager.current

        val toTravel = level.dimension() == Level.OVERWORLD
        val destKey = if (toTravel) TravelDimensionKeys.TRAVEL_LEVEL else Level.OVERWORLD
        val destLevel = server.getLevel(destKey) ?: return

        val convert: (Int) -> Int =
            if (toTravel) { coord -> PortalCoordinates.overworldToTravel(coord, config.ratio) }
            else { coord -> PortalCoordinates.travelToOverworld(coord, config.ratio) }

        val ideal = TravelPortalPlacer.idealPoint(destLevel, centre, height, config.platformDepth, convert)
        val actual = TravelPortalPlacer.findNearest(destLevel, ideal, toTravel, config)

        player.sendSystemMessage(
            when {
                actual == null -> Component.translatable(
                    "item.travellingdimension.amethyst_igniter.lit_reference", ideal.x, ideal.z
                )

                actual.centre == ideal -> Component.translatable(
                    "item.travellingdimension.amethyst_igniter.lit_exact",
                    ideal.x, ideal.z, actual.centre.y
                )

                else -> Component.translatable(
                    "item.travellingdimension.amethyst_igniter.lit_redirected",
                    ideal.x, ideal.z, actual.centre.x, actual.centre.y, actual.centre.z
                )
            }
        )
    }

    private fun isDimensionAllowed(level: Level): Boolean =
        level.dimension() == Level.OVERWORLD || level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL
}
