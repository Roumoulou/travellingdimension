// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.WorldgenMode
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

/**
 * Sélection du générateur de la dimension de voyage, avec fallback automatique.
 *
 * Mécanisme unique : le JSON embarqué de la dimension définit la génération par défaut
 * (vanilla + large biomes). Pour tout autre choix, un [SwapTarget] est résolu ici au
 * démarrage, puis [GeneratorSwapper] remplace le générateur au moment de la création
 * du monde (jamais fatal : en cas de problème, le JSON par défaut reste en place).
 *
 * - terralith / tectonic : ces mods écrasent les réglages `minecraft:overworld` ;
 *   la dimension pointe sur ces mêmes réglages et hérite de leur génération.
 * - william : pack embarqué re-namespacé (biomes WWOO sous `travellingdimension:wwoo/...`),
 *   activé uniquement dans la dimension de voyage. L'Overworld n'est pas touché.
 * - custom : ids libres depuis la config.
 */
object WorldgenSelector {

    /**
     * Réglages cibles pour le swap de générateur (null = garder le JSON par défaut).
     * [biomePreset] : id de preset du registre ; [wwooRemap] : disposition vanilla
     * codée en dur remappée vers les biomes WWOO embarqués (voir GeneratorSwapper).
     */
    data class SwapTarget(
        val noiseSettings: Identifier,
        val biomePreset: Identifier?,
        val label: String,
        val wwooRemap: Boolean = false,
    )

    private val WWOO_PACK_ID: Identifier =
        Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "wwoo_worldgen")

    private val MC_OVERWORLD = Identifier.parse("minecraft:overworld")
    private val MC_LARGE_BIOMES = Identifier.parse("minecraft:large_biomes")

    var swapTarget: SwapTarget? = null
        private set

    /**
     * Seed dédié de la dimension de voyage (null = seed du monde).
     * Consommé par les mixins ServerLevelMixin (noise + structures via getSeed)
     * et MinecraftServerMixin (seed de zoom des biomes).
     */
    @JvmStatic
    var dimensionSeed: Long? = null
        private set

    /** Message de fallback à afficher en jeu aux admins à la connexion (null = aucun). */
    var fallbackNotice: String? = null
        private set

    internal fun noteFallback(message: String) {
        if (ConfigManager.current.logFallback) {
            TravellingDimension.LOGGER.warn("Worldgen : {}", message)
        }
        fallbackNotice = message
    }

    /** À appeler au onInitialize, AVANT le chargement des datapacks. */
    fun apply() {
        val config = ConfigManager.current

        // Seed dédié (sémantique vanilla : nombre, ou texte haché ; vide = seed du monde).
        dimensionSeed = if (config.seed.isBlank()) null else {
            val parsed = net.minecraft.world.level.levelgen.WorldOptions.parseSeed(config.seed)
            if (parsed.isPresent) parsed.asLong else null
        }
        dimensionSeed?.let {
            TravellingDimension.LOGGER.info("Seed dédié de la dimension de voyage : {} (depuis \"{}\")", it, config.seed)
        }

        swapTarget = when (config.worldgen) {
            WorldgenMode.VANILLA -> {
                if (config.largeBiomes) {
                    TravellingDimension.LOGGER.info("Worldgen : vanilla (large biomes)")
                    null // le JSON par défaut est déjà exactement ça
                } else {
                    SwapTarget(MC_OVERWORLD, MC_OVERWORLD, "vanilla (biomes normaux)")
                }
            }

            WorldgenMode.TERRALITH -> {
                // Terralith embarque sa propre variante Large Biomes (il écrase AUSSI
                // minecraft:large_biomes) : y référencer donne un Terralith agrandi,
                // utilisé en pratique par la seule dimension de voyage, SANS toucher au
                // Terralith (même modifié) que l'utilisateur emploie pour son Overworld.
                // Terralith absent : ces ids existent en vanilla -> dégradation douce.
                val terralithLoaded = FabricLoader.getInstance().isModLoaded("terralith")
                if (!terralithLoaded) {
                    noteFallback(
                        "worldgen=terralith mais le mod Terralith est absent : la dimension de voyage " +
                                "utilisera la génération vanilla (${if (config.largeBiomes) "large biomes" else "biomes normaux"})."
                    )
                }
                val label =
                    if (terralithLoaded) "terralith" + if (config.largeBiomes) " (large biomes)" else ""
                    else "vanilla" + if (config.largeBiomes) " (large biomes)" else ""
                SwapTarget(if (config.largeBiomes) MC_LARGE_BIOMES else MC_OVERWORLD, MC_OVERWORLD, label)
            }

            WorldgenMode.TECTONIC -> {
                if (FabricLoader.getInstance().isModLoaded("tectonic")) {
                    // Tectonic écrase minecraft:overworld -> la dimension en hérite.
                    SwapTarget(MC_OVERWORLD, MC_OVERWORLD, "génération tectonic héritée de l'Overworld")
                } else {
                    noteFallback(
                        "Générateur 'tectonic' demandé dans la config mais mod absent : " +
                                "bascule automatique sur la génération vanilla (large biomes)."
                    )
                    null
                }
            }

            WorldgenMode.WILLIAM -> {
                if (registerWwooPack()) {
                    // Terrain vanilla (ou large) + biomes WWOO re-namespacés (Overworld intact).
                    SwapTarget(
                        if (config.largeBiomes) MC_LARGE_BIOMES else MC_OVERWORLD, null,
                        "biomes William Wythers (dimension de voyage uniquement)",
                        wwooRemap = true
                    )
                } else {
                    noteFallback(
                        "worldgen=william mais le pack embarqué wwoo_worldgen est absent du jar " +
                                "(à générer via 07-tools-and-scripts/build-wwoo-pack.ps1) : bascule sur vanilla (large biomes)."
                    )
                    null
                }
            }

            WorldgenMode.CUSTOM -> {
                val settings = parseId(config.customNoiseSettings, "customNoiseSettings")
                val preset = parseId(config.customBiomePreset, "customBiomePreset")
                if (settings != null && preset != null) {
                    SwapTarget(settings, preset, "custom ($settings + $preset)")
                } else {
                    null // parseId a déjà loggé le fallback
                }
            }
        }

        swapTarget?.let {
            TravellingDimension.LOGGER.info(
                "Worldgen : {} — noise settings '{}', biome preset '{}'", it.label, it.noiseSettings, it.biomePreset
            )
        }
    }

    private fun parseId(raw: String, fieldName: String): Identifier? =
        runCatching { Identifier.parse(raw) }.getOrElse {
            noteFallback("Config $fieldName='$raw' invalide : bascule sur la génération vanilla (large biomes).")
            null
        }

    /** Active le pack embarqué WWOO (présent uniquement si généré par l'outil dédié). */
    private fun registerWwooPack(): Boolean {
        val container = FabricLoader.getInstance().getModContainer(TravellingDimension.MOD_ID).orElseThrow()
        if (container.findPath("resourcepacks/wwoo_worldgen/pack.mcmeta").isEmpty) return false
        return ResourceLoader.registerBuiltinPack(WWOO_PACK_ID, container, PackActivationType.ALWAYS_ENABLED)
    }

    /**
     * Diagnostic au démarrage du serveur : vérifie que la dimension existe et logge
     * les réglages de bruit réellement utilisés.
     */
    fun logEffectiveWorldgen(server: MinecraftServer) {
        val travelLevel = server.getLevel(TravelDimensionKeys.TRAVEL_LEVEL)
        if (travelLevel == null) {
            val message = "La dimension de voyage '${TravelDimensionKeys.TRAVEL_ID}' n'est pas chargée !"
            TravellingDimension.LOGGER.error(message)
            fallbackNotice = message
            return
        }

        val generator = travelLevel.chunkSource.generator
        if (generator is NoiseBasedChunkGenerator) {
            val settingsKey = generator.generatorSettings().unwrapKey().map { it.identifier() }.orElse(null)
            TravellingDimension.LOGGER.info(
                "Dimension de voyage active — générateur noise, settings = {}, biomeSource = {}",
                settingsKey, generator.biomeSource
            )
        } else {
            TravellingDimension.LOGGER.info(
                "Dimension de voyage active — générateur {}", generator.javaClass.simpleName
            )
        }
    }
}
