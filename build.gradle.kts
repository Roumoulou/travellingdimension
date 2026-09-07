/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  BUILD SCRIPT — Travelling Dimension
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Ce fichier orchestre :
 *    - la compilation du mod, Kotlin et Java, côté serveur et côté client
 *    - deux étages de test séparés par le compilateur : logique pure (test), puis jeu amorcé (testMC)
 *    - la préparation des environnements de développement depuis l'entrepôt S:\18
 *    - le déploiement vers le serveur dédié et vers l'instance PrismLauncher
 *
 *  Plugins :
 *    - fabric-loom            outillage Fabric : déobfuscation (avant 26.1), runs, mixins
 *    - kotlin-jvm             le langage
 *    - kotlin-serialization   la configuration en JSON
 *    - maven-publish          publication de l'artefact
 * ════════════════════════════════════════════════════════════════════════════════
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.LocalDateTime
import java.util.Properties

plugins {
    alias(mc.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 1 — VARIABLES GLOBALES & CHEMINS
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Centralise les chemins et constantes réutilisés dans tout le script.
 *
 *  Le projet vit dans TravellingDimension/fabric-mod-core/TravellingDimension : DEUX niveaux
 *  au-dessus se trouvent les dossiers numérotés du classeur, d'où les `../../` parfois.
 *  Aucun chemin absolu ici, à une exception près : l'instance PrismLauncher, qui
 *  vit hors du classeur et se règle donc dans `local.properties`, JAMAIS versionné.
 *
 *  - targetJavaVersion    : version Java cible, lue depuis libs.versions.toml.
 *                           Utilisée pour la compilation ET injectée dans fabric.mod.json.
 *
 *  - serverInstancesDir   : le dossier des instances de serveur locales du classeur
 *                           (02-local-server-instances), en dehors du projet Gradle.
 *
 *  - runDir               : dossier de travail des runs Loom (run/client, run/server).
 *                           Contient les mondes, configs et logs générés en développement.
 *
 *  - serverPurDir         : l'instance server-pur elle-même, à plat : la version vit
 *                           dans le nom du jar Fabric, pas dans un sous-dossier.
 *                           « Pur » = rien que le mod et ses deux béquilles runtime.
 *                           Cible de deployToServerPur et setupServerPur.
 *
 *  - prismInstanceDir     : l'instance PrismLauncher « modded », qui porte son propre
 *                           modpack ; deployToPrism n'y pousse que le jar du mod. Chemin
 *                           réglé par prism_instance_dir dans `local.properties`, un
 *                           fichier propre à la machine et jamais versionné. Absent, la
 *                           configuration passe quand même : c'est deployToPrism qui le
 *                           dit, et lui seul en a besoin.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val targetJavaVersion = libs.versions.java.get().toInt()
val serverInstancesDir = layout.projectDirectory.dir("../../02-local-server-instances")
val runDir = layout.projectDirectory.dir("run")
val serverPurDir = serverInstancesDir.dir("server-pur/server")
/*
Le chemin de l'instance Prism est propre à CHAQUE machine : il ne peut donc pas vivre
dans `gradle.properties`, qui est versionné, ni dans le gradle.properties utilisateur,
que setup-pc.ps1 réécrit depuis son modèle SkyChest. Il vit dans `local.properties`,
même convention que la clé d'API CurseForge de PackTool.
*/
fun localProperty(cle: String): String? {
    val fichier = layout.projectDirectory.file("local.properties").asFile
    if (!fichier.exists()) return null
    val proprietes = Properties()
    fichier.inputStream().use { proprietes.load(it) }
    return proprietes.getProperty(cle)?.takeIf { it.isNotBlank() }
}

val prismInstanceDir = File(localProperty("prism_instance_dir") ?: "")

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 2 — L'ENTREPÔT S:\18, SOURCE UNIQUE DES RÉGLAGES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Pas de surcouche dans le classeur : tout ce qui prépare un environnement, soit
 *  options.txt, packs, server.properties et maps, vient de l'entrepôt par-version
 *  de SkyChest, et de lui seul. Un réglage se change là-bas, UNE fois, pour le jeu
 *  comme pour le développement.
 *
 *  ── DÉGRADATION VOULUE ──────────────────────────────────────────────────────
 *  Entrepôt absent, disque débranché, fichier pas encore posé : les copies sautent
 *  la source sans broncher et Minecraft génère ses propres réglages. Le build ne
 *  doit jamais dépendre d'un disque externe pour compiler.
 *
 *  ── UNE MAP = UN DOSSIER-MONDE ──────────────────────────────────────────────
 *  Une map de l'entrepôt est le monde lui-même : `level.dat` à sa racine, et son
 *  readme dans le même dossier s'il existe. Le nom de la map est le nom du
 *  dossier. Un dossier sans `level.dat` n'est pas une map, il est ignoré.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val warehouseMcVersion = "26.2"
val favoritesDir = File("S:/18/00-my-minecraft-favorites-configs/$warehouseMcVersion")
val warehouseMapsDir = File("S:/18/05-maps/$warehouseMcVersion")

fun warehouseWorlds(): List<Pair<String, File>> = warehouseMapsDir.listFiles { f: File -> f.isDirectory && File(f, "level.dat").exists() }?.map { it.name to it } ?: emptyList()

/*
Le profil de l'entrepôt peut désigner le monde de départ du serveur, par NOM et non
par chemin : même convention que ses packs, le profil pointe, l'entrepôt stocke.
Sans cette clé, le serveur prendrait la première map par ordre alphabétique, ce qui
change en silence dès qu'une map arrive avant elle dans l'alphabet.
*/
fun declaredWorld(profile: String): String? {
    val manifest = File(favoritesDir, "$profile/profile.json")
    if (!manifest.exists()) return null
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parse(manifest) as Map<String, Any?>
        (parsed["world"] as? String)?.takeIf { it.isNotBlank() }
    }.getOrElse { error ->
        println("[profil $profile] profile.json illisible (${error.message}) : monde de départ par ordre alphabétique")
        null
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 3 — IDENTITÉ DU MOD
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Ces trois propriétés définissent l'identité de l'artefact produit. Elles sont
 *  lues depuis gradle.properties, pas d'ici : la version se monte à UN seul
 *  endroit, et `processResources` l'injecte dans `fabric.mod.json`.
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
version = project.property("mod_version").toString()
group = project.property("maven_group").toString()
base { archivesName.set(project.property("archives_base_name") as String) }

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 4 — CONFIGURATIONS DE DÉPENDANCES
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
 *  │  facultatif, section 9). Filtré : includeGroup("me.shedaniel.cloth").     │
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
 *  stratégie Carpet de `00-documentation/readme - Comment s'y prendre avec les
 *  tests.md`.
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
    group = "travellingdimension-dev"
    description = "Liste les dépôts de dépendances effectifs du projet, les déclarés ici comme ceux que Loom pose"
    doLast { repositories.forEach { repo -> println("${repo.name.padEnd(32)} ${(repo as? org.gradle.api.artifacts.repositories.MavenArtifactRepository)?.url ?: ""}") } }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 5 — LES DEUX ÉTAGES DE TEST & LES DÉPENDANCES
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
 *  les configurations. La reprise du classpath est en section 8, après le bloc
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
 *  SECTION 6 — CONFIGURATION JAVA
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  - toolchain            : force Gradle à utiliser un JDK précis (Java 25 ici).
 *                           Si le JDK n'est pas installé localement, Gradle peut
 *                           le télécharger automatiquement via les toolchain resolvers.
 *
 *  - withSourcesJar()     : génère automatiquement un JAR de sources (-sources.jar)
 *                           lors du build. Utile pour les IDE et la publication Maven.
 * ════════════════════════════════════════════════════════════════════════════════
 */
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 7 — CONFIGURATION LOOM
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
 *  client, et c'est pour cela que la section 8 donne à `testMC` le classpath des
 *  DEUX source sets.
 *
 *  ── mods { } ────────────────────────────────────────────────────────────────
 *  Regroupe `main` et `client` sous le MÊME identifiant de mod. Sans ce bloc, Loom
 *  les traiterait comme deux mods distincts et fabriquerait de faux conflits de
 *  chargement de classes en développement.
 *
 *  ── runs { } ────────────────────────────────────────────────────────────────
 *  Deux environnements, vanilla purs. C'est là qu'on vérifie ce que voit un joueur
 *  qui n'a QUE ce mod.
 * ════════════════════════════════════════════════════════════════════════════════
 */
loom {
    splitEnvironmentSourceSets()

    mods {
        register("travellingdimension") {
            sourceSet("main")
            sourceSet("client")
        }
    }

    /*
    Deux environnements, vanilla pur : aucun mod tiers, Loom charge le mod depuis le
    classpath. Les tests avec mods se font dans l'instance Prism « modded »
    (gradlew deployToPrism), jamais ici.
    */
    runs {
        named("client") {
            runDirectory.set(layout.projectDirectory.dir("run/client"))
        }
        named("server") {
            runDirectory.set(layout.projectDirectory.dir("run/server"))
        }
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 8 — LE CLASSPATH DES DEUX ÉTAGES DE TEST
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  ⚠  CETTE SECTION DOIT RESTER APRÈS LE BLOC `loom` DE LA SECTION 7.
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
 *  lanceur est déclaré explicitement en section 5.
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
 *  SECTION 9 — ÉCRAN DE CONFIGURATION EN JEU, DÉPENDANCES FACULTATIVES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  ⚠  DÉCLARÉES ICI ET PAS EN SECTION 5, et ce n'est pas un choix de rangement :
 *  la configuration `clientCompileOnly` N'EXISTE PAS avant que
 *  `splitEnvironmentSourceSets()` (section 7) ait été appelé. Remonter ce bloc casse le build.
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
 *  SECTION 10 — PUBLICATION MAVEN
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Publication locale de l'artefact. La distribution publique du mod, elle, ne passe
 *  pas par ici : elle se fait à la main sur Modrinth et CurseForge, en suivant la
 *  recette de `05-releases-and-distribution`.
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
 *  SECTION 11 — PRÉPARATION DES ENVIRONNEMENTS DE DÉVELOPPEMENT
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Une fabrique de tâches, paramétrée par (environnement, profil, sous-dossier de
 *  `run/`). Le client se prépare sur le profil `vanilla` de l'entrepôt, dont les
 *  options de jeu servent aussi au dev ; le serveur prend le profil `dev` et son
 *  server.properties taillé pour les tests (RCON, watchdog coupé, jamais de pause).
 *
 *  Elle produit `syncClientConfigs` et `syncServerConfigs`, branchées sur
 *  `runClient` et `runServer` en 12.3.
 *
 *  ── LE MARQUEUR, ET POURQUOI IL A DEUX CONDITIONS ───────────────────────────
 *  `.setup-done` empêche d'écraser ce qui a été réglé en jeu. Mais on vérifie AUSSI
 *  qu'un fichier clé est présent : si l'environnement a été vidé à la main, le
 *  marqueur seul empêcherait la remise en place, Minecraft régénérerait un
 *  `eula=false` et le serveur s'arrêterait aussitôt sans dire pourquoi.
 *
 *  ── LE DOSSIER `mods` DE `run/` N'EST PAS GÉRÉ ──────────────────────────────
 *  Un jar déposé à la main y reste. Le développement n'en a besoin d'aucun.
 * ════════════════════════════════════════════════════════════════════════════════
 */
fun capitalized(text: String) = text.replaceFirstChar { it.uppercase() }

fun registerSyncConfigs(env: String, profile: String, runSub: String): TaskProvider<Task> {
    val suffix = if (profile == "modded") "Modded" else ""
    val favBase = File(favoritesDir, profile)
    val envRun = runDir.dir(runSub)

    return tasks.register("sync${capitalized(env)}Configs$suffix") {
        group = "travellingdimension-setup"
        description = "Prépare run/$runSub depuis l'entrepôt S:\\18 (profil $profile)"

        /* Deux conditions, pas une : voir « LE MARQUEUR, ET POURQUOI IL A DEUX CONDITIONS » dans le chapeau. */
        val markerFile = envRun.file(".setup-done").asFile
        val keyFile = envRun.file(if (env == "client") "options.txt" else "server.properties").asFile
        onlyIf { !markerFile.exists() || !keyFile.exists() }

        doLast {
            envRun.asFile.mkdirs()

            if (!favoritesDir.exists()) {
                println("[$runSub] entrepôt S:\\18 introuvable (disque débranché ?) : Minecraft générera ses propres réglages")
            }

            if (env == "client") {
                copy {
                    from(File(favBase, "client/options.txt"))
                    into(envRun)
                }
                warehouseWorlds().forEach { (name, world) ->
                    copy {
                        from(world)
                        into(envRun.dir("saves/$name"))
                    }
                }
                copy {
                    from(File(favBase, "client/resourcepacks"))
                    into(envRun.dir("resourcepacks"))
                }
                copy {
                    from(File(favBase, "client/shaderpacks"))
                    into(envRun.dir("shaderpacks"))
                }
            } else {
                copy {
                    from(File(favBase, "server/server.properties"))
                    from(File(favBase, "server/eula.txt"))
                    into(envRun)
                }
                if (!File(favBase, "server/server.properties").exists()) {
                    println("[$runSub] pas de server.properties dans l'entrepôt (${File(favBase, "server").path}) : le serveur générera le sien")
                }
                /*
                Sans eula.txt le serveur s'arrête aussitôt après l'init des mods, et le
                message de Minecraft n'explique pas d'où le fichier aurait dû venir.
                */
                if (!File(favBase, "server/eula.txt").exists() && !envRun.file("eula.txt").asFile.exists()) {
                    println("[$runSub] ATTENTION : pas d'eula.txt dans l'entrepôt (${File(favBase, "server").path}) : le serveur refusera de démarrer")
                }

                /*
                Le monde de départ devient `world`, le nom attendu par
                server.properties. Celui que le profil déclare, sinon le premier par
                ordre alphabétique.
                */
                val worlds = warehouseWorlds()
                val declared = declaredWorld(profile)
                val startingMap = when {
                    declared == null -> worlds.minByOrNull { it.first }
                    else -> worlds.firstOrNull { it.first == declared } ?: run {
                        println("[$runSub] profile.json déclare le monde \"$declared\" mais il est absent de ${warehouseMapsDir.path} : repli sur l'ordre alphabétique")
                        worlds.minByOrNull { it.first }
                    }
                }

                if (startingMap != null) {
                    val origin = if (startingMap.first == declared) "déclaré par le profil" else "premier par ordre alphabétique"
                    println("[$runSub] monde de départ : ${startingMap.first} -> world ($origin)")
                    copy {
                        from(startingMap.second)
                        into(envRun.dir("world"))
                    }
                } else {
                    println("[$runSub] aucun monde dans S:\\18\\05-maps : le serveur en générera un")
                }
            }

            // Configs de l'entrepôt : communes puis spécifiques à l'environnement
            copy {
                from(File(favBase, "common/config"))
                from(File(favBase, "$env/config"))
                into(envRun.dir("config"))
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
            }

            markerFile.writeText("Environnement $env préparé le ${LocalDateTime.now()} (profil $profile)\nSupprimer ce fichier (ou lancer gradlew resetDevEnvs) pour re-synchroniser.\n")
            println("[$runSub] environnement prêt (profil $profile)")
        }
    }
}

// Les deux environnements : le client sur le profil vanilla, le serveur sur le profil dev
val syncClientConfigs = registerSyncConfigs("client", "vanilla", "client")
val syncServerConfigs = registerSyncConfigs("server", "dev", "server")

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 12 — LES TÂCHES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Les tâches propres au projet portent deux groupes :
 *
 *      travellingdimension-setup   préparer un environnement de développement
 *      travellingdimension-dev     compiler, déployer, diagnostiquer
 *
 *  ┌───────────────────────────────────────────────────────────────────────────┐
 *  │  12.1  compilations, jar, ressources                                      │
 *  │  12.2  les deux étages de test                                            │
 *  │  12.3  branchements sur runClient et runServer                            │
 *  │  12.4  remise à zéro des environnements                                   │
 *  │  12.5  déploiements : serveur dédié, puis instance PrismLauncher          │
 *  │  12.6  préparation de l'instance serveur locale                           │
 *  └───────────────────────────────────────────────────────────────────────────┘
 * ════════════════════════════════════════════════════════════════════════════════
 */
tasks {

    /**
     *  ── 12.1 — Compilation, jar et ressources ──────────────────────────────
     *
     *  L'encodage UTF-8 est forcé des deux côtés, la compilation Java ici et le
     *  filtrage des ressources plus bas : sans lui, chacun suit l'encodage du
     *  système, et les accents de ce projet sortent en charabia sur une machine
     *  autrement réglée.
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
     *  ── 12.2 — Les deux étages de test ─────────────────────────────────────
     *
     *  `test` est celui de Gradle, `testMC` est enregistrée ici parce qu'elle a son
     *  propre source set, donc son propre classpath. Elle est branchée sur `check`,
     *  ce qui fait que `gradlew build` joue bien les DEUX étages.
     *
     *  Le classpath de chacune est réglé en section 8, pas ici.
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

    /**
     *  ── 12.3 — Branchement sur les tâches de lancement ─────────────────────
     *
     *  Lancer un run prépare son environnement d'abord. Le marqueur `.setup-done`
     *  fait que la préparation ne coûte qu'une fois.
     */
    named("runClient") { dependsOn(syncClientConfigs) }
    named("runServer") { dependsOn(syncServerConfigs) }

    /**
     *  ── 12.4 — Remise à zéro ───────────────────────────────────────────────
     *
     *  Deux portées volontairement distinctes : `resetDevEnvs` refait les réglages
     *  et GARDE les mondes, `resetDevWorlds` jette les mondes. Les confondre ferait
     *  perdre un terrain d'essai en voulant corriger un fichier de configuration.
     */
    register<Delete>("resetDevEnvs") {
        group = "travellingdimension-setup"
        description = "Force la re-synchronisation des deux environnements (garde les mondes)"
        listOf("client", "server").forEach { sub ->
            delete(runDir.file("$sub/.setup-done"), runDir.dir("$sub/config"))
        }
        doLast { println("marqueurs supprimés : la prochaine exécution re-synchronisera") }
    }

    register<Delete>("resetDevWorlds") {
        group = "travellingdimension-setup"
        description = "Supprime les mondes de dev des deux environnements"
        delete(runDir.dir("client/saves"), runDir.dir("server/world"))
        doLast { println("mondes de dev supprimés : ils seront régénérés au prochain lancement") }
    }

    /**
     *  ── 12.5 — Déploiement ─────────────────────────────────────────────────
     *
     *  Deux cibles, et elles ne reçoivent PAS la même chose. C'est la distinction
     *  la plus facile à casser de ce fichier.
     *
     *  ┌─ serveur dédié ───────────────────────────────────────────────────────┐
     *  │  Pas de modpack : il reçoit le jar ET ses dépendances runtime.        │
     *  └───────────────────────────────────────────────────────────────────────┘
     *
     *  ┌─ instance PrismLauncher « modded » ───────────────────────────────────┐
     *  │  Elle porte son propre modpack, Fabric API et FLK compris : elle ne   │
     *  │  reçoit QUE le jar du mod. Y pousser nos versions entrerait en        │
     *  │  conflit avec les siennes.                                            │
     *  └───────────────────────────────────────────────────────────────────────┘
     */
    // 26.2 n'est plus obfusqué : Loom ne produit plus de remapJar, ce jar est le livrable final.
    val jarFinal = named<org.gradle.jvm.tasks.Jar>("jar")

    register<Copy>("deployToServerPur") {
        group = "travellingdimension-dev"
        description = "Compile le mod et l'installe (avec ses dépendances) dans 02-local-server-instances/server-pur/server"

        dependsOn(jarFinal)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        /*
        Le jar du mod est écrasé à chaque déploiement (nom fixe), mais les dépendances
        gardent leur nom versionné : sans ce ménage, un bump de version laisserait
        l'ancienne à côté de la nouvelle, et Fabric refuse de démarrer sur un mod en double.
        */
        doFirst {
            serverPurDir.dir("mods").asFile.listFiles()
                ?.filter { it.name.startsWith("fabric-api-") || it.name.startsWith("fabric-language-kotlin-") }
                ?.forEach { it.delete() }
        }

        from(jarFinal.flatMap { it.archiveFile }) {
            rename { "travellingdimension-dev-latest.jar" }
        }

        /*
        Fabric API et FLK sont résolus ICI, à la demande, depuis le cache de Gradle :
        pas de panier déclaré en amont pour deux jars que seule cette tâche consomme.
        isTransitive = false : ces deux jars et RIEN d'autre, sinon le serveur
        recevrait tout leur graphe de dépendances.
        */
        from(configurations.detachedConfiguration(mc.fabric.api.get(), mc.fabric.language.kotlin.get()).apply { isTransitive = false })

        into(serverPurDir.dir("mods"))

        doLast { println("mod déployé vers ${serverPurDir.dir("mods").asFile}") }
    }

    /* La seconde cible du 12.5 : QUE le jar, jamais les dépendances. */
    register<Copy>("deployToPrism") {
        group = "travellingdimension-dev"
        description = "Compile le mod et l'installe dans les mods de l'instance Prism (prism_instance_dir)"

        dependsOn(jarFinal)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        from(jarFinal.flatMap { it.archiveFile }) {
            rename { "travellingdimension-dev-latest.jar" }
        }
        into(File(prismInstanceDir, "mods"))

        doFirst {
            check(prismInstanceDir.path.isNotEmpty() && prismInstanceDir.exists()) {
                "Instance Prism introuvable : \"$prismInstanceDir\". Poser prism_instance_dir dans local.properties, à la racine du projet (voir README)."
            }
        }
        doLast {
            val noms = File(prismInstanceDir, "mods").listFiles()?.map { it.name }.orEmpty()
            listOf("fabric-api", "fabric-language-kotlin").forEach { dep ->
                if (noms.none { it.startsWith(dep) }) println("[deployToPrism] ATTENTION : $dep semble absent de l'instance (le mod ne démarrera pas sans lui)")
            }
            println("mod déployé vers ${File(prismInstanceDir, "mods")}")
        }
    }

    /**
     *  ── 12.6 — Préparation de l'instance serveur locale ────────────────────
     *
     *  Même profil que le serveur de dev : server-pur sert surtout au
     *  développement. EXCLUDE : un fichier déjà présent dans l'instance n'est
     *  jamais réécrasé ; pour repartir du profil, supprimer le fichier puis
     *  relancer la tâche.
     */
    register<Copy>("setupServerPur") {
        group = "travellingdimension-setup"
        description = "Installe properties et eula dans l'instance de serveur locale (depuis l'entrepôt S:\\18)"

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE // ne réécrase pas l'existant

        from(File(favoritesDir, "dev/server/server.properties"))
        from(File(favoritesDir, "dev/server/eula.txt"))
        into(serverPurDir)

        doLast { println("instance de serveur préparée : ${serverPurDir.asFile}") }
    }
}
