// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import fr.roumoulou.travellingdimension.config.WorldgenMode
import fr.roumoulou.travellingdimension.dimension.WilliamJarRefusal
import fr.roumoulou.travellingdimension.dimension.WorldgenCopy
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyGuard.Verdict
import fr.roumoulou.travellingdimension.dimension.WorldgenCopyReport
import fr.roumoulou.travellingdimension.dimension.WorldgenDetection
import fr.roumoulou.travellingdimension.dimension.WorldgenNotice
import fr.roumoulou.travellingdimension.dimension.WorldgenReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La ligne « Travel dimension active », celles du dossier `worldgen/`, celle d'une copie fabriquée
 * et celle des datapacks du mod, ce que le log dit du terrain de VOYAGE.
 *
 * Elles se composent sans type du jeu : le générateur, ses biomes et le dépôt de datapacks se
 * lisent à l'étage 2, le moteur des copies à l'étage 1, les lignes s'éprouvent ici.
 */
class WorldgenReportTest {

    private companion object {
        const val LARGE_BIOMES = "noise settings 'minecraft:large_biomes'"

        val NOTHING = WorldgenDetection(
            terralithLoaded = false,
            tectonicLoaded = false,
            wwooInstalled = false,
            vanillaCopyLoaded = false,
            williamCopyLoaded = false,
            disabledCopies = emptyMap(),
            williamRefusals = emptyList(),
            customNoiseSettingsKnown = false,
            customBiomePresetKnown = false,
        )
    }

    @Test
    @DisplayName("la ligne nomme Terralith et WWOO quand ils sont détectés, et seulement alors")
    fun `ligne Travel dimension active`() {
        assertEquals(
            "Travel dimension active: mode terralith, noise settings 'minecraft:large_biomes', 146 biome(s) (minecraft=56, terralith=90), detected: Terralith",
            WorldgenReport.activeLine(WorldgenMode.TERRALITH, LARGE_BIOMES, sortedMapOf("minecraft" to 56, "terralith" to 90), NOTHING.copy(terralithLoaded = true)),
        )

        // Rien de détecté : la ligne ne nomme personne. Un datapack Terralith sans le mod est dans ce cas.
        assertEquals(
            "Travel dimension active: mode terralith, noise settings 'minecraft:large_biomes', 56 biome(s) (minecraft=56)",
            WorldgenReport.activeLine(WorldgenMode.TERRALITH, LARGE_BIOMES, sortedMapOf("minecraft" to 56), NOTHING),
        )

        // Tectonic et les copies se détectent aussi, la ligne ne nomme que Terralith et WWOO.
        val everything = NOTHING.copy(terralithLoaded = true, tectonicLoaded = true, wwooInstalled = true, vanillaCopyLoaded = true, williamCopyLoaded = true)
        assertEquals(
            "Travel dimension active: mode william, noise settings 'travellingdimension:vanilla/large_biomes', 56 biome(s) (minecraft=56), detected: Terralith, WWOO",
            WorldgenReport.activeLine(WorldgenMode.WILLIAM, "noise settings 'travellingdimension:vanilla/large_biomes'", sortedMapOf("minecraft" to 56), everything),
        )
    }

    @Test
    @DisplayName("la ligne d'une copie fabriquée compte ce qui est écrit et élagué, registre par registre")
    fun `ligne d'une copie`() {
        val report = WorldgenCopyReport(
            registries = sortedMapOf("worldgen/placed_feature" to WorldgenCopyReport.Count(written = 271, pruned = 2), "worldgen/biome" to WorldgenCopyReport.Count(written = 56, pruned = 0)),
            biomeTags = 67,
            refusals = mapOf("worldgen/placed_feature minecraft:a" to "refused", "worldgen/placed_feature minecraft:b" to "references the pruned worldgen/feature minecraft:c"),
        )

        assertEquals(327, report.written)
        assertEquals(2, report.pruned)
        assertEquals(
            "Worldgen copy 'vanilla' made in 840 ms: 327 file(s) written, 2 pruned (written/pruned by registry: worldgen/biome 56/0, worldgen/placed_feature 271/2), 67 biome tag file(s)",
            WorldgenReport.copyLine(WorldgenCopy.VANILLA, report, millis = 840),
        )

        // La copie William compte en plus ce que sa source porte en propre : ses tags et ses gabarits NBT.
        assertEquals(
            "Worldgen copy 'wwoo' made in 730 ms: 327 file(s) written, 2 pruned (written/pruned by registry: worldgen/biome 56/0, worldgen/placed_feature 271/2), 67 biome tag file(s), 14 tag file(s) and 265 structure template(s) of the source",
            WorldgenReport.copyLine(WorldgenCopy.WILLIAM, report.copy(tags = 14, templates = 265), millis = 730),
        )
        assertEquals(
            "Worldgen: the William Wythers copy could not be made, and will not be loaded: java.io.IOException: zip END header not found",
            WorldgenReport.failedCopyLine(WorldgenCopy.WILLIAM, "java.io.IOException: zip END header not found"),
        )
    }

    @Test
    @DisplayName("les lignes du dossier worldgen disent ce que le mod fait de chaque fichier : ignoré, refusé ou pris")
    fun `lignes du dossier`() {
        assertEquals(
            "Worldgen folder: 'notes.txt' is not a WWOO jar (not a readable archive): ignored",
            WorldgenReport.ignoredFileLine("notes.txt", "not a readable archive"),
        )
        assertEquals(
            "Worldgen folder: the WWOO jar 'wwoo-fabric-26.2-2.7.1.jar' is made for Minecraft ~26.2, not 26.3: refused",
            WorldgenReport.refusedJarLine(WilliamJarRefusal.WrongVersion("wwoo-fabric-26.2-2.7.1.jar", "~26.2", "26.3")),
        )
        assertEquals(
            "Worldgen folder: the WWOO jar 'wwoo.jar' cannot be read (no version in its fabric.mod.json): refused",
            WorldgenReport.refusedJarLine(WilliamJarRefusal.Unreadable("wwoo.jar", "no version in its fabric.mod.json")),
        )
        assertEquals(
            "Worldgen folder: the WWOO jar 'william.jar' (WWOO 3.0.1) is taken for the William Wythers copy",
            WorldgenReport.takenJarLine("william.jar", "3.0.1", accepted = 1),
        )
        assertEquals(
            "Worldgen folder: the WWOO jar 'b.jar' (WWOO 3.0.1) is taken for the William Wythers copy, the highest version of 2 accepted jars",
            WorldgenReport.takenJarLine("b.jar", "3.0.1", accepted = 2),
        )

        // Les deux messages du repli que le dossier fait naître, au démarrage du serveur.
        assertEquals(
            "Worldgen: worldgen=william but the WWOO jar 'wwoo-fabric-26.2-2.7.1.jar' is made for Minecraft ~26.2, not 26.3: falling back to the vanilla copy",
            WorldgenReport.warning(WorldgenNotice.WilliamWrongVersion("wwoo-fabric-26.2-2.7.1.jar", "~26.2", "26.3")),
        )
        assertEquals(
            "Worldgen: worldgen=william but the WWOO jar 'wwoo.jar' could not be read: falling back to the vanilla copy",
            WorldgenReport.warning(WorldgenNotice.WilliamUnreadable("wwoo.jar")),
        )
    }

    @Test
    @DisplayName("la ligne des datapacks dit ceux que le serveur a sélectionnés, et nomme une copie préparée qui manque")
    fun `ligne des datapacks`() {
        val vanilla = "travellingdimension/vanilla"
        val william = "travellingdimension/wwoo"

        assertEquals(
            "Worldgen datapacks selected by the server: travellingdimension/vanilla, travellingdimension/wwoo",
            WorldgenReport.datapacksLine(prepared = listOf(vanilla, william), selected = listOf(vanilla, william)),
        )

        // Une copie préparée que le serveur n'a pas : son dépôt de datapacks n'a pas reçu la source du mod.
        assertEquals(
            "Worldgen datapacks selected by the server: travellingdimension/vanilla (prepared but not selected: travellingdimension/wwoo)",
            WorldgenReport.datapacksLine(prepared = listOf(vanilla, william), selected = listOf(vanilla)),
        )
        assertEquals(
            "Worldgen datapacks selected by the server: none (prepared but not selected: travellingdimension/vanilla)",
            WorldgenReport.datapacksLine(prepared = listOf(vanilla), selected = emptyList()),
        )
    }

    @Test
    @DisplayName("les lignes du garde-fou nomment la copie et le fichier à supprimer, et se taisent quand rien ne la retient")
    fun `lignes du garde-fou`() {
        val file = "game/travellingdimension/generated/vanilla.disabled"

        assertEquals(null, WorldgenReport.guardLine(WorldgenCopy.VANILLA, Verdict.ENABLED, file))
        assertEquals(
            "Worldgen: a loading of the registries with the vanilla copy did not succeed at the previous launch: the copy is disabled. Delete 'game/travellingdimension/generated/vanilla.disabled' and restart to try again",
            WorldgenReport.guardLine(WorldgenCopy.VANILLA, Verdict.DISABLED_NOW, file),
        )
        assertEquals(
            "Worldgen: the vanilla copy stays disabled. Delete 'game/travellingdimension/generated/vanilla.disabled' and restart to try again",
            WorldgenReport.guardLine(WorldgenCopy.VANILLA, Verdict.DISABLED, file),
        )
        assertEquals(
            "Worldgen: the William Wythers copy was disabled under another key: it is made again and tried again",
            WorldgenReport.guardLine(WorldgenCopy.WILLIAM, Verdict.RETRIED, "game/travellingdimension/generated/wwoo.disabled"),
        )

        // Le message du repli, au démarrage du serveur.
        assertEquals(
            "Worldgen: the vanilla copy made a loading of the registries fail and is disabled: delete 'game/travellingdimension/generated/vanilla.disabled' and restart to try again",
            WorldgenReport.warning(WorldgenNotice.CopyDisabled("vanilla", file)),
        )
    }

    @Test
    @DisplayName("la ligne d'un chargement en échec dit si la copie est innocentée ou si son témoin reste")
    fun `ligne d'un chargement en echec`() {
        assertEquals(
            "Worldgen: the loading of the registries failed on elements that are not of the vanilla copy: the copy stays enabled",
            WorldgenReport.failedLoadingLine(WorldgenCopy.VANILLA, cleared = true),
        )
        assertEquals(
            "Worldgen: a loading of the registries with the vanilla copy failed: the copy is disabled at the next launch, unless a loading succeeds before",
            WorldgenReport.failedLoadingLine(WorldgenCopy.VANILLA, cleared = false),
        )
    }
}
