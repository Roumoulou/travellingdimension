// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.nether

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.portal.PortalTint
import io.netty.buffer.ByteBuf
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * La couleur d'un portail du NETHER vanilla.
 *
 * ## Pourquoi ce n'est pas un état de bloc
 *
 * Dans la dimension de voyage, la couleur est une propriété du bloc de portail
 * ([fr.roumoulou.travellingdimension.portal.TravelPortalBlock.COLOR]) : c'est notre bloc,
 * on lui ajoute ce qu'on veut. `minecraft:nether_portal` n'a que son axe, et lui ajouter un
 * état voudrait dire remplacer le bloc de Mojang par le nôtre. Le jeu cesserait alors de le
 * reconnaître comme un point d'intérêt `nether_portal`, et un portail teint deviendrait
 * invisible aux voyageurs sans couleur : exactement ce qu'on refuse.
 *
 * La couleur vit donc **à côté du bloc, dans le chunk qui le porte**, par un *data
 * attachment* Fabric persistant et synchronisé. Elle est écrite dans la sauvegarde du
 * chunk, elle disparaît avec lui, et le client la reçoit pour pouvoir teinter le rendu :
 * comme aucun état de bloc ne change, c'est son arrivée qui demande le redessin
 * ([onChanged]). Le bloc de Mojang, lui, n'est pas touché d'un octet.
 *
 * ## Ce qui garde la donnée propre
 *
 * Une entrée qui ne désigne plus un bloc de portail est **ignorée à la lecture** et
 * **purgée à la première pose** dans son chunk. Un portail cassé ne laisse donc rien de
 * durable, et une couleur oubliée ne peut pas ressusciter un lien : la résolution vérifie
 * toujours que le bloc est bien là ([NetherPortalLinks]).
 */
object NetherPortalTints {

    /** Une position de bloc de portail, et sa couleur. Format de la sauvegarde. */
    private data class Entry(val pos: BlockPos, val tint: PortalTint)

    private val TINT_CODEC: Codec<PortalTint> =
        StringRepresentable.fromEnum { PortalTint.entries.toTypedArray() }

    private val ENTRY_CODEC: Codec<Entry> = RecordCodecBuilder.create { instance ->
        instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter { entry: Entry -> entry.pos },
            TINT_CODEC.fieldOf("tint").forGetter { entry: Entry -> entry.tint },
        ).apply(instance) { pos, tint -> Entry(pos, tint) }
    }

    private val TINTS_CODEC: Codec<Map<BlockPos, PortalTint>> = ENTRY_CODEC.listOf().xmap(
        { entries -> entries.associate { it.pos to it.tint } },
        { tints -> tints.map { (pos, tint) -> Entry(pos, tint) } },
    )

    /**
     * Format réseau, écrit à la main : la composition générique de [StreamCodec] passe mal
     * depuis Kotlin, et une boucle explicite se relit mieux qu'une pile de `apply`.
     * L'ordinal suffit sur le fil, qui ne survit pas à la session.
     */
    private val TINTS_STREAM_CODEC: StreamCodec<ByteBuf, Map<BlockPos, PortalTint>> =
        object : StreamCodec<ByteBuf, Map<BlockPos, PortalTint>> {
            override fun decode(buffer: ByteBuf): Map<BlockPos, PortalTint> {
                val size = ByteBufCodecs.VAR_INT.decode(buffer)
                val tints = LinkedHashMap<BlockPos, PortalTint>(size)
                repeat(size) {
                    val pos = BlockPos.STREAM_CODEC.decode(buffer)
                    val ordinal = ByteBufCodecs.VAR_INT.decode(buffer)
                    tints[pos] = PortalTint.entries.getOrElse(ordinal) { PortalTint.NONE }
                }
                return tints
            }

            override fun encode(buffer: ByteBuf, value: Map<BlockPos, PortalTint>) {
                ByteBufCodecs.VAR_INT.encode(buffer, value.size)
                value.forEach { (pos, tint) ->
                    BlockPos.STREAM_CODEC.encode(buffer, pos)
                    ByteBufCodecs.VAR_INT.encode(buffer, tint.ordinal)
                }
            }
        }

    private val TINTS: AttachmentType<Map<BlockPos, PortalTint>> = AttachmentRegistry.create(
        Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "nether_portal_tints")
    ) { builder ->
        builder.persistent(TINTS_CODEC)
        // Tout le monde voit la couleur d'un portail : c'est un repère partagé, comme la
        // couleur d'un bloc de laine, et le client en a besoin pour le rendu.
        builder.syncWith(TINTS_STREAM_CODEC, AttachmentSyncPredicate.all())
    }

    /** Force l'enregistrement du type d'attachement au chargement du mod. */
    fun register() {
        TravellingDimension.LOGGER.debug("Couleurs des portails du Nether enregistrées : {}", TINTS)
    }

    /**
     * La couleur du bloc de portail en [pos], ou [PortalTint.NONE].
     *
     * Ne charge **jamais** un chunk : un chunk absent n'a pas de couleur à donner, et une
     * lecture ne doit pas générer du terrain. Vaut côté serveur comme côté client, le
     * client recevant l'attachement avec son chunk.
     */
    fun tintAt(level: LevelReader, pos: BlockPos): PortalTint {
        val chunk = level.getChunk(
            SectionPos.blockToSectionCoord(pos.x),
            SectionPos.blockToSectionCoord(pos.z),
            ChunkStatus.FULL,
            false,
        ) ?: return PortalTint.NONE
        return chunk.getAttached(TINTS)?.get(pos) ?: PortalTint.NONE
    }

    /**
     * Teint (ou déteint, avec [PortalTint.NONE]) tous les blocs donnés.
     *
     * Un portail est un objet, pas une collection de blocs : on lui pose la couleur
     * entière, comme dans la dimension de voyage.
     *
     * Deux détails qui comptent :
     * - le portail peut être **à cheval sur deux chunks**, jusqu'à 21 blocs de large, donc
     *   la pose est groupée par chunk ;
     * - aucun état de bloc ne change, et une mise à jour de bloc à état inchangé est ignorée
     *   par le client (`Level.setBlock` rend `false` dès que `LevelChunk.setBlockState` rend
     *   `null`, et `ModelManager.requiresRender` rend `false` à états identiques). C'est la
     *   synchronisation de l'attachement, que Fabric envoie à chaque `setAttached`, qui
     *   prévient le client, et [onChanged] qui lui permet de redessiner.
     */
    fun paint(level: ServerLevel, blocks: Collection<BlockPos>, tint: PortalTint) {
        blocks.groupBy { ChunkPos.containing(it) }.forEach { (chunkPos, inChunk) ->
            val chunk = level.getChunk(chunkPos.x, chunkPos.z)
            val updated = LinkedHashMap(chunk.getAttached(TINTS) ?: emptyMap())

            // Purge d'occasion : les entrées dont le bloc a disparu depuis.
            updated.entries.removeAll { !level.getBlockState(it.key).`is`(Blocks.NETHER_PORTAL) }

            inChunk.forEach { pos ->
                if (tint.isLink) updated[pos.immutable()] = tint else updated.remove(pos)
            }

            if (updated.isEmpty()) chunk.removeAttached(TINTS) else chunk.setAttached(TINTS, updated)
        }
    }

    /**
     * Appelle [listener] après chaque changement de couleur dans [chunk], avec la table des
     * couleurs d'avant et celle d'après, vides quand l'attachement est absent.
     *
     * Côté client, le changement est l'arrivée de la synchronisation : le rendu s'en sert pour
     * redessiner les sections dont une couleur a changé. L'écouteur appartient au chunk et
     * disparaît avec lui.
     */
    fun onChanged(chunk: ChunkAccess, listener: (before: Map<BlockPos, PortalTint>, after: Map<BlockPos, PortalTint>) -> Unit) {
        chunk.onAttachedSet(TINTS).register { before, after -> listener(before ?: emptyMap(), after ?: emptyMap()) }
    }
}
