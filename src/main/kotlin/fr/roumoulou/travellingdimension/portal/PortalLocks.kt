// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.UUID

/**
 * Le **verrou d'un portail** : une réservation de terrain, posée par un joueur.
 *
 * ## Ce qu'un verrou fait
 *
 * Un portail verrouillé **interdit d'allumer un autre portail à portée**. Le joueur qui
 * essaie reçoit un message qui nomme le propriétaire, donne les coordonnées du portail
 * verrouillé et la distance, pour qu'il comprenne pourquoi son cadre refuse de s'allumer au
 * lieu de croire à un bug.
 *
 * ## Le rayon, et pourquoi c'est celui de la recherche
 *
 * Le verrou porte exactement aussi loin que la recherche d'un portail existant : 128 blocs
 * dans l'OVERWORLD, 8 blocs dans VOYAGE, qui sont le même carré de monde. Ce n'est pas un
 * nombre choisi au hasard, c'est **l'emprise dans laquelle deux portails se disputent les
 * mêmes voyageurs**. Verrouiller son portail revient donc à dire : dans le territoire que ce
 * portail dessert, personne d'autre n'ouvre de porte.
 *
 * ## Ce qu'un verrou ne fait PAS
 *
 * Il ne bloque jamais la **création automatique** faite par le mod à l'arrivée d'un voyageur.
 * Un voyageur doit toujours atterrir quelque part, et lui refuser un portail le laisserait
 * coincé. En pratique la brèche est étroite : si le portail verrouillé est à portée du point
 * idéal, la recherche le rejoint au lieu d'en créer un second.
 *
 * ## Où vit le verrou
 *
 * Dans un *data attachment* de chunk persistant, une entrée par portail, indexée sur son
 * ANCRE. Il est écrit dans la sauvegarde du chunk et disparaît avec lui. Une entrée dont le
 * portail n'existe plus est ignorée à la lecture et purgée à la première écriture dans son
 * chunk : casser un portail verrouillé libère donc le terrain, ce qui est le comportement
 * attendu.
 */
object PortalLocks {

    /** Qui a posé le verrou. Le nom est retenu pour pouvoir l'afficher hors connexion. */
    data class Lock(val owner: UUID, val ownerName: String)

    /**
     * Le propriétaire d'un verrou posé depuis la console, qui n'appartient à aucun joueur.
     * Aucun joueur ne portant cet identifiant, seuls les opérateurs pourront le retirer :
     * c'est exactement ce qu'on attend d'un verrou administratif.
     */
    val CONSOLE_OWNER: UUID = UUID(0L, 0L)

    private val LOCK_CODEC: Codec<Lock> = RecordCodecBuilder.create { instance ->
        instance.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter { lock: Lock -> lock.owner },
            Codec.STRING.fieldOf("name").forGetter { lock: Lock -> lock.ownerName },
        ).apply(instance) { owner, name -> Lock(owner, name) }
    }

    private data class Entry(val anchor: BlockPos, val lock: Lock)

    private val ENTRY_CODEC: Codec<Entry> = RecordCodecBuilder.create { instance ->
        instance.group(
            BlockPos.CODEC.fieldOf("anchor").forGetter { entry: Entry -> entry.anchor },
            LOCK_CODEC.fieldOf("lock").forGetter { entry: Entry -> entry.lock },
        ).apply(instance) { anchor, lock -> Entry(anchor, lock) }
    }

    private val LOCKS_CODEC: Codec<Map<BlockPos, Lock>> = ENTRY_CODEC.listOf().xmap(
        { entries -> entries.associate { it.anchor to it.lock } },
        { locks -> locks.map { (anchor, lock) -> Entry(anchor, lock) } },
    )

    private val LOCKS: AttachmentType<Map<BlockPos, Lock>> = AttachmentRegistry.create(
        Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "portal_locks")
    ) { builder -> builder.persistent(LOCKS_CODEC) }

    /** Force l'enregistrement du type d'attachement au chargement du mod. */
    fun register() {
        TravellingDimension.LOGGER.debug("Verrous de portail enregistrés : {}", LOCKS)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lire
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Le verrou du portail dont l'ancre est [anchor], ou `null`.
     *
     * Rend `null` si le portail n'existe plus, sans rien écrire : une entrée orpheline ne
     * doit jamais protéger un terrain vide.
     */
    fun lockAt(level: ServerLevel, anchor: BlockPos): Lock? {
        val chunk = level.getChunk(
            SectionPos.blockToSectionCoord(anchor.x),
            SectionPos.blockToSectionCoord(anchor.z),
            ChunkStatus.FULL,
            false,
        ) ?: return null

        val lock = chunk.getAttached(LOCKS)?.get(anchor) ?: return null
        return if (TravelPortalPlacer.completePortalAt(level, anchor) != null) lock else null
    }

    /**
     * **Le verrou qui interdit d'allumer un portail dont l'ancre serait [anchor]**, ou `null`
     * si le terrain est libre.
     *
     * Cherche dans l'emprise de recherche de la dimension, la même que celle qui décide
     * quels portails se disputent les mêmes voyageurs. Le portail que l'on est en train
     * d'allumer, s'il porte lui-même un verrou, ne se bloque évidemment pas lui-même.
     *
     * Rend le portail verrouillé le plus proche, pour que le message nomme celui qui gêne
     * vraiment et non un autre choisi au hasard.
     */
    fun blockingLock(level: ServerLevel, anchor: BlockPos): Pair<BlockPos, Lock>? {
        val config = ConfigManager.current
        if (!config.portalLocks) return null

        val inTravel = level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL
        val radius = TravelPortalPlacer.searchRadius(level, config)
        if (radius < 1) return null

        return TravelPortalPlacer
            .portalsIn(level, TravelPortalPlacer.Box.around(anchor, radius), level.minY, level.maxY, loadedOnly = false)
            .asSequence()
            .filter { rect -> rect.centre != anchor }
            .mapNotNull { rect -> lockAt(level, rect.centre)?.let { rect.centre to it } }
            .minWithOrNull(
                compareBy(
                    { (pos, _) -> PortalCoordinates.distanceSquared(pos, anchor, inTravel, config.ratio) },
                    { (pos, _) -> pos.x }, { (pos, _) -> pos.y }, { (pos, _) -> pos.z },
                )
            )
    }

    /** La portée du verrou dans cette dimension, en blocs, pour les messages. */
    fun reach(level: Level): Int = TravelPortalPlacer.searchRadius(level, ConfigManager.current)

    // ─────────────────────────────────────────────────────────────────────────
    // Écrire
    // ─────────────────────────────────────────────────────────────────────────

    /** Pose un verrou. Rend `false` si le portail en portait déjà un. */
    fun lock(level: ServerLevel, anchor: BlockPos, owner: UUID, ownerName: String): Boolean {
        if (lockAt(level, anchor) != null) return false
        update(level, anchor) { locks -> locks[anchor.immutable()] = Lock(owner, ownerName) }
        TravellingDimension.LOGGER.info(
            "Portail verrouillé en {} par {} dans {}",
            anchor.toShortString(), ownerName, level.dimension().identifier()
        )
        return true
    }

    /** Retire un verrou. Rend `false` s'il n'y en avait pas. */
    fun unlock(level: ServerLevel, anchor: BlockPos): Boolean {
        if (lockAt(level, anchor) == null) return false
        update(level, anchor) { locks -> locks.remove(anchor) }
        TravellingDimension.LOGGER.info(
            "Verrou retiré en {} dans {}", anchor.toShortString(), level.dimension().identifier()
        )
        return true
    }

    /**
     * Modifie la table du chunk, en purgeant au passage les entrées dont le portail a
     * disparu. C'est le seul moment où l'on écrit, donc le seul moment où le ménage coûte
     * quelque chose, et il est alors négligeable.
     */
    private fun update(level: ServerLevel, anchor: BlockPos, change: (MutableMap<BlockPos, Lock>) -> Unit) {
        val chunkPos = ChunkPos.containing(anchor)
        val chunk = level.getChunk(chunkPos.x, chunkPos.z)
        val locks = LinkedHashMap(chunk.getAttached(LOCKS) ?: emptyMap())

        locks.entries.removeAll { (pos, _) -> TravelPortalPlacer.completePortalAt(level, pos) == null }
        change(locks)

        if (locks.isEmpty()) chunk.removeAttached(LOCKS) else chunk.setAttached(LOCKS, locks)
    }
}
