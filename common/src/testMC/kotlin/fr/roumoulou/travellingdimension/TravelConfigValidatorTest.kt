// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.moulou.storify.validation.ValidationResult
import fr.moulou.storify.validation.evaluate
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.TravelConfigValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les bornes de la configuration : refusées, nommées, jamais corrigées.
 *
 * À l'étage 1 parce que le validateur vérifie la forme des identifiants avec
 * `Identifier.tryParse`, une classe du jeu. L'invariant qui gouverne le tout : une
 * configuration est entière et valide, ou le jeu ne démarre pas.
 */
class TravelConfigValidatorTest {

    @Test
    @DisplayName("la configuration par défaut est valide")
    fun `defauts valides`() {
        assertEquals(ValidationResult.Success, TravelConfigValidator.evaluate(TravelConfig()))
    }

    @Test
    @DisplayName("une configuration aberrante est refusée, chaque champ nommé, et rien n'est corrigé")
    fun `config aberrante refusee`() {
        val config = TravelConfig(
            ratio = 0,
            searchRadiusOverworld = 99999,
            platformDepth = 99,
            clearanceHeight = -3,
            verticalWeight = -1.0,
            platformBlock = "",
            buildShiftMaxOffset = -5,
            rescueRadius = -1,
            inhabitedThreshold = -1L,
        )

        val failure = assertInstanceOf(ValidationResult.Failure::class.java, TravelConfigValidator.evaluate(config))
        assertEquals(
            setOf(
                "ratio", "searchRadiusOverworld", "platformDepth", "clearanceHeight", "verticalWeight",
                "platformBlock", "buildShiftMaxOffset", "rescueRadius", "inhabitedThreshold",
            ),
            failure.errors.map { it.field }.toSet(),
            failure.formatFull(),
        )
        assertEquals(9, failure.errors.size, failure.formatFull())
        // Rien n'est corrigé : la configuration refusée est intacte.
        assertEquals(0, config.ratio)
        assertEquals(-1, config.rescueRadius)
    }

    @Test
    @DisplayName("un identifiant mal formé est refusé, un identifiant d'un bloc absent du jeu ne l'est pas")
    fun `identifiants`() {
        val badForm = assertInstanceOf(
            ValidationResult.Failure::class.java,
            TravelConfigValidator.evaluate(TravelConfig(frameBlock = "Amethyst Block")),
        )
        assertEquals(listOf("frameBlock"), badForm.errors.map { it.field })

        // L'existence se résout en jeu, avec repli et avertissement : un bloc d'un autre mod
        // peut ne pas être enregistré quand ce mod démarre.
        assertEquals(ValidationResult.Success, TravelConfigValidator.evaluate(TravelConfig(frameBlock = "othermod:unknown_block")))
    }

    @Test
    @DisplayName("les deux identifiants de custom mal formés sont refusés, chacun nommé ; inconnus des registres, ils ne le sont pas")
    fun `identifiants de custom`() {
        val badForm = assertInstanceOf(
            ValidationResult.Failure::class.java,
            TravelConfigValidator.evaluate(TravelConfig(customNoiseSettings = "Large Biomes", customBiomePreset = "minecraft:")),
        )
        assertEquals(listOf("customNoiseSettings", "customBiomePreset"), badForm.errors.map { it.field })

        // L'existence se résout à la création des mondes, registres chargés : un identifiant
        // inconnu y mène au repli, avec un message, et n'arrête pas le jeu.
        assertEquals(
            ValidationResult.Success,
            TravelConfigValidator.evaluate(TravelConfig(customNoiseSettings = "othermod:hills", customBiomePreset = "othermod:layout")),
        )
    }

    @Test
    @DisplayName("la taille maximale d'un portail est bornée de 3 à 41")
    fun `taille maximale bornee`() {
        val failure = assertInstanceOf(
            ValidationResult.Failure::class.java,
            TravelConfigValidator.evaluate(TravelConfig(portalMaxSize = 999, netherPortalMaxSize = 1)),
        )
        assertEquals(setOf("portalMaxSize", "netherPortalMaxSize"), failure.errors.map { it.field }.toSet())
    }
}
