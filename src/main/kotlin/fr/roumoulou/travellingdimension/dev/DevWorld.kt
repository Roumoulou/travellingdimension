package fr.roumoulou.travellingdimension.dev

import fr.roumoulou.travellingdimension.TravellingDimension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Aménagements réservés à l'environnement de développement.
 *
 * Hors `gradlew runClient` / `runServer`, tout ici est inerte : un serveur de
 * production n'est jamais concerné, même si le fichier de config est présent.
 */
object DevWorld {

    /** Réglages lus depuis `config/travellingdimension/dev.json`. */
    @Serializable
    data class DevSettings(
        /**
         * Génère l'Overworld en superflat (le générateur vanilla, pas une variante
         * maison). Pratique pour bâtir des portails de test sans terraformer.
         *
         * Attention : le remplacement a lieu au chargement du monde. Un monde déjà
         * commencé en terrain normal verra ses NOUVEAUX chunks générés plats.
         * Passer à false pour tester la génération de portail en montagne ou en océan.
         */
        val flatWorld: Boolean = true,

        /** Hauteur de la surface d'herbe du superflat (63 = niveau de la mer habituel). */
        val surfaceY: Int = 63,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
        isLenient = true
    }

    private val settings: DevSettings by lazy { loadSettings() }

    private fun loadSettings(): DevSettings {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return DevSettings(flatWorld = false)

        val file = TravellingDimension.CONFIG_DIRECTORY.resolve("dev.json")
        return try {
            if (file.exists()) {
                json.decodeFromString<DevSettings>(file.readText().removePrefix("﻿"))
            } else {
                // Écrit à la première exécution : sans ce fichier, le réglage existe
                // mais reste invisible (rien ne l'annonce dans le dossier config).
                writeDefaultTemplate(file)
                DevSettings()
            }
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error("[dev] {} illisible ({}), valeurs par défaut", file, e.message)
            DevSettings()
        }
    }

    /** Template commenté du dev.json, écrit quand le fichier n'existe pas encore. */
    private fun writeDefaultTemplate(file: java.nio.file.Path) {
        try {
            file.parent.createDirectories()
            file.writeText(
                """
                {
                  // ════════ Travelling Dimension - réglages de DÉVELOPPEMENT ════════
                  // Ce fichier n'est lu que sous gradlew runClient / runServer.
                  // Un serveur de production l'ignore totalement, même s'il est présent.

                  // Génère l'Overworld en superflat (générateur vanilla) : idéal pour
                  // bâtir des cadres de portail sans terraformer.
                  // Attention : le remplacement a lieu au chargement du monde, donc un
                  // monde commencé en terrain normal verra ses NOUVEAUX chunks générés
                  // plats. Passer à false pour éprouver la génération de portail en
                  // montagne ou en océan.
                  "flatWorld": true,

                  // Hauteur de la surface d'herbe du superflat (63 = niveau de la mer).
                  "surfaceY": 63
                }
                """.trimIndent() + "\n"
            )
            TravellingDimension.LOGGER.info("[dev] réglages de développement créés : {}", file)
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error("[dev] impossible d'écrire {} : {}", file, e.message)
        }
    }

    /**
     * Remplace le générateur de l'Overworld par le superflat vanilla.
     * Sans effet hors développement ou si `flatWorld` est désactivé.
     */
    @JvmStatic
    fun flattenOverworld(server: MinecraftServer, stem: LevelStem): LevelStem {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return stem
        if (!settings.flatWorld) return stem

        // Le remplacement a lieu à chaque chargement, y compris si le monde est déjà
        // plat : c'est ce qui permet de changer surfaceY et de voir l'effet sur les
        // chunks encore à générer, sans avoir à recréer le monde.

        return try {
            val access = server.registryAccess()
            val biomes = access.lookupOrThrow(Registries.BIOME)
            val base = FlatLevelGeneratorSettings.getDefault(
                biomes,
                access.lookupOrThrow(Registries.STRUCTURE_SET),
                access.lookupOrThrow(Registries.PLACED_FEATURE),
            )

            // Couches explicites : le défaut de 26.2 est de la pierre nue. On veut une
            // surface d'herbe à une hauteur naturelle, pour travailler aux coordonnées
            // habituelles (y=63, comme le niveau de la mer d'un monde normal).
            // L'Overworld est en cours de création : sa hauteur se lit sur le type de
            // dimension, pas sur server.overworld() qui n'existe pas encore.
            val minY = stem.type().value().minY()
            val stoneHeight = settings.surfaceY - minY - 4 // bedrock + stone + 3 dirt + grass
            val layers = listOf(
                FlatLayerInfo(1, Blocks.BEDROCK),
                FlatLayerInfo(stoneHeight.coerceAtLeast(1), Blocks.STONE),
                FlatLayerInfo(3, Blocks.DIRT),
                FlatLayerInfo(1, Blocks.GRASS_BLOCK),
            )
            val flatSettings = base.withBiomeAndLayers(
                layers,
                base.structureOverrides(),
                biomes.getOrThrow(Biomes.PLAINS),
            )

            TravellingDimension.LOGGER.info(
                "[dev] Overworld généré en superflat : surface d'herbe à y={} (dev.json : flatWorld)",
                settings.surfaceY
            )
            LevelStem(stem.type(), FlatLevelSource(flatSettings))
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error(
                "[dev] superflat impossible ({}), le générateur d'origine est conservé", e.message
            )
            stem
        }
    }
}
