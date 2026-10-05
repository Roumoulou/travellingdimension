// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.gameversion.GameVersion
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Optional
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/**
 * La copie William : le datapack que le mod fabrique à partir du jar WWOO du dossier `worldgen/`, sous
 * `travellingdimension:wwoo/`.
 *
 * Elle prend tout ce que le datapack `resources/wwoo_main` du jar porte dans les registres de génération, dont les biomes
 * `minecraft:` qu'il remplace, les tags de ses propres espaces de noms que ces éléments référencent, et ses gabarits NBT
 * (chapitre 5.2 de la spécification). Ses tags `minecraft:`, ses tables de butin et ses variantes d'animaux n'y entrent pas : ce
 * sont des effets sur toutes les dimensions. Le jar n'est que lu, le temps de la fabrication.
 *
 * Une référence vers un élément `minecraft:` que WWOO ne porte pas y vise la copie vanilla : l'identifiant d'origine appartient
 * à qui le remplace. La copie William ne se fabrique donc qu'après la copie vanilla, et ne se charge jamais sans elle.
 */
object WilliamCopy {

    private const val MINECRAFT = "minecraft"
    private const val JSON = "json"

    /**
     * Ce qui fabrique la copie William à partir de [jar], et sa clé : la version du mod, celle du jeu et l'empreinte SHA-256 du
     * jar. Ses références vers la copie vanilla visent celle de [generated]. Le jar se lit ici en entier, pour son empreinte :
     * une lecture qui échoue lève.
     */
    fun source(jar: Path, generated: Path = WorldgenPacks.GENERATED_FOLDER): WorldgenCopyMaker.Source =
        WorldgenCopyMaker.Source(WorldgenCopy.WILLIAM, WorldgenCopyMaker.key(sha256(jar))) { target -> fabricate(jar, generated.resolve(WorldgenCopy.VANILLA.folder), target) }

    /** Fabrique dans [target] la copie William du jar [jar], dont les références vers le jeu visent la copie vanilla de [vanillaCopy], et rend son compte. */
    fun fabricate(jar: Path, vanillaCopy: Path, target: Path): WorldgenCopyReport {
        val bridge = GameVersion.bridge
        val vanilla = elementsOf(vanillaCopy)
        return FileSystems.newFileSystem(jar).use { archive ->
            val datapack = PathPackResources(PackLocationInfo(WilliamJars.MOD_ID, Component.literal("WWOO"), PackSource.BUILT_IN, Optional.empty()), archive.getPath(WilliamJars.DATAPACK))
            WorldgenCopyEngine(
                WorldgenCopy.WILLIAM,
                datapack,
                WorldgenCopyEngine.generationRegistries(bridge.worldRegistries),
                biomeTags = bridge.vanillaDatapack(),
                elsewhere = { folder, element ->
                    if (element.namespace == MINECRAFT && element.path in vanilla[folder].orEmpty()) Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, WorldgenCopy.VANILLA.renamedPath(MINECRAFT, element.path))
                    else null
                },
            ).fabricate(target)
        }
    }

    /**
     * Les éléments `minecraft:` que la copie vanilla de [copy] porte, par dossier de registre et par chemin d'origine : c'est
     * elle, sur le disque, qui dit ce qu'une référence peut viser.
     */
    private fun elementsOf(copy: Path): Map<String, Set<String>> =
        WorldgenCopyEngine.GENERATION_FOLDERS.associateWith { folder ->
            val root = copy.resolve("data/${TravellingDimension.MOD_ID}/$folder/${WorldgenCopy.VANILLA.folder}")
            if (!Files.isDirectory(root)) emptySet()
            else root.walk().filter { it.isRegularFile() && it.extension == JSON }.mapTo(HashSet()) { root.relativize(it).invariantSeparatorsPathString.removeSuffix(".$JSON") }
        }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
