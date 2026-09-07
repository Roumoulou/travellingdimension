package fr.roumoulou.travellingdimension.nether

import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.portal.PortalTint
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import kotlin.math.abs

/**
 * Le colorant sur un portail du NETHER vanilla.
 *
 * Même geste et mêmes règles que dans la dimension de voyage
 * ([fr.roumoulou.travellingdimension.portal.TravelPortalBlock.useItemOn]) : clic droit pour
 * teindre le portail entier, même colorant pour effacer, et **rien n'est refusé**. Autant de
 * portails d'une même couleur que l'on veut, des deux côtés : la couleur est un FILTRE sur
 * les candidats, et c'est le plus proche de cette couleur qui gagne.
 *
 * Le branchement passe par un événement Fabric et non par un mixin : le bloc de Mojang
 * n'a aucune interaction au clic droit, il n'y a donc rien à envelopper, juste une
 * interaction à ajouter avant que le jeu ne conclue qu'il ne se passe rien.
 */
object NetherPortalDye {

    /** Quel colorant tenu en main correspond à quelle couleur, comme pour le portail de voyage. */
    private val DYES: Map<Item, DyeColor> by lazy {
        DyeColor.VALUES.associate { color -> Items.DYE.pick(color) to color }
    }

    fun register() {
        UseBlockCallback.EVENT.register { player, level, hand, hit -> onUse(player, level, hand, hit) }
    }

    private fun onUse(player: Player, level: Level, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        val stack = player.getItemInHand(hand)
        DYES[stack.item] ?: return InteractionResult.PASS

        val pos = hit.blockPos
        if (!level.getBlockState(pos).`is`(Blocks.NETHER_PORTAL)) return InteractionResult.PASS
        if (!ConfigManager.current.netherPortalTints) return InteractionResult.PASS

        // Le client ne décide de rien : il fait juste le geste, le serveur tranche.
        if (level.isClientSide) return InteractionResult.SUCCESS
        val server = level as? ServerLevel ?: return InteractionResult.PASS

        val dye = DYES.getValue(stack.item)
        val wanted = PortalTint.of(dye)
        val current = NetherPortalTints.tintAt(server, pos)
        val target = if (current == wanted) PortalTint.NONE else wanted

        val axis = NetherPortalGeometry.axisAt(server, pos) ?: return InteractionResult.PASS
        val rectangle = NetherPortalGeometry.rectangleAt(server, pos) ?: return InteractionResult.PASS
        val blocks = NetherPortalGeometry.blocksOf(rectangle, axis)

        NetherPortalTints.paint(server, blocks, target)
        if (!player.abilities.instabuild) stack.consume(1, player)
        server.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0f, 1.0f)

        player.sendOverlayMessage(
            if (target.isLink) {
                Component.translatable(
                    "travellingdimension.nether_portal.tinted",
                    Component.translatable("color.minecraft.${target.serializedName}")
                )
            } else {
                Component.translatable("travellingdimension.nether_portal.untinted")
            }
        )
        return InteractionResult.SUCCESS
    }
}
