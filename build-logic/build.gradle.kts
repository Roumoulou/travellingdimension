/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  BUILD-LOGIC — les plugins de convention du mod
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Deux plugins, écrits en scripts Kotlin précompilés dans src/main/kotlin, et que
 *  chacun ouvre par un en-tête qui dit ce qu'il pose :
 *
 *    - travellingdimension.loom-module    ce que partagent tous les modules du mod :
 *                                         Loom, Kotlin, Java, les dépôts, l'identité
 *    - travellingdimension.game-version   un module de version : le jar livrable de
 *                                         sa version, et les tests du jeu rejoués
 *                                         contre elle
 *
 *  Le code Kotlin ordinaire qu'ils emploient (les tâches qui vérifient la
 *  compatibilité de common et le jar livrable) vit sous le package
 *  fr.roumoulou.travellingdimension.buildlogic, et jamais sous un package `build` :
 *  son dossier tomberait sous le motif `build/` du .gitignore, et Git ne le suivrait
 *  pas. Mesuré, une fois.
 *
 *  Les plugins qu'ils appliquent sont des dépendances de ce build, aux versions des
 *  catalogues : c'est ici que se fixent celles de Loom, de Kotlin et de
 *  mod-publish-plugin, et les modules les appliquent sans version. Outfitter n'en
 *  fait pas partie : chaque module de version l'applique lui-même, parce que ses
 *  environnements tiennent à sa version.
 *
 *  Plugins :
 *    - kotlin-dsl    compile les scripts précompilés, avec le Kotlin que Gradle
 *                    embarque ; sans version, exprès, comme dans Outfitter.
 * ════════════════════════════════════════════════════════════════════════════════
 */

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(pluginArtifact(mc.plugins.fabric.loom))
    implementation(pluginArtifact(libs.plugins.kotlin.jvm))
    implementation(pluginArtifact(libs.plugins.kotlin.serialization))
    implementation(pluginArtifact(libs.plugins.mod.publish))
}

/* Un plugin du catalogue, en coordonnées de dépendance : son marqueur, qui pointe vers l'artefact du plugin. */
fun pluginArtifact(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
