// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Le cache des copies, sous `travellingdimension/generated/` : le chapitre 5.6 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Une copie vit dans le dossier de son nom, à côté du fichier qui dit ce qui l'a fabriquée, `<copie>.key.json`. Elle se
 * refabrique quand cette clé change, et seulement alors. Elle se fabrique dans un dossier temporaire, puis prend sa place d'un
 * seul renommage : une fabrication interrompue ne laisse jamais un datapack à moitié écrit.
 *
 * Aucun type du jeu n'y entre : la fabrication lui est donnée.
 */
object WorldgenCopyCache {

    private const val PACK_MCMETA = "pack.mcmeta"

    private val json = Json { prettyPrint = true }

    /**
     * Rend prête la copie [copy] sous [generated] : la reprend quand [reuse] le permet, que sa clé vaut [key] et que son
     * dossier porte un `pack.mcmeta`, sinon la fait fabriquer par [fabricate], qui reçoit un dossier temporaire vide.
     *
     * Quand la fabrication ou la mise en place lève, l'ancienne copie ne reste pas : sa clé ne vaut plus, et le jeu ne doit pas
     * charger le datapack d'une autre version. L'exception remonte à l'appelant.
     */
    fun prepare(generated: Path, copy: WorldgenCopy, key: WorldgenCopyKey, reuse: Boolean = true, fabricate: (target: Path) -> Unit): Outcome {
        val folder = generated.resolve(copy.folder)
        val keyFile = generated.resolve(copy.keyFile)
        val temporary = generated.resolve("${copy.folder}.tmp")

        if (reuse && readKey(keyFile) == key && Files.isRegularFile(folder.resolve(PACK_MCMETA))) return Outcome.REUSED

        // La clé part la première : un arrêt en cours de route laisse une copie sans clé, que le lancement suivant refait.
        Files.deleteIfExists(keyFile)
        discard(temporary)
        try {
            Files.createDirectories(temporary)
            fabricate(temporary)
            discard(folder)
            Files.move(temporary, folder, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            discard(temporary)
            discard(folder)
            throw e
        }
        Files.writeString(keyFile, textOf(key))
        return Outcome.FABRICATED
    }

    /** Le texte de [key], tel que `<copie>.key.json` le porte : le garde-fou compare les clés par lui, sans les décoder ([WorldgenCopyGuard]). */
    fun textOf(key: WorldgenCopyKey): String = json.encodeToString(WorldgenCopyKey.serializer(), key) + "\n"

    /** La clé qu'écrit [file], ou `null` quand il manque ou ne se lit pas : la copie se refabrique. */
    private fun readKey(file: Path): WorldgenCopyKey? =
        try {
            if (Files.isRegularFile(file)) json.decodeFromString(WorldgenCopyKey.serializer(), Files.readString(file)) else null
        } catch (e: Exception) {
            null
        }

    /** Retire [directory]. Son `pack.mcmeta` part le premier : un retrait interrompu ne laisse pas un datapack que le jeu chargerait. */
    private fun discard(directory: Path) {
        if (!Files.exists(directory)) return
        Files.deleteIfExists(directory.resolve(PACK_MCMETA))
        if (!directory.toFile().deleteRecursively()) throw IOException("cannot delete $directory")
    }

    /** Ce que [prepare] a fait d'une copie. */
    enum class Outcome { REUSED, FABRICATED }
}

/**
 * Ce qui a fabriqué une copie : la version du mod, celle du jeu, et pour la copie William l'empreinte SHA-256 du jar déposé. La
 * copie vanilla n'a pas de source déposée.
 */
@Serializable
data class WorldgenCopyKey(
    @SerialName("mod") val modVersion: String,
    @SerialName("game") val gameVersion: String,
    @SerialName("source_sha256") val sourceSha256: String? = null,
)
