// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import fr.roumoulou.travellingdimension.dimension.VanillaCopy
import fr.roumoulou.travellingdimension.dimension.VanillaCopy.Readiness
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyEngine
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyGuard
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyReport
import fr.roumoulou.travellingdimension.dimension.WorldgenPacks
import fr.roumoulou.travellingdimension.gameversion.GameVersion
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
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.tags.TagKey
import net.minecraft.world.level.validation.DirectoryValidator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * La copie vanilla, fabriquée par le moteur contre le datapack vanilla de la version du module : le chapitre 8.1 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Elle se fabrique une fois pour la classe, et chaque test la relit, sauf celui du garde-fou, qui prépare la sienne dans un dossier
 * à lui, par le cache. Les références se contrôlent par un détecteur que le moteur
 * n'a pas écrit : un second décodage par les codecs du jeu, dont la recherche refuse un élément copié sous `minecraft:` et un
 * élément `travellingdimension:` que la copie ne porte pas, puis la lecture des champs que le jeu lit en clé nue.
 *
 * Le chargement des registres du jeu avec la copie ne tient pas sans serveur : le jeu n'y charge les tags de ses registres
 * qu'une fois ceux-ci figés, par un code d'amorçage qui lui est privé. Il se voit à l'étage 2, dans le run `gameTestVanilla`.
 */
class VanillaCopyTest {

    private companion object {
        const val MOD = TravellingDimension.MOD_ID
        const val VANILLA_PACK = "travellingdimension/vanilla"

        /** Les champs où le jeu lit une clé nue vers un registre copié, et le dossier de ce registre. */
        val KEY_FIELDS = mapOf("noise" to "worldgen/noise", "biome_is" to "worldgen/biome")

        lateinit var generated: Path
        lateinit var copy: Path
        lateinit var report: WorldgenCopyReport

        @JvmStatic
        @BeforeAll
        fun fabricate() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            generated = Files.createTempDirectory("travellingdimension-vanilla-copy")
            copy = generated.resolve(WorldgenCopy.VANILLA.folder)
            report = VanillaCopy.fabricate(copy)
        }

        @JvmStatic
        @AfterAll
        fun discard() {
            WorldgenPacks.prepare(emptySet(), generated)
            generated.toFile().deleteRecursively()
        }
    }

    private val registries: List<RegistryDataLoader.RegistryData<*>> = WorldgenCopyEngine.generationRegistries(GameVersion.bridge.worldRegistries)

    @Test
    @DisplayName("la copie vanilla se fabrique sans rien élaguer, et prend chaque registre de génération de la version")
    fun `sans elagage`() {
        assertEquals(emptyMap<String, String>(), report.refusals, "les fichiers élagués et leur raison")
        assertEquals(0, report.pruned)

        val folders = registries.map { Registries.elementsDirPath(it.key()) }.toSortedSet()
        assertEquals(folders, report.registries.keys, "les registres de génération de la version")

        val source = sourceCounts()
        report.registries.forEach { (folder, count) ->
            val expected = when (folder) {
                // Les deux réglages de bruit que la résolution référence, et les biomes de la disposition codée dans le jeu.
                "worldgen/noise_settings" -> 2
                "worldgen/biome" -> VanillaCopy.layoutBiomes().size
                else -> source.getValue(folder)
            }
            assertEquals(expected, count.written, "les fichiers écrits de $folder")
            assertEquals(count.written, copiedIds(folder).size, "les fichiers de $folder sur le disque")
        }
    }

    @Test
    @DisplayName("aucune référence ne vise un élément copié sous minecraft:, et aucune ne vise un élément que la copie ne porte pas")
    fun `references`() {
        val copied = registries.associate { Registries.elementsDirPath(it.key()) to copiedIds(Registries.elementsDirPath(it.key())) }
        val knows = { folder: String, id: Identifier ->
            val ours = copied[folder]
            when {
                ours == null -> true
                id.namespace == MOD -> id.path in ours
                else -> WorldgenCopy.VANILLA.renamedPath(id.namespace, id.path) !in ours
            }
        }
        val lookups = HashMap<ResourceKey<out Registry<*>>, HolderGetter<*>>()
        val ops = GameVersion.bridge.registryOps(JsonOps.INSTANCE) { registry -> lookups.getOrPut(registry) { strictLookup(registry, knows) } }

        val faults = ArrayList<String>()
        var keys = 0
        registries.forEach { data ->
            val folder = Registries.elementsDirPath(data.key())
            copied.getValue(folder).forEach { path ->
                val json = JsonParser.parseString(copy.resolve("data/$MOD/$folder/$path.json").readText())
                data.elementCodec().parse(ops, json).error().ifPresent { faults += "$folder $MOD:$path : ${it.message()}" }
                keysOf(json).forEach { (field, key) ->
                    keys++
                    if (!knows(KEY_FIELDS.getValue(field), key)) faults += "$folder $MOD:$path : le champ $field porte la clé $key"
                }
            }
        }

        assertEquals(emptyList<String>(), faults)
        assertTrue(keys > 0, "le détecteur a lu au moins une clé nue : les conditions de surface en portent")
    }

    @Test
    @DisplayName("la copie n'écrit sous minecraft: que des tags de biomes, en replace: false, où ses biomes entrent")
    fun `tags de biomes`() {
        val data = copy.resolve("data")
        val foreign = data.walk().filter { it.isRegularFile() }.map { data.relativize(it).invariantSeparatorsPathString }
            .filterNot { it.startsWith("$MOD/") || it.startsWith("minecraft/tags/worldgen/biome/") }.toList()
        assertEquals(emptyList<String>(), foreign, "les fichiers hors de l'espace du mod et des tags de biomes")

        val tags = data.resolve("minecraft/tags/worldgen/biome").walk().filter { it.isRegularFile() }.toList()
        assertEquals(report.biomeTags, tags.size, "les fichiers de tag de biomes")
        tags.forEach { tag ->
            val json = JsonParser.parseString(tag.readText()).asJsonObject
            assertFalse(json.get("replace").asBoolean, "${tag.name} ne remplace pas le tag vanilla")
            json.getAsJsonArray("values").forEach { assertTrue(it.asString.startsWith("$MOD:vanilla/"), "${tag.name} ne liste que des biomes de la copie : ${it.asString}") }
        }

        // L'exemple de la spécification : minecraft:plains est dans le tag des villages de plaine, sa copie y entre.
        val villages = JsonParser.parseString(data.resolve("minecraft/tags/worldgen/biome/has_structure/village_plains.json").readText()).asJsonObject
        assertEquals(listOf("$MOD:vanilla/meadow", "$MOD:vanilla/plains"), villages.getAsJsonArray("values").map { it.asString })
    }

    @Test
    @DisplayName("le jeu lit le pack.mcmeta de la copie : elle entre dans un dépôt de datapacks, requise")
    fun `datapack du jeu`() {
        WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA), generated)
        val repository = PackRepository(*WorldgenPacks.withPreparedCopies(arrayOf<Any>(ServerPacksSource(DirectoryValidator { true }))).map { it as RepositorySource }.toTypedArray())
        repository.reload()

        assertTrue(repository.getPack(VANILLA_PACK)?.isRequired == true, "la copie vanilla est dans le dépôt, et requise")
    }

    @Test
    @DisplayName("un témoin resté désactive la copie vanilla : ni reprise ni fabriquée, puis refabriquée quand le fichier est supprimé")
    fun `garde-fou a la preparation`() {
        val own = Files.createTempDirectory("travellingdimension-vanilla-guard")
        val keyFile = own.resolve("vanilla.key.json")
        val disabled = own.resolve("vanilla.disabled")
        try {
            // Hors développement, le cache reprend et le garde-fou tient : `reuse` le demande ici.
            assertEquals(Readiness.READY, VanillaCopy.prepare(own, reuse = true))
            val key = keyFile.readText()

            // Un chargement des registres avec la copie n'a pas réussi : son témoin est resté.
            WorldgenCopyGuard.arm(own, WorldgenCopy.VANILLA)
            val stale = own.resolve("vanilla/stale.txt").also { it.writeText("faulty") }

            assertEquals(Readiness.DISABLED, VanillaCopy.prepare(own, reuse = true))
            assertEquals(key, disabled.readText(), ".disabled porte la clé de la copie fautive")
            assertFalse(keyFile.exists())
            assertTrue(stale.exists(), "une copie désactivée n'est ni reprise ni refabriquée")

            // La clé du jour, écrite par la sérialisation, est bien celle que .disabled porte : la désactivation tient.
            assertEquals(Readiness.DISABLED, VanillaCopy.prepare(own, reuse = true))

            disabled.deleteExisting()
            assertEquals(Readiness.READY, VanillaCopy.prepare(own, reuse = true))
            assertFalse(stale.exists(), "le nouvel essai refabrique la copie")
            assertEquals(key, keyFile.readText())
        } finally {
            own.toFile().deleteRecursively()
        }
    }

    /** Les fichiers JSON du datapack vanilla, par dossier de registre de génération. */
    private fun sourceCounts(): Map<String, Int> {
        val source = GameVersion.bridge.vanillaDatapack()
        return registries.associate { data ->
            val folder = Registries.elementsDirPath(data.key())
            var files = 0
            source.listResources(PackType.SERVER_DATA, "minecraft", folder) { file, _ -> if (file.path.endsWith(".json")) files++ }
            folder to files
        }
    }

    /** Les éléments que la copie porte dans le registre de dossier [folder], par leur chemin sous `travellingdimension:`. */
    private fun copiedIds(folder: String): Set<String> {
        val root = copy.resolve("data/$MOD/$folder")
        if (!Files.isDirectory(root)) return emptySet()
        return root.walk().filter { it.isRegularFile() && it.extension == "json" }.map { root.relativize(it).invariantSeparatorsPathString.removeSuffix(".json") }.toSet()
    }

    /** Les clés nues de [json] : chaque identifiant d'un champ de [KEY_FIELDS], seul ou en liste, avec le nom de son champ. */
    private fun keysOf(json: JsonElement): List<Pair<String, Identifier>> = when (json) {
        is JsonArray -> json.flatMap(::keysOf)
        is JsonObject -> json.entrySet().flatMap { (name, value) ->
            val keys = if (name in KEY_FIELDS) (if (value is JsonArray) value.toList() else listOf(value)) else emptyList()
            keys.filter { it is JsonPrimitive && it.isString }.mapNotNull { Identifier.tryParse(it.asString) }.map { name to it } + keysOf(value)
        }

        else -> emptyList()
    }

    /* Le type d'élément d'un registre n'est connu que du codec qui demande sa recherche. */
    @Suppress("UNCHECKED_CAST")
    private fun strictLookup(registry: ResourceKey<out Registry<*>>, knows: (String, Identifier) -> Boolean): HolderGetter<*> = StrictLookup(registry as ResourceKey<out Registry<Any>>, knows)

    /** Une recherche qui ne rend que les éléments que [knows] admet : une référence vers un autre fait échouer le décodage. */
    private class StrictLookup<T : Any>(registry: ResourceKey<out Registry<T>>, private val knows: (folder: String, id: Identifier) -> Boolean) : HolderGetter<T>, HolderOwner<T> {

        private val folder: String = Registries.elementsDirPath(registry)

        override fun get(id: ResourceKey<T>): Optional<Holder.Reference<T>> =
            if (knows(folder, id.identifier())) Optional.of(Holder.Reference.createStandAlone(this, id)) else Optional.empty()

        /* `emptyNamed` est déprécié pour qui lirait le contenu d'un tag : le décodage n'en demande que le nom. */
        @Suppress("DEPRECATION")
        override fun get(id: TagKey<T>): Optional<HolderSet.Named<T>> = Optional.of(HolderSet.emptyNamed(this, id))
    }
}
