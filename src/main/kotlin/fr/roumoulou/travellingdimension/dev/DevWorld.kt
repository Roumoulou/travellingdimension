package fr.roumoulou.travellingdimension.dev

import fr.moulou.storify.core.StoreFactory
import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ModJson
import kotlinx.serialization.Serializable
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings

/**
 * Aménagements réservés à l'environnement de développement.
 *
 * Hors `gradlew runClient` / `runServer`, tout ici est inerte : un serveur de
 * production n'est jamais concerné, même si le fichier de config est présent.
 */
object DevWorld {

    /**
     * Réglages lus depuis `config/travellingdimension/dev.json`. Le fichier naît au premier
     * lancement où il manque, copie à l'octet de la ressource commentée
     * `travellingdimension/dev.json` du jar : ce que le développeur trouve est ce que le mod a
     * livré, commentaires compris.
     */
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

    private val settings: DevSettings by lazy { loadSettings() }

    /**
     * Un store Storify en lecture seule, le temps de lire : le mod n'écrit ce fichier qu'une
     * fois, en copiant sa ressource quand le fichier manque. Sans ce fichier, le réglage
     * existerait mais resterait invisible, rien ne l'annonçant dans le dossier config.
     */
    private fun loadSettings(): DevSettings {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return DevSettings(flatWorld = false)

        val file = TravellingDimension.CONFIG_DIRECTORY.resolve("dev.json")
        return try {
            StoreFactory.createFromResource<DevSettings>(
                file.toString(), "travellingdimension/dev.json", ModJson.format, ModJson.storeConfig(readOnly = true)
            ).use { it.data }
        } catch (e: Exception) {
            TravellingDimension.LOGGER.error("[dev] {} illisible ({}), valeurs par défaut", file, e.message)
            DevSettings()
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
