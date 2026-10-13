// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import fr.roumoulou.travellingdimension.dimension.VanillaCopy
import fr.roumoulou.travellingdimension.dimension.WilliamCopy
import fr.roumoulou.travellingdimension.dimension.WilliamJar
import fr.roumoulou.travellingdimension.dimension.WilliamJars
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyEngine
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyGuard
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyMaker
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyMaker.Readiness
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyReport
import fr.roumoulou.travellingdimension.gameversion.GameVersion
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.HolderGetter
import net.minecraft.core.HolderOwner
import net.minecraft.core.HolderSet
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.resources.ResourceKey
import net.minecraft.server.Bootstrap
import net.minecraft.tags.TagKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Optional
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * La copie William, fabriquée par le moteur contre le faux jar WWOO du build : les chapitres 5 et 8.2 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Le faux jar est écrit par le projet (`common/src/fakeWwooJar`) et fabriqué par le module de version, qui en donne le chemin par
 * une propriété système : aucun test ne lit un fichier de WWOO. La copie vanilla se fabrique d'abord, une fois pour la classe,
 * puisque la copie William la vise ; chaque test relit ensuite les deux, sauf celui de la préparation, qui refait les siennes
 * dans un dossier à lui, par le cache et le garde-fou.
 *
 * Le type de feature `minecraft:template` n'existe qu'à partir de 26.2 : en 26.1.2, la feature du faux jar qui pose un gabarit
 * est refusée par le codec, et s'élague avec sa placed feature, comme celle d'un type inconnu de toutes les versions.
 */
class WilliamCopyTest {

    private companion object {
        const val MOD = TravellingDimension.MOD_ID

        /** La propriété système par laquelle le build donne le chemin du faux jar (section 6 du plugin de version). */
        const val JAR_PROPERTY = "$MOD.fake_wwoo_jar"

        lateinit var jar: Path
        lateinit var game: Version
        lateinit var generated: Path
        lateinit var vanilla: Path
        lateinit var copy: Path
        lateinit var report: WorldgenCopyReport

        @JvmStatic
        @BeforeAll
        fun fabricate() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            jar = Path.of(System.getProperty(JAR_PROPERTY) ?: throw IllegalStateException("le build ne donne pas le faux jar WWOO : la propriété $JAR_PROPERTY manque"))
            game = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().metadata.version
            generated = Files.createTempDirectory("travellingdimension-william-copy")
            vanilla = generated.resolve(WorldgenCopy.VANILLA.folder)
            copy = generated.resolve(WorldgenCopy.WILLIAM.folder)
            VanillaCopy.fabricate(vanilla)
            report = WilliamCopy.fabricate(jar, vanilla, copy)
        }

        @JvmStatic
        @AfterAll
        fun discard() {
            generated.toFile().deleteRecursively()
        }
    }

    private val registries: List<RegistryDataLoader.RegistryData<*>> = WorldgenCopyEngine.generationRegistries(GameVersion.bridge.worldRegistries)

    /** Le dossier du registre des features : `worldgen/configured_feature` jusqu'en 26.2, `worldgen/feature` en 26.3. */
    private val featureFolder: String = registries.map { Registries.elementsDirPath(it.key()) }.single { it == "worldgen/configured_feature" || it == "worldgen/feature" }

    /** Le jeu du module connaît la feature `minecraft:template` : à partir de 26.2. */
    private val knowsTemplates: Boolean = game >= Version.parse("26.2")

    @Test
    @DisplayName("le faux jar du build est un jar WWOO, accepté pour la version du jeu de son module")
    fun `faux jar accepte`() {
        assertEquals(WilliamJar.Accepted(jar, Version.parse("0.0.1")), WilliamJars.inspect(jar, game))
    }

    @Test
    @DisplayName("la feature d'un type inconnu et sa placed feature sont élaguées, et le log les compte, registre par registre")
    fun `elagage`() {
        val refused = buildSet {
            add("$featureFolder wythers:relic")
            add("worldgen/placed_feature wythers:relic")
            if (!knowsTemplates) {
                add("$featureFolder wythers:hut")
                add("worldgen/placed_feature wythers:hut")
            }
        }
        assertEquals(refused, report.refusals.keys)
        assertEquals("references the pruned $featureFolder wythers:relic", report.refusals["worldgen/placed_feature wythers:relic"])

        val kept = if (knowsTemplates) 2 else 1
        assertEquals(
            sortedMapOf(
                "worldgen/biome" to WorldgenCopyReport.Count(written = 1, pruned = 0),
                featureFolder to WorldgenCopyReport.Count(written = kept, pruned = 3 - kept),
                "worldgen/placed_feature" to WorldgenCopyReport.Count(written = kept, pruned = 3 - kept),
            ),
            report.registries,
        )
        assertFalse(copied("$featureFolder/wwoo/wythers/relic.json").exists())
        assertFalse(copied("worldgen/placed_feature/wwoo/wythers/relic.json").exists())
        assertEquals(knowsTemplates, copied("$featureFolder/wwoo/wythers/hut.json").exists(), "la feature à gabarit, connue à partir de 26.2")
    }

    @Test
    @DisplayName("le biome garde sa liste raccourcie, et sa référence vers une placed feature vanilla vise la copie vanilla")
    fun `biome et copie vanilla`() {
        val features = json(copied("worldgen/biome/wwoo/plains.json")).getAsJsonArray("features").map { step -> step.asJsonArray.map { it.asString } }

        assertEquals(11, features.size)
        assertEquals(listOf("$MOD:wwoo/wythers/platform"), features[2], "la placed feature élaguée est sortie de la liste")
        assertEquals(listOf("$MOD:vanilla/freeze_top_layer"), features[10], "minecraft:freeze_top_layer, que le faux jar ne porte pas")
        assertTrue(vanilla.resolve("data/$MOD/worldgen/placed_feature/vanilla/freeze_top_layer.json").exists(), "la copie vanilla porte ce que la copie William vise")
    }

    @Test
    @DisplayName("les tags propres à la source que ses éléments référencent se copient sous leur nouvel identifiant, clé nue comprise")
    fun `tags de la source`() {
        val placed = json(copied("worldgen/placed_feature/wwoo/wythers/platform.json"))
        assertEquals("$MOD:wwoo/wythers/platform", placed.get("feature").asString)
        val predicates = placed.getAsJsonArray("placement").single().asJsonObject.getAsJsonObject("predicate").getAsJsonArray("predicates").map { it.asJsonObject }
        // Le premier se lit par la recherche des registres, le second est une clé de tag nue, que le jeu lit sans elle.
        assertEquals("#$MOD:wwoo/wythers/ground", predicates.single { it.get("type").asString == "minecraft:matching_blocks" }.get("blocks").asString)
        assertEquals("$MOD:wwoo/wythers/ground", predicates.single { it.get("type").asString == "minecraft:matching_block_tag" }.get("tag").asString)

        val tags = copy.resolve("data/$MOD/tags/block/wwoo/wythers")
        assertEquals(setOf("ground.json", "soil.json"), Files.list(tags).use { files -> files.map { it.fileName.toString() }.toList().toSet() }, "un tag que rien ne référence ne se copie pas")
        assertEquals(2, report.tags)

        // Le tag inclus suit sous son nouvel identifiant, et un bloc reste un bloc.
        assertEquals(listOf("minecraft:grass_block", "#$MOD:wwoo/wythers/soil"), json(tags.resolve("ground.json")).getAsJsonArray("values").map { it.asString })
        val soil = json(tags.resolve("soil.json")).getAsJsonArray("values")
        assertEquals("minecraft:dirt", soil[0].asString)
        assertEquals("othermod:peat", soil[1].asJsonObject.get("id").asString)
        assertFalse(soil[1].asJsonObject.get("required").asBoolean, "une entrée facultative le reste")
    }

    @Test
    @DisplayName("les gabarits NBT de la source se copient tels quels sous leur nouvel identifiant, et la feature qui en pose un le vise")
    fun `gabarits NBT`() {
        val source = FileSystems.newFileSystem(jar).use { archive -> Files.readAllBytes(archive.getPath("${WilliamJars.DATAPACK}/data/wythers/structure/hut.nbt")) }

        assertArrayEquals(source, copy.resolve("data/$MOD/structure/wwoo/wythers/hut.nbt").readBytes())
        assertEquals(1, report.templates)

        if (knowsTemplates) {
            val feature = json(copied("$featureFolder/wwoo/wythers/hut.json"))
            // La liste est dans `config` en 26.2, et dans la feature elle-même en 26.3.
            val templates = (feature.get("templates") ?: feature.getAsJsonObject("config").get("templates")) as JsonArray
            assertEquals("$MOD:wwoo/wythers/hut", templates.single().asJsonObject.getAsJsonObject("data").get("id").asString)
        }
    }

    @Test
    @DisplayName("tout ce que la copie écrit porte un identifiant travellingdimension:, hors les tags de biomes minecraft: où ses biomes entrent")
    fun `rien hors de l'espace du mod`() {
        val data = copy.resolve("data")
        val files = data.walk().filter { it.isRegularFile() }.toList()
        val foreign = files.map { data.relativize(it).invariantSeparatorsPathString }.filterNot { it.startsWith("$MOD/") || it.startsWith("minecraft/tags/worldgen/biome/") }
        assertEquals(emptyList<String>(), foreign, "les fichiers hors de l'espace du mod et des tags de biomes : ni tag de blocs minecraft:, ni rien sous wythers:")

        val left = files.filter { it.extension == "json" }.filter { file -> Regex("\"#?wythers:").containsMatchIn(file.readText()) }.map { data.relativize(it).invariantSeparatorsPathString }
        assertEquals(emptyList<String>(), left, "les fichiers qui nomment encore un identifiant wythers:")

        val villages = json(data.resolve("minecraft/tags/worldgen/biome/has_structure/village_plains.json"))
        assertFalse(villages.get("replace").asBoolean)
        assertEquals(listOf("$MOD:wwoo/plains"), villages.getAsJsonArray("values").map { it.asString })
        assertTrue(report.biomeTags > 1, "minecraft:plains est dans plusieurs tags de biomes vanilla")
    }

    @Test
    @DisplayName("aucune référence de la copie William ne reste sans cible : chacune vise un élément de la copie, de la copie vanilla, ou du jeu")
    fun `references`() {
        val ours = registries.associate { Registries.elementsDirPath(it.key()) to copiedIds(copy, it) }
        val theirs = registries.associate { Registries.elementsDirPath(it.key()) to copiedIds(vanilla, it) }
        val knows = { folder: String, id: Identifier ->
            when {
                folder !in ours -> true
                id.namespace == MOD -> id.path in ours.getValue(folder) || id.path in theirs.getValue(folder)
                // Un élément que la copie vanilla porte ne se référence plus sous minecraft:.
                else -> id.namespace == "minecraft" && WorldgenCopy.VANILLA.renamedPath(id.namespace, id.path) !in theirs.getValue(folder)
            }
        }
        val knowsTag = { folder: String, id: Identifier -> id.namespace != MOD || copy.resolve("data/$MOD/$folder/${id.path}.json").exists() }
        val lookups = HashMap<ResourceKey<out Registry<*>>, HolderGetter<*>>()
        val ops = GameVersion.bridge.registryOps(JsonOps.INSTANCE) { registry -> lookups.getOrPut(registry) { strictLookup(registry, knows, knowsTag) } }

        val faults = ArrayList<String>()
        registries.forEach { data ->
            val folder = Registries.elementsDirPath(data.key())
            ours.getValue(folder).forEach { path ->
                data.elementCodec().parse(ops, JsonParser.parseString(copy.resolve("data/$MOD/$folder/$path.json").readText())).error().ifPresent { faults += "$folder $MOD:$path : ${it.message()}" }
            }
        }
        assertEquals(emptyList<String>(), faults)
    }

    @Test
    @DisplayName("la préparation des deux copies : la clé de la copie William porte l'empreinte du jar, le cache la reprend, et un autre jar la refabrique")
    fun `cle et cache`() {
        val own = Files.createTempDirectory("travellingdimension-william-cache")
        try {
            val both = { source: Path -> WorldgenCopyMaker.prepare(own, listOf(VanillaCopy.source(), WilliamCopy.source(source, own)), reuse = true) }
            val ready = mapOf(WorldgenCopy.VANILLA to Readiness.READY, WorldgenCopy.WILLIAM to Readiness.READY)

            assertEquals(ready, both(jar))
            val key = JsonParser.parseString(own.resolve("wwoo.key.json").readText()).asJsonObject
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar.readBytes())), key.get("source_sha256").asString)
            assertFalse(JsonParser.parseString(own.resolve("vanilla.key.json").readText()).asJsonObject.has("source_sha256"), "la copie vanilla n'a pas de source déposée")

            // Le même jar sous un autre nom : l'empreinte est la même, rien ne se refabrique.
            val stale = own.resolve("wwoo/stale.txt").also { it.writeText("kept by the cache") }
            val renamed = Files.copy(jar, own.resolve("william.jar"))
            assertEquals(ready, both(renamed))
            assertTrue(stale.exists(), "le cache a repris la copie")

            // Un autre jar : l'empreinte change, la copie William se refabrique, et elle seule.
            val vanillaStale = own.resolve("vanilla/stale.txt").also { it.writeText("kept by the cache") }
            assertEquals(ready, both(withOneMoreEntry(jar, own.resolve("newer.jar"))))
            assertFalse(stale.exists(), "la copie William est refaite")
            assertTrue(vanillaStale.exists(), "la copie vanilla est reprise")
        } finally {
            own.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("le garde-fou à la préparation : deux témoins restés ne désactivent que la copie William, et sans copie vanilla elle ne se prépare pas")
    fun `garde-fou a la preparation`() {
        val own = Files.createTempDirectory("travellingdimension-william-guard")
        try {
            val both = { WorldgenCopyMaker.prepare(own, listOf(VanillaCopy.source(), WilliamCopy.source(jar, own)), reuse = true) }
            both()

            // Un chargement des registres avec les deux copies n'a pas réussi, sans qu'une erreur nomme la fautive.
            WorldgenCopyGuard.arm(own, WorldgenCopy.VANILLA)
            WorldgenCopyGuard.arm(own, WorldgenCopy.WILLIAM)
            assertEquals(mapOf(WorldgenCopy.VANILLA to Readiness.READY, WorldgenCopy.WILLIAM to Readiness.DISABLED), both())
            assertTrue(own.resolve("wwoo.disabled").exists())
            assertFalse(own.resolve("vanilla.disabled").exists(), "la copie vanilla garde le bénéfice du doute")
            assertEquals(mapOf(WorldgenCopy.VANILLA to Readiness.READY, WorldgenCopy.WILLIAM to Readiness.DISABLED), both(), "la désactivation tient")

            // Le fichier supprimé, la copie William se refabrique.
            own.resolve("wwoo.disabled").deleteExisting()
            assertEquals(mapOf(WorldgenCopy.VANILLA to Readiness.READY, WorldgenCopy.WILLIAM to Readiness.READY), both())

            // La copie vanilla désactivée : la copie William, qui la vise, ne se prépare pas.
            WorldgenCopyGuard.arm(own, WorldgenCopy.VANILLA)
            assertEquals(mapOf(WorldgenCopy.VANILLA to Readiness.DISABLED, WorldgenCopy.WILLIAM to Readiness.UNMADE), both())
        } finally {
            own.toFile().deleteRecursively()
        }
    }

    private fun copied(path: String): Path = copy.resolve("data/$MOD/$path")

    private fun json(file: Path): JsonObject = JsonParser.parseString(file.readText()).asJsonObject

    /** Les éléments que la copie de dossier [root] porte dans le registre [data], par leur chemin sous `travellingdimension:`. */
    private fun copiedIds(root: Path, data: RegistryDataLoader.RegistryData<*>): Set<String> {
        val folder = root.resolve("data/$MOD/${Registries.elementsDirPath(data.key())}")
        if (!Files.isDirectory(folder)) return emptySet()
        return folder.walk().filter { it.isRegularFile() && it.extension == "json" }.map { folder.relativize(it).invariantSeparatorsPathString.removeSuffix(".json") }.toSet()
    }

    /** Une copie de [source] dans [target] avec une entrée de plus : un autre jar, d'une autre empreinte, qui porte le même datapack. */
    private fun withOneMoreEntry(source: Path, target: Path): Path {
        ZipOutputStream(Files.newOutputStream(target)).use { output ->
            ZipInputStream(Files.newInputStream(source)).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) input.copyTo(output)
                    output.closeEntry()
                }
            }
            output.putNextEntry(ZipEntry("one-more.txt"))
            output.write("another jar".toByteArray())
            output.closeEntry()
        }
        return target
    }

    /* Le type d'élément d'un registre n'est connu que du codec qui demande sa recherche. */
    @Suppress("UNCHECKED_CAST")
    private fun strictLookup(registry: ResourceKey<out Registry<*>>, knows: (String, Identifier) -> Boolean, knowsTag: (String, Identifier) -> Boolean): HolderGetter<*> =
        StrictLookup(registry as ResourceKey<out Registry<Any>>, knows, knowsTag)

    /** Une recherche qui ne rend que les éléments que [knows] admet et les tags que [knowsTag] admet : une référence vers un autre fait échouer le décodage. */
    private class StrictLookup<T : Any>(
        registry: ResourceKey<out Registry<T>>,
        private val knows: (folder: String, id: Identifier) -> Boolean,
        private val knowsTag: (folder: String, id: Identifier) -> Boolean,
    ) : HolderGetter<T>, HolderOwner<T> {

        private val folder: String = Registries.elementsDirPath(registry)
        private val tagFolder: String = Registries.tagsDirPath(registry)

        override fun get(id: ResourceKey<T>): Optional<Holder.Reference<T>> =
            if (knows(folder, id.identifier())) Optional.of(Holder.Reference.createStandAlone(this, id)) else Optional.empty()

        /* `emptyNamed` est déprécié pour qui lirait le contenu d'un tag : le décodage n'en demande que le nom. */
        @Suppress("DEPRECATION")
        override fun get(id: TagKey<T>): Optional<HolderSet.Named<T>> =
            if (knowsTag(tagFolder, id.location())) Optional.of(HolderSet.emptyNamed(this, id)) else Optional.empty()
    }
}
