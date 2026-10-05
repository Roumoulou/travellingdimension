// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.WorldgenMode
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.WorldOptions
import java.util.SortedMap

/**
 * Le choix du terrain de VOYAGE, et ce que le serveur en retient.
 *
 * Au chargement du mod, rien ne se décide : [prepare] prépare seulement les datapacks qui
 * pourraient servir, parce qu'un datapack ne se déclare plus une fois les mondes créés. À la
 * création des mondes, registres chargés, [select] lit la configuration, détecte ce qui est
 * installé ([WorldgenDetector]) et résout ([WorldgenResolver]) ; [GeneratorSwapper] construit ce
 * que la résolution rend. Serveur démarré, [logEffectiveWorldgen] écrit ce que VOYAGE génère
 * vraiment.
 *
 * Ce que [select] retient vaut pour le serveur en cours : un réglage de génération modifié
 * ensuite ne change rien avant le prochain démarrage du serveur.
 */
object WorldgenSelector {

    private val WWOO_PACK_ID: Identifier =
        Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "wwoo_worldgen")

    /** Le dossier où le joueur dépose le jar de WWOO : le message `william_no_source` le donne. */
    private val WORLDGEN_FOLDER: String =
        FabricLoader.getInstance().gameDir.resolve(TravellingDimension.MOD_ID).resolve("worldgen").toString()

    /** Le groupe d'un biome, ou d'un réglage de bruit, sans clé de registre : un datapack peut le déclarer en ligne. */
    private const val UNREGISTERED = "unregistered"

    /**
     * Seed dédié de VOYAGE (null = seed du monde), lu à la création des mondes.
     * Consommé par les mixins ServerLevelMixin (noise + structures via getSeed)
     * et MinecraftServerMixin (seed de zoom des biomes).
     */
    @JvmStatic
    var dimensionSeed: Long? = null
        private set

    /** Les messages du repli du serveur en cours, envoyés aux opérateurs à chaque connexion. */
    var notices: List<WorldgenNotice> = emptyList()
        private set

    /** Ce que [select] a retenu pour le serveur en cours. */
    private var selection: Selection? = null

    /**
     * Au chargement du mod, avant celui des datapacks : prépare les copies que le mode demande
     * ([WorldgenPreparation]). La copie vanilla est fabriquée ou reprise du cache ([VanillaCopy]),
     * puis déclarée au jeu ([WorldgenPacks]) ; aucune ne l'est en mode `terralith`. Le datapack WWOO
     * embarqué se déclare ensuite quand le mode est `william` : il n'existe que là où l'outil du
     * projet l'a fabriqué.
     */
    fun prepare() {
        val mode = ConfigManager.current.worldgen
        val mods = FabricLoader.getInstance()

        // Aucun jar n'est accepté : le mod ne lit pas son dossier worldgen.
        val wanted = WorldgenPreparation.copiesFor(mode, wwooModLoaded = mods.isModLoaded("wwoo"), williamJarAccepted = false)
        // Sans la copie vanilla, rien ne se déclare : la copie William la référence.
        WorldgenPacks.prepare(if (WorldgenCopy.VANILLA in wanted && !VanillaCopy.prepare()) emptySet() else wanted)

        if (mode != WorldgenMode.WILLIAM) return
        val container = mods.getModContainer(TravellingDimension.MOD_ID).orElseThrow()
        if (container.findPath("resourcepacks/wwoo_worldgen/pack.mcmeta").isPresent) {
            ResourceLoader.registerBuiltinPack(WWOO_PACK_ID, container, PackActivationType.ALWAYS_ENABLED)
        }
    }

    /**
     * À la création des mondes : lit la configuration et le seed dédié, détecte ce que portent
     * [registries], résout le terrain de VOYAGE et le retient.
     */
    fun select(registries: RegistryAccess): Selection {
        // Une copie : la racine du store se modifie en place, et ce choix vaut pour le serveur en cours.
        val config = ConfigManager.current.copy()

        // Seed dédié (sémantique vanilla : nombre, ou texte haché ; vide = seed du monde).
        dimensionSeed = parseSeed(config.seed)
        dimensionSeed?.let {
            TravellingDimension.LOGGER.info("Dedicated seed of the travel dimension: {} (from \"{}\")", it, config.seed)
        }

        val detection = WorldgenDetector.detect(registries, config)
        return retain(Selection(config, detection, WorldgenResolver.resolve(config, detection, WORLDGEN_FOLDER)))
    }

    /** La construction du terrain de [failed] a levé pour [reason] : descend d'un maillon du repli, et le retient. */
    fun descend(failed: Selection, reason: String): Selection =
        retain(failed.copy(resolution = WorldgenResolver.fallback(failed.config, failed.detection, failed.resolution, reason)))

    private fun retain(selection: Selection): Selection {
        this.selection = selection
        notices = selection.resolution.notices
        return selection
    }

    private fun parseSeed(raw: String): Long? {
        if (raw.isBlank()) return null
        val parsed = WorldOptions.parseSeed(raw)
        return if (parsed.isPresent) parsed.asLong else null
    }

    /**
     * Diagnostic au démarrage du serveur. Quand une copie est préparée, une première ligne dit
     * les datapacks du mod que le serveur a sélectionnés ([WorldgenReport.datapacksLine]). Puis
     * il vérifie que VOYAGE existe, écrit les messages du repli au niveau `WARN` quand
     * `logFallback` est actif, et la ligne « Travel dimension active », ce que VOYAGE génère
     * vraiment ([WorldgenReport.activeLine]). Elle dit le mode retenu à la création des mondes, le
     * réglage de bruit du générateur, ou sa classe quand il n'est pas de bruit, ses biomes comptés
     * par espace de noms ([biomeCounts]), et Terralith et WWOO quand ils sont détectés.
     */
    fun logEffectiveWorldgen(server: MinecraftServer) {
        val prepared = WorldgenPacks.prepared.keys.map { it.packId }
        if (prepared.isNotEmpty()) {
            TravellingDimension.LOGGER.info("{}", WorldgenReport.datapacksLine(prepared, WorldgenPacks.selectedIn(server.packRepository)))
        }

        val travelLevel = server.getLevel(TravelDimensionKeys.TRAVEL_LEVEL)
        if (travelLevel == null) {
            // Sans VOYAGE, rien n'a été choisi pour ce serveur : ce qu'un monde précédent a retenu ne vaut plus.
            selection = null
            notices = listOf(WorldgenNotice.DimensionMissing)
        }
        if (ConfigManager.current.logFallback) {
            notices.forEach { TravellingDimension.LOGGER.warn("{}", WorldgenReport.warning(it)) }
        }
        // Quand VOYAGE existe, select() a retenu son choix à sa création.
        val selection = selection
        if (travelLevel == null || selection == null) return

        val generator = travelLevel.chunkSource.generator
        val terrain = if (generator is NoiseBasedChunkGenerator) {
            val settings = generator.generatorSettings().unwrapKey().map { it.identifier().toString() }.orElse(UNREGISTERED)
            "noise settings '$settings'"
        } else {
            "generator ${generator.javaClass.simpleName}"
        }
        TravellingDimension.LOGGER.info("{}", WorldgenReport.activeLine(selection.config.worldgen, terrain, biomeCounts(generator.biomeSource), selection.detection))
    }

    /**
     * Les biomes que [source] peut poser, comptés par espace de noms, dans l'ordre alphabétique.
     * Ceux du mod se comptent par copie, au premier segment de leur chemin :
     * `travellingdimension:wwoo`. Un biome sans clé de registre se compte sous `unregistered`.
     *
     * `possibleBiomes()` fige son ensemble au premier appel : la fonction s'appelle serveur
     * démarré, registres chargés. Publique pour que l'étage 2 des tests compte par le même
     * chemin que la ligne.
     */
    fun biomeCounts(source: BiomeSource): SortedMap<String, Int> =
        source.possibleBiomes()
            .groupingBy { biome -> biome.unwrapKey().map { groupOf(it.identifier()) }.orElse(UNREGISTERED) }
            .eachCount()
            .toSortedMap()

    private fun groupOf(biome: Identifier): String =
        if (biome.namespace == TravellingDimension.MOD_ID && '/' in biome.path) "${biome.namespace}:${biome.path.substringBefore('/')}"
        else biome.namespace

    /** Ce qui est retenu à la création des mondes : la configuration lue, ce qui a été détecté, et ce que la résolution a rendu. */
    data class Selection(val config: TravelConfig, val detection: WorldgenDetection, val resolution: WorldgenResolution)
}
