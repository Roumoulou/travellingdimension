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
 * `minecraft:gravel` reste, le bruit `minecraft:gravel` devient celui de la copie. Une référence vers un élément que la source ne
 * porte pas reste telle quelle, sauf quand [elsewhere] lui donne une cible : la copie William y vise la copie vanilla. Quelques
 * champs échappent à cette recherche, parce que le jeu y lit une clé nue et non une référence ([KEY_FIELDS], [TAG_FIELDS],
 * [TEMPLATE_TYPE]) : ils se réécrivent dans l'arbre réencodé.
 *
 * Un fichier que le codec refuse n'est pas écrit, et tout fichier qui le référence part avec lui, sauf un biome, dont la liste
 * raccourcit. Chaque biome copié entre dans les tags de biomes `minecraft:` de [biomeTags] où son original est listé, par un
 * fichier de tag en `replace: false` : les structures et les apparitions choisissent leurs biomes par tag.
 *
 * Un espace de noms autre que `minecraft:` sous lequel la source porte un élément de génération lui est propre : la source y
 * porte les tags en entier, là où un tag `minecraft:` n'est chez elle qu'un fragment que d'autres complètent. Les tags de ces
 * espaces que les éléments copiés référencent, et leurs gabarits NBT, se copient sous leur nouvel identifiant.
 *
 * Le moteur ne nomme aucun registre de génération par une constante du jeu, hors celui des biomes : `Registries.FEATURE` désigne
 * les features en 26.3 et leurs types avant. Il les reçoit du pont de version, et les reconnaît à leur dossier
 * ([generationRegistries]).
 */
class WorldgenCopyEngine(
    private val copy: WorldgenCopy,
    private val source: PackResources,
    private val registries: List<RegistryDataLoader.RegistryData<*>>,
    private val biomeTags: PackResources = source,
    private val elsewhere: (folder: String, element: Identifier) -> Identifier? = { _, _ -> null },
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
        private const val TEMPLATE_FOLDER = "structure"
        private const val MINECRAFT = "minecraft"
        private const val JSON = ".json"
        private const val NBT = ".nbt"

        /**
         * Les champs où le jeu lit une clé nue (`ResourceKey.codec`) vers un registre copié, que la recherche d'un `RegistryOps`
         * ne voit donc pas : le bruit d'une condition de surface `noise_threshold`, dans les trois versions, et les biomes d'une
         * condition `biome` en 26.1.2, qui sont des références depuis 26.2. Relevés dans le bytecode des classes de génération.
         */
        private val KEY_FIELDS = listOf(
            KeyField(type = "minecraft:noise_threshold", name = "noise", folder = "worldgen/noise"),
            KeyField(type = "minecraft:biome", name = "biome_is", folder = BIOME_FOLDER),
        )

        /**
         * Les objets où le jeu lit une clé de tag de blocs nue et sans `#` (`TagKey.codec`) : le prédicat de bloc
         * `matching_block_tag` et le test de règle `tag_match`, dans les trois versions. Relevés dans le bytecode des classes de
         * génération, où ce sont les deux seuls.
         */
        private val TAG_FIELDS = listOf(
            TagField(discriminator = "type", value = "minecraft:matching_block_tag", name = "tag"),
            TagField(discriminator = "predicate_type", value = "minecraft:tag_match", name = "tag"),
        )

        /**
         * Le type de la feature qui pose un gabarit NBT, en 26.2 et en 26.3 : chaque entrée de sa liste `templates` nomme le sien
         * par un identifiant nu, `data.id` (`TemplateEntry.CODEC`, relevé dans le bytecode). En 26.2 la liste est dans `config`.
         */
        private const val TEMPLATE_TYPE = "minecraft:template"

        /** Le dossier des tags de blocs : toute clé de tag nue des classes de génération est une clé de tag de blocs. */
        private val BLOCK_TAGS: String = Registries.tagsDirPath(Registries.BLOCK)

        /** Ce qu'une référence vers un élément élagué devient dans un biome, avant de sortir de sa liste. */
        private val PRUNED: Identifier = Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "pruned")

        private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        /** Parmi [all], les registres que le jeu charge des datapacks, ceux de la génération. */
        fun generationRegistries(all: List<RegistryDataLoader.RegistryData<*>>): List<RegistryDataLoader.RegistryData<*>> =
            all.filter { Registries.elementsDirPath(it.key()) in GENERATION_FOLDERS }
    }

    /** Les éléments de [source] que la copie prend, registre par registre, dans l'ordre de leurs identifiants. */
    private val elements: List<Element> by lazy { listElements() }

    /** Les espaces de noms propres à la source : ceux, hors `minecraft:`, sous lesquels elle porte un élément que la copie prend. */
    private val ownNamespaces: Set<String> by lazy { elements.mapTo(HashSet()) { it.id.namespace } - MINECRAFT }

    /** Les gabarits NBT que la source porte sous ses espaces de noms propres, par identifiant d'origine. */
    private val templates: Map<Identifier, IoSupplier<InputStream>> by lazy { listTemplates() }

    /** Ce que [carries] a déjà répondu. */
    private val carriedTags = HashMap<TagRef, Boolean>()

    /**
     * Fabrique la copie dans [target], un dossier vide ou absent, et rend le compte de ce qui est écrit et élagué. Une erreur de
     * lecture ou d'écriture lève : l'appelant fabrique dans un dossier temporaire, qu'il jette.
     */
    fun fabricate(target: Path): WorldgenCopyReport {
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
        val biomeTagFiles = writeBiomeTags(target, kept.filter { it.folder == BIOME_FOLDER }.mapTo(HashSet()) { it.id })
        val tagFiles = writeTags(target, kept.flatMapTo(LinkedHashSet()) { first.tagReferences[it.ref].orEmpty() }, Pass(taken, pruned))
        templates.forEach { (template, content) ->
            val file = target.resolve("data/${TravellingDimension.MOD_ID}/$TEMPLATE_FOLDER/${copy.renamedPath(template.namespace, template.path)}$NBT")
            Files.createDirectories(file.parent)
            content.get().use { Files.copy(it, file) }
        }

        val counts = TreeMap<String, WorldgenCopyReport.Count>()
        elements.groupBy { it.folder }.forEach { (folder, listed) ->
            val gone = listed.count { it.ref in pruned }
            counts[folder] = WorldgenCopyReport.Count(written = listed.size - gone, pruned = gone)
        }
        return WorldgenCopyReport(counts, biomeTagFiles, refusals.entries.associate { (ref, reason) -> ref.toString() to reason }, tagFiles, templates.size)
    }

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

    private fun listTemplates(): Map<Identifier, IoSupplier<InputStream>> {
        val listed = TreeMap<Identifier, IoSupplier<InputStream>>()
        ownNamespaces.forEach { namespace ->
            source.listResources(PackType.SERVER_DATA, namespace, TEMPLATE_FOLDER) { file, content ->
                if (file.path.endsWith(NBT)) {
                    listed[Identifier.fromNamespaceAndPath(file.namespace, file.path.substring(TEMPLATE_FOLDER.length + 1, file.path.length - NBT.length))] = content
                }
            }
        }
        return listed
    }

    /** Dit si la copie écrit le tag [tag] : son espace de noms est propre à la source, qui porte son fichier. */
    private fun carries(tag: TagRef): Boolean = carriedTags.getOrPut(tag) { tag.id.namespace in ownNamespaces && source.getResource(PackType.SERVER_DATA, tag.file) != null }

    /** L'identifiant que la copie écrit pour ce qu'elle renomme : un élément, un tag ou un gabarit d'identifiant d'origine [id]. */
    private fun renamed(id: Identifier): Identifier = Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, copy.renamedPath(id.namespace, id.path))

    /**
     * Les tags de biomes : chaque fichier de tag `minecraft:` de [biomeTags] qui liste un biome de [biomes] donne un fichier du
     * même nom, en `replace: false`, qui liste sa copie. Un tag qui en inclut un autre suit sans rien demander. Rend le nombre
     * de fichiers écrits.
     */
    private fun writeBiomeTags(target: Path, biomes: Set<Identifier>): Int {
        var files = 0
        biomeTags.listResources(PackType.SERVER_DATA, MINECRAFT, Registries.tagsDirPath(Registries.BIOME)) { file, content ->
            if (file.path.endsWith(JSON)) {
                val copies = listedIn(read(content)).filter { it in biomes }.map { renamed(it).toString() }
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
        return values.mapNotNull { entry -> entryText(entry)?.let(Identifier::tryParse) }
    }

    /**
     * Les tags propres à la source : chaque tag de [referenced], et ceux qu'il inclut de proche en proche, s'écrit sous son
     * nouvel identifiant, ses entrées réécrites par [pass]. Rend le nombre de fichiers écrits.
     */
    private fun writeTags(target: Path, referenced: Set<TagRef>, pass: Pass): Int {
        val waiting = ArrayDeque(referenced)
        val written = HashSet<TagRef>()
        while (waiting.isNotEmpty()) {
            val tag = waiting.removeFirst()
            if (!written.add(tag)) continue
            val content = source.getResource(PackType.SERVER_DATA, tag.file) ?: continue
            write(target.resolve("data/${TravellingDimension.MOD_ID}/${tag.folder}/${copy.renamedPath(tag.id.namespace, tag.id.path)}$JSON"), pass.recodeTag(tag, read(content), waiting::add))
        }
        return written.size
    }

    /** L'identifiant que porte une entrée de tag, `#` compris pour un tag inclus : une chaîne, ou le champ `id` d'une entrée facultative. */
    private fun entryText(entry: JsonElement): String? = when {
        entry is JsonPrimitive && entry.isString -> entry.asString
        entry is JsonObject -> text(entry.get("id"))
        else -> null
    }

    private fun text(json: JsonElement?): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.asString

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
     * Un passage sur les éléments : sa recherche renomme les éléments de [taken] et les tags propres à la source, note chaque
     * référence vers l'un d'eux ([references], [tagReferences]), et rend [PRUNED] pour un élément de [pruned].
     */
    private inner class Pass(private val taken: Map<String, Set<Identifier>>, private val pruned: Set<ElementRef>) {

        /** Pour chaque élément recodé, les éléments copiés qu'il référence. */
        val references = HashMap<ElementRef, MutableSet<ElementRef>>()

        /** Pour chaque élément recodé, les tags propres à la source qu'il référence. */
        val tagReferences = HashMap<ElementRef, MutableSet<TagRef>>()

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
                rewriteBareKeys(json)
                return if (pruned.isEmpty()) json else withoutPruned(json)
            } catch (e: Exception) {
                // Un fichier illisible, ou un codec qui lève au lieu de rendre une erreur.
                refused(e.toString())
                return null
            } finally {
                current = null
            }
        }

        /**
         * Le fichier du tag [tag] tel que la copie l'écrit, d'après [json], son fichier dans la source : chaque entrée s'y réécrit
         * comme une référence, et celle d'un élément élagué en sort. [included] reçoit les tags propres à la source qu'il inclut.
         */
        fun recodeTag(tag: TagRef, json: JsonElement, included: (TagRef) -> Unit): JsonObject {
            val elementFolder = tag.folder.removePrefix("tags/")
            val values = JsonArray()
            ((json as? JsonObject)?.get("values") as? JsonArray)?.forEach { entry ->
                val listed = entryText(entry)
                val id = listed?.removePrefix("#")?.let(Identifier::tryParse)
                if (listed == null || id == null) {
                    values.add(entry)
                    return@forEach
                }
                val target = if (listed.startsWith("#")) {
                    val inner = TagRef(tag.folder, id)
                    if (carries(inner)) "#${renamed(id)}".also { included(inner) } else listed
                } else {
                    written(elementFolder, id).takeIf { it != PRUNED }?.toString() ?: return@forEach
                }
                values.add(if (entry is JsonObject) entry.deepCopy().also { it.addProperty("id", target) } else JsonPrimitive(target))
            }
            return JsonObject().also {
                it.addProperty("replace", false)
                it.add("values", values)
            }
        }

        private fun <T : Any> transcode(data: RegistryDataLoader.RegistryData<T>, json: JsonElement): DataResult<JsonElement> =
            data.elementCodec().parse(ops, json).flatMap { data.elementCodec().encodeStart(ops, it) }

        /* Le type d'élément d'un registre n'est connu que du codec qui demande sa recherche. */
        @Suppress("UNCHECKED_CAST")
        private fun lookupOf(registry: ResourceKey<out Registry<*>>): Lookup<*> = Lookup(registry as ResourceKey<out Registry<Any>>, ::written, ::writtenTag)

        /**
         * L'identifiant que la copie écrit pour [id], du registre de dossier [folder] : le sien quand la source le porte, celui
         * que [elsewhere] donne sinon, et à défaut l'identifiant d'origine.
         */
        private fun written(folder: String, id: Identifier): Identifier {
            if (taken[folder]?.contains(id) != true) return elsewhere(folder, id) ?: id
            val ref = ElementRef(folder, id)
            current?.takeIf { it != ref }?.let { references.getOrPut(it) { HashSet() }.add(ref) }
            return if (ref in pruned) PRUNED else renamed(id)
        }

        /** L'identifiant que la copie écrit pour le tag [id] du dossier [folder] : le sien quand il est propre à la source, qui le porte. */
        private fun writtenTag(folder: String, id: Identifier): Identifier {
            val tag = TagRef(folder, id)
            if (!carries(tag)) return id
            current?.let { tagReferences.getOrPut(it) { HashSet() }.add(tag) }
            return renamed(id)
        }

        /**
         * Réécrit dans [json] ce que la recherche n'a pas vu : les clés nues de [KEY_FIELDS], les clés de tag nues de
         * [TAG_FIELDS], les gabarits d'une feature de type [TEMPLATE_TYPE], et toute chaîne `#<tag>` restée sous un espace de
         * noms propre à la source. Une telle chaîne est une clé de tag de blocs que le jeu lit sans passer par la recherche
         * (`TagKey.hashedCodec`) : celles qu'elle a vues portent déjà leur nouvel identifiant.
         */
        private fun rewriteBareKeys(json: JsonElement) {
            when (json) {
                is JsonArray -> for (index in 0 until json.size()) {
                    val item = json.get(index)
                    hashedTag(item)?.let { json.set(index, it) } ?: rewriteBareKeys(item)
                }

                is JsonObject -> {
                    val type = text(json.get("type"))
                    KEY_FIELDS.filter { it.type == type && json.has(it.name) }.forEach { field -> json.add(field.name, rewrittenKeys(field.folder, json.get(field.name))) }
                    TAG_FIELDS.filter { text(json.get(it.discriminator)) == it.value }.forEach { field ->
                        text(json.get(field.name))?.let(Identifier::tryParse)?.let { json.addProperty(field.name, writtenTag(BLOCK_TAGS, it).toString()) }
                    }
                    if (type == TEMPLATE_TYPE) rewriteTemplates(json)
                    json.entrySet().forEach { entry -> hashedTag(entry.value)?.let { entry.setValue(it) } ?: rewriteBareKeys(entry.value) }
                }
            }
        }

        /** [value], une clé ou une liste de clés du registre de dossier [folder]. Un tag ou un objet en ligne restent tels quels. */
        private fun rewrittenKeys(folder: String, value: JsonElement): JsonElement = when {
            value is JsonArray -> JsonArray().also { keys -> value.forEach { keys.add(rewrittenKeys(folder, it)) } }
            value is JsonPrimitive && value.isString -> Identifier.tryParse(value.asString)?.let { JsonPrimitive(written(folder, it).toString()) } ?: value
            else -> value
        }

        /** La chaîne `#<tag>` que la copie écrit à la place de [value], ou `null` quand [value] n'est pas une telle clé d'un tag de blocs qu'elle copie. */
        private fun hashedTag(value: JsonElement): JsonElement? {
            val hashed = text(value)?.takeIf { it.startsWith("#") } ?: return null
            val id = Identifier.tryParse(hashed.substring(1)) ?: return null
            return writtenTag(BLOCK_TAGS, id).takeIf { it != id }?.let { JsonPrimitive("#$it") }
        }

        /** Réécrit le gabarit de chaque entrée de [feature], une feature de type [TEMPLATE_TYPE], quand la copie le porte. */
        private fun rewriteTemplates(feature: JsonObject) {
            val holder = if (feature.has("templates")) feature else feature.get("config") as? JsonObject
            (holder?.get("templates") as? JsonArray)?.forEach { entry ->
                val data = (entry as? JsonObject)?.get("data") as? JsonObject ?: return@forEach
                text(data.get("id"))?.let(Identifier::tryParse)?.takeIf { it in templates }?.let { data.addProperty("id", renamed(it).toString()) }
            }
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
     * [written] donne. Un tag y est un ensemble nommé et vide, qui se réencode par le nom que [writtenTag] donne.
     */
    private class Lookup<T : Any>(
        private val registry: ResourceKey<out Registry<T>>,
        private val written: (folder: String, id: Identifier) -> Identifier,
        private val writtenTag: (folder: String, id: Identifier) -> Identifier,
    ) : HolderGetter<T>, HolderOwner<T> {

        private val folder: String = Registries.elementsDirPath(registry)
        private val tagFolder: String = Registries.tagsDirPath(registry)
        private val holders = HashMap<Identifier, Holder.Reference<T>>()
        private val tags = HashMap<Identifier, HolderSet.Named<T>>()

        override fun get(id: ResourceKey<T>): Optional<Holder.Reference<T>> {
            // La référence se note à chaque passage, même déjà bâtie : un autre fichier peut la demander.
            val target = written(folder, id.identifier())
            return Optional.of(holders.getOrPut(target) { Holder.Reference.createStandAlone(this, ResourceKey.create(registry, target)) })
        }

        /* `emptyNamed` est déprécié pour qui lirait le contenu d'un tag : celui-ci ne sert qu'à porter son nom jusqu'au réencodage. */
        @Suppress("DEPRECATION")
        override fun get(id: TagKey<T>): Optional<HolderSet.Named<T>> {
            val target = writtenTag(tagFolder, id.location())
            return Optional.of(tags.getOrPut(target) { HolderSet.emptyNamed(this, TagKey.create(registry, target)) })
        }
    }

    /** Un élément de la source : son registre, le dossier de ce registre, son identifiant d'origine et son contenu. */
    private class Element(val data: RegistryDataLoader.RegistryData<*>, val folder: String, val id: Identifier, val content: IoSupplier<InputStream>) {
        val ref = ElementRef(folder, id)
    }

    /** Un élément copié, par le dossier de son registre et son identifiant d'origine. */
    private data class ElementRef(val folder: String, val id: Identifier) {
        override fun toString(): String = "$folder $id"
    }

    /** Un tag de la source, par le dossier des tags de son registre (`tags/block`) et son identifiant d'origine. */
    private data class TagRef(val folder: String, val id: Identifier) {

        /** Son fichier dans un datapack. */
        val file: Identifier
            get() = Identifier.fromNamespaceAndPath(id.namespace, "$folder/${id.path}$JSON")
    }

    /** Un champ de [KEY_FIELDS] : dans un objet de type [type], le champ [name] porte des clés du registre de dossier [folder]. */
    private class KeyField(val type: String, val name: String, val folder: String)

    /** Un champ de [TAG_FIELDS] : dans un objet dont le champ [discriminator] vaut [value], le champ [name] porte une clé de tag de blocs. */
    private class TagField(val discriminator: String, val value: String, val name: String)
}
