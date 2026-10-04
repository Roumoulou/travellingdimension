// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.validation.ValidationException
import fr.roumoulou.travellingdimension.config.ModJson
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.TravelConfigValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le chapitre 10 de `configuration.md`, prouvé sur un vrai store : le fichier est ouvert
 * exactement comme `ConfigManager` le fait, même format, mêmes options, même validateur, sur
 * un fichier temporaire. Ce que le jeu ferait d'un fichier cassé se lit ici sans le lancer :
 * l'exception qui sort de l'ouverture est celle qui remonterait jusqu'au rapport de crash.
 */
class ConfigStoreTest {

    @TempDir
    lateinit var directory: Path

    private val file: Path get() = directory.resolve("config.json")

    @Test
    @DisplayName("un fichier absent naît des défauts, sans searchRadiusVoyage")
    fun `fichier absent`() {
        assertFalse(Files.exists(file))
        open().use { store -> assertEquals(TravelConfig(), store.data) }
        assertTrue(Files.exists(file), "le fichier est créé à l'ouverture")
        val written = file.readText()
        assertFalse(written.contains("searchRadiusVoyage"), written)
        assertTrue(written.contains("\"searchRadiusOverworld\": 128"), written)
    }

    @Test
    @DisplayName("un fichier hors bornes est refusé à l'ouverture, tous ses problèmes nommés")
    fun `fichier hors bornes`() {
        file.writeText(defaultsJson().replace("\"ratio\": 16", "\"ratio\": 1").replace("\"rescueRadius\": 8", "\"rescueRadius\": 99"))
        val refusal = assertThrows(ValidationException::class.java) { open().close() }
        assertEquals(2, refusal.errorCount, refusal.message)
        assertEquals(setOf("ratio", "rescueRadius"), refusal.errors.map { it.field }.toSet(), refusal.message)
    }

    @Test
    @DisplayName("une clé inconnue est refusée : le searchRadiusVoyage d'un fichier de la 2.7.0")
    fun `cle inconnue`() {
        file.writeText(defaultsJson().replace("\"searchRadiusOverworld\": 128", "\"searchRadiusOverworld\": 128,\n  \"searchRadiusVoyage\": 8"))
        val refusal = assertThrows(StoreDecodeException::class.java) { open().close() }
        assertTrue(refusal.message!!.contains("searchRadiusVoyage"), refusal.message)
    }

    @Test
    @DisplayName("une clé déclarée deux fois est refusée, avec sa ligne")
    fun `cle en double`() {
        file.writeText(defaultsJson().replace("\"ratio\": 16", "\"ratio\": 16,\n  \"ratio\": 16"))
        val refusal = assertThrows(StoreDecodeException::class.java) { open().close() }
        assertTrue(refusal.message!!.contains("ratio"), refusal.message)
    }

    @Test
    @DisplayName("un commentaire est refusé : le fichier est du JSON strict")
    fun `commentaire refuse`() {
        file.writeText("// un fichier commenté à la main\n" + defaultsJson())
        assertThrows(StoreDecodeException::class.java) { open().close() }
    }

    private fun open() = StoreFactory.createFromConstructor<TravelConfig>(file.toString(), ModJson.format, ModJson.storeConfig(), TravelConfigValidator)

    private fun defaultsJson(): String = ModJson.json.encodeToString(TravelConfig.serializer(), TravelConfig())
}
