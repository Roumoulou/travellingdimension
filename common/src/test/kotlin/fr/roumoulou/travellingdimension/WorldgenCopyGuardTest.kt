// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.dimension.LoadingError
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyGuard
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyGuard.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le garde-fou des copies, comme automate de fichiers : un test par cas du chapitre 6.2 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * L'automate ne prend aucun type du jeu, et la clé d'une copie y est un texte : ces tests vivent dans le source set **pur**. Le
 * test pose lui-même la copie et sa clé, telles que le cache les laisse. Le mixin qui pose et lève le témoin autour d'un vrai
 * chargement des registres s'éprouve à l'étage 2. Les derniers cas sont ceux des deux copies revues ensemble, quand les deux
 * témoins sont restés.
 */
class WorldgenCopyGuardTest {

    private companion object {
        val COPY = WorldgenCopy.VANILLA

        /** La clé de la copie posée par le test, telle que le cache l'écrit : sur plusieurs lignes, un saut de ligne en fin. */
        const val KEY = "{\n    \"mod\": \"2.9.0+26.3\",\n    \"game\": \"26.3\"\n}\n"

        /** La clé d'un autre jour : le mod a monté. */
        const val OTHER_KEY = "{\n    \"mod\": \"2.10.0+26.3\",\n    \"game\": \"26.3\"\n}\n"

        /** La clé de la copie William : elle porte en plus l'empreinte du jar déposé. */
        const val WILLIAM_KEY = "{\n    \"mod\": \"2.9.0+26.3\",\n    \"game\": \"26.3\",\n    \"source_sha256\": \"0f\"\n}\n"
    }

    @TempDir
    lateinit var generated: Path

    private val folder: Path get() = generated.resolve("vanilla")
    private val keyFile: Path get() = generated.resolve("vanilla.key.json")
    private val witness: Path get() = generated.resolve("vanilla.loading")
    private val disabled: Path get() = generated.resolve("vanilla.disabled")

    /** Une copie fabriquée et sa clé, comme après un premier lancement. */
    @BeforeEach
    fun fabricated() {
        Files.createDirectories(folder).resolve("pack.mcmeta").writeText("{}")
        keyFile.writeText(KEY)
    }

    @Test
    @DisplayName("le témoin posé puis levé : rien n'est désactivé, la copie garde sa clé")
    fun `temoin pose puis leve`() {
        WorldgenCopyGuard.arm(generated, COPY)
        assertTrue(witness.exists(), "le témoin vit le temps du chargement")

        WorldgenCopyGuard.disarm(generated, COPY)
        assertFalse(witness.exists())

        assertEquals(Verdict.ENABLED, review())
        assertEquals(KEY, keyFile.readText())
        assertFalse(disabled.exists())
    }

    @Test
    @DisplayName("un plantage sans rapport, serveur démarré : le témoin est déjà levé, rien n'est désactivé")
    fun `plantage sans rapport`() {
        // Le chargement des registres a réussi, puis le jeu plante plus tard : aucun fichier ne change entre les deux.
        WorldgenCopyGuard.arm(generated, COPY)
        WorldgenCopyGuard.disarm(generated, COPY)

        assertEquals(Verdict.ENABLED, review())
        assertEquals(Verdict.ENABLED, review(), "le lancement d'après non plus")
        assertTrue(keyFile.exists(), "le cache reprendra la copie")
    }

    @Test
    @DisplayName("le témoin resté : la copie est désactivée, sa clé devient .disabled, le témoin part")
    fun `temoin reste`() {
        WorldgenCopyGuard.arm(generated, COPY)

        assertEquals(Verdict.DISABLED_NOW, review())
        assertFalse(Verdict.DISABLED_NOW.declares)
        assertEquals(KEY, disabled.readText(), ".disabled porte la clé de la copie fautive")
        assertFalse(keyFile.exists(), "la copie n'a plus de clé : un nouvel essai la refabriquera")
        assertFalse(witness.exists())
        assertTrue(folder.resolve("pack.mcmeta").exists(), "le dossier de la copie fautive reste, pour qui veut le lire")
    }

    @Test
    @DisplayName("la désactivation tient d'un lancement à l'autre tant que la clé ne change pas")
    fun `desactivation qui tient`() {
        WorldgenCopyGuard.arm(generated, COPY)
        review()

        assertEquals(Verdict.DISABLED, review())
        assertEquals(Verdict.DISABLED, review())
        assertFalse(Verdict.DISABLED.declares)
        assertEquals(KEY, disabled.readText())
    }

    @Test
    @DisplayName("la clé se compare sans ses blancs de début et de fin")
    fun `cle sans ses blancs`() {
        WorldgenCopyGuard.arm(generated, COPY)
        review()

        assertEquals(Verdict.DISABLED, review(key = KEY.trim()))
        assertEquals(Verdict.DISABLED, review(key = "\n$KEY\n"))
    }

    @Test
    @DisplayName("le nouvel essai par la clé : la clé du jour n'est plus celle de la copie fautive")
    fun `nouvel essai par la cle`() {
        WorldgenCopyGuard.arm(generated, COPY)
        review()

        assertEquals(Verdict.RETRIED, review(key = OTHER_KEY))
        assertTrue(Verdict.RETRIED.declares)
        assertFalse(disabled.exists())
        assertFalse(keyFile.exists(), "sans clé, le cache refabrique la copie")

        // Le cache a refabriqué et écrit la clé du jour : plus rien ne retient la copie.
        keyFile.writeText(OTHER_KEY)
        assertEquals(Verdict.ENABLED, review(key = OTHER_KEY))
    }

    @Test
    @DisplayName("le nouvel essai par le fichier supprimé : la copie se prépare, et se refabrique faute de clé")
    fun `nouvel essai par le fichier supprime`() {
        WorldgenCopyGuard.arm(generated, COPY)
        review()

        disabled.deleteExisting()

        assertEquals(Verdict.ENABLED, review())
        assertTrue(Verdict.ENABLED.declares)
        assertFalse(keyFile.exists(), "sans clé, le cache refabrique la copie")
    }

    @Test
    @DisplayName("un témoin resté alors que la clé a changé : la copie fautive n'est plus celle du jour, elle est réessayée")
    fun `temoin reste et cle changee`() {
        WorldgenCopyGuard.arm(generated, COPY)

        assertEquals(Verdict.RETRIED, review(key = OTHER_KEY))
        assertFalse(witness.exists())
        assertFalse(disabled.exists())
        assertFalse(keyFile.exists())
    }

    @Test
    @DisplayName("un chargement réussi après un échec lève le témoin : rien n'est désactivé")
    fun `reussite apres un echec`() {
        // Sur le client, un chargement échoue, le jeu continue, et le suivant réussit.
        WorldgenCopyGuard.arm(generated, COPY)
        WorldgenCopyGuard.arm(generated, COPY)
        WorldgenCopyGuard.disarm(generated, COPY)

        assertEquals(Verdict.ENABLED, review())
        assertEquals(KEY, keyFile.readText())
    }

    @Test
    @DisplayName("un témoin sans clé : rien ne nomme la copie fautive, rien n'est désactivé")
    fun `temoin sans cle`() {
        keyFile.deleteExisting()
        WorldgenCopyGuard.arm(generated, COPY)

        assertEquals(Verdict.ENABLED, review())
        assertFalse(witness.exists())
        assertFalse(disabled.exists())
    }

    @Test
    @DisplayName("une désactivation interrompue, la clé déjà déplacée et le témoin encore là, se termine au lancement suivant")
    fun `desactivation interrompue`() {
        Files.move(keyFile, disabled)
        WorldgenCopyGuard.arm(generated, COPY)

        assertEquals(Verdict.DISABLED, review())
        assertFalse(witness.exists())
        assertEquals(KEY, disabled.readText())
    }

    @Test
    @DisplayName("en développement, la copie se refabrique de toute façon : le témoin et la désactivation tombent")
    fun `en developpement`() {
        WorldgenCopyGuard.arm(generated, COPY)
        assertEquals(Verdict.ENABLED, review(fresh = true))
        assertFalse(witness.exists())
        assertFalse(disabled.exists())
        assertEquals(KEY, keyFile.readText(), "la clé reste au cache, qui refabrique sans la lire")

        // Une désactivation venue d'un lancement hors développement tombe aussi.
        Files.move(keyFile, disabled)
        assertEquals(Verdict.ENABLED, review(fresh = true))
        assertFalse(disabled.exists())
    }

    @Test
    @DisplayName("le témoin de la copie vanilla ne dit rien de la copie William")
    fun `une copie a la fois`() {
        generated.resolve("wwoo.key.json").writeText(KEY)
        WorldgenCopyGuard.arm(generated, WorldgenCopy.WILLIAM)

        assertEquals(Verdict.ENABLED, review())
        assertEquals(Verdict.DISABLED_NOW, WorldgenCopyGuard.review(generated, WorldgenCopy.WILLIAM, KEY))
        assertEquals(KEY, generated.resolve("wwoo.disabled").readText())
        assertTrue(keyFile.exists())
    }

    @Test
    @DisplayName("deux témoins restés, aucune erreur n'a nommé la fautive : la copie William seule est désactivée, le témoin de la copie vanilla tombe")
    fun `deux temoins restes`() {
        williamFabricated()
        WorldgenCopyGuard.arm(generated, WorldgenCopy.VANILLA)
        WorldgenCopyGuard.arm(generated, WorldgenCopy.WILLIAM)

        assertEquals(mapOf(WorldgenCopy.VANILLA to Verdict.ENABLED, WorldgenCopy.WILLIAM to Verdict.DISABLED_NOW), reviewBoth())
        assertFalse(witness.exists())
        assertFalse(disabled.exists(), "la copie vanilla garde le bénéfice du doute")
        assertEquals(KEY, keyFile.readText(), "le cache reprendra la copie vanilla")
        assertEquals(WILLIAM_KEY, generated.resolve("wwoo.disabled").readText())
        assertFalse(generated.resolve("wwoo.loading").exists())

        // La fautive était la copie vanilla : au lancement suivant son témoin reste seul, et c'est elle que la revue désactive.
        WorldgenCopyGuard.arm(generated, WorldgenCopy.VANILLA)
        assertEquals(mapOf(WorldgenCopy.VANILLA to Verdict.DISABLED_NOW, WorldgenCopy.WILLIAM to Verdict.DISABLED), reviewBoth())
    }

    @Test
    @DisplayName("deux témoins restés, la copie William ne se prépare plus : elle est désactivée quand même, et n'épargne la copie vanilla qu'une fois")
    fun `deux temoins et une copie qui ne se prepare plus`() {
        williamFabricated()
        WorldgenCopyGuard.arm(generated, WorldgenCopy.VANILLA)
        WorldgenCopyGuard.arm(generated, WorldgenCopy.WILLIAM)

        // Le mode a changé entre les deux lancements : seule la copie vanilla a une clé du jour.
        val vanillaOnly = mapOf(WorldgenCopy.VANILLA to KEY)
        assertEquals(mapOf(WorldgenCopy.VANILLA to Verdict.ENABLED, WorldgenCopy.WILLIAM to Verdict.DISABLED_NOW), WorldgenCopyGuard.review(generated, vanillaOnly))
        assertFalse(generated.resolve("wwoo.loading").exists(), "un témoin laissé là épargnerait la copie vanilla à chaque lancement")

        WorldgenCopyGuard.arm(generated, WorldgenCopy.VANILLA)
        assertEquals(mapOf(WorldgenCopy.VANILLA to Verdict.DISABLED_NOW, WorldgenCopy.WILLIAM to Verdict.DISABLED), WorldgenCopyGuard.review(generated, vanillaOnly))
        assertEquals(WILLIAM_KEY, generated.resolve("wwoo.disabled").readText(), "sans clé du jour, la désactivation reste")
    }

    @Test
    @DisplayName("un seul témoin resté, la revue des deux copies : la copie nommée est désactivée, l'autre ne l'est pas")
    fun `un seul temoin a la revue des deux copies`() {
        williamFabricated()
        // Une erreur a nommé un élément de la copie vanilla : le témoin de la copie William a été levé à l'échec.
        WorldgenCopyGuard.arm(generated, WorldgenCopy.VANILLA)

        assertEquals(mapOf(WorldgenCopy.VANILLA to Verdict.DISABLED_NOW, WorldgenCopy.WILLIAM to Verdict.ENABLED), reviewBoth())
        assertEquals(WILLIAM_KEY, generated.resolve("wwoo.key.json").readText())
    }

    @Test
    @DisplayName("en développement, la revue des deux copies fait tomber tous les témoins et toutes les désactivations")
    fun `revue des deux copies en developpement`() {
        williamFabricated()
        WorldgenCopyGuard.arm(generated, WorldgenCopy.VANILLA)
        WorldgenCopyGuard.arm(generated, WorldgenCopy.WILLIAM)
        generated.resolve("wwoo.disabled").writeText(WILLIAM_KEY)

        assertEquals(mapOf(WorldgenCopy.VANILLA to Verdict.ENABLED, WorldgenCopy.WILLIAM to Verdict.ENABLED), WorldgenCopyGuard.review(generated, emptyMap(), fresh = true))
        assertEquals(emptyList<String>(), listOf("vanilla.loading", "vanilla.disabled", "wwoo.loading", "wwoo.disabled").filter { generated.resolve(it).exists() })
    }

    @Test
    @DisplayName("des erreurs qui ne nomment que des éléments étrangers innocentent la copie")
    fun `erreurs etrangeres`() {
        val foreign = listOf(
            LoadingError("minecraft:worldgen/biome", "othermod:swamp"),
            LoadingError("minecraft:worldgen/placed_feature", "minecraft:patch_grass"),
            // Un élément du mod qui n'est pas d'une copie.
            LoadingError("minecraft:dimension_type", "travellingdimension:travel"),
        )

        assertTrue(WorldgenCopyGuard.clears(WorldgenCopy.VANILLA, foreign))
        assertTrue(WorldgenCopyGuard.clears(WorldgenCopy.WILLIAM, foreign))
    }

    @Test
    @DisplayName("une erreur sur un élément de la copie l'accuse, elle seule")
    fun `erreur de la copie`() {
        val errors = listOf(
            LoadingError("minecraft:worldgen/biome", "othermod:swamp"),
            LoadingError("minecraft:worldgen/biome", "travellingdimension:wwoo/plains"),
        )

        assertFalse(WorldgenCopyGuard.clears(WorldgenCopy.WILLIAM, errors))
        assertTrue(WorldgenCopyGuard.clears(WorldgenCopy.VANILLA, errors), "la copie vanilla n'est pas nommée")
    }

    @Test
    @DisplayName("une erreur de registre ne nomme pas le fautif, un échec sans erreur non plus : le doute joue contre la copie")
    fun `doute contre la copie`() {
        val unbound = listOf(
            LoadingError("minecraft:worldgen/biome", "othermod:swamp"),
            LoadingError("minecraft:worldgen/placed_feature", element = null),
        )

        assertFalse(WorldgenCopyGuard.clears(WorldgenCopy.VANILLA, unbound))
        assertFalse(WorldgenCopyGuard.clears(WorldgenCopy.VANILLA, emptyList()))
    }

    private fun review(key: String = KEY, fresh: Boolean = false): Verdict = WorldgenCopyGuard.review(generated, COPY, key, fresh)

    /** La revue des deux copies, toutes deux préparées à ce lancement. */
    private fun reviewBoth(): Map<WorldgenCopy, Verdict> = WorldgenCopyGuard.review(generated, mapOf(WorldgenCopy.VANILLA to KEY, WorldgenCopy.WILLIAM to WILLIAM_KEY))

    /** La copie William fabriquée et sa clé, à côté de la copie vanilla. */
    private fun williamFabricated() {
        Files.createDirectories(generated.resolve("wwoo")).resolve("pack.mcmeta").writeText("{}")
        generated.resolve("wwoo.key.json").writeText(WILLIAM_KEY)
    }
}
