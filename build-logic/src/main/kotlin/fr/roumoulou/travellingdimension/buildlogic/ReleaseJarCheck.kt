// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Ouvre le jar livrable et vérifie ce qu'il porte : le geste « ouvrir le jar avant de le téléverser » de la recette de publication, fait à
 * chaque build plutôt qu'à la main une fois par version. Aucune entrée interdite (le pack WWOO, contenu dérivé du mod de quelqu'un d'autre,
 * a failli partir dans un livrable public), exactement les jars embarqués attendus, et les entrées attendues à la racine, les licences.
 *
 * Elle lit le fichier lui-même, pas la déclaration du build : c'est ce qui part chez les joueurs qui compte.
 */
@CacheableTask
abstract class ReleaseJarCheck : DefaultTask() {

    private companion object {
        const val NESTED_JARS = "META-INF/jars/"
    }

    /** Le jar à ouvrir, celui que la publication enverrait. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** Les débuts de chemin interdits dans le jar, `resourcepacks/wwoo_worldgen/` par exemple. */
    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    /** Les jars embarqués attendus sous `META-INF/jars/`, chacun par le nom de son artefact : `tomlkt` pour `tomlkt-jvm-0.6.1.jar`. */
    @get:Input
    abstract val nestedJars: ListProperty<String>

    /** Les entrées attendues à la racine du jar : les textes de licence. */
    @get:Input
    abstract val rootEntries: ListProperty<String>

    /** Le compte rendu de la dernière ouverture : ce que porte le jar, et ce qui ne va pas. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val jarFile = jar.get().asFile
        val entries = ZipFile(jarFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val nested = entries.filter { it.startsWith(NESTED_JARS) && it.endsWith(".jar") }.map { it.removePrefix(NESTED_JARS) }
        val expected = nestedJars.get()
        val problems = mutableListOf<String>()

        forbiddenPrefixes.get().forEach { prefix ->
            val found = entries.filter { it.startsWith(prefix) }
            if (found.isNotEmpty()) problems += "${found.size} forbidden entries under $prefix, like ${found.first()}"
        }
        expected.forEach { artifact ->
            val count = nested.count { isNestedJarOf(it, artifact) }
            if (count != 1) problems += "expected one nested jar of $artifact, found $count"
        }
        nested.filter { name -> expected.none { isNestedJarOf(name, it) } }.forEach { problems += "unexpected nested jar $it" }
        rootEntries.get().filter { it !in entries }.forEach { problems += "missing entry $it" }

        val lines = buildList {
            add("${jarFile.name}: ${entries.size} entries")
            add("Nested jars: ${nested.sorted().joinToString(", ")}")
            add("Problems: ${problems.size}")
            problems.forEach { add("  $it") }
        }
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(lines.joinToString("\n", postfix = "\n"))

        if (problems.isNotEmpty()) {
            throw GradleException("The release jar ${jarFile.name} is not fit for publication, see $reportFile:\n" + problems.joinToString("\n") { "  $it" })
        }
        logger.info("{}: {} entries, nested jars {}, fit for publication", jarFile.name, entries.size, nested.sorted())
    }

    /* Loom garde le nom de l'artefact en tête de celui du jar embarqué, la version et la plateforme après lui : `json5-jvm.jar`, `storify-0.4.0-SNAPSHOT.jar`. */
    private fun isNestedJarOf(jarName: String, artifact: String): Boolean = jarName == "$artifact.jar" || jarName.startsWith("$artifact-")
}
