/*
════════════════════════════════════════════════════════════════════════════════
 PLUGIN DE CONVENTION : travellingdimension.loom-module
════════════════════════════════════════════════════════════════════════════════

Ce que partagent TOUS les modules du mod, common comme les modules de version :

  1  l'identité Maven, lue dans le gradle.properties de la racine
  2  les dépôts de dépendances, le seul endroit où ils se déclarent
  3  Java 25 et la séparation des source sets main et client
  4  l'encodage UTF-8 et la cible Java des deux compilateurs

Plugins, sans version : build-logic les fixe, depuis les catalogues.
  - fabric-loom            outillage Fabric : le jeu sur le classpath, les runs,
                           les mixins
  - kotlin-jvm             le langage
  - kotlin-serialization   la configuration en JSON. Appliqué ici et non dans
                           common, le seul module qui en sérialise aujourd'hui :
                           un module ne peut appliquer sans version qu'un plugin
                           que build-logic déclare, et celui-là n'en est qu'une
                           dépendance. Inerte là où rien n'est @Serializable.

Chaque module garde ses dépendances, ses tests et ce qu'il livre.
════════════════════════════════════════════════════════════════════════════════
*/

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("net.fabricmc.fabric-loom")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/* Les catalogues se lisent par leur nom : un script précompilé n'a pas d'accesseurs typés pour eux. */
val libs = the<VersionCatalogsExtension>().named("libs")

/*
────────────────────────────────────────────────────────────────────────────────
 1. L'IDENTITÉ
────────────────────────────────────────────────────────────────────────────────

Lue dans gradle.properties, que les modules héritent de la racine : chaque valeur
se monte à UN seul endroit.

  - group          groupe Maven, clé maven_group ("fr.roumoulou")
  - archivesName   nom de base des jars, clé archives_base_name
                   ("travellingdimension")
  - version        version du mod, clé mod_version. Un module de version la
                   complète de la sienne (2.8.0+26.2).
*/
group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()
base { archivesName = providers.gradleProperty("archives_base_name") }

val modId = providers.gradleProperty("mod_id").get()

/*
────────────────────────────────────────────────────────────────────────────────
 2. LES DÉPÔTS DE DÉPENDANCES
────────────────────────────────────────────────────────────────────────────────

LE SEUL ENDROIT OÙ DÉCLARER UN DÉPÔT DE DÉPENDANCES, pour tous les modules.

On n'utilise PAS settings.gradle.kts pour déclarer les dépôts : Loom pose les siens
au projet, et le mode par défaut de Gradle (`PREFER_PROJECT`) ignore alors ceux de
settings, entièrement et sans rien journaliser.

── CE QUE LOOM POSE TOUT SEUL, DONC PAS REDÉCLARÉ ICI ───────────────────────────
En s'appliquant, Loom ajoute ses dépôts au module : ses trois caches locaux,
Fabric (maven.fabricmc.net), Mojang et mavenCentral. Kotlin, JUnit,
kotlinx-serialization, le chargeur, Fabric API, fabric-language-kotlin et
fabric-loader-junit se résolvent donc sans une ligne ici. La liste effective se
mesure avec `gradlew :<module>:listRepositories -q`, tâche déclarée sous le bloc.
Si une résolution casse un jour après une montée de Loom, c'est ICI que le dépôt
disparu se redéclarera.

  Shedaniel (Cloth Config)     les widgets de l'écran de configuration en jeu
                               (facultatif). Filtré : me.shedaniel.cloth.
  TerraformersMC (Mod Menu)    l'autre moitié de l'écran de configuration
                               (facultatif aussi). Filtré : com.terraformersmc.
  Repsy (Storify)              Storify, la bibliothèque des fichiers JSON du mod,
                               publiée par l'auteur. Filtré : fr.moulou.

── L'ORDRE COMPTE, LE FILTRE AUSSI ──────────────────────────────────────────────
Gradle interroge les dépôts DANS L'ORDRE, en deux passes : d'abord les caches
locaux de tous, puis le réseau de ceux qui n'ont rien rendu. Un raté coûte donc un
aller-retour par dépôt traversé avant le bon. `content { includeGroup(...) }`
déclare « ce dépôt ne sert QUE ce groupe » : hors du groupe, Gradle le saute
entièrement, ni cache ni réseau. Sans le filtre, Shedaniel et TerraformersMC
seraient interrogés pour CHAQUE artefact introuvable ailleurs, Kotlin et JUnit
compris.

── QUAND CARPET ARRIVERA ────────────────────────────────────────────────────────
Modrinth et CurseMaven se déclareront ICI, et nulle part ailleurs. Voir la
stratégie Carpet de `01-docs/technical-docs/02-finalized/strategie-de-test.md`.
*/
repositories {
    maven("https://maven.shedaniel.me/") {
        name = "Shedaniel (Cloth Config)"
        content { includeGroup("me.shedaniel.cloth") }
    }
    maven("https://maven.terraformersmc.com/releases/") {
        name = "TerraformersMC (Mod Menu)"
        content { includeGroup("com.terraformersmc") }
    }
    maven("https://repo.repsy.io/roumoulou/maven") {
        name = "Repsy (Storify)"
        content { includeGroup("fr.moulou") }
    }
}

/* La liste effective, celle que Gradle utilise vraiment, Loom compris : gradlew :<module>:listRepositories -q */
tasks.register("listRepositories") {
    group = "$modId-dev"
    description = "Liste les dépôts de dépendances effectifs du module, les déclarés par la convention comme ceux que Loom pose"
    // Lue en fin de configuration, quand Loom a posé tous les siens ; la tâche n'emporte que les lignes, pas le projet.
    val lines = provider { repositories.map { repo -> "${repo.name.padEnd(32)} ${(repo as? MavenArtifactRepository)?.url ?: ""}" } }
    doLast { lines.get().forEach { println(it) } }
}

/*
────────────────────────────────────────────────────────────────────────────────
 3. JAVA ET LES SOURCE SETS
────────────────────────────────────────────────────────────────────────────────

  - toolchain            force Gradle à compiler avec un JDK précis, celui de la
                         clé `java` du catalogue libs (25). Utilisée aussi par la
                         section 4, et injectée dans fabric.mod.json par les
                         modules de version.
  - withSourcesJar()     un jar de sources à côté du jar, pour les IDE et la
                         publication Maven.

── splitEnvironmentSourceSets() ─────────────────────────────────────────────────
Crée un source set `client` séparé de `main`. Le code client est ainsi isolé du
code serveur, ce qui évite les ClassNotFoundException quand le jar tourne sur un
serveur dédié. Conséquence à connaître : `minecraft-clientOnly` est rangé du côté
client, et c'est pour cela que les tests du jeu (étage 1) reçoivent le classpath
des DEUX source sets. Le source set `client` n'existe qu'après cet appel : un
module ne peut parler de `clientCompileOnly` qu'après l'application du plugin.
*/
val targetJavaVersion = libs.findVersion("java").get().requiredVersion.toInt()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()
}

/*
────────────────────────────────────────────────────────────────────────────────
 4. LES COMPILATEURS
────────────────────────────────────────────────────────────────────────────────

L'encodage UTF-8 est forcé côté Java ; le filtrage des ressources le force de son
côté, dans les modules de version. Sans lui, chacun suit l'encodage du système, et
les accents de ce projet sortent en charabia sur une machine autrement réglée.
Kotlin vise la même version de Java que javac.

── LE NOM DE MODULE KOTLIN ──────────────────────────────────────────────────────
Chaque compilation Kotlin écrit un META-INF/<nom>.kotlin_module, et tire ce nom du
groupe et du nom des archives, les mêmes dans tous les modules. Le jar d'une
version, qui réunit common et son module, en recevrait deux du même nom. Mesuré :
« Entry META-INF/fr.roumoulou_travellingdimension.kotlin_module is a duplicate ».
Chaque module et chaque source set prend donc le sien :
travellingdimension-common, travellingdimension-common-client,
travellingdimension-mc-26.2...
*/
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = targetJavaVersion
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.fromTarget(targetJavaVersion.toString())
}

val kotlinModulePrefix = "${providers.gradleProperty("archives_base_name").get()}-$name"

kotlin {
    target.compilations.configureEach {
        val kotlinModuleName = if (name == "main") kotlinModulePrefix else "$kotlinModulePrefix-$name"
        compileTaskProvider.configure { (this as KotlinJvmCompile).compilerOptions.moduleName = kotlinModuleName }
    }
}
