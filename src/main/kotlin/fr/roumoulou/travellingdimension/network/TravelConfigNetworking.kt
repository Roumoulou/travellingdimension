package fr.roumoulou.travellingdimension.network

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions

/**
 * Côté serveur de l'édition de config en jeu.
 *
 * Règle d'or : **le serveur fait autorité**. Le client affiche ce qu'on lui envoie et
 * peut demander un changement ; c'est le serveur qui vérifie la permission, assainit
 * les valeurs, écrit le fichier, puis rediffuse ce qu'il a retenu à tous les joueurs.
 *
 * Un client sans le mod, ou un serveur sans le mod, ne changent rien : les paquets ne
 * partent que si le destinataire les déclare (`canSend`).
 */
object TravelConfigNetworking {

    /** À appeler au onInitialize, sur les deux côtés (les types doivent être connus des deux). */
    fun register() {
        PayloadTypeRegistry.clientboundPlay().register(TravelConfigSyncPayload.TYPE, TravelConfigSyncPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(TravelConfigUpdatePayload.TYPE, TravelConfigUpdatePayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(TravelConfigUpdatePayload.TYPE) { payload, context ->
            handleUpdate(payload, context.player(), context.server())
        }

        // À la connexion, le joueur reçoit la config du serveur : c'est elle que son
        // écran affichera, pas le fichier de son propre dossier de jeu.
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            sendTo(handler.player, server)
        }
    }

    /**
     * Qui a le droit de modifier ? L'hôte d'une partie solo (c'est sa partie), et
     * sur un serveur les joueurs de niveau 4. Une permission refusée n'est jamais
     * silencieuse : le client est remis d'aplomb avec la vraie config.
     */
    fun mayEdit(player: ServerPlayer, server: MinecraftServer): Boolean =
        server.isSingleplayerOwner(player.nameAndId()) ||
                player.permissions().hasPermission(Permissions.COMMANDS_OWNER)

    fun sendTo(player: ServerPlayer, server: MinecraftServer) {
        if (!ServerPlayNetworking.canSend(player, TravelConfigSyncPayload.TYPE)) return
        ServerPlayNetworking.send(
            player,
            TravelConfigSyncPayload(ConfigManager.encode(), mayEdit(player, server))
        )
    }

    private fun handleUpdate(payload: TravelConfigUpdatePayload, player: ServerPlayer, server: MinecraftServer) {
        if (!mayEdit(player, server)) {
            TravellingDimension.LOGGER.warn(
                "Refus de modification de config : {} n'a pas la permission requise",
                player.name.string
            )
            player.sendSystemMessage(
                Component.translatable("travellingdimension.config.denied").withStyle(ChatFormatting.RED)
            )
            sendTo(player, server)
            return
        }

        val requested = try {
            ConfigManager.decode(payload.configJson)
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error(
                "Config reçue de {} illisible : {}", player.name.string, e.message
            )
            player.sendSystemMessage(
                Component.translatable("travellingdimension.config.invalid").withStyle(ChatFormatting.RED)
            )
            sendTo(player, server)
            return
        }

        val previous = ConfigManager.current
        val applied = ConfigManager.apply(requested)

        TravellingDimension.LOGGER.info("Config modifiée en jeu par {}", player.name.string)
        player.sendSystemMessage(
            Component.translatable("travellingdimension.config.saved").withStyle(ChatFormatting.GREEN)
        )
        if (applied.needsRestartAgainst(previous)) {
            player.sendSystemMessage(
                Component.translatable("travellingdimension.config.restart_needed").withStyle(ChatFormatting.GOLD)
            )
        }

        // Tout le monde se resynchronise : chacun avec SON propre droit de modifier.
        server.playerList.players.forEach { sendTo(it, server) }
    }
}
