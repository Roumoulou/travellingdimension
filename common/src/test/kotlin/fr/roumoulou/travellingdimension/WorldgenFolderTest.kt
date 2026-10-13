// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.dimension.WorldgenFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le dossier où le joueur dépose le jar de WWOO, et sa notice : le chapitre 4.1 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Le dossier se donne, et rien du jeu ni du chargeur n'y entre : ces tests vivent dans le source set **pur**. La lecture d'un
 * jar déposé, qui demande le chargeur, s'éprouve à l'étage 1.
 */
class WorldgenFolderTest {

    @TempDir
    lateinit var game: Path

    private val folder: Path get() = game.resolve("travellingdimension/worldgen")
    private val notice: Path get() = folder.resolve("README.txt")

    @Test
    @DisplayName("au premier lancement, le mod crée le dossier et sa notice, qui dit quel fichier déposer et où le trouver")
    fun `premier lancement`() {
        assertTrue(WorldgenFolder.ensure(folder), "la notice vient d'être écrite")

        val text = notice.readText()
        assertEquals(WorldgenFolder.NOTICE, text)
        assertTrue("William Wythers' Overhauled Overworld (WWOO) for Fabric" in text, "le fichier à déposer")
        assertTrue("https://modrinth.com/mod/wwoo" in text, "où le trouver")
        assertTrue("\"worldgen\": \"william\"" in text, "le réglage qui s'en sert")
        assertFalse('\r' in text, "la notice s'écrit en LF")
        assertFalse(Char(0x2014) in text || Char(0x2013) in text, "aucun tiret cadratin ni demi-cadratin")
    }

    @Test
    @DisplayName("aux lancements suivants, rien ne se réécrit ; une notice d'une autre version du mod se remet à jour")
    fun `lancements suivants`() {
        WorldgenFolder.ensure(folder)
        assertFalse(WorldgenFolder.ensure(folder), "la notice est déjà celle du mod")

        notice.writeText("the notice of an older version of the mod")
        assertTrue(WorldgenFolder.ensure(folder))
        assertEquals(WorldgenFolder.NOTICE, notice.readText())
    }

    @Test
    @DisplayName("le mod n'écrit que sa notice, et ne supprime rien de ce que le joueur a déposé")
    fun `rien que la notice`() {
        Files.createDirectories(folder.resolve("kept"))
        val jar = folder.resolve("wwoo-fabric-26.3-3.0.1.jar").also { it.writeText("the jar of the player") }
        val other = folder.resolve("Terralith_v2.6.5+26.3.zip").also { it.writeText("another file") }

        WorldgenFolder.ensure(folder)

        assertEquals("the jar of the player", jar.readText())
        assertEquals("another file", other.readText())
        assertTrue(folder.resolve("kept").exists())
        assertEquals(setOf("README.txt", "Terralith_v2.6.5+26.3.zip", "kept", "wwoo-fabric-26.3-3.0.1.jar"), Files.list(folder).use { files -> files.map { it.name }.toList().toSet() })
    }

    @Test
    @DisplayName("les dépôts du joueur sont les fichiers du dossier, dans l'ordre de leurs noms : ni la notice, ni un sous-dossier")
    fun `depots du joueur`() {
        assertEquals(emptyList<Path>(), WorldgenFolder.deposits(folder), "un dossier absent n'a aucun dépôt")

        WorldgenFolder.ensure(folder)
        assertEquals(emptyList<Path>(), WorldgenFolder.deposits(folder), "la notice ne compte pas")

        Files.createDirectories(folder.resolve("a-folder"))
        listOf("wwoo-b.jar", "notes.txt", "wwoo-a.jar").forEach { folder.resolve(it).writeText(it) }
        assertEquals(listOf("notes.txt", "wwoo-a.jar", "wwoo-b.jar"), WorldgenFolder.deposits(folder).map { it.name })
    }
}
