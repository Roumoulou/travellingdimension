// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gametest

import com.google.gson.JsonElement
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.dimension.VanillaCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyGuard
import fr.roumoulou.travellingdimension.dimension.WorldgenDetection
import fr.roumoulou.travellingdimension.dimension.WorldgenDetector
import fr.roumoulou.travellingdimension.dimension.WorldgenLoadWatch
import fr.roumoulou.travellingdimension.dimension.WorldgenPacks
import fr.roumoulou.travellingdimension.dimension.WorldgenResolver
import fr.roumoulou.travellingdimension.dimension.WorldgenSelector
import fr.roumoulou.travellingdimension.gameversion.GameVersion
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.tags.TagKey
import net.minecraft.util.Util
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.validation.DirectoryValidator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * VOYAGE, la dimension elle-même : son type, son générateur, ce que la détection lit des registres, et les datapacks que le mod charge
 * pour elle.
 *
 * VOYAGE est une dimension de type OVERWORLD : son type de dimension dit ce que dit celui de
 * l'OVERWORLD, `coordinate_scale` mis à part, qui porte le ratio. Joué contre le jeu de chaque
 * module de version, à chaque build : quand une release ajoute ou change un attribut, l'écart
 * devient un échec qui le nomme, au lieu d'une enquête à la main.
 *
 * Les attributs se comparent à leur valeur effective, défaut compris, parce que c'est elle qui joue
 * en jeu : un attribut que `travel.json` ne déclare pas prend son défaut. Le `straw_bed_rule` de
 * 26.3 en est le cas : absent de VOYAGE, il y vaut la règle même que l'OVERWORLD déclare. Valeurs
 * et champs se comparent en JSON, par leurs codecs et les registres du serveur : certains types de
 * valeur n'ont pas d'égalité par valeur.
 *
 * Les tests de la génération tournent dans les deux runs de l'étage 2, et savent lequel par le nom que le build leur donne
 * ([GameTestRun]) : un run n'éprouve qu'un mode, parce que le générateur de VOYAGE se fige au lancement du jeu.
 */
class TravelDimensionGameTests {

    private companion object {
        /** La propriété système par laquelle le build nomme le run à ses tests (section 7 du plugin de version). */
        const val RUN_PROPERTY = "${TravellingDimension.MOD_ID}.gametest.run"

        /** Un `pack.mcmeta` de datapack que les trois versions servies lisent : leurs formats vont de 101 à 121. */
        const val PACK_MCMETA = """{ "pack": { "description": "A copy made by Travelling Dimension", "min_format": 101, "max_format": 121 } }"""

        /** Le tag des biomes où naissent les villages de plaine : le datapack vanilla y liste `minecraft:plains` et `minecraft:meadow`. */
        val VILLAGE_PLAINS: TagKey<Biome> = TagKey.create(Registries.BIOME, Identifier.withDefaultNamespace("has_structure/village_plains"))

        /** Ce que VOYAGE dit autrement que l'OVERWORLD, et lui seul : le ratio. */
        val INTENDED_DIFFERENCES = setOf("coordinate_scale")

        /** Les biomes que porte le faux jar WWOO du run `gameTestWilliam` (`common/src/fakeWwooJar`). */
        val FAKE_WWOO_BIOMES = setOf("minecraft:plains")

        /** L'espace de noms sous lequel WWOO, et le faux jar avec lui, range ses features, ses tags et ses gabarits. */
        const val WWOO_NAMESPACE = "wythers"

        /** Le groupe où se comptent les biomes de [copy] ([WorldgenSelector.biomeCounts]). */
        fun group(copy: WorldgenCopy): String = "${TravellingDimension.MOD_ID}:${copy.folder}"
    }

    /** Chaque champ du type de VOYAGE, et chaque attribut du registre, vaut ce qu'il vaut dans l'OVERWORLD. */
    @GameTest
    fun travelTypeMatchesTheOverworld(helper: GameTestHelper) {
        val registries = helper.level.registryAccess()
        val ops = registries.createSerializationContext(JsonOps.INSTANCE)
        val types = registries.lookupOrThrow(Registries.DIMENSION_TYPE)
        val overworld = types.getOrThrow(BuiltinDimensionTypes.OVERWORLD).value()
        val travel = types.getOrThrow(TravelDimensionKeys.TRAVEL_TYPE).value()
        val differences = mutableListOf<String>()

        val overworldFields = fields(overworld, ops)
        val travelFields = fields(travel, ops)
        (overworldFields.keys + travelFields.keys)
            .filter { it !in INTENDED_DIFFERENCES && overworldFields[it] != travelFields[it] }
            .forEach { differences += "$it : OVERWORLD ${overworldFields[it]}, VOYAGE ${travelFields[it]}" }

        BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.forEach { attribute ->
            val inOverworld = effective(overworld, attribute, ops)
            val inTravel = effective(travel, attribute, ops)
            if (inOverworld != inTravel) {
                differences += "${BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute)} : OVERWORLD $inOverworld, VOYAGE $inTravel"
            }
        }

        helper.assertTrue(differences.isEmpty(), "VOYAGE s'écarte de l'OVERWORLD : ${differences.joinToString(" ; ")}")
        helper.succeed()
    }

    /**
     * Le générateur de VOYAGE prend le terrain du mode de son run, sur un serveur sans mod de génération. Dans `gameTest`, le mode
     * `terralith` suit l'OVERWORLD : le réglage de bruit et les biomes sont `minecraft:`, que rien n'a remplacés. Dans
     * `gameTestWilliam`, le mode `william` prend le relief de la copie vanilla, son réglage de bruit, et les biomes de la copie
     * William : celui que le faux jar WWOO porte, `plains`, et ceux de la copie vanilla pour tous les autres. Les deux terrains
     * portent les biomes de la disposition vanilla, autant l'un que l'autre, et aucun n'est un repli : aucun message n'attend les
     * opérateurs. Les biomes se comptent par [WorldgenSelector.biomeCounts], le chemin de la ligne « Travel dimension active ».
     */
    @GameTest
    fun travelGeneratorFollowsTheMode(helper: GameTestHelper) {
        val run = currentRun(helper)
        helper.assertTrue(ConfigManager.current.largeBiomes, "les deux runs gardent largeBiomes à son défaut")

        val generator = Harness.travel(helper).chunkSource.generator
        val noise = generator as? NoiseBasedChunkGenerator
            ?: throw helper.assertionException("le générateur de VOYAGE n'est pas un générateur de bruit : ${generator.javaClass.simpleName}")
        val settings = noise.generatorSettings().unwrapKey().map { it.identifier().toString() }.orElse("sans clé de registre")
        helper.assertValueEqual(settings, run.noiseSettings, "le réglage de bruit de VOYAGE dans le run ${run.runName}")

        val layout = VanillaCopy.layoutBiomes().size
        val expected = when (run) {
            GameTestRun.GAME_TEST -> mapOf("minecraft" to layout)
            GameTestRun.GAME_TEST_WILLIAM -> mapOf(group(WorldgenCopy.VANILLA) to layout - FAKE_WWOO_BIOMES.size, group(WorldgenCopy.WILLIAM) to FAKE_WWOO_BIOMES.size)
        }
        helper.assertValueEqual(WorldgenSelector.biomeCounts(generator.biomeSource).toMap(), expected, "les biomes de VOYAGE par espace de noms dans le run ${run.runName}")

        helper.assertValueEqual(WorldgenSelector.notices.map { it.key }, emptyList(), "les messages du repli dans le run ${run.runName}")
        helper.succeed()
    }

    /**
     * La détection sur les registres du run, sans mod de génération : rien n'est installé, et les deux identifiants par défaut de
     * `custom` sont connus. Deux identifiants d'un mod absent ne le sont pas. La copie vanilla et la copie William ne se détectent
     * que dans `gameTestWilliam`, où le mod les a chargées : la copie William n'y passe pas pour un WWOO installé, parce que
     * `minecraft:plains` n'y porte aucune feature `wythers:`. Le garde-fou ne tient aucune copie désactivée, et aucun jar WWOO
     * n'est refusé, dans aucun des deux.
     */
    @GameTest
    fun detectionSeesOnlyTheCopiesOfTheRun(helper: GameTestHelper) {
        val run = currentRun(helper)
        val registries = helper.level.registryAccess()
        val expected = WorldgenDetection(
            terralithLoaded = false,
            tectonicLoaded = false,
            wwooInstalled = false,
            vanillaCopyLoaded = WorldgenCopy.VANILLA in run.copies,
            williamCopyLoaded = WorldgenCopy.WILLIAM in run.copies,
            disabledCopies = emptyMap(),
            williamRefusals = emptyList(),
            customNoiseSettingsKnown = true,
            customBiomePresetKnown = true,
        )
        helper.assertValueEqual(WorldgenDetector.detect(registries, TravelConfig()), expected, "la détection dans le run ${run.runName}")

        val unknown = TravelConfig(customNoiseSettings = "othermod:hills", customBiomePreset = "othermod:layout")
        helper.assertValueEqual(
            WorldgenDetector.detect(registries, unknown),
            expected.copy(customNoiseSettingsKnown = false, customBiomePresetKnown = false),
            "la détection de deux identifiants absents des registres",
        )
        helper.succeed()
    }

    /**
     * Ce que le serveur du run a chargé. Dans `gameTest`, aucune copie n'est préparée : aucun datapack du mod, et rien d'une copie
     * dans les registres. Dans `gameTestWilliam`, les datapacks de `travellingdimension/generated/vanilla/` et de
     * `travellingdimension/generated/wwoo/`, que le mod vient de fabriquer, sont préparés, sélectionnés et requis, et les
     * registres du serveur se sont chargés avec eux : les deux réglages de bruit de la copie vanilla, ses biomes de la disposition
     * vanilla, le biome de la copie William, et ces biomes dans les tags `minecraft:` de leurs originaux. Le tag des villages de
     * plaine en est l'exemple : les deux copies de `minecraft:plains` y entrent, à côté de l'original, qu'aucune ne remplace.
     *
     * Le garde-fou a suivi ce chargement : le témoin de chaque copie préparée a été posé puis levé, et le dossier du serveur ne
     * garde ni témoin ni désactivation. Dans `gameTestWilliam`, les fixtures du run en déposent pourtant un de chaque, pour chaque
     * copie, avant le lancement, comme les laisserait un run interrompu : en développement les copies se refabriquent, et ils
     * tombent.
     */
    @GameTest
    fun serverLoadsThePreparedCopies(helper: GameTestHelper) {
        val run = currentRun(helper)
        val expected = run.copies

        val repository = helper.level.server.packRepository
        helper.assertValueEqual(WorldgenPacks.prepared.keys.toList(), expected, "les copies préparées dans le run ${run.runName}")
        helper.assertValueEqual(WorldgenPacks.selectedIn(repository), expected.map { it.packId }, "les datapacks du mod sélectionnés par le serveur")
        expected.forEach { copy ->
            helper.assertTrue(repository.getPack(copy.packId)?.isRequired == true, "le datapack ${copy.packId} est requis")
        }

        helper.assertValueEqual(WorldgenLoadWatch.loaded, expected.toSet(), "les copies dont le chargement des registres a posé puis levé le témoin")
        helper.assertValueEqual(WorldgenPacks.disabled.keys.toSet(), emptySet(), "les copies que le garde-fou tient désactivées")
        val leftovers = WorldgenCopy.entries.flatMap { listOf(it.witnessFile, it.disabledFile) }.filter { Files.exists(WorldgenPacks.GENERATED_FOLDER.resolve(it)) }
        helper.assertValueEqual(leftovers, emptyList(), "les témoins et les désactivations restés dans le dossier du serveur")

        val registries = helper.level.registryAccess()
        val layout = VanillaCopy.layoutBiomes()
        val copied = WorldgenCopy.VANILLA in expected

        val noiseSettings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).listElementIds().map { it.identifier().toString() }.filter { it.startsWith(WorldgenResolver.VANILLA_COPY) }.toList().toSet()
        val expectedNoiseSettings = if (copied) setOf("overworld", "large_biomes").map { WorldgenResolver.VANILLA_COPY + it }.toSet() else emptySet()
        helper.assertValueEqual(noiseSettings, expectedNoiseSettings, "les réglages de bruit de la copie vanilla dans les registres")

        val biomes = registries.lookupOrThrow(Registries.BIOME)
        val copies = biomes.listElementIds().map { it.identifier().toString() }.filter { it.startsWith(WorldgenResolver.VANILLA_COPY) }.toList().toSet()
        val expectedCopies = if (copied) layout.map { WorldgenCopy.VANILLA.renamed(it.toString()) }.toSet() else emptySet()
        helper.assertValueEqual(copies, expectedCopies, "les biomes de la copie vanilla dans les registres")

        val william = WorldgenCopy.WILLIAM in expected
        val williamCopies = biomes.listElementIds().map { it.identifier().toString() }.filter { it.startsWith(WorldgenResolver.WILLIAM_COPY) }.toList().toSet()
        val expectedWilliamCopies = if (william) FAKE_WWOO_BIOMES.map { WorldgenCopy.WILLIAM.renamed(it) }.toSet() else emptySet()
        helper.assertValueEqual(williamCopies, expectedWilliamCopies, "les biomes de la copie William dans les registres")

        val villages = biomes.get(VILLAGE_PLAINS).map { tag -> tag.stream().map { it.unwrapKey().orElseThrow().identifier().toString() }.toList().toSet() }.orElse(emptySet())
        val originals = setOf("minecraft:plains", "minecraft:meadow")
        val expectedVillages = originals + (if (copied) originals.map { WorldgenCopy.VANILLA.renamed(it) } else emptyList()) + (if (william) listOf(WorldgenCopy.WILLIAM.renamed("minecraft:plains")) else emptyList())
        helper.assertValueEqual(villages, expectedVillages, "les biomes du tag ${VILLAGE_PLAINS.location()}")
        helper.succeed()
    }

    /**
     * Ce que les copies laissent hors de VOYAGE, dans les deux runs : rien. Aucun identifiant `wythers:` n'est dans les registres
     * du serveur, élément ou tag, alors que le faux jar WWOO en est plein : la copie William range tout sous
     * `travellingdimension:wwoo/`. `minecraft:plains`, que ce jar remplace, ne porte aucune feature d'une copie, et l'OVERWORLD
     * du serveur ne pose que des biomes `minecraft:`.
     */
    @GameTest
    fun copiesLeaveTheOverworldUntouched(helper: GameTestHelper) {
        val run = currentRun(helper)
        val registries = helper.level.registryAccess()

        val wythers = registries.registries().toList().flatMap { entry ->
            val registry = entry.value()
            registry.keySet().filter { it.namespace == WWOO_NAMESPACE }.map { "${entry.key().identifier()} $it" } +
                registry.listTagIds().toList().filter { it.location().namespace == WWOO_NAMESPACE }.map { "${entry.key().identifier()} #${it.location()}" }
        }
        helper.assertValueEqual(wythers, emptyList(), "les identifiants $WWOO_NAMESPACE: dans les registres du run ${run.runName}")

        val plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value()
        val copied = plains.generationSettings.features().flatMap { step -> step.map { it.unwrapKey().map { key -> key.identifier().toString() }.orElse("sans clé de registre") } }
            .filter { it.startsWith("${TravellingDimension.MOD_ID}:") }
        helper.assertValueEqual(copied, emptyList(), "les features d'une copie que porte minecraft:plains")

        val overworld = WorldgenSelector.biomeCounts(helper.level.server.overworld().chunkSource.generator.biomeSource)
        helper.assertValueEqual(overworld.keys.toSet(), setOf("minecraft"), "les espaces de noms des biomes de l'OVERWORLD")
        helper.succeed()
    }

    /**
     * Ce que le jeu a chargé de la copie William, dans `gameTestWilliam` : le chapitre 5 de la spécification sur de vrais
     * registres. Le biome du faux jar a gardé sa liste raccourcie, et sa référence vers une placed feature vanilla vise la copie
     * vanilla. La feature d'un type inconnu et sa placed feature ne sont pas dans les registres. Le tag de blocs propre au faux
     * jar existe sous son nouvel identifiant, avec les blocs du tag qu'il inclut : le jeu l'a résolu, donc lu là où la copie l'a
     * écrit. Le gabarit NBT est dans les données du serveur, là où le jeu le cherche.
     *
     * La feature qui pose ce gabarit n'est dans les registres qu'à partir de 26.2 : en 26.1.2 son type n'existe pas, et le moteur
     * l'a élaguée avec sa placed feature.
     */
    @GameTest
    fun williamCopyCarriesWhatItsBiomesNeed(helper: GameTestHelper) {
        if (currentRun(helper) == GameTestRun.GAME_TEST) {
            helper.succeed()
            return
        }
        val registries = helper.level.registryAccess()
        val prefix = WorldgenResolver.WILLIAM_COPY + "$WWOO_NAMESPACE/"

        val plains = registries.lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME, Identifier.parse(WorldgenResolver.WILLIAM_COPY + "plains")))
            .orElseThrow { helper.assertionException("le biome plains de la copie William n'est pas dans les registres") }.value()
        val features = plains.generationSettings.features().flatMap { step -> step.map { it.unwrapKey().map { key -> key.identifier().toString() }.orElse("sans clé de registre") } }
        helper.assertValueEqual(features, listOf(prefix + "platform", WorldgenResolver.VANILLA_COPY + "freeze_top_layer"), "les features du biome plains de la copie William")

        val knowsTemplates = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().metadata.version >= Version.parse("26.2")
        val placed = registries.lookupOrThrow(Registries.PLACED_FEATURE).keySet().map { it.toString() }.filter { it.startsWith(WorldgenResolver.WILLIAM_COPY) }.toSet()
        helper.assertValueEqual(placed, setOfNotNull(prefix + "platform", (prefix + "hut").takeIf { knowsTemplates }), "les placed features de la copie William : celle d'un type inconnu est élaguée")

        val ground = TagKey.create(Registries.BLOCK, Identifier.parse(prefix + "ground"))
        val blocks = BuiltInRegistries.BLOCK.get(ground).map { tag -> tag.stream().map { it.unwrapKey().orElseThrow().identifier().toString() }.toList().toSet() }.orElse(emptySet())
        helper.assertValueEqual(blocks, setOf("minecraft:grass_block", "minecraft:dirt"), "les blocs du tag ${ground.location()}, avec ceux du tag qu'il inclut")

        val template = Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "structure/${WorldgenCopy.WILLIAM.folder}/$WWOO_NAMESPACE/hut.nbt")
        helper.assertTrue(helper.level.server.resourceManager.getResource(template).isPresent, "le gabarit NBT de la copie William est dans les données du serveur : $template")
        helper.succeed()
    }

    /**
     * Le mixin du dépôt de datapacks, dans les deux runs : une copie préparée entre dans tout dépôt bâti autour de la source vanilla du
     * jeu, par la fabrique de `ServerPacksSource` comme par le constructeur seul, la forme que prend l'écran de création d'un monde, et
     * elle y est requise.
     */
    @GameTest
    fun preparedCopiesEnterEveryDatapackRepository(helper: GameTestHelper) {
        val ownPrepared = WorldgenPacks.prepared.keys.toSet()
        val ownDisabled = WorldgenPacks.disabled.keys.toSet()
        val ownRefusals = WorldgenPacks.williamRefusals
        val scratch = Files.createTempDirectory("travellingdimension-gametest")
        try {
            val generated = scratch.resolve("generated")
            Files.createDirectories(generated.resolve(WorldgenCopy.VANILLA.folder)).resolve("pack.mcmeta").writeText(PACK_MCMETA)
            WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA), generated)

            val validator = DirectoryValidator { true }
            val repositories = listOf(
                "par la fabrique" to ServerPacksSource.createPackRepository(scratch.resolve("datapacks"), validator),
                "par le constructeur seul" to PackRepository(ServerPacksSource(validator)),
            )
            for ((built, repository) in repositories) {
                repository.reload()
                repository.setSelected(listOf("vanilla"))
                helper.assertValueEqual(WorldgenPacks.selectedIn(repository), listOf(WorldgenCopy.VANILLA.packId), "les datapacks du mod dans un dépôt bâti $built")
                helper.assertTrue(repository.getPack(WorldgenCopy.VANILLA.packId)?.isRequired == true, "la copie vanilla est requise dans un dépôt bâti $built")
            }
        } finally {
            // Les copies préparées sont un état du processus : le run retrouve les siennes.
            WorldgenPacks.prepare(ownPrepared, disabledCopies = ownDisabled, refusals = ownRefusals)
            scratch.toFile().deleteRecursively()
        }
        helper.succeed()
    }

    /**
     * Le témoin de chargement autour d'un vrai chargement des registres du monde en échec : `RegistryDataLoaderMixin`, ses deux
     * injections, sur un dépôt de datapacks que le test bâtit. Ses deux copies ne portent qu'un `pack.mcmeta`, pour tenir dans les
     * trois versions, et un fichier `{}`, que tout codec du jeu refuse, tient lieu d'élément cassé.
     *
     * Un datapack étranger cassé fait échouer le chargement sans que les copies y soient pour rien : les erreurs que le jeu
     * rapporte les innocentent, et leurs témoins se lèvent. Un élément cassé de la copie William l'accuse, elle seule : son témoin
     * reste, celui de la copie vanilla se lève. Un élément cassé de la copie vanilla l'accuse à son tour. Un témoin resté est ce
     * que le lancement suivant trouverait ([WorldgenCopyGuard.review], éprouvé à l'étage 0). Le chargement qui réussit se voit
     * ailleurs, au lancement même du serveur de `gameTestWilliam` ([serverLoadsThePreparedCopies]).
     *
     * Le test ne joue que dans `gameTestWilliam`, le run des copies : ses trois échecs écrivent dans le log les erreurs du jeu et
     * des `WARN` du mod, et le run `gameTest` reste sans `WARN` de génération.
     */
    @GameTest
    fun loadingWitnessFollowsTheRegistryLoading(helper: GameTestHelper) {
        if (currentRun(helper) == GameTestRun.GAME_TEST) {
            helper.succeed()
            return
        }

        val ownPrepared = WorldgenPacks.prepared.keys.toSet()
        val ownDisabled = WorldgenPacks.disabled.keys.toSet()
        val ownRefusals = WorldgenPacks.williamRefusals
        val scratch = Files.createTempDirectory("travellingdimension-gametest")
        TravellingDimension.LOGGER.info("Gametest: three loadings of the registries fail on purpose, the errors and the warnings that follow are expected")
        try {
            val generated = scratch.resolve("generated")
            val copies = WorldgenCopy.entries.associateWith { copy -> Files.createDirectories(generated.resolve(copy.folder)).also { it.resolve("pack.mcmeta").writeText(PACK_MCMETA) } }
            val vanillaWitness = generated.resolve(WorldgenCopy.VANILLA.witnessFile)
            val williamWitness = generated.resolve(WorldgenCopy.WILLIAM.witnessFile)
            WorldgenPacks.prepare(copies.keys, generated)

            val foreign = scratch.resolve("datapacks/foreign")
            Files.createDirectories(foreign.resolve("data/othermod/worldgen/biome")).resolve("broken.json").writeText("{}")
            foreign.resolve("pack.mcmeta").writeText(PACK_MCMETA)
            helper.assertTrue(worldRegistriesFailure(helper, scratch) != null, "un biome étranger cassé fait échouer le chargement des registres")
            helper.assertTrue(!Files.exists(vanillaWitness) && !Files.exists(williamWitness), "des erreurs étrangères innocentent les deux copies : leurs témoins sont levés")

            breakBiomeOf(copies, WorldgenCopy.WILLIAM)
            helper.assertTrue(worldRegistriesFailure(helper, scratch) != null, "un biome cassé de la copie William fait échouer le chargement des registres")
            helper.assertTrue(Files.exists(williamWitness), "une erreur sur un élément de la copie William l'accuse : son témoin reste")
            helper.assertTrue(!Files.exists(vanillaWitness), "elle innocente la copie vanilla, qu'aucune erreur ne nomme : son témoin est levé")

            breakBiomeOf(copies, WorldgenCopy.VANILLA)
            helper.assertTrue(worldRegistriesFailure(helper, scratch) != null, "un biome cassé de chaque copie fait échouer le chargement des registres")
            helper.assertTrue(Files.exists(vanillaWitness) && Files.exists(williamWitness), "une erreur sur un élément de chaque copie les accuse toutes les deux : leurs témoins restent")
        } finally {
            // Les copies préparées sont un état du processus : le run retrouve les siennes.
            WorldgenPacks.prepare(ownPrepared, disabledCopies = ownDisabled, refusals = ownRefusals)
            scratch.toFile().deleteRecursively()
        }
        helper.succeed()
    }

    /** Pose dans la copie [copy] du test un biome `{}`, sous son espace de noms : le jeu le refuse, et l'erreur la nomme. */
    private fun breakBiomeOf(copies: Map<WorldgenCopy, Path>, copy: WorldgenCopy) {
        Files.createDirectories(copies.getValue(copy).resolve("data/${TravellingDimension.MOD_ID}/worldgen/biome/${copy.folder}")).resolve("broken.json").writeText("{}")
    }

    /**
     * Charge les registres du monde comme le jeu, depuis un dépôt de datapacks bâti sur [root] par la fabrique du jeu, avec le
     * datapack `foreign` de son dossier `datapacks/` : rend l'échec du chargement, ou `null` s'il réussit. Le chargement se joue
     * sur les fils de fond du jeu, et le fil du serveur l'attend : le test ne rend la main qu'une fois le témoin levé ou resté.
     */
    private fun worldRegistriesFailure(helper: GameTestHelper, root: Path): Throwable? {
        val repository = ServerPacksSource.createPackRepository(root.resolve("datapacks"), DirectoryValidator { true })
        repository.reload()
        repository.setSelected(listOf("vanilla", "file/foreign"))
        helper.assertTrue("file/foreign" in repository.selectedIds, "le datapack étranger est dans le dépôt")
        helper.assertValueEqual(WorldgenPacks.selectedIn(repository), WorldgenCopy.entries.map { it.packId }, "les deux copies du test sont dans le dépôt")

        // Les registres statiques d'un serveur qui tourne portent déjà leurs tags : ils servent de recherche tels quels.
        val lookups = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).listRegistries().toList()
        return MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected()).use { resources ->
            RegistryDataLoader.load(resources, lookups, GameVersion.bridge.worldRegistries, Util.backgroundExecutor())
                .handle { _, failure -> failure }
                .get(2, TimeUnit.MINUTES)
        }
    }

    /**
     * Le run où le test tourne : le build le nomme par une propriété système, et sa configuration doit porter son mode. Un run qui
     * aurait perdu ses fixtures échoue ici, au lieu de passer pour l'autre.
     */
    private fun currentRun(helper: GameTestHelper): GameTestRun {
        val name = System.getProperty(RUN_PROPERTY)
        val run = GameTestRun.entries.firstOrNull { it.runName == name }
            ?: throw helper.assertionException("le run de l'étage 2 est inconnu : la propriété $RUN_PROPERTY vaut $name")
        helper.assertValueEqual(ConfigManager.current.worldgen, run.mode, "le mode de la configuration du run ${run.runName}")
        return run
    }

    /** Les champs d'un type de dimension en JSON, sauf ses attributs, qui se comparent à leur valeur effective. */
    private fun fields(type: DimensionType, ops: DynamicOps<JsonElement>): Map<String, JsonElement> =
        DimensionType.DIRECT_CODEC.encodeStart(ops, type).getOrThrow().asJsonObject.entrySet()
            .filter { it.key != "attributes" }
            .associate { it.key to it.value }

    /* Le registre rend des attributs de type inconnu : la valeur et le codec d'un même attribut vont pourtant ensemble. */
    @Suppress("UNCHECKED_CAST")
    private fun effective(type: DimensionType, attribute: EnvironmentAttribute<*>, ops: DynamicOps<JsonElement>): JsonElement =
        effectiveValue(type, attribute as EnvironmentAttribute<Any>, ops)

    /** La valeur que [attribute] prend dans [type], son défaut s'il n'y est pas déclaré, en JSON. */
    private fun <V : Any> effectiveValue(type: DimensionType, attribute: EnvironmentAttribute<V>, ops: DynamicOps<JsonElement>): JsonElement =
        attribute.valueCodec().encodeStart(ops, type.attributes().applyModifier(attribute, attribute.defaultValue())).getOrThrow()

    /**
     * Les deux runs de l'étage 2 : leur nom dans le build, le mode que leur configuration porte, le réglage de bruit que VOYAGE y
     * prend, `largeBiomes` à son défaut, et les copies que le mod y prépare, dans l'ordre où elles se déclarent.
     */
    private enum class GameTestRun(val runName: String, val mode: WorldgenMode, val noiseSettings: String, val copies: List<WorldgenCopy>) {

        /** La configuration née des défauts, donc `terralith` : le chemin de tous les joueurs, où VOYAGE suit l'OVERWORLD. */
        GAME_TEST("gameTest", WorldgenMode.TERRALITH, "minecraft:large_biomes", emptyList()),

        /**
         * `worldgen` à `william` et le faux jar WWOO dans le dossier `worldgen/`, par ses fixtures : le mod fabrique la copie
         * vanilla et la copie William au lancement, et VOYAGE prend le relief de l'une et les biomes de l'autre.
         */
        GAME_TEST_WILLIAM("gameTestWilliam", WorldgenMode.WILLIAM, WorldgenResolver.VANILLA_COPY + "large_biomes", listOf(WorldgenCopy.VANILLA, WorldgenCopy.WILLIAM)),
    }
}
