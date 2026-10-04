// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.network

import fr.roumoulou.travellingdimension.TravellingDimension
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * Paquets de la configuration.
 *
 * La config voyage en **JSON**, pas champ par champ : un client et un serveur de
 * versions différentes se comprennent quand même (les clés inconnues sont ignorées,
 * les manquantes prennent leur défaut), et ajouter un réglage ne demande aucune
 * retouche du protocole.
 *
 * PIÈGE : `CustomPacketPayload.createType(String)` attend un **chemin seul** et lui
 * colle le namespace `minecraft:`. Lui passer "monmod:truc" donne
 * `minecraft:monmod:truc`, refusé par le validateur d'Identifier (et donc un crash au
 * chargement de la classe). On construit donc le Type à la main avec notre namespace.
 */

/**
 * Serveur -> client : la config qui fait autorité, et le droit de la modifier.
 *
 * [editable] est calculé par le serveur pour CE joueur : le client ne décide jamais
 * lui-même s'il a le droit, il ne fait qu'afficher.
 */
data class TravelConfigSyncPayload(val configJson: String, val editable: Boolean) : CustomPacketPayload {

    companion object {
        val TYPE: CustomPacketPayload.Type<TravelConfigSyncPayload> =
            CustomPacketPayload.Type(payloadId("config_sync"))

        val CODEC: StreamCodec<FriendlyByteBuf, TravelConfigSyncPayload> =
            CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeUtf(payload.configJson)
                    buf.writeBoolean(payload.editable)
                },
                { buf -> TravelConfigSyncPayload(buf.readUtf(), buf.readBoolean()) }
            )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * Client -> serveur : demande d'appliquer une nouvelle config.
 *
 * Ce n'est qu'une DEMANDE : le serveur revérifie la permission, assainit les valeurs,
 * et renvoie à tout le monde ce qu'il a réellement retenu.
 */
data class TravelConfigUpdatePayload(val configJson: String) : CustomPacketPayload {

    companion object {
        val TYPE: CustomPacketPayload.Type<TravelConfigUpdatePayload> =
            CustomPacketPayload.Type(payloadId("config_update"))

        val CODEC: StreamCodec<FriendlyByteBuf, TravelConfigUpdatePayload> =
            CustomPacketPayload.codec(
                { payload, buf -> buf.writeUtf(payload.configJson) },
                { buf -> TravelConfigUpdatePayload(buf.readUtf()) }
            )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

private fun payloadId(path: String): Identifier =
    Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, path)
