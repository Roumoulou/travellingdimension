// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyCache
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyCache.Outcome
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le cache des copies, comme automate de fichiers : la clé, la reprise, la refabrication quand la clé change, et ce qui reste
 * d'une fabrication qui échoue.
 *
 * La fabrication est donnée par le test, qui écrit un `pack.mcmeta` et un fichier témoin : rien du jeu ne s'amorce. Le test vit à
 * cet étage parce que la clé s'écrit par la sérialisation que le jeu apporte.
 */
class WorldgenCopyCacheTest {

    private companion object {
        val KEY = WorldgenCopyKey(modVersion = "2.9.0+26.3", gameVersion = "26.3")
    }

    @TempDir
    lateinit var generated: Path

    /** Combien de fois le cache a fait fabriquer. */
    private var fabrications = 0

    private val folder: Path get() = generated.resolve("vanilla")
    private val keyFile: Path get() = generated.resolve("vanilla.key.json")
    private val temporary: Path get() = generated.resolve("vanilla.tmp")

    @Test
    @DisplayName("une copie absente se fabrique : son dossier prend sa place, sa clé s'écrit, rien de temporaire ne reste")
    fun `premiere fabrication`() {
        assertEquals(Outcome.FABRICATED, prepare(KEY, content = "first"))

        assertEquals("first", folder.resolve("marker.txt").readText())
        assertTrue(folder.resolve("pack.mcmeta").exists())
        assertFalse(temporary.exists())
        // La copie vanilla n'a pas de source déposée : sa clé n'en nomme pas.
        assertEquals("""{"mod":"2.9.0+26.3","game":"26.3"}""", keyFile.readText().filterNot { it.isWhitespace() })
        // Le garde-fou compare les clés par ce texte : c'est celui du fichier, au caractère près.
        assertEquals(WorldgenCopyCache.textOf(KEY), keyFile.readText())
    }

    @Test
    @DisplayName("une copie dont la clé n'a pas changé se reprend, sans fabrication")
    fun `reprise`() {
        prepare(KEY, content = "first")

        assertEquals(Outcome.REUSED, prepare(KEY, content = "second"))
        assertEquals(1, fabrications)
        assertEquals("first", folder.resolve("marker.txt").readText())
    }

    @Test
    @DisplayName("une copie se refabrique quand sa clé change : la version du mod, celle du jeu, l'empreinte de sa source")
    fun `cle changee`() {
        prepare(KEY, content = "first")

        assertEquals(Outcome.FABRICATED, prepare(KEY.copy(modVersion = "2.10.0+26.3"), content = "mod"))
        assertEquals(Outcome.FABRICATED, prepare(KEY.copy(modVersion = "2.10.0+26.3", gameVersion = "26.4"), content = "game"))
        assertEquals(Outcome.FABRICATED, prepare(KEY.copy(modVersion = "2.10.0+26.3", gameVersion = "26.4", sourceSha256 = "abc"), content = "source"))
        assertEquals("source", folder.resolve("marker.txt").readText())

        // La nouvelle clé est écrite : le lancement suivant reprend.
        assertEquals(Outcome.REUSED, prepare(KEY.copy(modVersion = "2.10.0+26.3", gameVersion = "26.4", sourceSha256 = "abc"), content = "again"))
    }

    @Test
    @DisplayName("une copie sans clé, ou à la clé illisible, se refabrique : ce que son dossier portait ne reste pas")
    fun `sans cle`() {
        // Un datapack posé là sans clé : rien ne dit que le mod l'a fabriqué, ni pour quelle version.
        Files.createDirectories(folder.resolve("data")).resolve("stale.json").writeText("{}")
        folder.resolve("pack.mcmeta").writeText("{}")

        assertEquals(Outcome.FABRICATED, prepare(KEY, content = "first"))
        assertFalse(folder.resolve("data/stale.json").exists())

        keyFile.writeText("not json")
        assertEquals(Outcome.FABRICATED, prepare(KEY, content = "second"))
        assertEquals("second", folder.resolve("marker.txt").readText())
    }

    @Test
    @DisplayName("sans reprise permise, la copie se refabrique à chaque fois, clé inchangée")
    fun `sans reprise`() {
        prepare(KEY, content = "first")

        assertEquals(Outcome.FABRICATED, prepare(KEY, content = "second", reuse = false))
        assertEquals("second", folder.resolve("marker.txt").readText())
    }

    @Test
    @DisplayName("une fabrication qui lève ne laisse ni copie, ni clé, ni dossier temporaire : l'ancienne copie part aussi")
    fun `fabrication en echec`() {
        prepare(KEY, content = "first")

        val failure = assertThrows(IllegalStateException::class.java) {
            WorldgenCopyCache.prepare(generated, WorldgenCopy.VANILLA, KEY.copy(gameVersion = "26.4")) { target ->
                target.resolve("pack.mcmeta").writeText("{}")
                throw IllegalStateException("half written")
            }
        }

        assertEquals("half written", failure.message)
        assertFalse(folder.exists(), "l'ancienne copie, d'une clé qui ne vaut plus")
        assertFalse(keyFile.exists())
        assertFalse(temporary.exists())
    }

    /** Fait préparer la copie vanilla : la fabrication écrit un `pack.mcmeta` et [content] dans un fichier témoin. */
    private fun prepare(key: WorldgenCopyKey, content: String, reuse: Boolean = true): Outcome =
        WorldgenCopyCache.prepare(generated, WorldgenCopy.VANILLA, key, reuse) { target ->
            fabrications++
            target.resolve("pack.mcmeta").writeText("{}")
            target.resolve("marker.txt").writeText(content)
        }
}
