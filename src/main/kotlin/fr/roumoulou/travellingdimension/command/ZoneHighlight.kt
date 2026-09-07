package fr.roumoulou.travellingdimension.command

import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * **L'affichage de l'emprise de recherche d'un portail**, à la façon d'un MiniHUD, mais sans
 * une seule ligne de code client : le serveur sème des particules le long des arêtes et ne les
 * envoie qu'au joueur qui a demandé l'affichage. Ça marche donc sur un client vanilla.
 *
 * Ce qu'on voit est le **carré dans lequel ce portail cherche un partenaire**, centré sur son
 * ANCRE : 128 blocs de chaque côté dans l'OVERWORLD, 8 dans VOYAGE, qui sont le même carré de
 * monde. C'est exactement l'emprise que consulte [TravelPortalPlacer.findNearest], prise à la
 * même source, donc l'affichage ne peut pas dériver de la règle.
 *
 * Trois détails font la lisibilité :
 *
 * - **la densité s'adapte à la distance.** Près du joueur un point par bloc, ce qui donne une
 *   ligne franche ; au-delà, un point tous les quatre blocs, assez pour lire la direction du
 *   mur sans noyer l'écran ni le réseau ;
 * - **le rideau a de la hauteur.** Un trait au sol se confond avec le terrain, un mur de
 *   quelques blocs se voit de loin et par-dessus les collines ;
 * - **tout suit la hauteur du joueur.** Le rideau se redessine à son altitude, donc il reste
 *   devant lui qu'il soit en surface, en grotte ou en vol.
 *
 * Les murs tombent sur les **bords physiques** de l'emprise : le bloc `maxX` est dedans, donc
 * le mur passe en `maxX + 1`. Un joueur qui longe le rideau de l'intérieur est encore à portée.
 */
object ZoneHighlight {

    /** Cadence de redessin. Les particules vivent environ une seconde, quatre ticks suffisent. */
    private const val REDRAW_EVERY = 4

    /** Hauteur du rideau, en blocs, sous et sur les pieds du joueur. */
    private const val WALL_BELOW = 2
    private const val WALL_ABOVE = 6
    private const val WALL_STEP = 2

    /** En deçà de cette distance, un point par bloc ; au-delà, un point tous les [FAR_STEP]. */
    private const val NEAR_RANGE = 28
    private const val FAR_STEP = 4

    /** Ambre franc, la couleur des limites de région d'un MiniHUD. */
    private const val WALL_COLOUR = 0xFFB300

    /** Le portail suivi par un joueur : sa dimension, son ancre, et l'emprise qui en découle. */
    data class Watch(
        val dimension: ResourceKey<Level>,
        val anchor: BlockPos,
        val box: TravelPortalPlacer.Box,
        val radius: Int,
    )

    private val watchers = LinkedHashMap<UUID, Watch>()
    private var ticks = 0

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (watchers.isEmpty()) return@register
            ticks++
            if (ticks % REDRAW_EVERY != 0) return@register
            // Copie : un joueur déconnecté doit sortir de la table sans casser l'itération.
            watchers.keys.toList().forEach { id ->
                val player = server.playerList.getPlayer(id)
                if (player == null) watchers.remove(id) else draw(player, watchers.getValue(id))
            }
        }
    }

    /** Suit ce portail pour ce joueur, en remplaçant ce qu'il suivait déjà. */
    fun watch(player: ServerPlayer, watch: Watch) {
        watchers[player.uuid] = watch
    }

    /** Ce que ce joueur suit, ou `null`. */
    fun watched(player: ServerPlayer): Watch? = watchers[player.uuid]

    /** Éteint l'affichage de ce joueur. Rend `false` s'il n'y en avait pas. */
    fun stop(player: ServerPlayer): Boolean = watchers.remove(player.uuid) != null

    private fun draw(player: ServerPlayer, watch: Watch) {
        val level = player.level()
        // Le joueur est parti ailleurs : on garde son choix sous le coude sans rien dessiner,
        // il retrouvera son rideau en revenant.
        if (level.dimension() != watch.dimension) return

        val dust = DustParticleOptions(WALL_COLOUR, 1.0f)
        val base = player.y
        val box = watch.box

        // Bords PHYSIQUES : le bloc maxX est dans l'emprise, son bord extérieur est en maxX + 1.
        val x0 = box.minX.toDouble()
        val x1 = (box.maxX + 1).toDouble()
        val z0 = box.minZ.toDouble()
        val z1 = (box.maxZ + 1).toDouble()

        walk(box.minZ, box.maxZ + 1, player.z) { z ->
            column(level, player, dust, x0, base, z.toDouble())
            column(level, player, dust, x1, base, z.toDouble())
        }
        walk(box.minX, box.maxX + 1, player.x) { x ->
            column(level, player, dust, x.toDouble(), base, z0)
            column(level, player, dust, x.toDouble(), base, z1)
        }

        player.sendOverlayMessage(
            Component.translatable(
                "travellingdimension.zones.overlay",
                watch.anchor.x, watch.anchor.y, watch.anchor.z,
                box.minX, box.maxX, box.minZ, box.maxZ,
            ).withStyle(ChatFormatting.GOLD)
        )
    }

    /**
     * Parcourt un segment entier en resserrant le pas autour du joueur : une ligne franche là
     * où il regarde, une trace lisible au loin.
     */
    private inline fun walk(from: Int, to: Int, near: Double, action: (Int) -> Unit) {
        var v = from
        while (v <= to) {
            action(v)
            v += if (kotlin.math.abs(v - near) <= NEAR_RANGE) 1 else FAR_STEP
        }
    }

    /** Une colonne de rideau : quelques points du dessous des pieds à au-dessus de la tête. */
    private fun column(
        level: ServerLevel,
        player: ServerPlayer,
        dust: DustParticleOptions,
        x: Double,
        base: Double,
        z: Double,
    ) {
        var dy = -WALL_BELOW
        while (dy <= WALL_ABOVE) {
            level.sendParticles(player, dust, true, true, x, base + dy, z, 1, 0.0, 0.0, 0.0, 0.0)
            dy += WALL_STEP
        }
    }
}
