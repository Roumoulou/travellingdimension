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
        LOGGER.info("Travelling Dimension : initialisation…")

        LOGGER.trace("HEHEHEHEHEHHE")

        // 1. Config d'abord : le choix du worldgen en dépend.
        ConfigManager.load()

        // 2. Sélection du générateur (datapack miroir si Terralith/Tectonic).
        WorldgenSelector.apply()

        // 3. Registres.
        ModBlocks.init()
        ModItems.init()

        // 4. Commandes utilitaires (accessibles à tous les joueurs).
        WhereCommand.register()

        // 4 pre. L'affichage de l'emprise de recherche d'un portail, en particules envoyées
        //        au seul joueur qui l'a demandé : aucun code client, ça marche en vanilla.
        ZoneHighlight.register()
        ZonesCommand.register()

        // 4 ter. Banc d'essai des portails, réservé aux opérateurs.
        TravelTestCommand.register()

        // 4 quater. Mémoire des trajets : on ressort par SON portail, pas par le
        //           premier de la cellule. En mémoire vive uniquement.
        PortalMemory.register()

        // 4 quinquies. Les verrous de portail : un joueur réserve le territoire de son
        //              portail, et personne ne peut plus en allumer un autre à portée.
        PortalLocks.register()
        LockCommand.register()

        // 4 quinquies. Les liens de couleur sur les portails du NETHER vanilla. Bloc à
        //              part, réglage à part (netherPortalTints) : rien ici ne touche à la
        //              dimension de voyage, et rien ne change tant qu'aucun colorant
        //              n'est posé.
        NetherPortalTints.register()
        NetherPortalDye.register()
        NetherPortalCommand.register()

        // 4 bis. Édition de la config en jeu (écran Mod Menu côté client).
        //        Le serveur reste seul juge : permission, bornes, écriture du fichier.
        TravelConfigNetworking.register()

        // 5. Diagnostic au démarrage serveur + message de fallback aux admins.
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            WorldgenSelector.logEffectiveWorldgen(server)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val notice = WorldgenSelector.fallbackNotice ?: return@register
            val player = handler.player
            if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                player.sendSystemMessage(Component.literal("[Travelling Dimension] $notice"))
            }
        }
    }
}
