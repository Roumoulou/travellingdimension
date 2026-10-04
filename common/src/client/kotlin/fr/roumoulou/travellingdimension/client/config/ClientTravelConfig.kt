// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.client.config

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.network.TravelConfigUpdatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Ce que le client sait de la config, et où il l'écrit.
 *
 * Deux situations, et c'est toute la subtilité de l'écran :
 *
 * - **Hors partie** (écran titre) : il n'y a pas de serveur, l'écran édite le fichier
 *   local `config/travellingdimension/config.json`. C'est celui que la prochaine partie
 *   solo lira.
 * - **En partie** : le serveur a envoyé SA config à la connexion, c'est elle qui fait
 *   autorité et c'est elle qu'on affiche. Le fichier local du client ne sert à rien
 *   ici, l'afficher serait mentir. Enregistrer envoie une demande au serveur, qui
 *   revérifie la permission et rediffuse ce qu'il a retenu.
 *
 * En solo, les deux se rejoignent : le serveur intégré tourne dans le même processus
 * et lit le même fichier, et l'hôte a toujours le droit de modifier.
 */
object ClientTravelConfig {

    /** Config reçue du serveur (null = pas en partie, ou serveur sans le mod). */
    var serverConfig: TravelConfig? = null
        private set

    /** Droit de modifier, tel que le serveur l'a calculé pour NOUS. */
    var serverEditable: Boolean = false
        private set

    val connected: Boolean get() = serverConfig != null

    fun onSync(configJson: String, canEdit: Boolean) {
        serverConfig = try {
            ConfigManager.decode(configJson)
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error("Config received from the server is unreadable: {}", e.message)
            null
        }
        serverEditable = canEdit && serverConfig != null
    }

    /** À la déconnexion : on oublie la config du serveur, on redevient local. */
    fun forget() {
        serverConfig = null
        serverEditable = false
    }

    /** La config que l'écran doit afficher. */
    fun displayed(): TravelConfig = serverConfig ?: ConfigManager.current

    /** Hors partie on édite son propre fichier ; en partie, seul le serveur décide. */
    fun mayEdit(): Boolean = if (connected) serverEditable else true

    /** Enregistre : demande au serveur si l'on est en partie, fichier local sinon. */
    fun save(config: TravelConfig) {
        if (connected) {
            if (!ClientPlayNetworking.canSend(TravelConfigUpdatePayload.TYPE)) {
                TravellingDimension.LOGGER.warn(
                    "The server does not accept remote config changes (mod missing or too old)"
                )
                return
            }
            ClientPlayNetworking.send(TravelConfigUpdatePayload(ConfigManager.encode(config)))
        } else {
            // L'écran borne déjà ses valeurs : un refus ici n'arrive que par un chemin que
            // l'écran ne couvre pas, et le log suffit, il n'y a personne à qui parler hors partie.
            try {
                ConfigManager.apply(config)
            } catch (e: Exception) {
                TravellingDimension.LOGGER.error("Local config refused or not written: {}", e.message)
            }
        }
    }
}
