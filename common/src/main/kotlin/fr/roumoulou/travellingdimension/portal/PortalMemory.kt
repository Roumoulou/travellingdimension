// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import fr.roumoulou.travellingdimension.TravellingDimension
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity

/**
 * Mémoire du dernier passage d'une entité : par quel portail elle est entrée, et sur
 * lequel elle est arrivée.
 *
 * **Pourquoi il en faut une.** L'aller DIVISE les coordonnées : tous les portails d'un
 * carré de `ratio` blocs mènent au même portail de voyage. Lequel des trois a-t-on
 * emprunté ? L'information est mathématiquement perdue, aucun calcul ne peut la
 * retrouver au retour. Sans mémoire, on ressort toujours par le même portail (celui le
 * plus proche du coin de la cellule), ce qui rend inutilisable un réseau de portails
 * serré autour d'une base.
 *
 * **Où elle vit.** Dans un *data attachment* Fabric porté par l'entité elle-même, et
 * persistant : il est écrit dans sa sauvegarde, donc il survit à un redémarrage du
 * serveur, à une déconnexion, et il voyage avec elle d'une dimension à l'autre. Rien
 * n'est stocké à côté : pas de registre global à purger, pas de fuite possible, la
 * donnée disparaît avec l'entité.
 *
 * **Ce que ça ne change pas.** Le monde reste sans état : aucune liaison de portails
 * n'est écrite dans la sauvegarde du niveau, aucun portail supplémentaire n'est créé,
 * et la conversion de coordonnées est exactement la même. Le rappel se contente de
 * CHOISIR, parmi les portails déjà présents, celui d'où l'on venait.
 *
 * Le rappel n'a lieu que si l'entité repart **du portail exact** sur lequel elle était
 * arrivée. Prendre un autre portail de la dimension de voyage rend la main au calcul.
 */
object PortalMemory {

    /** Un passage : le portail quitté, et celui sur lequel on est arrivé. */
    data class Passage(val from: BlockPos, val via: BlockPos)

    private val PASSAGE_CODEC: Codec<Passage> = RecordCodecBuilder.create { instance ->
        instance.group(
            BlockPos.CODEC.fieldOf("from").forGetter { passage: Passage -> passage.from },
            BlockPos.CODEC.fieldOf("via").forGetter { passage: Passage -> passage.via },
        ).apply(instance) { from, via -> Passage(from, via) }
    }

    /**
     * `copyOnDeath` : mourir dans la dimension de voyage ne doit pas effacer le trajet.
     * Le rappel reste de toute façon conditionné à repartir du bon portail, donc une
     * mémoire devenue caduque ne fait jamais de dégât, elle est simplement ignorée.
     */
    private val LAST_PASSAGE: AttachmentType<Passage> = AttachmentRegistry.create(
        Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "last_passage")
    ) { builder ->
        builder.persistent(PASSAGE_CODEC)
        builder.copyOnDeath()
    }

    /** Force l'enregistrement du type d'attachement au chargement du mod. */
    fun register() {
        TravellingDimension.LOGGER.debug("Mémoire des trajets enregistrée : {}", LAST_PASSAGE)
    }

    fun remember(entity: Entity, from: BlockPos, via: BlockPos) {
        entity.setAttached(LAST_PASSAGE, Passage(from.immutable(), via.immutable()))
    }

    /**
     * Le portail d'où l'entité venait, à condition qu'elle reparte bien de [via], le
     * portail sur lequel elle était arrivée. Sinon `null` : elle est passée par
     * ailleurs, et le calcul habituel reprend la main.
     */
    fun recall(entity: Entity, via: BlockPos): BlockPos? =
        entity.getAttached(LAST_PASSAGE)?.takeIf { it.via == via }?.from

    fun forget(entity: Entity) {
        entity.removeAttached(LAST_PASSAGE)
    }
}
