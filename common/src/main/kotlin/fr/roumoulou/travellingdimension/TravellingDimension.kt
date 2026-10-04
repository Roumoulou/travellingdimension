// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.command.LockCommand
import fr.roumoulou.travellingdimension.command.TravelTestCommand
import fr.roumoulou.travellingdimension.command.WhereCommand
import fr.roumoulou.travellingdimension.command.ZoneHighlight
import fr.roumoulou.travellingdimension.command.ZonesCommand
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.WorldgenSelector
import fr.roumoulou.travellingdimension.nether.NetherPortalCommand
import fr.roumoulou.travellingdimension.nether.NetherPortalDye
import fr.roumoulou.travellingdimension.nether.NetherPortalTints
import fr.roumoulou.travellingdimension.network.TravelConfigNetworking
import fr.roumoulou.travellingdimension.portal.PortalLocks
import fr.roumoulou.travellingdimension.portal.PortalMemory
import fr.roumoulou.travellingdimension.registry.ModBlocks
import fr.roumoulou.travellingdimension.registry.ModItems
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Point d'entrée principal du mod Travelling Dimension.
 *
 * Une dimension de voyage de type Overworld au ratio configurable (1:16 par défaut) :
 * 1 bloc parcouru dans la Travel Dimension = `ratio` blocs dans l'Overworld.
 */
class TravellingDimension : ModInitializer {

    companion object {
        const val MOD_ID = "travellingdimension"
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

        val CONFIG_DIRECTORY: Path = FabricLoader.getInstance().configDir.resolve(MOD_ID)
    }

    override fun onInitialize() {
        LOGGER.info("Travelling Dimension: initialising...")

        // 1. La configuration d'abord : ce premier accès ouvre et valide le store ; un fichier
        //    cassé lève ici, et le jeu ne démarre pas.
        ConfigManager.announce()

        // 2. La génération de VOYAGE : rien ne se décide ici, le terrain se choisit à la
        //    création des mondes. Seul se déclare le datapack qui pourrait servir, avant le
        //    chargement des datapacks.
        WorldgenSelector.prepare()

        // 3. Les registres.
        ModBlocks.init()
        ModItems.init()

        // 4. Les attachements persistants : la mémoire de trajet, portée par l'entité, et
        //    les verrous, portés par le chunk.
        PortalMemory.register()
        PortalLocks.register()

        // 5. Les commandes ouvertes à tous : /where, /tdzones et son rideau de particules
        //    (envoyé au seul joueur qui l'a demandé, aucun code client), /tdlock.
        WhereCommand.register()
        ZoneHighlight.register()
        ZonesCommand.register()
        LockCommand.register()

        // 6. Les portails du NETHER vanilla : la couleur dans un attachement de chunk, le
        //    clic droit au colorant, la commande d'opérateur. Rien ici ne touche à VOYAGE,
        //    et rien ne change tant qu'aucun colorant n'est posé.
        NetherPortalTints.register()
        NetherPortalDye.register()
        NetherPortalCommand.register()

        // 7. Le banc d'essai des portails, réservé aux opérateurs.
        TravelTestCommand.register()

        // 8. L'édition de la config en jeu (écran Mod Menu côté client). Le serveur reste
        //    seul juge : permission, bornes, écriture du fichier.
        TravelConfigNetworking.register()

        // 9. Le diagnostic au démarrage du serveur, et les messages du repli aux opérateurs.
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            WorldgenSelector.logEffectiveWorldgen(server)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                WorldgenSelector.notices.forEach { player.sendSystemMessage(Component.translatable(it.key, *it.arguments.toTypedArray())) }
            }
        }
    }
}
