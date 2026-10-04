// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.client.nether

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.nether.NetherPortalTints
import fr.roumoulou.travellingdimension.portal.PortalTint
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Collections
import java.util.WeakHashMap

/**
 * La teinte visible d'un portail du NETHER.
 *
 * ## Pourquoi un pack de ressources, et pas des fichiers posés dans le mod
 *
 * Teinter, c'est MULTIPLIER la texture par une couleur. La texture vanilla du portail du
 * Nether est franchement violette (moyenne mesurée R=87 V=11 B=191, le vert plafonnant à
 * 92) : multipliée par du vert ou du jaune, elle ne rend rien, faute de matière à
 * éclairer. Pour que les seize couleurs s'affichent telles quelles, il faut donc
 * neutraliser la texture en niveaux de gris, et rendre le violet d'origine par la teinte
 * de [UNTINTED] (`07-tools-and-scripts/build-nether-portal-greyscale.ps1`, écart moyen de
 * reconstruction 9/255 : un portail non coloré garde son aspect).
 *
 * Neutraliser une texture de VANILLA n'est pas anodin : ça change le Nether de tout le
 * monde, y compris de qui ne se sert jamais des couleurs. D'où le pack **intégré et
 * désactivable** : il est actif par défaut, et se coupe depuis l'écran des packs de
 * ressources comme n'importe quel autre. Pack coupé, la texture et les modèles de Mojang
 * reprennent la main, les faces n'ont plus de `tintindex`, la fabrique ci-dessous n'est
 * plus jamais consultée, et le jeu retrouve son aspect d'origine au pixel près. Les liens
 * de couleur, eux, continuent de fonctionner : ils ne dépendent pas du rendu.
 *
 * C'est aussi ce qui règle proprement le conflit avec un pack de ressources tiers qui
 * redéfinirait le portail du Nether : le sien passe devant, et il n'y a rien à arbitrer.
 */
object NetherPortalTintRendering {

    /**
     * Le violet du portail vanilla, mesuré, qui rend son aspect d'origine à la texture
     * neutralisée. C'est la teinte d'un portail SANS lien de couleur : à l'écran, il est
     * inchangé.
     *
     * Valeur produite par `build-nether-portal-greyscale.ps1` : à reprendre de sa sortie
     * si Mojang retouche la texture.
     */
    private const val UNTINTED: Int = 0x740EFF

    /** Le pack intégré qui porte la texture neutralisée et les deux modèles teintables. */
    private val PACK_ID: Identifier =
        Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "nether_portal_tints")

    /**
     * Les chunks dont la couleur est déjà écoutée. `CHUNK_LOAD` repasse sur la même instance
     * quand le serveur renvoie un chunk que le client tient encore : sans ce filet, l'écouteur
     * s'empilerait. Les références sont faibles, un chunk déchargé part avec son écouteur.
     */
    private val watched: MutableSet<LevelChunk> = Collections.newSetFromMap(WeakHashMap<LevelChunk, Boolean>())

    fun register() {
        FabricLoader.getInstance().getModContainer(TravellingDimension.MOD_ID).ifPresent { container ->
            val registered = ResourceLoader.registerBuiltinPack(
                PACK_ID,
                container,
                Component.translatable("travellingdimension.pack.nether_portal_tints"),
                PackActivationType.DEFAULT_ENABLED,
            )
            if (!registered) {
                TravellingDimension.LOGGER.warn(
                    "Built-in pack {} not registered: Nether portals keep their original texture " +
                            "(colour links still work)", PACK_ID
                )
            }
        }

        // Les faces du portail du Nether ne portent un tintindex que si le pack ci-dessus
        // est actif. Sans lui, cette fabrique n'est jamais appelée : l'enregistrer coûte
        // donc zéro et n'impose rien.
        BlockColorRegistry.register(
            BlockTintsFactory { _, level, pos, tints -> tints.add(argbAt(level, pos)) },
            Blocks.NETHER_PORTAL,
        )

        // La couleur arrive par la synchronisation de l'attachement du chunk, sans qu'aucun
        // état de bloc ne change, et le client ne redessine une section que sur un changement
        // d'état : sans ce qui suit, la couleur n'apparaît qu'au prochain redessin venu
        // d'ailleurs, un bloc posé à côté ou F3+A.
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk -> watch(level, chunk) }
    }

    /** Marque à redessiner les sections de [chunk] dont une couleur change, à chaque fois qu'il en reçoit. */
    private fun watch(level: ClientLevel, chunk: LevelChunk) {
        if (!watched.add(chunk)) return
        NetherPortalTints.onChanged(chunk) { before, after ->
            val sections = HashSet<SectionPos>()
            for (pos in before.keys + after.keys) {
                if (before[pos] != after[pos]) sections.add(SectionPos.of(pos))
            }
            sections.forEach { level.setSectionDirtyWithNeighbors(it.x(), it.y(), it.z()) }
        }
    }

    /**
     * La couleur à multiplier, pour ce bloc précis.
     *
     * La fabrique reçoit un [BlockAndTintGetter], qui pendant la cuisson d'une section est
     * une vue partielle du monde et non le niveau : elle ne sait pas rendre un chunk, donc
     * pas d'attachement à lire. On retombe alors sur le niveau du client, qui est bien là
     * puisqu'on est en train d'en dessiner un morceau.
     */
    private fun argbAt(level: BlockAndTintGetter, pos: BlockPos): Int {
        val reader = level as? LevelReader ?: Minecraft.getInstance().level ?: return opaque(UNTINTED)
        val tint = NetherPortalTints.tintAt(reader, pos)
        return if (tint.isLink) tint.argb() else opaque(UNTINTED)
    }

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or rgb
}
