/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  BUILD SCRIPT — Travelling Dimension
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Ce fichier orchestre :
 *    - la compilation du mod, Kotlin et Java, côté serveur et côté client
 *    - deux étages de test séparés par le compilateur : logique pure (test), puis jeu amorcé (testMC)
 *    - la déclaration des environnements de développement et des cibles de déploiement,
 *      préparés et servis par le plugin Outfitter
 *
 *  Plugins :
 *    - fabric-loom            outillage Fabric : déobfuscation (avant 26.1), runs, mixins
 *    - kotlin-jvm             le langage
 *    - kotlin-serialization   la configuration en JSON
 *    - fr.moulou.outfitter    les environnements de développement et les déploiements
 *                             (S:\16\_V\Outfitter, consommé en build composite)
 *    - maven-publish          publication de l'artefact
 * ════════════════════════════════════════════════════════════════════════════════
 */

@file:Suppress("AvoidDuplicateDependencies")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(mc.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.outfitter)
    id("maven-publish")
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 1 — IDENTITÉ DU MOD
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Ces quatre propriétés définissent l'identité du mod et de l'artefact produit.
 *  Elles sont lues depuis gradle.properties, pas d'ici : chacune se monte à UN
 *  seul endroit, et `processResources` injecte ce qui doit l'être dans
 *  `fabric.mod.json`.
 *
 *  - modId                : l'identifiant Fabric du mod, clé mod_id. Déclaré UNE
 *                           fois : processResources l'injecte dans fabric.mod.json,
 *                           le build s'en sert (bloc mods de Loom, groupe de tâches)
 *                           et Outfitter aussi (nom du jar déployé, mod jamais
 *                           recopié depuis l'instance, logger de levels.xml). Pour
 *                           dériver un nouveau projet de celui-ci : changer mod_id
 *                           ici, puis renommer à la main ce qui vit dans les SOURCES
 *                           (packages, fichier <id>.mixins.json, dossier assets/<id>/).
 *
 *  - version              : version du mod, clé mod_version. Injectée dans
 *                           fabric.mod.json via processResources.
 *
 *  - group                : groupe Maven, clé maven_group ("fr.roumoulou").
 *                           Utilisé pour la publication et l'identification du projet.
 *
 *  - archivesName         : nom de base du jar produit, clé archives_base_name
 *                           ("travellingdimension"). Le fichier final s'appelle
 *                           travellingdimension-<version>.jar.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val modId = project.property("mod_id").toString()
version = project.property("mod_version").toString()
group = project.property("maven_group").toString()
base { archivesName.set(project.property("archives_base_name") as String) }

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 2 — CONFIGURATIONS DE DÉPENDANCES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  LE SEUL ENDROIT OÙ DÉCLARER UN DÉPÔT DE DÉPENDANCES.
 *
 *  On n'utilise PAS `settings.gradle.kts` pour déclarer les dépôts : Loom pose
 *  les siens au projet, et le mode par défaut de Gradle (`PREFER_PROJECT`) ignore
 *  alors ceux de settings, entièrement et sans rien journaliser.
 *
 *  ── CE QUE LOOM POSE TOUT SEUL, DONC PAS REDÉCLARÉ ICI ──────────────────────
 *  En s'appliquant, Loom ajoute ses dépôts au projet : ses trois caches locaux,
 *  Fabric (maven.fabricmc.net), Mojang et mavenCentral. Kotlin, JUnit,
 *  kotlinx-serialization, le chargeur, Fabric API, fabric-language-kotlin et
 *  fabric-loader-junit se résolvent donc sans une ligne ici. La liste effective
 *  se mesure avec `gradlew listRepositories -q`, tâche déclarée sous le bloc.
 *  Si une résolution casse un jour après une montée de Loom, c'est ICI que le
 *  dépôt disparu se redéclarera.
 *
 *  ┌─ Shedaniel (Cloth Config) ────────────────────────────────────────────────┐
 *  │  Les widgets de l'écran de configuration en jeu (clientCompileOnly,       │
 *  │  facultatif, section 7). Filtré : includeGroup("me.shedaniel.cloth").     │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ┌─ TerraformersMC (Mod Menu) ───────────────────────────────────────────────┐
 *  │  Mod Menu, l'autre moitié de l'écran de configuration (facultatif aussi). │
 *  │  Filtré : includeGroup("com.terraformersmc").                             │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ── LES HUIT DÉPÔTS RÉELLEMENT CONSULTÉS, dans l'ordre, mesurés ─────────────
 *
 *      1-3    les trois caches de Loom           posés par Loom
 *      4      Fabric                             posé par Loom
 *      5      Mojang                             posé par Loom
 *      6      mavenCentral (MavenRepo)           posé par Loom
 *      7-8    Shedaniel, TerraformersMC          les seuls déclarés ICI
 *
 *  ── L'ORDRE COMPTE, LE FILTRE AUSSI ─────────────────────────────────────────
 *  Gradle interroge les dépôts DANS L'ORDRE, en deux passes : d'abord les caches
 *  locaux de tous, puis le réseau de ceux qui n'ont rien rendu. Un raté coûte donc
 *  un aller-retour par dépôt traversé avant le bon. `content { includeGroup(...) }`
 *  déclare « ce dépôt ne sert QUE ce groupe » : hors du groupe, Gradle le saute
 *  entièrement, ni cache ni réseau. Sans le filtre, Shedaniel et TerraformersMC
 *  seraient interrogés pour CHAQUE artefact introuvable ailleurs, Kotlin et JUnit
 *  compris.
 *
 *  ── QUAND CARPET ARRIVERA ───────────────────────────────────────────────────
 *  Modrinth et CurseMaven se déclareront ICI, et nulle part ailleurs. Voir la
 *  stratégie Carpet de `01-docs/technical-docs/02-finalized/strategie-de-test.md`.
 * ════════════════════════════════════════════════════════════════════════════════
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
}

/* La liste effective, celle que Gradle utilise vraiment, Loom compris : gradlew listRepositories -q */
tasks.register("listRepositories") {
    group = "$modId-dev"
    description = "Liste les dépôts de dépendances effectifs du projet, les déclarés ici comme ceux que Loom pose"
    doLast { repositories.forEach { repo -> println("${repo.name.padEnd(32)} ${(repo as? org.gradle.api.artifacts.repositories.MavenArtifactRepository)?.url ?: ""}") } }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 3 — LES DEUX ÉTAGES DE TEST & LES DÉPENDANCES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Deux source sets de test, et c'est le COMPILATEUR qui tient la frontière.
 *
 *  ┌─ src/test ─ étage 0 ──────────────────────────────────────────────────────┐
 *  │  La logique pure : l'arithmétique des coordonnées, les bornes de la       │
 *  │  configuration. AUCUN accès à Minecraft, et ce n'est pas une convention : │
 *  │  un import du jeu ne compile pas.                                         │
 *  │  Tâche : gradlew test              Mesuré : 13 tests, 0,05 s              │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ┌─ src/testMC ─ étage 1 ────────────────────────────────────────────────────┐
 *  │  Le jeu amorcé par fabric-loader-junit : registres, blocs, et tout ce qui │
 *  │  ne fait que MENTIONNER un type du jeu, comme BlockPos.                   │
 *  │  Tâche : gradlew testMC            Mesuré : 6 tests, 2,8 s                │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ── LES DÉPENDANCES, déclarées dans le bloc plus bas ────────────────────────
 *
 *  - minecraft                  : le jeu lui-même, fourni et câblé par Loom.
 *
 *  - fabric-loader              : le chargeur : entrypoints et annotations de mixin.
 *
 *  - fabric-api                 : les API haut niveau : events, registres, réseau.
 *
 *  - fabric-language-kotlin     : l'adaptateur Kotlin de Fabric ; il embarque le
 *                                 runtime Kotlin et kotlinx-serialization.
 *
 *  - kotlinx-serialization-json : la configuration en JSON. Fourni au runtime par
 *                                 fabric-language-kotlin, déclaré quand même pour
 *                                 compiler avec une version contrôlée.
 *
 *  - junit-jupiter              : l'écriture et l'exécution des tests, aux deux
 *                                 étages. L'agrégat porte l'api, les tests
 *                                 paramétrés et le moteur : rien d'autre à déclarer.
 *
 *  - junit-platform-launcher    : le lanceur, étage 0 seulement : l'héritage coupé
 *                                 ci-dessous perd celui que Gradle aurait posé sur
 *                                 le classpath d'exécution.
 *
 *  - fabric-loader-junit        : l'amorçage du jeu à l'étage 1. Même version.ref
 *                                 que le chargeur : il suit tout seul ses montées.
 *
 *  ── CE QUE L'ÉTAGE 1 NE DONNE PAS : LES MIXINS ──────────────────────────────
 *  Mesuré, et refait sur deux cadres de test : `PortalShape` chargée depuis un
 *  test ne porte AUCUNE méthode de synthèse du mixin. Le log d'exécution montre
 *  pourtant le sous-système Mixin s'initialiser (Service=Knot/Fabric) : le
 *  mécanisme exact reste à élucider, seul le résultat mesuré fait foi. Tout ce
 *  qui passe par un mixin se vérifie en jeu, par script, ou par lecture du
 *  bytecode.
 *
 *  ── COUPER L'HÉRITAGE NE SUFFIT PAS ─────────────────────────────────────────
 *  Les deux lignes `setExtendsFrom(emptyList())` ci-dessous coupent ce que Gradle
 *  fait hériter par défaut à `testImplementation`. Elles sont NÉCESSAIRES, mais pas
 *  SUFFISANTES : Loom pose Minecraft directement sur le source set, sans passer par
 *  les configurations. La reprise du classpath est en section 6, après le bloc
 *  `loom`, et c'est là que la frontière devient réelle.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val testMC: SourceSet = sourceSets.create("testMC")

configurations {
    named("testImplementation") { setExtendsFrom(emptyList()) }
    named("testRuntimeOnly") { setExtendsFrom(emptyList()) }
}

dependencies {
    minecraft(mc.minecraft)

    implementation(mc.fabric.loader)
    implementation(mc.fabric.api)
    implementation(mc.fabric.language.kotlin)
    implementation(libs.kotlinx.serialization.json)

    // ── Étage 0 : la logique pure, aucune dépendance au jeu ──────────────────
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // ── Étage 1 : le jeu, et son amorçage ────────────────────────────────────
    "testMCImplementation"(libs.junit.jupiter)
    "testMCImplementation"(mc.fabric.loader.junit)
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 4 — CONFIGURATION JAVA
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  - targetJavaVersion    : la version Java cible, lue depuis libs.versions.toml.
 *                           Utilisée pour la compilation (ici et en 10.1) ET injectée
 *                           dans fabric.mod.json.
 *
 *  - toolchain            : force Gradle à utiliser un JDK précis (Java 25 ici).
 *                           Si le JDK n'est pas installé localement, Gradle peut
 *                           le télécharger automatiquement via les toolchain resolvers.
 *
 *  - withSourcesJar()     : génère automatiquement un JAR de sources (-sources.jar)
 *                           lors du build. Utile pour les IDE et la publication Maven.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val targetJavaVersion = libs.versions.java.get().toInt()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 5 — CONFIGURATION LOOM
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Loom est le plugin Gradle officiel de Fabric. Il gère :
 *    - Le téléchargement de Minecraft (la déobfuscation, c'était avant 26.1)
 *    - La configuration des runs (client, serveur)
 *    - La gestion des mixins
 *    - L'injection d'interfaces : une interface déclarée par un mod est greffée sur
 *      une classe du jeu dans le jar de développement, si bien que le code compile
 *      contre elle ; au runtime, c'est le mixin du mod déclarant qui l'implémente.
 *      C'est ainsi que Fabric API donne `getAttached` et `setAttached` aux entités
 *      et aux chunks (mémoire de trajet, verrous, couleurs des portails du NETHER)
 *
 *  ── splitEnvironmentSourceSets() ────────────────────────────────────────────
 *  Crée un source set `client` séparé de `main`. Le code client est ainsi isolé du
 *  code serveur, ce qui évite les ClassNotFoundException quand le jar tourne sur un
 *  serveur dédié. Conséquence à connaître : `minecraft-clientOnly` est rangé du côté
 *  client, et c'est pour cela que la section 6 donne à `testMC` le classpath des
 *  DEUX source sets.
 *
 *  ── mods { } ────────────────────────────────────────────────────────────────
 *  Regroupe `main` et `client` sous le MÊME identifiant de mod. Sans ce bloc, Loom
 *  les traiterait comme deux mods distincts et fabriquerait de faux conflits de
 *  chargement de classes en développement.
 *
 *  ── runs { } : chez Outfitter, section 9 ────────────────────────────────────
 *  Les quatre runs, leur dossier, leur préparation avant le lancement, leurs
 *  niveaux et leur format de log, le joueur des runs client : tout vient des
 *  environnements déclarés en section 9 et des clés `outfitter.*`. Ce bloc n'en
 *  parle plus.
 * ════════════════════════════════════════════════════════════════════════════════
 */
loom {
    splitEnvironmentSourceSets()

    mods {
        register(modId) {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 6 — LE CLASSPATH DES DEUX ÉTAGES DE TEST
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  ⚠  CETTE SECTION DOIT RESTER APRÈS LE BLOC `loom` DE LA SECTION 5.
 *
 *  C'est tout son intérêt : Loom ajoute Minecraft DIRECTEMENT sur le source set
 *  `test`, sans passer par l'héritage des configurations. Couper `extendsFrom` ne
 *  retire donc rien, et reprendre le classpath avant que Loom soit passé ne sert
 *  à rien non plus : il repasserait derrière.
 *
 *  ── `test` : LA FRONTIÈRE DEVIENT UNE ERREUR DE COMPILATION ─────────────────
 *  Son classpath est reconstruit à partir de sa seule configuration, donc JUnit et
 *  la sortie de `main`. Éprouvé en y glissant un import du jeu :
 *
 *      E: ScratchFrontiereTest.kt:3:12 Unresolved reference 'minecraft'.
 *
 *  ── LE PIÈGE DES DEUX CLASSPATH ─────────────────────────────────────────────
 *  Ils se reprennent SÉPARÉMENT. Bâtir celui d'exécution en partant de celui de
 *  compilation perd `junit-platform-launcher`, que Gradle ne pose QUE sur
 *  l'exécution : la tâche démarre alors sans savoir lancer quoi que ce soit, avec
 *  un message qui ne dit pas d'où vient le manque. C'est aussi pour cela que le
 *  lanceur est déclaré explicitement en section 3.
 *
 *  ── `testMC` : `main` ET `client` ───────────────────────────────────────────
 *  Le second compte. `splitEnvironmentSourceSets` range `minecraft-clientOnly` du
 *  côté client, et sans lui le chargeur Fabric refuse de s'instancier.
 * ════════════════════════════════════════════════════════════════════════════════
 */
sourceSets {
    named("test") {
        /* Compilation et exécution se reprennent séparément : voir « LE PIÈGE DES DEUX CLASSPATH » ci-dessus. */
        val jeu: (File) -> Boolean = { it.name.startsWith("minecraft-") }
        compileClasspath = configurations["testCompileClasspath"].filter { !jeu(it) } + sourceSets["main"].output
        runtimeClasspath = output + sourceSets["main"].output + configurations["testRuntimeClasspath"].filter { !jeu(it) }
    }
    named("testMC") {
        compileClasspath += sourceSets["main"].compileClasspath + sourceSets["client"].compileClasspath + sourceSets["main"].output + sourceSets["client"].output
        runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["client"].runtimeClasspath + sourceSets["main"].output + sourceSets["client"].output
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 7 — ÉCRAN DE CONFIGURATION EN JEU, DÉPENDANCES FACULTATIVES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  ⚠  DÉCLARÉES ICI ET PAS EN SECTION 3, et ce n'est pas un choix de rangement :
 *  la configuration `clientCompileOnly` N'EXISTE PAS avant que
 *  `splitEnvironmentSourceSets()` (section 5) ait été appelé. Remonter ce bloc casse le build.
 *
 *  ── `compileOnly`, C'EST-À-DIRE VRAIMENT FACULTATIF ─────────────────────────
 *  Ni embarquées, ni exigées au runtime, ni chargées par `runClient` et `runServer`, qui
 *  restent vanilla purs. Le mod fonctionne sans : l'écran apparaît seulement là où
 *  Mod Menu et Cloth Config sont installés. Terrain d'essai : l'instance Prism
 *  « modded », qui les porte déjà.
 *
 *  ── NON TRANSITIF ───────────────────────────────────────────────────────────
 *  Ces deux jars suffisent à compiler. Leurs dépendances, dont une AUTRE version de
 *  Fabric API, n'ont rien à faire sur le classpath et masqueraient la nôtre.
 * ════════════════════════════════════════════════════════════════════════════════
 */
configurations.named("clientCompileOnly") { isTransitive = false }

dependencies {
    add("clientCompileOnly", mc.modmenu)
    add("clientCompileOnly", mc.cloth.config)
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 8 — PUBLICATION MAVEN
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Publication locale de l'artefact. La distribution publique du mod, elle, ne passe
 *  pas par ici : elle se fait à la main sur Modrinth et CurseForge, en suivant la
 *  recette de `04-releases`.
 *
 *  `gradlew publishToMavenLocal` pose le jar et le jar de sources dans le dépôt
 *  Maven local (~/.m2), sous `fr.roumoulou:travellingdimension`.
 * ════════════════════════════════════════════════════════════════════════════════
 */
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 9 — LES ENVIRONNEMENTS DE DÉVELOPPEMENT : OUTFITTER
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Le plugin Outfitter (S:\16\_V\Outfitter, consommé en build composite : voir
 *  settings.gradle.kts) prépare chaque environnement avant son run et déploie le jar.
 *  Ce bloc ne déclare que ce qui est propre au mod : la version de Minecraft, les
 *  quatre environnements avec leur profil de l'entrepôt, les deux cibles de
 *  déploiement et le panier du serveur dédié. Tout le reste vient des clés
 *  `outfitter.*` : gradle.properties pour ce qui est propre au projet (maps, monde
 *  du serveur, exclusions, logs, joueur), machine.properties pour ce qui est propre
 *  au poste (l'entrepôt S:\18, l'instance Prism, PackTool).
 *
 *  ── QUATRE ENVIRONNEMENTS, deux par deux ────────────────────────────────────
 *  `client` et `server` sont VANILLA PURS : aucun mod tiers, Loom charge le mod
 *  depuis le classpath. Ce sont eux la référence, celle qui dit ce que voit un
 *  joueur n'ayant QUE ce mod. `clientModded` et `serverModded` reçoivent le noyau
 *  MDTK de l'instance Prism du poste, filtré par side, puis les packs, datapacks
 *  et réglages de MDTK par PackTool : un environnement moddé EST l'instance MDTK
 *  du poste. Le dossier est run\<nom-en-kebab-case>, le run Loom porte le nom de
 *  l'environnement (runClientModded), et prepare<Env> s'exécute avant lui.
 *
 *  ── DEUX CIBLES, et elles ne reçoivent PAS la même chose ────────────────────
 *  `serverPur`, le serveur dédié du classeur (05-instances\server-pur), n'a pas de
 *  modpack : il reçoit le jar ET le panier `serverPurBundle`, Fabric API et FLK aux
 *  versions du catalogue, sans leurs dépendances. `prism`, l'instance PrismLauncher,
 *  porte son propre modpack : elle ne reçoit QUE le jar, et la tâche avertit si
 *  Fabric API ou FLK semblent absents de ses mods.
 *
 *  Les tâches, groupe `outfitter` : sync<Env>Profile, Worlds, Mods, Packs,
 *  Datapacks, Settings, prepare<Env>, deployTo<Cible>, setup<Cible>,
 *  resetEnvironments, resetWorlds, outfitterLog4jConfigs. Le détail, condition,
 *  geste et marqueur de chacune : 01-docs\technical-docs\02-finalized\
 *  taches-de-developpement.md, et la doc du plugin.
 * ════════════════════════════════════════════════════════════════════════════════
 */
outfitter {
    minecraftVersion = mc.versions.minecraft
    environments {
        register("client") { client(); profile = "vanilla" }
        register("server") { server(); profile = "dev" }
        register("clientModded") { client(); profile = "vanilla"; modded = true }
        register("serverModded") { server(); profile = "dev"; modded = true }
    }
    deployTargets {
        register("serverPur") {
            directory = layout.projectDirectory.dir("../../05-instances/server-pur/server")
            profile = "dev"
        }
        register("prism") {
            directory = referenceInstance
            expectedMods.set(listOf("fabric-api", "fabric-language-kotlin"))
        }
    }
}

/* Le panier du serveur dédié : ces deux jars et rien d'autre (la configuration n'est pas transitive), aux versions que le mod a compilées. */
dependencies {
    "serverPurBundle"(mc.fabric.api)
    "serverPurBundle"(mc.fabric.language.kotlin)
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 10 — LES TÂCHES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Les tâches propres au projet portent le groupe travellingdimension-dev
 *  (diagnostiquer : listRepositories, section 2). Celles qui préparent les
 *  environnements et déploient le jar sont au groupe `outfitter` (section 9).
 *
 *  ┌───────────────────────────────────────────────────────────────────────────┐
 *  │  10.1  compilations, jar, ressources                                      │
 *  │  10.2  les deux étages de test                                            │
 *  └───────────────────────────────────────────────────────────────────────────┘
 * ════════════════════════════════════════════════════════════════════════════════
 */
tasks {

    /**
     *  ── 10.1 — Compilation, jar et ressources ──────────────────────────────
     *
     *  L'encodage UTF-8 est forcé des deux côtés, la compilation Java ici et le
     *  filtrage des ressources plus bas : sans lui, chacun suit l'encodage du
     *  système, et les accents de ce projet sortent en charabia sur une machine
     *  autrement réglée.
     *
     *  ── L'AVERTISSEMENT IDEA « MatchingCopyAction », PERMANENT ET BÉNIN ────
     *  À chaque sync Gradle, IntelliJ affiche « Cannot resolve resource
     *  filtering of MatchingCopyAction » sur ce projet. La cause est le
     *  `filesMatching("fabric.mod.json") { expand(...) }` de processResources :
     *  l'IDE ne sait pas reproduire ce filtrage dans son compilateur interne,
     *  et le dit. Sans aucune conséquence tant que le build est délégué à
     *  Gradle (le défaut), et commun à tous les projets Fabric, dont le
     *  gabarit officiel. Enquêté et clos le 2026-09-12 : ne pas rouvrir.
     */
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
    }

    withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
    }

    /*
    Le nom de base est lu ICI, a la configuration, et non dans le `rename` : ce dernier
    s'execute pendant la tache, et y toucher `project` est deprecie (erreur franche en
    Gradle 10), en plus d'interdire le cache de configuration.
    */
    jar {
        val nomBase = project.base.archivesName.get()
        from("LICENSE.txt") { rename { "${it}_$nomBase" } }
    }

    /*
    LE PACK WWOO NE PART PAS DANS UN LIVRABLE.

    `resourcepacks/wwoo_worldgen/` est fabriqué par `07-tools-and-scripts/build-wwoo-pack.ps1`
    à partir du jar officiel de William Wythers' Overhauled Overworld : c'est du contenu
    DÉRIVÉ du mod de quelqu'un d'autre, à usage local. Sans cette exclusion, il pèse
    1547 entrées sur 1696 et plus de 80 pour cent du jar publié.

    L'exclusion ne porte que sur les archives : les runs de développement lisent
    `build/resources/main`, donc `worldgen = william` continue de fonctionner en local. Chez
    qui installe le mod, le pack est absent et `WorldgenSelector.registerWwooPack` rend
    `false`, ce qui bascule proprement sur vanilla avec un avertissement. Qui veut William
    régénère le pack depuis SON exemplaire du mod.
    */
    withType<Jar>().configureEach {
        exclude("resourcepacks/wwoo_worldgen/**")
    }

    processResources {
        val resourceTargets = mapOf(
            "mod_id" to modId,
            "version" to version,
            "minecraft_version" to mc.versions.minecraft.get(),
            "fabric_loader_version" to mc.versions.fabric.loader.get(),
            "fabric_language_kotlin_version" to mc.versions.fabric.language.kotlin.get(),
            "java_version" to libs.versions.java.get()
        )

        filteringCharset = "UTF-8"

        /*
        Les sauvegardes ponctuelles `.avant-<motif>` vivent à côté du fichier qu'elles
        doublent (convention de `_archives/readme - Archives.md`), y compris dans les
        ressources. Elles n'ont rien à faire dans le jar.
        */
        exclude("**/*.avant-*")

        inputs.properties(resourceTargets)
        filesMatching("fabric.mod.json") { expand(resourceTargets) }
    }

    /**
     *  ── 10.2 — Les deux étages de test ─────────────────────────────────────
     *
     *  `test` est celui de Gradle, `testMC` est enregistrée ici parce qu'elle a son
     *  propre source set, donc son propre classpath. Elle est branchée sur `check`,
     *  ce qui fait que `gradlew build` joue bien les DEUX étages.
     *
     *  Le classpath de chacune est réglé en section 6, pas ici.
     */
    test {
        useJUnitPlatform()
        description = "Étage 0 : la logique pure, sans Minecraft"

        /*
        Ceinture et bretelles : Minecraft n'est déjà plus au classpath, mais si le chargeur
        Fabric s'y trouvait un jour par transitivité, cette propriété l'empêche de chercher
        les points d'entrée du mod dans un environnement de jeu qui n'existe pas.
        */
        systemProperty("fabric.development", "false")

        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            showExceptions = true
            showCauses = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    register<Test>("testMC") {
        group = "verification"
        description = "Étage 1 : le jeu amorcé, les registres et les types du jeu"

        testClassesDirs = testMC.output.classesDirs
        classpath = testMC.runtimeClasspath
        useJUnitPlatform()

        // L'étage 0, rapide, tombe d'abord : inutile d'amorcer le jeu si la logique pure échoue.
        shouldRunAfter(named("test"))

        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            showExceptions = true
            showCauses = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    named("check") { dependsOn(named("testMC")) }
}
