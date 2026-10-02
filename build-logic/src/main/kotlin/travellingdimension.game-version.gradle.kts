/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  PLUGIN DE CONVENTION — travellingdimension.game-version
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Un module de version, mc-<version du jeu> : le mod livrable pour CETTE version.
 *  Il réunit le code de common, compilé une fois contre la dernière release servie,
 *  et le sien, compilé contre son jeu. Il en fait un seul mod, un jar et des runs,
 *  et rejoue contre son jeu les tests de common qui demandent le jeu.
 *
 *  ┌───────────────────────────────────────────────────────────────────────────┐
 *  │  1  la version servie : la lignée, la version compilée, deux garde-fous   │
 *  │  2  les dépendances communes à toutes les versions                        │
 *  │  3  le mod en développement : common et le module, un seul mod            │
 *  │  4  les ressources : celles de common, fabric.mod.json rempli             │
 *  │  5  le jar livrable, ouvert à chaque build                                │
 *  │  6  étage 1 des tests : le jeu amorcé                                     │
 *  │  7  étage 2 des tests : le serveur GameTest                               │
 *  │  8  la vérification de compatibilité de common avec ce jeu                │
 *  │  9  la publication Maven                                                  │
 *  │  10 la publication sur Modrinth et CurseForge                             │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  Le module, lui, n'écrit que ce qui tient à sa version : les dépendances de son
 *  catalogue (le jeu, la Fabric API, son module GameTest) et sa déclaration à
 *  Outfitter, ses environnements et son serveur GameTest.
 *
 *  Les tâches que ce plugin ajoute se rangent avec celles de Gradle qu'elles
 *  accompagnent : testMC, checkCommonCompatibility et checkReleaseJar au groupe
 *  verification, branchées sur check ; checkPublication au groupe publishing, avec
 *  publishMods, publishModrinth et publishCurseforge de mod-publish-plugin.
 *  runGameTest est au groupe fabric de Loom. listRepositories, de la convention
 *  loom-module, porte le groupe travellingdimension-dev, celui des tâches de
 *  diagnostic du projet. Ce qu'Outfitter ajoute (préparer les environnements,
 *  remettre à neuf le serveur GameTest, déployer le jar) est au groupe outfitter.
 *
 *  Plugins :
 *    - travellingdimension.loom-module   Loom, Kotlin et sa sérialisation, Java,
 *                                        dépôts, identité
 *    - maven-publish                     publication locale de l'artefact
 *    - mod-publish-plugin                la publication sur Modrinth et CurseForge
 * ════════════════════════════════════════════════════════════════════════════════
 */

import fr.roumoulou.travellingdimension.buildlogic.CommonCompatibilityCheck
import fr.roumoulou.travellingdimension.buildlogic.ReleaseJarCheck
import me.modmuss50.mpp.PublishModTask

plugins {
    id("travellingdimension.loom-module")
    `maven-publish`
    id("me.modmuss50.mod-publish-plugin")
}

/* Les catalogues se lisent par leur nom : un script précompilé n'a pas d'accesseurs typés pour eux. */
val libs = the<VersionCatalogsExtension>().named("libs")
val mc = the<VersionCatalogsExtension>().named("mc")

val modId = providers.gradleProperty("mod_id").get()

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 1 — LA VERSION SERVIE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Deux versions du jeu, chacune à sa place :
 *
 *  ┌─ nom du module ─ la LIGNÉE ───────────────────────────────────────────────┐
 *  │  mc-26.1 sert la lignée 26.1 (26.1, 26.1.1, 26.1.2), mc-26.2 la lignée    │
 *  │  26.2. Elle se lit avant tout le reste.                                   │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ┌─ dépendance minecraft ─ la version COMPILÉE ──────────────────────────────┐
 *  │  La dernière release de sa lignée : 26.1.2 pour mc-26.1, 26.2 pour        │
 *  │  mc-26.2. Le jar en tire sa version (2.8.0+26.1.2) et son nom,            │
 *  │  fabric.mod.json sa contrainte ("minecraft": "~26.1.2" : 26.1.2 et ses    │
 *  │  correctifs, jamais 26.2). Elle se lit au moment où le module déclare     │
 *  │  cette dépendance, et la version du projet avec.                          │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  La Fabric API que le module déclare devient le plancher de fabric.mod.json
 *  ("fabric-api": ">=0.155.2") : le jar exige la Fabric API contre laquelle il a été
 *  compilé et testé, comme il exige déjà son chargeur. La lignée 26.1 le montre :
 *  son BlockTintsFactory, dont le mod se sert, n'est arrivé qu'en cours de lignée.
 *
 *  Deux garde-fous tiennent ce contrat, au moment même où le module déclare ses
 *  dépendances : le Minecraft déclaré doit appartenir à la lignée du nom, et la
 *  Fabric API doit avoir été publiée pour elle (sa version finit par +<lignée>,
 *  correctif compris). Ils arrêtent le module copié d'un autre dont le catalogue
 *  n'a pas suivi.
 *
 *  Dans les deux blocs `dependencies.configureEach`, `group`, `name` et `version`
 *  sont ceux de la dépendance : le nom du module a été mis de côté avant, et la
 *  version du projet s'y écrit `project.version`.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val moduleName: String = name
val releaseLine: String = moduleName.removePrefix("mc-")
require(moduleName.startsWith("mc-") && Regex("""\d+\.\d+""").matches(releaseLine)) {
    "A game version module is named mc-<release line>, like mc-26.2, not '$moduleName'"
}

val modVersion: String = version.toString()
val minecraftVersion: Property<String> = objects.property<String>()
val fabricApiVersion: Property<String> = objects.property<String>()

fun belongsToReleaseLine(gameVersion: String): Boolean = gameVersion == releaseLine || gameVersion.startsWith("$releaseLine.")

configurations.named("minecraft") {
    dependencies.configureEach {
        val declared = version.orEmpty()
        if (!belongsToReleaseLine(declared)) {
            throw GradleException("Module $moduleName serves the $releaseLine release line but declares minecraft $declared: it must read the catalog of $releaseLine")
        }
        minecraftVersion.set(declared)
        project.version = "$modVersion+$declared"
    }
}

configurations.named("implementation") {
    dependencies.configureEach {
        if (group == "net.fabricmc.fabric-api" && name == "fabric-api") {
            val declared = version.orEmpty()
            if (!belongsToReleaseLine(declared.substringAfter('+', ""))) {
                throw GradleException("Module $moduleName serves the $releaseLine release line but declares $group:$name:$declared, built for another version")
            }
            fabricApiVersion.set(declared)
        }
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 2 — LES DÉPENDANCES COMMUNES À TOUTES LES VERSIONS
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  ── LE CODE DE COMMON : SES CLASSES, PAS SES DÉPENDANCES ────────────────────
 *  common publie ses classes compilées (main et client) dans sa configuration
 *  `commonClasses`, et rien d'autre : sa Fabric API et son Minecraft sont ceux de la
 *  dernière release, et n'ont rien à faire sur le classpath d'une autre version.
 *  Chaque module de version déclare les siens, à ses versions.
 *
 *  Ces classes entrent par `commonCode`, une configuration à part que les classpath
 *  de compilation et d'exécution étendent : elles servent à compiler, à lancer et à
 *  tester, mais elles n'apparaissent pas comme une dépendance dans le POM publié,
 *  puisque le jar les embarque (section 5).
 *
 *  ── LE RESTE NE DÉPEND PAS DE LA VERSION DU JEU ─────────────────────────────
 *  - fabric-loader              : le chargeur, le même pour toutes les versions
 *                                 servies (catalogue mc).
 *  - fabric-language-kotlin     : l'adaptateur Kotlin de Fabric, le même aussi.
 *  - kotlinx-serialization-json : fourni au runtime par fabric-language-kotlin,
 *                                 déclaré pour compiler avec une version contrôlée.
 *  - storify                    : les fichiers JSON du mod (config.json et
 *                                 dev.json), EMBARQUÉE dans le jar par l'include de
 *                                 Loom (jar-in-jar), avec tomlkt et json5 que
 *                                 fabric-language-kotlin ne fournit pas ; include
 *                                 n'étant pas transitif, chaque jar se nomme. Sous
 *                                 LGPL-3.0 : embarquée telle quelle, sa licence
 *                                 voyage dans son propre jar.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val commonCode = configurations.dependencyScope("commonCode")

listOf("compileClasspath", "runtimeClasspath", "clientCompileClasspath", "clientRuntimeClasspath").forEach { classpath ->
    configurations.named(classpath) { extendsFrom(commonCode.get()) }
}

dependencies {
    "commonCode"(project(path = ":common", configuration = "commonClasses"))

    implementation(mc.findLibrary("fabric-loader").get())
    implementation(mc.findLibrary("fabric-language-kotlin").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())

    implementation(libs.findLibrary("storify").get())
    "include"(libs.findLibrary("storify").get())
    "include"(libs.findLibrary("tomlkt").get())
    "include"(libs.findLibrary("json5").get())
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 3 — LE MOD EN DÉVELOPPEMENT
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  `mods { }` regroupe sous le MÊME identifiant de mod les source sets du module et
 *  ceux de common, main et client des deux côtés. Sans ce bloc, Loom les traiterait
 *  comme des mods distincts, ou laisserait les classes de common hors du mod, et
 *  fabriquerait de faux conflits de chargement de classes en développement.
 *
 *  Les runs (client, server et leurs variantes moddées) viennent des environnements
 *  Outfitter que chaque module déclare.
 *
 *  ── LES CONFIGURATIONS DE LANCEMENT D'INTELLIJ ──────────────────────────────
 *  Loom ne les génère d'office que pour la racine d'un build. Chaque module de
 *  version les demande donc, et chacune porte le chemin de son module dans son nom :
 *  les runs de deux versions ne se confondent pas. common n'en a pas, ses runs
 *  n'ayant pas de mod à lancer.
 * ════════════════════════════════════════════════════════════════════════════════
 */
loom {
    mods {
        register(modId) {
            sourceSet("main")
            sourceSet("client")
            sourceSet("main", ":common")
            sourceSet("client", ":common")
        }
    }
    runs.configureEach {
        generateRunConfig = true
        appendProjectPathToDisplayName = true
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 4 — LES RESSOURCES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Les ressources du mod vivent dans common/src/main/resources, et chaque module de
 *  version les traite lui-même, avec les siennes : le mod trouve alors tout dans un
 *  seul dossier (fabric.mod.json, assets, data, configurations de mixins), en
 *  développement comme dans le jar. Le dossier de common est une ENTRÉE de la tâche,
 *  pas un dossier de ressources du source set : IntelliJ ne le voit ainsi que dans
 *  common, et deux modules ne se disputent pas la même racine.
 *
 *  fabric.mod.json y est un gabarit : `processResources` le remplit avec l'identité
 *  du mod et les versions de CETTE version du jeu. Une ressource présente des deux
 *  côtés fait échouer la tâche : un module ne remplace pas en silence un fichier de
 *  common.
 *
 *  ── L'AVERTISSEMENT IDEA « MatchingCopyAction », PERMANENT ET BÉNIN ─────────
 *  À chaque sync Gradle, IntelliJ affiche « Cannot resolve resource filtering of
 *  MatchingCopyAction » sur ce projet. La cause est le `filesMatching(...) {
 *  expand(...) }` ci-dessous : l'IDE ne sait pas reproduire ce filtrage dans son
 *  compilateur interne, et le dit. Sans aucune conséquence tant que le build est
 *  délégué à Gradle (le défaut), et commun à tous les projets Fabric, dont le
 *  gabarit officiel. Enquêté et clos le 2026-09-12 : ne pas rouvrir.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val commonResources = layout.settingsDirectory.dir("common/src/main/resources")

tasks.processResources {
    /*
    La version compilée et la Fabric API ne se connaissent qu'une fois le module
    évalué (section 1) : la carte porte leurs providers, résolus à l'écriture du
    fichier. La Fabric API y perd son suffixe de build (+26.1.2) : le plancher se
    compare sur 0.155.2.
    */
    val resourceTargets: Map<String, Any> = mapOf(
        "mod_id" to modId,
        "version" to minecraftVersion.map { "$modVersion+$it" },
        "minecraft_version" to minecraftVersion,
        "fabric_api_version" to fabricApiVersion.map { it.substringBefore('+') },
        "fabric_loader_version" to mc.findVersion("fabric-loader").get().requiredVersion,
        "fabric_language_kotlin_version" to mc.findVersion("fabric-language-kotlin").get().requiredVersion,
        "java_version" to libs.findVersion("java").get().requiredVersion
    )

    from(commonResources)
    filteringCharset = "UTF-8"

    /*
    Les sauvegardes ponctuelles `.avant-<motif>` vivent à côté du fichier qu'elles
    doublent (convention de `_archives/readme - Archives.md`), y compris dans les
    ressources. Elles n'ont rien à faire dans le jar.
    */
    exclude("**/*.avant-*")

    inputs.properties(resourceTargets)
    filesMatching("fabric.mod.json") { expand(resourceTargets.mapValues { (_, value) -> if (value is Provider<*>) value.get() else value }) }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 5 — LE JAR LIVRABLE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Le jar d'une version porte le mod entier : les classes du module, celles de
 *  common (lues sur le classpath d'exécution, où `commonCode` les a posées), les
 *  ressources de la section 4, et les jars embarqués par include.
 *
 *  Les deux textes de licence, la LGPL v3 et la GPL v3 qu'elle incorpore, vivent à
 *  la racine du dépôt et entrent dans le jar et dans le jar de sources, suffixés du
 *  nom de l'artefact pour ne pas entrer en collision avec ceux des jars embarqués
 *  (Storify porte les siens). Le nom se lit ICI, à la configuration, et non dans le
 *  `rename` : ce dernier s'exécute pendant la tâche, et y toucher `project` est
 *  déprécié (erreur franche en Gradle 10), en plus d'interdire le cache de
 *  configuration.
 *
 *  Le jar de sources reçoit aussi celles de common : il décrit le même mod que le
 *  jar.
 *
 *  ── LE PACK WWOO NE PART PAS DANS UN LIVRABLE ───────────────────────────────
 *  `resourcepacks/wwoo_worldgen/` est fabriqué par
 *  `07-tools-and-scripts/build-wwoo-pack.ps1` à partir du jar officiel de William
 *  Wythers' Overhauled Overworld : c'est du contenu DÉRIVÉ du mod de quelqu'un
 *  d'autre, à usage local. Sans cette exclusion, il pèse 1547 entrées sur 1696 et
 *  plus de 80 pour cent du jar publié.
 *
 *  L'exclusion ne porte que sur les archives : les runs de développement lisent
 *  `build/resources/main`, donc `worldgen = william` continue de fonctionner en
 *  local. Chez qui installe le mod, le pack est absent et
 *  `WorldgenSelector.registerWwooPack` rend `false`, ce qui bascule proprement sur
 *  vanilla avec un avertissement. Qui veut William régénère le pack depuis SON
 *  exemplaire du mod.
 *
 *  ── LE JAR S'OUVRE À CHAQUE BUILD ───────────────────────────────────────────
 *  `checkReleaseJar` ouvre le fichier lui-même, branché sur `check` : aucune entrée
 *  du pack WWOO, exactement les jars embarqués par include, les deux licences à la
 *  racine. C'était un geste à la main de la recette de publication, une fois par
 *  version ; à trois jars, il se fait seul, et la publication (section 10)
 *  l'attend. Le pack n'existe que là où il a été fabriqué, dans le dépôt
 *  principal : c'est là que le contrôle compte, puisque c'est de là que part la
 *  publication.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val commonClasses: Provider<FileCollection> = configurations.runtimeClasspath.map { classpath ->
    classpath.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":common" } }.files
}
val archivesSuffix = base.archivesName.get()
val licenseFiles = listOf("LICENSE", "LICENSE.GPL").map { layout.settingsDirectory.file(it) }

tasks.jar {
    from(commonClasses)
    from(licenseFiles) { rename { "${it}_$archivesSuffix" } }
}

tasks.named<Jar>("sourcesJar") {
    listOf("main/kotlin", "main/java", "main/resources", "client/kotlin").forEach { from(layout.settingsDirectory.dir("common/src/$it")) }
    from(licenseFiles) { rename { "${it}_$archivesSuffix" } }
}

tasks.withType<Jar>().configureEach {
    exclude("resourcepacks/wwoo_worldgen/**")
}

val checkReleaseJar = tasks.register<ReleaseJarCheck>("checkReleaseJar") {
    group = "verification"
    description = "Ouvre le jar livrable : aucune entrée du pack WWOO, les jars embarqués par include, les deux licences"
    jar = tasks.jar.flatMap { it.archiveFile }
    forbiddenPrefixes.add("resourcepacks/wwoo_worldgen/")
    // Les jars attendus sont ceux qu'include déclare (section 2), lus quand la tâche se réalise, toutes les déclarations faites :
    // en ajouter un ne demande rien ici.
    nestedJars.addAll(configurations["include"].dependencies.map { it.name })
    rootEntries.addAll(licenseFiles.map { "${it.asFile.name}_$archivesSuffix" })
    report = layout.buildDirectory.file("reports/release-jar.txt")
}

tasks.named("check") { dependsOn(checkReleaseJar) }

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 6 — ÉTAGE 1 DES TESTS : LE JEU AMORCÉ
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Les tests de l'étage 1 vivent dans common (src/testMC) et s'y compilent, contre
 *  la dernière release. Chaque module de version les REJOUE contre son jeu : c'est
 *  la preuve que le code de common, compilé une fois, tourne sur cette version.
 *
 *  Le source set `testMC` du module n'a pas de sources : il ne sert qu'à bâtir le
 *  classpath d'exécution. Les classes de test arrivent de la configuration
 *  `testMCElements` de common, le jeu, le mod assemblé et fabric-loader-junit sont
 *  ceux de CE module.
 *
 *  ── `main` ET `client` ──────────────────────────────────────────────────────
 *  Le second compte. `splitEnvironmentSourceSets` range `minecraft-clientOnly` du
 *  côté client, et sans lui le chargeur Fabric refuse de s'instancier.
 *
 *  ── LE LANCEUR ──────────────────────────────────────────────────────────────
 *  junit-platform-launcher se déclare, comme dans common pour l'étage 0 : Gradle ne
 *  le pose pas sur un classpath qu'on bâtit soi-même, et la tâche démarrerait sans
 *  savoir lancer quoi que ce soit.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val testMC: SourceSet = sourceSets.create("testMC") {
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["client"].runtimeClasspath
}

dependencies {
    "testMCRuntimeOnly"(project(path = ":common", configuration = "testMCElements"))
    "testMCRuntimeOnly"(libs.findLibrary("junit-jupiter").get())
    "testMCRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    "testMCRuntimeOnly"(mc.findLibrary("fabric-loader-junit").get())
}

val commonTestMCClasses: Provider<FileCollection> = configurations.named(testMC.runtimeClasspathConfigurationName).map { classpath ->
    classpath.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":common" } }.files
}

val runTestMC = tasks.register<Test>("testMC") {
    group = "verification"
    description = "Étage 1 : les tests de common qui demandent le jeu, rejoués contre la lignée $releaseLine de Minecraft"

    testClassesDirs = files(commonTestMCClasses)
    classpath = testMC.runtimeClasspath
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") { dependsOn(runTestMC) }

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 7 — ÉTAGE 2 DES TESTS : LE SERVEUR GAMETEST
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  `fabricApi.configureTests` de Loom crée le source set `gametest` et le run
 *  `gameTest` : un serveur dédié sans fenêtre, hérité du run `server`, qui joue les
 *  `@GameTest` puis s'arrête. Sa tâche, `runGameTest`, est branchée sur `check`,
 *  donc sur `build`. Fabric API y accepte l'EULA d'office. Les tests clients de
 *  Fabric restent éteints.
 *
 *  Le mod de test (`travellingdimension-gametest`, ses tests et son mixin) vit dans
 *  common et s'y assemble en un jar : chaque module le pose sur le classpath de son
 *  serveur GameTest, avec le module GameTest de SA Fabric API, que le module
 *  déclare. Le source set `gametest` du module reste vide.
 *
 *  C'est le seul étage qui voit les mixins et les traversées : un vrai serveur, ses
 *  dimensions, ses chunks. Chaque run repart d'un monde et d'une configuration
 *  neufs, parce qu'un portail survivant d'un run précédent capterait les
 *  traversées, et parce que le premier lancement du mod doit s'éprouver à chaque
 *  build.
 *
 *  ── LE DOSSIER, LE NEUF, LE RAPPORT : CHEZ OUTFITTER ────────────────────────
 *  Le run naît ici, et chaque module le déclare à Outfitter, dans le `gameTests { }`
 *  de son bloc `outfitter` : Outfitter s'y lie sans le recréer. Il lui donne son
 *  dossier, run/game-test dans le module, hors de build/ ; il y retire world/ et
 *  config/ avant chaque run (`freshGameTest`) ; il pose le rapport XML des tests
 *  dans build/test-results/gameTest/, et le filtre de la clé
 *  `outfitter.gametest_filter`.
 *
 *  La déclaration vit dans le module parce qu'Outfitter s'applique module par
 *  module : la faire d'ici lierait build-logic aux classes d'Outfitter. Un
 *  garde-fou la réclame, sans quoi un module qui l'oublie jouerait ses tests dans
 *  un monde qui survit d'un run à l'autre.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val gametestModId = "$modId-gametest"

fabricApi {
    configureTests {
        createSourceSet = true
        modId = gametestModId
        enableGameTests = true
        enableClientGameTests = false
    }
}

dependencies {
    "gametestRuntimeOnly"(project(path = ":common", configuration = "gametestElements"))
}

/* Outfitter enregistre prepareGameTest dès que le module déclare son serveur : après l'évaluation du module, son absence dit l'oubli. */
afterEvaluate {
    if ("prepareGameTest" !in tasks.names) {
        throw GradleException("Module $moduleName runs its GameTest server without Outfitter: declare it in its outfitter block, gameTests { register(\"gameTest\") }")
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 8 — LA VÉRIFICATION DE COMPATIBILITÉ
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  common se compile contre la dernière release : le compilateur ne voit pas ce
 *  qu'il emploie et qu'une version plus ancienne n'a pas, ou nomme autrement. Les
 *  étages 1 et 2 ne le voient que là où un test passe. `checkCommonCompatibility`
 *  le voit partout : elle lit tout le code compilé de common (main, client, et les
 *  tests des étages 1 et 2) et cherche chaque classe, méthode et champ qu'il nomme
 *  dans le classpath de ce module, le jeu, la Fabric API et les bibliothèques de sa
 *  version, en remontant les supertypes jusqu'au JDK. Ce qui manque fait échouer
 *  `check`, donc `build`, avec la liste des symboles et des classes qui les
 *  emploient ; le remède est le pont de version. Compte rendu :
 *  build/reports/common-compatibility.txt.
 *
 *  Mod Menu et Cloth Config, facultatifs, entrent dans ce classpath par le
 *  `clientCompileOnly` de chaque module, aux versions de son catalogue, et non
 *  transitifs comme dans common : l'écran de configuration, compilé contre ceux de
 *  la dernière release, doit trouver leur API dans chaque version du jeu.
 *
 *  Ce qu'elle ne voit pas : les cibles des mixins, écrites en chaînes (l'étage 2
 *  les éprouve, un mixin qui ne s'applique pas arrêtant le serveur), les constantes
 *  que le compilateur a recopiées dans le bytecode, et ce qui change de comportement
 *  sans changer de nom.
 * ════════════════════════════════════════════════════════════════════════════════
 */
configurations.named("clientCompileOnly") { isTransitive = false }

val commonGametestJar: Provider<FileCollection> = configurations.named(sourceSets["gametest"].runtimeClasspathConfigurationName).map { classpath ->
    classpath.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":common" } }.files
}

val checkCommonCompatibility = tasks.register<CommonCompatibilityCheck>("checkCommonCompatibility") {
    group = "verification"
    description = "Vérifie que le code compilé de common ne nomme que ce qui existe dans la lignée $releaseLine de Minecraft et dans ses bibliothèques"
    commonCode.from(commonClasses, commonTestMCClasses, commonGametestJar)
    gameClasspath.from(sourceSets["main"].compileClasspath, sourceSets["client"].compileClasspath, testMC.runtimeClasspath, sourceSets["gametest"].runtimeClasspath)
    gameVersion = minecraftVersion
    report = layout.buildDirectory.file("reports/common-compatibility.txt")
}

tasks.named("check") { dependsOn(checkCommonCompatibility) }

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 9 — LA PUBLICATION MAVEN
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Publication locale de l'artefact. La distribution publique du mod, sur Modrinth
 *  et CurseForge, est la section 10.
 *
 *  `gradlew :<module>:publishToMavenLocal` pose le jar de sa version et son jar de
 *  sources dans le dépôt Maven local (~/.m2), sous `fr.roumoulou:travellingdimension`,
 *  à la version du jar : 2.8.0+<version compilée du jeu>.
 * ════════════════════════════════════════════════════════════════════════════════
 */
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = archivesSuffix
            from(components["java"])

            pom {
                name = "Travelling Dimension"
                url = "https://github.com/Roumoulou/travellingdimension"

                licenses {
                    license {
                        name = "GNU Lesser General Public License v3.0 only"
                        url = "https://www.gnu.org/licenses/lgpl-3.0.txt"
                        distribution = "repo"
                        comments = "SPDX-License-Identifier: LGPL-3.0-only"
                    }
                }
            }
        }
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 10 — LA PUBLICATION SUR MODRINTH ET CURSEFORGE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  mod-publish-plugin envoie le jar de CE module sur les deux plateformes : une
 *  version Modrinth et un fichier CurseForge par version du jeu.
 *  `gradlew publishMods` lancé à la racine envoie donc les trois jars, en six
 *  envois.
 *
 *  Ce qui tient à la version ne s'écrit pas à la main, il se calcule comme les
 *  planchers de la section 1 : le fichier, la version (2.8.0+26.3), le nom affiché
 *  (TravellingDimension-2.8.0+26.3, sur le modèle de la 2.7.0) et la version du
 *  jeu, celle que le jar exige. Le reste est commun aux trois jars :
 *    - Fabric, release, client et serveur
 *    - Fabric API et Fabric Language Kotlin requises, Mod Menu et Cloth Config
 *      facultatives, par leur slug, le même sur les deux plateformes
 *    - le changelog : 04-releases\<version du mod>\changelog - a publier.md, dans
 *      le classeur, le même pour les trois jars
 *    - les deux projets, nommés dans gradle.properties (modrinth_project_id,
 *      curseforge_project_id)
 *
 *  ── À BLANC PAR DÉFAUT ──────────────────────────────────────────────────────
 *  Sans `-Ppublish_live=true`, rien ne part : le plugin valide la déclaration, copie
 *  les jars sous build\publishMods\ et y écrit ce qu'il aurait envoyé. C'est
 *  l'essai, que ni Modrinth ni CurseForge ne voient.
 *
 *  ── LES GARDE-FOUS ──────────────────────────────────────────────────────────
 *  Chaque envoi attend `check` : les trois étages de test, la vérification de
 *  compatibilité et l'ouverture du jar (section 5). Il attend aussi
 *  `checkPublication`, qui passe avant eux : le changelog, l'identifiant Modrinth,
 *  et les jetons MODRINTH_TOKEN et CURSEFORGE_TOKEN, que
 *  `dev-secrets.ps1 -Apply MODRINTH_TOKEN, CURSEFORGE_TOKEN` pose dans le terminal
 *  qui publie, jamais dans un fichier. À blanc, ce qui manque se dit ; en envoi
 *  réel, ce qui manque refuse tout, avant le premier envoi. La tâche ne lit que la
 *  présence des jetons, jamais leur valeur.
 *
 *  ── CE QUI N'EST PAS DÉCLARÉ ────────────────────────────────────────────────
 *  Java 25, à CurseForge : le plugin le traduit en étiquette « Java 25 », que
 *  CurseForge n'a peut-être pas encore, et l'envoi réel échouerait là où l'essai à
 *  blanc ne voit rien. Le jar exige déjà Java 25, par fabric.mod.json. Le jar de
 *  sources part sur Modrinth seulement, comme le faisait la recette manuelle.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val publishLive: Boolean = providers.gradleProperty("publish_live").map(String::toBoolean).getOrElse(false)
val releaseVersion: Provider<String> = minecraftVersion.map { "$modVersion+$it" }
val releaseChangelog: RegularFile = layout.settingsDirectory.file("../../04-releases/$modVersion/changelog - a publier.md")
val modrinthProjectId: Provider<String> = providers.gradleProperty("modrinth_project_id").filter(String::isNotBlank)
val tokenVariables = listOf("MODRINTH_TOKEN", "CURSEFORGE_TOKEN")

publishMods {
    dryRun = !publishLive
    file = tasks.jar.flatMap { it.archiveFile }
    version = releaseVersion
    displayName = releaseVersion.map { "TravellingDimension-$it" }
    changelog = providers.fileContents(releaseChangelog).asText.orElse("")
    type = STABLE
    modLoaders.add("fabric")

    modrinth {
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        // L'essai à blanc passe sans identifiant, que seul l'envoi réel emploie : checkPublication refuse l'envoi réel sans lui.
        projectId = modrinthProjectId.orElse("absent")
        minecraftVersions.add(minecraftVersion)
        environment = CLIENT_AND_SERVER
        requires("fabric-api", "fabric-language-kotlin")
        optional("modmenu", "cloth-config")
        additionalFile(tasks.named("sourcesJar")) { type = SOURCES_JAR }
    }

    curseforge {
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
        projectId = providers.gradleProperty("curseforge_project_id")
        // L'adresse du projet sur CurseForge reprend l'identifiant du mod : le plugin en tire le lien du fichier envoyé.
        projectSlug = modId
        minecraftVersions.add(minecraftVersion)
        client = true
        server = true
        requires("fabric-api", "fabric-language-kotlin")
        optional("modmenu", "cloth-config")
    }
}

/* Ce qui manquerait à un envoi réel, lu à la configuration : un chemin, une clé, des noms de variables ; jamais la valeur d'un jeton. */
val missingForRelease: List<String> = buildList {
    if (!releaseChangelog.asFile.isFile) add(releaseChangelog.asFile.toString())
    if (!modrinthProjectId.isPresent) add("modrinth_project_id (gradle.properties)")
    tokenVariables.filter { !providers.environmentVariable(it).isPresent }.forEach { add(it) }
}

val checkPublication = tasks.register("checkPublication") {
    group = "publishing"
    description = "Vérifie avant tout envoi le changelog, l'identifiant Modrinth et les jetons ; en envoi réel, ce qui manque refuse tout"
    val live = publishLive
    val missing = missingForRelease
    val tokens = tokenVariables.joinToString(", ")
    doLast {
        when {
            missing.isEmpty() -> logger.lifecycle("[publication] ${if (live) "envoi réel" else "à blanc"} : rien ne manque")
            live -> throw GradleException("Publication refused before any upload, missing: ${missing.joinToString(", ")}. The tokens come from dev-secrets.ps1 -Apply $tokens, in this terminal.")
            else -> logger.lifecycle("[publication] à blanc ; un envoi réel refuserait, il manque : ${missing.joinToString(", ")}")
        }
    }
}

tasks.withType<PublishModTask>().configureEach { dependsOn(checkPublication, "check") }
tasks.named("check") { mustRunAfter(checkPublication) }
