// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.dimension.WilliamJar
import fr.roumoulou.travellingdimension.dimension.WilliamJarRefusal
import fr.roumoulou.travellingdimension.dimension.WilliamJars
import net.fabricmc.loader.api.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * La reconnaissance d'un jar WWOO, son contrôle de version et le choix parmi plusieurs : les chapitres 4.3 et 4.4 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Chaque jar est écrit par le test, dans un dossier temporaire : aucun ne porte quoi que ce soit de WWOO. La version du jeu se
 * donne, pour que chaque cas vaille dans les trois modules. Le prédicat et la version se lisent par le chargeur Fabric, que
 * l'étage 0 ne voit pas : c'est ce qui fait vivre ces tests ici.
 */
class WilliamJarsTest {

    private companion object {
        val GAME: Version = Version.parse("26.3")

        /** Le datapack que la reconnaissance attend dans un jar WWOO. */
        const val DATAPACK_FILE = "resources/wwoo_main/data/minecraft/worldgen/biome/plains.json"
    }

    @TempDir
    lateinit var folder: Path

    @Test
    @DisplayName("un jar WWOO se reconnaît à son contenu, pas à son nom : renommé, il est accepté")
    fun `reconnaissance par le contenu`() {
        val jar = jar("william.jar", metadata(version = "3.0.1", minecraft = "\"~26.3\""))

        assertEquals(WilliamJar.Accepted(jar, Version.parse("3.0.1")), WilliamJars.inspect(jar, GAME))
        // Le correctif d'une lignée est accepté par le même prédicat.
        assertInstanceOf(WilliamJar.Accepted::class.java, WilliamJars.inspect(jar, Version.parse("26.3.1")))
    }

    @Test
    @DisplayName("un fichier qui n'est pas un jar WWOO est ignoré, et dit pourquoi")
    fun `fichiers ignores`() {
        val text = folder.resolve("notes.txt").also { it.writeText("not an archive") }
        val empty = jar("empty.jar", metadata = null)
        val broken = jar("broken.jar", "{ not json")
        val other = jar("wwoo-fabric-26.3-3.0.1.jar", metadata(id = "othermod", version = "1.0.0", minecraft = "\"~26.3\""))
        val noDatapack = jar("no-datapack.jar", metadata(version = "3.0.1", minecraft = "\"~26.3\""), datapack = false)

        assertEquals(WilliamJar.Foreign(text, "not a readable archive"), WilliamJars.inspect(text, GAME))
        assertEquals(WilliamJar.Foreign(empty, "no fabric.mod.json at its root"), WilliamJars.inspect(empty, GAME))
        assertEquals(WilliamJar.Foreign(broken, "its fabric.mod.json is not a JSON object"), WilliamJars.inspect(broken, GAME))
        // Le nom d'un jar WWOO ne suffit pas.
        assertEquals(WilliamJar.Foreign(other, "it is the mod 'othermod'"), WilliamJars.inspect(other, GAME))
        assertEquals(WilliamJar.Foreign(noDatapack, "no resources/wwoo_main/data in it"), WilliamJars.inspect(noDatapack, GAME))
        assertEquals(WilliamJar.Foreign(folder.resolve("absent.jar"), "not a readable archive"), WilliamJars.inspect(folder.resolve("absent.jar"), GAME))
    }

    @Test
    @DisplayName("un jar WWOO dont le depends.minecraft n'accepte pas la version du jeu est refusé, et nomme les deux versions")
    fun `controle de version`() {
        val older = jar("wwoo-fabric-26.2-2.7.1.jar", metadata(version = "2.7.1", minecraft = "\"~26.2\""))

        assertEquals(WilliamJar.WrongVersion(older, Version.parse("2.7.1"), "~26.2"), WilliamJars.inspect(older, GAME))
        assertInstanceOf(WilliamJar.Accepted::class.java, WilliamJars.inspect(older, Version.parse("26.2")), "le même jar, dans un jeu en 26.2")

        val reading = WilliamJars.read(listOf(older), GAME)
        assertNull(reading.taken)
        assertEquals(listOf(WilliamJarRefusal.WrongVersion("wwoo-fabric-26.2-2.7.1.jar", "~26.2", "26.3")), reading.reported)

        // Une liste de prédicats : un seul suffit.
        val several = jar("several.jar", metadata(version = "2.7.1", minecraft = "[\"~26.2\", \"~26.3\"]"))
        assertInstanceOf(WilliamJar.Accepted::class.java, WilliamJars.inspect(several, GAME))
        val neither = jar("neither.jar", metadata(version = "2.7.1", minecraft = "[\"~26.1.2\", \"~26.2\"]"))
        assertEquals(WilliamJar.WrongVersion(neither, Version.parse("2.7.1"), "~26.1.2 || ~26.2"), WilliamJars.inspect(neither, GAME))
    }

    @Test
    @DisplayName("un jar WWOO dont le fabric.mod.json ne permet pas le contrôle est illisible")
    fun `jar illisible`() {
        val noVersion = jar("no-version.jar", """{ "id": "wwoo", "depends": { "minecraft": "~26.3" } }""")
        val noPredicate = jar("no-predicate.jar", """{ "id": "wwoo", "version": "3.0.1", "depends": { "fabricloader": "*" } }""")
        val badPredicate = jar("bad-predicate.jar", metadata(version = "3.0.1", minecraft = "\">=\""))

        assertEquals(WilliamJar.Unreadable(noVersion, "no version in its fabric.mod.json"), WilliamJars.inspect(noVersion, GAME))
        assertEquals(WilliamJar.Unreadable(noPredicate, "no depends.minecraft in its fabric.mod.json"), WilliamJars.inspect(noPredicate, GAME))
        assertEquals(WilliamJar.Unreadable(badPredicate, "its depends.minecraft '>=' is not readable"), WilliamJars.inspect(badPredicate, GAME))

        val reading = WilliamJars.read(listOf(badPredicate, noVersion), GAME)
        assertEquals(listOf(WilliamJarRefusal.Unreadable("bad-predicate.jar", "its depends.minecraft '>=' is not readable")), reading.reported, "le premier illisible, seul")
        assertEquals(2, reading.refused.size, "le log les dit tous")
    }

    @Test
    @DisplayName("parmi plusieurs jars acceptés, le mod prend la plus haute version de WWOO, et le premier par son nom à version égale")
    fun `choix parmi plusieurs`() {
        val low = jar("a-low.jar", metadata(version = "2.7.1", minecraft = "\">=26.2\""))
        val high = jar("b-high.jar", metadata(version = "3.0.1", minecraft = "\"~26.3\""))
        val twin = jar("c-twin.jar", metadata(version = "3.0.1", minecraft = "\"~26.3\""))
        val older = jar("d-older.jar", metadata(version = "9.9.9", minecraft = "\"~26.2\""))
        val notes = folder.resolve("notes.txt").also { it.writeText("not an archive") }

        val reading = WilliamJars.read(listOf(low, high, twin, older, notes), GAME)

        assertEquals(listOf("a-low.jar", "b-high.jar", "c-twin.jar"), reading.accepted.map { it.file.name })
        assertEquals("b-high.jar", reading.taken?.file?.name, "la plus haute version acceptée, pas la plus haute du dossier")
        // Un jar est pris : ceux qui sont refusés n'ont que leur ligne au log, aucun message.
        assertEquals(emptyList<WilliamJarRefusal>(), reading.reported)
        assertEquals(listOf<WilliamJarRefusal>(WilliamJarRefusal.WrongVersion("d-older.jar", "~26.2", "26.3")), reading.refused)
    }

    @Test
    @DisplayName("sans jar accepté, la résolution reçoit au plus un jar d'une autre version, le plus haut, puis un jar illisible")
    fun `refus rapportes`() {
        val old = jar("a-old.jar", metadata(version = "2.7.0", minecraft = "\"~26.1.2\""))
        val older = jar("b-older.jar", metadata(version = "2.7.1", minecraft = "\"~26.2\""))
        val unreadable = jar("c-unreadable.jar", """{ "id": "wwoo", "depends": { "minecraft": "~26.3" } }""")

        val reading = WilliamJars.read(listOf(old, older, unreadable), GAME)

        assertNull(reading.taken)
        assertEquals(
            listOf(WilliamJarRefusal.WrongVersion("b-older.jar", "~26.2", "26.3"), WilliamJarRefusal.Unreadable("c-unreadable.jar", "no version in its fabric.mod.json")),
            reading.reported,
        )
        assertEquals(3, reading.refused.size)

        // Un dossier vide, ou sans aucun jar WWOO : rien à rapporter, la résolution dira que le jar manque.
        assertEquals(emptyList<WilliamJarRefusal>(), WilliamJars.read(emptyList(), GAME).reported)
    }

    /** Un `fabric.mod.json` de mod [id], de version [version], dont `depends.minecraft` vaut [minecraft], écrit en JSON. */
    private fun metadata(id: String = "wwoo", version: String, minecraft: String): String =
        """{ "schemaVersion": 1, "id": "$id", "version": "$version", "depends": { "minecraft": $minecraft } }"""

    /** Écrit dans le dossier du test le jar [name] : son `fabric.mod.json` quand [metadata] en donne un, et son datapack quand [datapack] le demande. */
    private fun jar(name: String, metadata: String?, datapack: Boolean = true): Path {
        val file = folder.resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { archive ->
            if (metadata != null) archive.entry("fabric.mod.json", metadata)
            if (datapack) archive.entry(DATAPACK_FILE, "{}")
            archive.entry("unrelated.txt", "something else")
        }
        return file
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }
}
