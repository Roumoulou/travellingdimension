// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.WorldgenMode
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.WorldOptions
import java.nio.file.Path
import java.util.SortedMap
import kotlin.io.path.name

/**
 * Le choix du terrain de VOYAGE, et ce que le serveur en retient.
 *
 * Au chargement du mod, rien ne se décide : [prepare] crée le dossier où se dépose le jar de
 * WWOO, et prépare seulement les datapacks qui pourraient servir, parce qu'un datapack ne se
 * déclare plus une fois les mondes créés. À la
 * création des mondes, registres chargés, [select] lit la configuration, détecte ce qui est
 * installé ([WorldgenDetector]) et résout ([WorldgenResolver]) ; [GeneratorSwapper] construit ce
 * que la résolution rend. Serveur démarré, [logEffectiveWorldgen] écrit ce que VOYAGE génère
 * vraiment.
 *
 * Ce que [select] retient vaut pour le serveur en cours : un réglage de génération modifié
 * ensuite ne change rien avant le prochain démarrage du serveur.
 */
object WorldgenSelector {

    private const val MINECRAFT = "minecraft"

    /** Le dossier où le joueur dépose le jar de WWOO ([WorldgenFolder]) : le message `william_no_source` le donne. */
    private val WORLDGEN_FOLDER: Path = FabricLoader.getInstance().gameDir.resolve(TravellingDimension.MOD_ID).resolve("worldgen")

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
     * Au chargement du mod, avant celui des datapacks : crée le dossier `worldgen/` et sa notice,
     * quel que soit le mode, puis prépare les copies que le mode demande ([WorldgenPreparation]).
     * En mode `william`, sans le mod WWOO, le dossier se lit ([WilliamJars]), et le jar qu'il
     * donne fait préparer la copie William. Les copies sont fabriquées ou reprises du cache
     * ([WorldgenCopyMaker]), puis déclarées au jeu ([WorldgenPacks]) ; aucune ne l'est en mode
     * `terralith`. Une copie que le garde-fou tient désactivée n'est pas déclarée, et
     * [WorldgenPacks] la retient pour la résolution, avec les jars WWOO refusés.
     */
    fun prepare() {
        val mode = ConfigManager.current.worldgen
        val loader = FabricLoader.getInstance()
        ensureFolder()

        // WWOO installé en mod l'emporte sur le jar déposé : le dossier ne se lit pas.
        val wwooLoaded = loader.isModLoaded(WilliamJars.MOD_ID)
        val reading = if (mode == WorldgenMode.WILLIAM && !wwooLoaded) readFolder(loader) else null
        if (mode == WorldgenMode.WILLIAM && wwooLoaded) TravellingDimension.LOGGER.info("Worldgen folder: not read, WWOO is installed as a mod")

        val taken = reading?.taken
        var refusals = reading?.reported.orEmpty()
        val wanted = WorldgenPreparation.copiesFor(mode, wwooModLoaded = wwooLoaded, williamJarAccepted = taken != null)
        val sources = ArrayList<WorldgenCopyMaker.Source>()
        if (WorldgenCopy.VANILLA in wanted) sources += VanillaCopy.source()
        if (WorldgenCopy.WILLIAM in wanted && taken != null) {
            try {
                sources += WilliamCopy.source(taken.file)
            } catch (e: Exception) {
                // Le jar ne se lit plus, le temps d'en prendre l'empreinte.
                refusals = listOf(refuse(WilliamJarRefusal.Unreadable(taken.file.name, e.toString())))
            }
        }

        val made = if (sources.isEmpty()) emptyMap() else WorldgenCopyMaker.prepare(WorldgenPacks.GENERATED_FOLDER, sources)
        if (taken != null && made[WorldgenCopy.WILLIAM] == WorldgenCopyMaker.Readiness.FAILED) {
            refusals = listOf(WilliamJarRefusal.Unreadable(taken.file.name, "its copy could not be made"))
        }
        WorldgenPacks.prepare(
            // Sans la copie vanilla, rien ne se déclare : la copie William la référence.
            wanted = if (made[WorldgenCopy.VANILLA] == WorldgenCopyMaker.Readiness.READY) made.filterValues { it == WorldgenCopyMaker.Readiness.READY }.keys else emptySet(),
            disabledCopies = made.filterValues { it == WorldgenCopyMaker.Readiness.DISABLED }.keys,
            refusals = refusals,
        )
    }

    /** Crée le dossier `worldgen/` et sa notice. Jamais fatal : sans lui, `william` dira que le jar manque. */
    private fun ensureFolder() {
        try {
            if (WorldgenFolder.ensure(WORLDGEN_FOLDER)) TravellingDimension.LOGGER.info("Worldgen folder: '{}' is ready, with its notice", WORLDGEN_FOLDER)
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Worldgen folder: '{}' could not be prepared: {}", WORLDGEN_FOLDER, e.toString())
        }
    }

    /** Lit le dossier `worldgen/` fichier par fichier, et dit au log ce qu'il en fait : une ligne par fichier ignoré, refusé ou pris. */
    private fun readFolder(loader: FabricLoader): WilliamJars.Reading {
        val deposits = try {
            WorldgenFolder.deposits(WORLDGEN_FOLDER)
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Worldgen folder: '{}' could not be read: {}", WORLDGEN_FOLDER, e.toString())
            emptyList()
        }
        val reading = WilliamJars.read(deposits, loader.getModContainer(MINECRAFT).orElseThrow().metadata.version)
        reading.jars.filterIsInstance<WilliamJar.Foreign>().forEach { TravellingDimension.LOGGER.info("{}", WorldgenReport.ignoredFileLine(it.file.name, it.reason)) }
        reading.refused.forEach { refuse(it) }
        reading.taken?.let { TravellingDimension.LOGGER.info("{}", WorldgenReport.takenJarLine(it.file.name, it.version.friendlyString, reading.accepted.size)) }
        return reading
    }

    /** Écrit au log le refus [refusal] d'un jar WWOO, et le rend. */
    private fun refuse(refusal: WilliamJarRefusal): WilliamJarRefusal {
        TravellingDimension.LOGGER.info("{}", WorldgenReport.refusedJarLine(refusal))
        return refusal
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
        return retain(Selection(config, detection, WorldgenResolver.resolve(config, detection, WORLDGEN_FOLDER.toString())))
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
