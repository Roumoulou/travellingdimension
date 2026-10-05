// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mojang.serialization.DataResult
import com.mojang.serialization.JsonOps
import fr.roumoulou.travellingdimension.TravellingDimension
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
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.pack.PackFormat
import net.minecraft.server.packs.resources.IoSupplier
import net.minecraft.tags.TagKey
import net.minecraft.util.InclusiveRange
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.TreeMap

/**
 * Le moteur des copies : le chapitre 5 de `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Il lit dans [source] les éléments des registres de génération que [takes] retient, et écrit sous l'espace de noms du mod un
 * datapack au format du jeu qui tourne : `minecraft:<chemin>` y devient `travellingdimension:<copie>/<chemin>`.
 *
 * Une référence se réécrit là où le codec du jeu en lit une. Chaque fichier est décodé par le codec de son registre, à travers un
 * `RegistryOps` dont la recherche rend, pour un élément copié, une référence au nouvel identifiant, puis il est réencodé : le bloc
 * `minecraft:gravel` reste, le bruit `minecraft:gravel` devient celui de la copie. Deux champs échappent à cette recherche, parce
 * que le jeu y lit une clé nue et non une référence ([KEY_FIELDS]) : ils se réécrivent dans l'arbre réencodé.
 *
 * Un fichier que le codec refuse n'est pas écrit, et tout fichier qui le référence part avec lui, sauf un biome, dont la liste
 * raccourcit. Chaque biome copié entre dans les tags de biomes `minecraft:` de [biomeTags] où son original est listé, par un
 * fichier de tag en `replace: false` : les structures et les apparitions choisissent leurs biomes par tag.
 *
 * Le moteur ne nomme aucun registre par une constante du jeu, hors celui des biomes : `Registries.FEATURE` désigne les features
 * en 26.3 et leurs types avant. Il les reçoit du pont de version, et les reconnaît à leur dossier ([generationRegistries]).
 */
class WorldgenCopyEngine(
    private val copy: WorldgenCopy,
    private val source: PackResources,
    private val registries: List<RegistryDataLoader.RegistryData<*>>,
    private val biomeTags: PackResources = source,
    private val takes: (folder: String, element: Identifier) -> Boolean = { _, _ -> true },
) {

    companion object {

        /**
         * Les dossiers des registres de génération, toutes versions servies confondues : les features et les carvers changent de
         * dossier en 26.3, qui ajoute les règles et conditions de matériau et les fournisseurs d'état de bloc.
         */
        val GENERATION_FOLDERS: Set<String> = setOf(
            "worldgen/biome",
            "worldgen/placed_feature",
            "worldgen/configured_feature",
            "worldgen/feature",
            "worldgen/configured_carver",
            "worldgen/carver",
            "worldgen/density_function",
            "worldgen/noise",
            "worldgen/noise_settings",
            "worldgen/material_rule",
            "worldgen/material_condition",
            "worldgen/block_state_provider",
        )

        private const val BIOME_FOLDER = "worldgen/biome"
        private const val MINECRAFT = "minecraft"
        private const val JSON = ".json"

        /**
         * Les champs où le jeu lit une clé nue (`ResourceKey.codec`) vers un registre copié, que la recherche d'un `RegistryOps`
         * ne voit donc pas : le bruit d'une condition de surface `noise_threshold`, dans les trois versions, et les biomes d'une
         * condition `biome` en 26.1.2, qui sont des références depuis 26.2. Relevés dans le bytecode des classes de génération.
         */
        private val KEY_FIELDS = listOf(
            KeyField(type = "minecraft:noise_threshold", name = "noise", folder = "worldgen/noise"),
            KeyField(type = "minecraft:biome", name = "biome_is", folder = BIOME_FOLDER),
        )

        /** Ce qu'une référence vers un élément élagué devient dans un biome, avant de sortir de sa liste. */
        private val PRUNED: Identifier = Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "pruned")

        private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        /** Parmi [all], les registres que le jeu charge des datapacks, ceux de la génération. */
        fun generationRegistries(all: List<RegistryDataLoader.RegistryData<*>>): List<RegistryDataLoader.RegistryData<*>> =
            all.filter { Registries.elementsDirPath(it.key()) in GENERATION_FOLDERS }
    }

    /**
     * Fabrique la copie dans [target], un dossier vide ou absent, et rend le compte de ce qui est écrit et élagué. Une erreur de
     * lecture ou d'écriture lève : l'appelant fabrique dans un dossier temporaire, qu'il jette.
     */
    fun fabricate(target: Path): WorldgenCopyReport {
        val elements = listElements()
        val taken = elements.groupBy({ it.folder }, { it.id }).mapValues { it.value.toSet() }
        val refusals = LinkedHashMap<ElementRef, String>()

        val first = Pass(taken, emptySet())
        val recoded = HashMap<ElementRef, JsonElement>()
        elements.forEach { element -> first.recode(element) { refusals[element.ref] = it }?.let { recoded[element.ref] = it } }

        val pruned = HashSet(refusals.keys)
        var biomeLost: Boolean
        do {
            // L'élagage descend le long des références, jusqu'à ne plus rien emporter. Un biome ne part que pour son propre refus.
            do {
                val carried = elements.filter { it.ref !in pruned && it.folder != BIOME_FOLDER }.mapNotNull { element ->
                    first.references[element.ref].orEmpty().firstOrNull { it in pruned }?.let { element to it }
                }
                carried.forEach { (element, cause) ->
                    refusals[element.ref] = "references the pruned $cause"
                    pruned += element.ref
                }
            } while (carried.isNotEmpty())

            // Un biome qui liste un élément élagué se recode sans lui : sa liste raccourcit. S'il est refusé à son tour, ce qui le
            // référence part au tour suivant.
            biomeLost = false
            if (pruned.isNotEmpty()) {
                val second = Pass(taken, pruned)
                elements.filter { it.folder == BIOME_FOLDER && it.ref !in pruned && first.references[it.ref].orEmpty().any { cause -> cause in pruned } }.forEach { biome ->
                    val shortened = second.recode(biome) { refusals[biome.ref] = it }
                    if (shortened != null) {
                        recoded[biome.ref] = shortened
                    } else {
                        pruned += biome.ref
                        biomeLost = true
                    }
                }
            }
        } while (biomeLost)

        Files.createDirectories(target)
        write(target.resolve("pack.mcmeta"), packMetadata())
        val kept = elements.filter { it.ref !in pruned }
        kept.forEach { element ->
            write(target.resolve("data/${TravellingDimension.MOD_ID}/${element.folder}/${copy.renamedPath(element.id.namespace, element.id.path)}$JSON"), recoded.getValue(element.ref))
        }
        val tagFiles = writeBiomeTags(target, kept.filter { it.folder == BIOME_FOLDER }.mapTo(HashSet()) { it.id })

        val counts = TreeMap<String, WorldgenCopyReport.Count>()
        elements.groupBy { it.folder }.forEach { (folder, listed) ->
            val gone = listed.count { it.ref in pruned }
            counts[folder] = WorldgenCopyReport.Count(written = listed.size - gone, pruned = gone)
        }
        return WorldgenCopyReport(counts, tagFiles, refusals.entries.associate { (ref, reason) -> ref.toString() to reason })
    }

    /** Les éléments de [source] que la copie prend, registre par registre, dans l'ordre de leurs identifiants. */
    private fun listElements(): List<Element> {
        val listed = LinkedHashMap<ElementRef, Element>()
        registries.forEach { data ->
            val folder = Registries.elementsDirPath(data.key())
            source.getNamespaces(PackType.SERVER_DATA).forEach { namespace ->
                source.listResources(PackType.SERVER_DATA, namespace, folder) { file, content ->
                    if (file.path.endsWith(JSON)) {
                        val id = Identifier.fromNamespaceAndPath(file.namespace, file.path.substring(folder.length + 1, file.path.length - JSON.length))
                        if (takes(folder, id)) listed.putIfAbsent(ElementRef(folder, id), Element(data, folder, id, content))
                    }
                }
            }
        }
        return listed.values.sortedWith(compareBy({ it.folder }, { it.id.toString() }))
    }

    /**
     * Les tags de biomes : chaque fichier de tag `minecraft:` de [biomeTags] qui liste un biome de [biomes] donne un fichier du
     * même nom, en `replace: false`, qui liste sa copie. Un tag qui en inclut un autre suit sans rien demander. Rend le nombre
     * de fichiers écrits.
     */
    private fun writeBiomeTags(target: Path, biomes: Set<Identifier>): Int {
        var files = 0
        biomeTags.listResources(PackType.SERVER_DATA, MINECRAFT, Registries.tagsDirPath(Registries.BIOME)) { file, content ->
            if (file.path.endsWith(JSON)) {
                val copies = listedIn(read(content)).filter { it in biomes }.map { "${TravellingDimension.MOD_ID}:${copy.renamedPath(it.namespace, it.path)}" }
                if (copies.isNotEmpty()) {
                    val tag = JsonObject()
                    tag.addProperty("replace", false)
                    tag.add("values", JsonArray().also { values -> copies.sorted().forEach(values::add) })
                    write(target.resolve("data/${file.namespace}/${file.path}"), tag)
                    files++
                }
            }
        }
        return files
    }

    /** Les éléments qu'un fichier de tag liste lui-même : ni ses tags inclus (`#...`), ni une entrée illisible. */
    private fun listedIn(tag: JsonElement): List<Identifier> {
        val values = (tag as? JsonObject)?.get("values") as? JsonArray ?: return emptyList()
        return values.mapNotNull { entry ->
            val id = when {
                entry is JsonPrimitive && entry.isString -> entry.asString
                entry is JsonObject -> (entry.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.asString
                else -> null
            }
            id?.let(Identifier::tryParse)
        }
    }

    /** Le `pack.mcmeta` de la copie : le format de datapack du jeu qui tourne, écrit par le codec du jeu. */
    private fun packMetadata(): JsonObject {
        val format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)
        val pack = PackFormat.packCodec(PackType.SERVER_DATA).codec().encodeStart(JsonOps.INSTANCE, InclusiveRange(format, format)).getOrThrow().asJsonObject
        pack.addProperty("description", "Travelling Dimension: the ${copy.folder} copy, made by the mod")
        return JsonObject().also { it.add("pack", pack) }
    }

    private fun read(content: IoSupplier<InputStream>): JsonElement = content.get().use { JsonParser.parseReader(it.reader(Charsets.UTF_8)) }

    private fun write(file: Path, json: JsonElement) {
        Files.createDirectories(file.parent)
        Files.writeString(file, GSON.toJson(json) + "\n")
    }

    /**
     * Un passage sur les éléments : sa recherche renomme les éléments de [taken], note chaque référence vers l'un d'eux
     * ([references]), et rend [PRUNED] pour un élément de [pruned].
     */
    private inner class Pass(private val taken: Map<String, Set<Identifier>>, private val pruned: Set<ElementRef>) {

        /** Pour chaque élément recodé, les éléments copiés qu'il référence. */
        val references = HashMap<ElementRef, MutableSet<ElementRef>>()

        private val lookups = HashMap<ResourceKey<out Registry<*>>, Lookup<*>>()
        private val ops: RegistryOps<JsonElement> = GameVersion.bridge.registryOps(JsonOps.INSTANCE) { registry -> lookups.getOrPut(registry) { lookupOf(registry) } }

        /** L'élément en cours de recodage, à qui vont les références que la recherche voit passer. */
        private var current: ElementRef? = null

        /** L'arbre JSON de [element] tel que la copie l'écrit, ou `null` quand le codec le refuse : [refused] en reçoit la raison. */
        fun recode(element: Element, refused: (reason: String) -> Unit): JsonElement? {
            current = element.ref
            try {
                val result = transcode(element.data, read(element.content))
                val refusal = result.error()
                if (refusal.isPresent) {
                    refused(refusal.get().message())
                    return null
                }
                val json = result.result().get()
                rewriteKeyFields(json)
                return if (pruned.isEmpty()) json else withoutPruned(json)
            } catch (e: Exception) {
                // Un fichier illisible, ou un codec qui lève au lieu de rendre une erreur.
                refused(e.toString())
                return null
            } finally {
                current = null
            }
        }

        private fun <T : Any> transcode(data: RegistryDataLoader.RegistryData<T>, json: JsonElement): DataResult<JsonElement> =
            data.elementCodec().parse(ops, json).flatMap { data.elementCodec().encodeStart(ops, it) }

        /* Le type d'élément d'un registre n'est connu que du codec qui demande sa recherche. */
        @Suppress("UNCHECKED_CAST")
        private fun lookupOf(registry: ResourceKey<out Registry<*>>): Lookup<*> = Lookup(registry as ResourceKey<out Registry<Any>>, ::written)

        /** L'identifiant que la copie écrit pour [id], du registre de dossier [folder] : le sien s'il n'est pas copié. */
        private fun written(folder: String, id: Identifier): Identifier {
            if (taken[folder]?.contains(id) != true) return id
            val ref = ElementRef(folder, id)
            current?.takeIf { it != ref }?.let { references.getOrPut(it) { HashSet() }.add(ref) }
            return if (ref in pruned) PRUNED else Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, copy.renamedPath(id.namespace, id.path))
        }

        /** Réécrit dans [json] les clés nues de [KEY_FIELDS], que la recherche n'a pas vues. */
        private fun rewriteKeyFields(json: JsonElement) {
            when (json) {
                is JsonArray -> json.forEach(::rewriteKeyFields)
                is JsonObject -> {
                    val type = (json.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.asString
                    KEY_FIELDS.filter { it.type == type && json.has(it.name) }.forEach { field -> json.add(field.name, rewrittenKeys(field.folder, json.get(field.name))) }
                    json.entrySet().forEach { rewriteKeyFields(it.value) }
                }
            }
        }

        /** [value], une clé ou une liste de clés du registre de dossier [folder]. Un tag ou un objet en ligne restent tels quels. */
        private fun rewrittenKeys(folder: String, value: JsonElement): JsonElement = when {
            value is JsonArray -> JsonArray().also { keys -> value.forEach { keys.add(rewrittenKeys(folder, it)) } }
            value is JsonPrimitive && value.isString -> Identifier.tryParse(value.asString)?.let { JsonPrimitive(written(folder, it).toString()) } ?: value
            else -> value
        }

        /** [json] sans ses références vers [PRUNED] : elles sortent des listes, et une référence seule devient une liste vide. */
        private fun withoutPruned(json: JsonElement): JsonElement = when {
            isPruned(json) -> JsonArray()
            json is JsonArray -> JsonArray().also { kept -> json.filterNot(::isPruned).forEach { kept.add(withoutPruned(it)) } }
            json is JsonObject -> JsonObject().also { kept -> json.entrySet().forEach { (name, value) -> kept.add(name, withoutPruned(value)) } }
            else -> json
        }

        private fun isPruned(json: JsonElement): Boolean = json is JsonPrimitive && json.isString && json.asString == PRUNED.toString()
    }

    /**
     * La recherche d'un registre, et le propriétaire de ses références. Chaque élément y existe : la référence qu'elle rend n'est
     * liée à aucune valeur, comme celles que le jeu rend lui-même pendant qu'il charge ses registres, et porte la clé que
     * [written] donne. Un tag y est un ensemble nommé et vide, qui se réencode par son nom.
     */
    private class Lookup<T : Any>(private val registry: ResourceKey<out Registry<T>>, private val written: (folder: String, id: Identifier) -> Identifier) : HolderGetter<T>, HolderOwner<T> {

        private val folder: String = Registries.elementsDirPath(registry)
        private val holders = HashMap<Identifier, Holder.Reference<T>>()
        private val tags = HashMap<TagKey<T>, HolderSet.Named<T>>()

        override fun get(id: ResourceKey<T>): Optional<Holder.Reference<T>> {
            // La référence se note à chaque passage, même déjà bâtie : un autre fichier peut la demander.
            val target = written(folder, id.identifier())
            return Optional.of(holders.getOrPut(target) { Holder.Reference.createStandAlone(this, ResourceKey.create(registry, target)) })
        }

        /* `emptyNamed` est déprécié pour qui lirait le contenu d'un tag : celui-ci ne sert qu'à porter son nom jusqu'au réencodage. */
        @Suppress("DEPRECATION")
        override fun get(id: TagKey<T>): Optional<HolderSet.Named<T>> = Optional.of(tags.getOrPut(id) { HolderSet.emptyNamed(this, id) })
    }

    /** Un élément de la source : son registre, le dossier de ce registre, son identifiant d'origine et son contenu. */
    private class Element(val data: RegistryDataLoader.RegistryData<*>, val folder: String, val id: Identifier, val content: IoSupplier<InputStream>) {
        val ref = ElementRef(folder, id)
    }

    /** Un élément copié, par le dossier de son registre et son identifiant d'origine. */
    private data class ElementRef(val folder: String, val id: Identifier) {
        override fun toString(): String = "$folder $id"
    }

    /** Un champ de [KEY_FIELDS] : dans un objet de type [type], le champ [name] porte des clés du registre de dossier [folder]. */
    private class KeyField(val type: String, val name: String, val folder: String)
}
