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
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.tags.TagKey
import net.minecraft.util.Util
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.level.biome.Biome
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
     * `gameTestVanilla`, le mode `vanilla` prend la copie vanilla que le mod a fabriquée au lancement : son réglage de bruit, et des
     * biomes qui sont tous les siens. Les deux terrains portent les biomes de la disposition vanilla, autant l'un que l'autre, et
     * aucun n'est un repli : aucun message n'attend les opérateurs. Les biomes se comptent par [WorldgenSelector.biomeCounts], le
     * chemin de la ligne « Travel dimension active ».
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

        val counts = WorldgenSelector.biomeCounts(generator.biomeSource)
        helper.assertValueEqual(counts.toMap(), mapOf(run.biomes to VanillaCopy.layoutBiomes().size), "les biomes de VOYAGE par espace de noms dans le run ${run.runName}")

        helper.assertValueEqual(WorldgenSelector.notices.map { it.key }, emptyList(), "les messages du repli dans le run ${run.runName}")
        helper.succeed()
    }

    /**
     * La détection sur les registres du run, sans mod de génération : rien n'est installé, et les deux identifiants par défaut de
     * `custom` sont connus. Deux identifiants d'un mod absent ne le sont pas. La copie vanilla ne se détecte que dans
     * `gameTestVanilla`, où le mod l'a chargée ; la copie William, dans aucun des deux. Le garde-fou ne tient aucune copie
     * désactivée, dans aucun des deux.
     */
    @GameTest
    fun detectionSeesOnlyTheCopiesOfTheRun(helper: GameTestHelper) {
        val run = currentRun(helper)
        val registries = helper.level.registryAccess()
        val expected = WorldgenDetection(
            terralithLoaded = false,
            tectonicLoaded = false,
            wwooInstalled = false,
            vanillaCopyLoaded = run == GameTestRun.GAME_TEST_VANILLA,
            williamCopyLoaded = false,
            disabledCopies = emptyMap(),
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
     * Ce que le serveur du run a chargé. Dans `gameTest`, aucune copie n'est préparée : aucun datapack du mod, et rien de la copie
     * vanilla dans les registres. Dans `gameTestVanilla`, le datapack de `travellingdimension/generated/vanilla/`, que le mod vient
     * de fabriquer, est préparé, sélectionné et requis, et les registres du serveur se sont chargés avec lui : ses deux réglages de
     * bruit, les biomes de la disposition vanilla, et ces biomes dans les tags `minecraft:` de leurs originaux. Le tag des villages
     * de plaine en est l'exemple : la copie de `minecraft:plains` y entre, à côté de l'original, que la copie ne remplace pas.
     *
     * Le garde-fou a suivi ce chargement : le témoin de chaque copie préparée a été posé puis levé, et le dossier du serveur ne
     * garde ni témoin ni désactivation. Dans `gameTestVanilla`, les fixtures du run en déposent pourtant un de chaque avant le
     * lancement, comme les laisserait un run interrompu : en développement la copie se refabrique, et ils tombent.
     */
    @GameTest
    fun serverLoadsThePreparedCopies(helper: GameTestHelper) {
        val run = currentRun(helper)
        val expected: List<WorldgenCopy> = when (run) {
            GameTestRun.GAME_TEST -> emptyList()
            GameTestRun.GAME_TEST_VANILLA -> listOf(WorldgenCopy.VANILLA)
        }

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

        val villages = biomes.get(VILLAGE_PLAINS).map { tag -> tag.stream().map { it.unwrapKey().orElseThrow().identifier().toString() }.toList().toSet() }.orElse(emptySet())
        val originals = setOf("minecraft:plains", "minecraft:meadow")
        val expectedVillages = if (copied) originals + originals.map { WorldgenCopy.VANILLA.renamed(it) } else originals
        helper.assertValueEqual(villages, expectedVillages, "les biomes du tag ${VILLAGE_PLAINS.location()}")
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
            WorldgenPacks.prepare(ownPrepared, disabledCopies = ownDisabled)
            scratch.toFile().deleteRecursively()
        }
        helper.succeed()
    }

    /**
     * Le témoin de chargement autour d'un vrai chargement des registres du monde en échec : `RegistryDataLoaderMixin`, ses deux
     * injections, sur un dépôt de datapacks que le test bâtit. Sa copie vanilla ne porte qu'un `pack.mcmeta`, pour tenir dans les
     * trois versions, et un fichier `{}`, que tout codec du jeu refuse, tient lieu d'élément cassé.
     *
     * Un datapack étranger cassé fait échouer le chargement sans que la copie y soit pour rien : les erreurs que le jeu rapporte
     * l'innocentent, et son témoin se lève. Un élément cassé de la copie l'accuse : son témoin reste, et c'est lui que le
     * lancement suivant trouverait ([WorldgenCopyGuard.review], éprouvé à l'étage 0). Le chargement qui réussit se voit ailleurs,
     * au lancement même du serveur de `gameTestVanilla` ([serverLoadsThePreparedCopies]).
     *
     * Le test ne joue que dans `gameTestVanilla`, le run des copies : ses deux échecs écrivent dans le log les erreurs du jeu et un
     * `WARN` du mod, et le run `gameTest` reste sans `WARN` de génération.
     */
    @GameTest
    fun loadingWitnessFollowsTheRegistryLoading(helper: GameTestHelper) {
        if (currentRun(helper) == GameTestRun.GAME_TEST) {
            helper.succeed()
            return
        }

        val ownPrepared = WorldgenPacks.prepared.keys.toSet()
        val ownDisabled = WorldgenPacks.disabled.keys.toSet()
        val scratch = Files.createTempDirectory("travellingdimension-gametest")
        TravellingDimension.LOGGER.info("Gametest: two loadings of the registries fail on purpose, the errors and the warning that follow are expected")
        try {
            val generated = scratch.resolve("generated")
            val copy = Files.createDirectories(generated.resolve(WorldgenCopy.VANILLA.folder))
            copy.resolve("pack.mcmeta").writeText(PACK_MCMETA)
            val witness = generated.resolve(WorldgenCopy.VANILLA.witnessFile)
            WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA), generated)

            val foreign = scratch.resolve("datapacks/foreign")
            Files.createDirectories(foreign.resolve("data/othermod/worldgen/biome")).resolve("broken.json").writeText("{}")
            foreign.resolve("pack.mcmeta").writeText(PACK_MCMETA)
            helper.assertTrue(worldRegistriesFailure(helper, scratch) != null, "un biome étranger cassé fait échouer le chargement des registres")
            helper.assertTrue(!Files.exists(witness), "des erreurs étrangères innocentent la copie : son témoin est levé")

            Files.createDirectories(copy.resolve("data/${TravellingDimension.MOD_ID}/worldgen/biome/${WorldgenCopy.VANILLA.folder}")).resolve("broken.json").writeText("{}")
            helper.assertTrue(worldRegistriesFailure(helper, scratch) != null, "un biome cassé de la copie fait échouer le chargement des registres")
            helper.assertTrue(Files.exists(witness), "une erreur sur un élément de la copie l'accuse : son témoin reste")
        } finally {
            // Les copies préparées sont un état du processus : le run retrouve les siennes.
            WorldgenPacks.prepare(ownPrepared, disabledCopies = ownDisabled)
            scratch.toFile().deleteRecursively()
        }
        helper.succeed()
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
        helper.assertTrue(WorldgenCopy.VANILLA.packId in repository.selectedIds, "la copie du test est dans le dépôt")

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
     * Les deux runs de l'étage 2 : leur nom dans le build, le mode que leur configuration porte, et le terrain que VOYAGE y prend,
     * `largeBiomes` à son défaut : son réglage de bruit, et le groupe où se comptent ses biomes ([WorldgenSelector.biomeCounts]).
     */
    private enum class GameTestRun(val runName: String, val mode: WorldgenMode, val noiseSettings: String, val biomes: String) {

        /** La configuration née des défauts, donc `terralith` : le chemin de tous les joueurs, où VOYAGE suit l'OVERWORLD. */
        GAME_TEST("gameTest", WorldgenMode.TERRALITH, "minecraft:large_biomes", "minecraft"),

        /** `worldgen` à `vanilla` par ses fixtures : le mod fabrique la copie vanilla au lancement, et VOYAGE la prend. */
        GAME_TEST_VANILLA("gameTestVanilla", WorldgenMode.VANILLA, WorldgenResolver.VANILLA_COPY + "large_biomes", "${TravellingDimension.MOD_ID}:${WorldgenCopy.VANILLA.folder}"),
    }
}
