// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.client

import fr.roumoulou.travellingdimension.client.config.ClientTravelConfig
import fr.roumoulou.travellingdimension.client.nether.NetherPortalTintRendering
import fr.roumoulou.travellingdimension.network.TravelConfigSyncPayload
import fr.roumoulou.travellingdimension.portal.PortalTint
import fr.roumoulou.travellingdimension.portal.TravelPortalBlock
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory

/**
 * Point d'entrée client.
 *
 * Note : depuis MC 26.x le render layer d'un bloc est déduit de ses textures
 * (le modèle du portail déclare `force_translucent`), il n'y a donc plus rien
 * à enregistrer côté code pour le rendu translucide.
 */
class TravellingDimensionClient : ClientModInitializer {

    override fun onInitializeClient() {
        // Config du serveur : reçue à la connexion, puis à chaque modification.
        ClientPlayNetworking.registerGlobalReceiver(TravelConfigSyncPayload.TYPE) { payload, _ ->
            ClientTravelConfig.onSync(payload.configJson, payload.editable)
        }

        // En quittant la partie, on oublie : l'écran doit se remettre à éditer le
        // fichier local, pas garder les valeurs du dernier serveur visité.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ClientTravelConfig.forget() }

        // Teinte du portail selon sa couleur de lien. Le modèle marque ses faces
        // d'un tintindex 0, cette fabrique fournit la couleur correspondante.
        // La texture est en niveaux de gris et TOUT portail est teinté, y compris
        // PortalTint.NONE, dont la teinte est l'améthyste d'origine (voir PortalTint).
        BlockColorRegistry.register(
            BlockTintsFactory { state, _, _, tints ->
                tints.add(state.getOptionalValue(TravelPortalBlock.COLOR).orElse(PortalTint.NONE).argb())
            },
            ModBlocks.TRAVEL_PORTAL
        )

        // Les portails du NETHER vanilla, teintables eux aussi : pack intégré désactivable
        // pour la texture neutralisée, et la fabrique de teinte qui va avec. Tout est à
        // part, rien ici ne touche au portail de voyage.
        NetherPortalTintRendering.register()
    }
}
