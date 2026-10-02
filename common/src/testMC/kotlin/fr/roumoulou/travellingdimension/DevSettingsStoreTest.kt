// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.moulou.storify.StoreDecodeException
import fr.roumoulou.travellingdimension.dev.DevWorld
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/**
 * `dev.json` lu exactement comme le jeu le lit, par [DevWorld.readSettings], sur un fichier
 * temporaire. Le fichier naît à l'octet de la ressource commentée du jar : lu en JSON strict,
 * il était illisible à chaque lancement, et ses réglages n'étaient jamais pris en compte. Le
 * JSON5 y admet commentaires et virgule finale, et refuse toujours ce qu'une faute de frappe
 * produirait.
 */
class DevSettingsStoreTest {

    @TempDir
    lateinit var directory: Path

    private val file: Path get() = directory.resolve("dev.json")

    @Test
    @DisplayName("un fichier absent naît de la ressource commentée, à l'octet, et se lit")
    fun `fichier absent`() {
        assertEquals(DevWorld.DevSettings(), DevWorld.readSettings(file))
        val resource = DevWorld::class.java.classLoader.getResourceAsStream("travellingdimension/dev.json")!!.use { it.readBytes() }
        assertArrayEquals(resource, file.readBytes(), "le fichier est la copie de la ressource, commentaires compris")
    }

    @Test
    @DisplayName("un commentaire et une virgule finale passent : le fichier est du JSON5")
    fun `json5 admis`() {
        file.writeText("{\n  // le superflat coupé pour un essai en relief\n  \"flatWorld\": false,\n  \"surfaceY\": 80,\n}\n")
        assertEquals(DevWorld.DevSettings(flatWorld = false, surfaceY = 80), DevWorld.readSettings(file))
    }

    @Test
    @DisplayName("une clé inconnue est refusée : une faute de frappe ne passe pas en silence")
    fun `cle inconnue`() {
        file.writeText("{ \"flatWorld\": true, \"surfaceZ\": 63 }\n")
        val refusal = assertThrows(StoreDecodeException::class.java) { DevWorld.readSettings(file) }
        assertTrue(refusal.message!!.contains("surfaceZ"), refusal.message)
    }

    @Test
    @DisplayName("une clé déclarée deux fois est refusée, avec sa ligne")
    fun `cle en double`() {
        file.writeText("{\n  \"surfaceY\": 63,\n  \"surfaceY\": 80\n}\n")
        val refusal = assertThrows(StoreDecodeException::class.java) { DevWorld.readSettings(file) }
        assertTrue(refusal.message!!.contains("surfaceY"), refusal.message)
    }
}
