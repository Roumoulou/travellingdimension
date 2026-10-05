// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenPacks
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.world.level.validation.DirectoryValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Les copies préparées et la source de datapacks du mod, sur un vrai dépôt de datapacks du jeu.
 *
 * À l'étage 1 parce que la source bâtit des `Pack` du jeu, et que le dépôt lit leur
 * `pack.mcmeta` : chaque version servie y prouve qu'elle lit celui d'une copie. Le mixin qui
 * ajoute la source à un dépôt ne s'applique pas ici : ce qu'il appelle,
 * [WorldgenPacks.withPreparedCopies], s'éprouve directement, et le mixin à l'étage 2.
 */
class WorldgenPacksTest {

    private companion object {
        const val VANILLA_PACK = "travellingdimension/vanilla"

        /** Un `pack.mcmeta` de datapack que les trois versions servies lisent : leurs formats vont de 101 à 121. */
        const val PACK_MCMETA = """{ "pack": { "description": "A copy made by Travelling Dimension", "min_format": 101, "max_format": 121 } }"""

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @TempDir
    lateinit var generated: Path

    /** Les copies préparées sont un état du processus : chaque test le rend vide. */
    @AfterEach
    fun forget() = WorldgenPacks.prepare(emptySet(), generated)

    @Test
    @DisplayName("une copie se prépare quand le mode la demande et que son dossier porte un pack.mcmeta")
    fun `copie preparee`() {
        writeCopy(WorldgenCopy.VANILLA, PACK_MCMETA)
        Files.createDirectories(generated.resolve(WorldgenCopy.WILLIAM.folder))

        // La copie William est demandée, mais son dossier est vide : elle n'est pas préparée.
        WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA, WorldgenCopy.WILLIAM), generated)
        assertEquals(mapOf(WorldgenCopy.VANILLA to generated.resolve("vanilla")), WorldgenPacks.prepared)

        // La copie vanilla est là, mais le mode ne la demande pas.
        WorldgenPacks.prepare(emptySet(), generated)
        assertEquals(emptyMap<WorldgenCopy, Path>(), WorldgenPacks.prepared)
    }

    @Test
    @DisplayName("sans copie préparée, les sources d'un dépôt restent telles quelles")
    fun `rien a ajouter`() {
        val sources = arrayOf<Any>(vanillaSource())

        assertSame(sources, WorldgenPacks.withPreparedCopies(sources))
    }

    @Test
    @DisplayName("une copie préparée ajoute la source du mod au dépôt qui porte la source vanilla, pas aux autres")
    fun `source ajoutee`() {
        writeCopy(WorldgenCopy.VANILLA, PACK_MCMETA)
        WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA), generated)

        assertEquals(2, WorldgenPacks.withPreparedCopies(arrayOf<Any>(vanillaSource())).size)

        // Un dépôt sans la source vanilla du jeu, comme celui des resourcepacks du client.
        val others = arrayOf<Any>(RepositorySource { })
        assertSame(others, WorldgenPacks.withPreparedCopies(others))
    }

    @Test
    @DisplayName("la copie est un datapack requis : le dépôt la garde sélectionnée sans qu'on la lui demande")
    fun `datapack requis`() {
        writeCopy(WorldgenCopy.VANILLA, PACK_MCMETA)
        WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA), generated)

        val repository = repositoryOf(WorldgenPacks.withPreparedCopies(arrayOf<Any>(vanillaSource())))
        repository.reload()
        repository.setSelected(listOf("vanilla"))

        assertTrue(repository.getPack(VANILLA_PACK)?.isRequired == true, "la copie vanilla est dans le dépôt, et requise")
        assertEquals(listOf(VANILLA_PACK), WorldgenPacks.selectedIn(repository))
    }

    @Test
    @DisplayName("un pack.mcmeta illisible : la copie n'est pas offerte au dépôt")
    fun `pack mcmeta illisible`() {
        writeCopy(WorldgenCopy.VANILLA, "{}")
        WorldgenPacks.prepare(setOf(WorldgenCopy.VANILLA), generated)

        val repository = repositoryOf(WorldgenPacks.withPreparedCopies(arrayOf<Any>(vanillaSource())))
        repository.reload()

        assertFalse(repository.isAvailable(VANILLA_PACK))
    }

    private fun writeCopy(copy: WorldgenCopy, packMcmeta: String) {
        val directory = Files.createDirectories(generated.resolve(copy.folder))
        directory.resolve("pack.mcmeta").writeText(packMcmeta)
    }

    /** La source vanilla du jeu, celle que porte tout dépôt de datapacks. */
    private fun vanillaSource(): RepositorySource = ServerPacksSource(DirectoryValidator { true })

    private fun repositoryOf(sources: Array<Any>): PackRepository = PackRepository(*sources.map { it as RepositorySource }.toTypedArray())
}
