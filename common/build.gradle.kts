@file:Suppress("AvoidDuplicateDependencies", "UnstableApiUsage") // Ajouté car IntelliJ dit "Dependency 'org.junit.jupiter:junit-jupiter:6.1.3' is declared multiple times" et le souligne. C'est ok, on garde ça !

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  MODULE common — le code partagé par toutes les versions du jeu
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Tout le mod, sauf ce qu'une version du jeu ne partage pas avec les autres : le
 *  code serveur et client, les mixins, les ressources, et les trois étages de test.
 *  Il se compile UNE fois, contre la dernière release servie, et ne livre aucun
 *  jar : ce sont les modules de version qui assemblent le mod, chacun pour son jeu.
 *
 *  ┌───────────────────────────────────────────────────────────────────────────┐
 *  │  1  la version de compilation et les dépendances                          │
 *  │  2  les étages de test : le 0 joué ici, le 1 et le 2 rejoués par version  │
 *  │  3  le classpath des étages 0, 1 et 2                                     │
 *  │  4  l'écran de configuration en jeu, dépendances facultatives             │
 *  │  5  ce que common publie pour les modules de version                      │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  Plugins :
 *    - travellingdimension.loom-module   Loom, Kotlin et sa sérialisation, Java,
 *                                        dépôts, identité
 * ════════════════════════════════════════════════════════════════════════════════
 */

plugins {
    id("travellingdimension.loom-module")
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 1 — LA VERSION DE COMPILATION ET LES DÉPENDANCES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  common se compile contre la dernière release de Minecraft servie, 26.3, et lit
 *  son catalogue, `mc263` : le code commun suit toujours la dernière release,
 *  jamais un snapshot. Ce qu'il nomme doit exister, sous le même nom, dans toutes
 *  les versions servies ; ce qu'une version plus ancienne n'a pas, ou nomme
 *  autrement, passe par le pont de version (`GameVersionBridge`), que chaque module
 *  de version implémente contre son jeu. Compilé une fois, il tourne ensuite sur
 *  chacune, et les étages 1 et 2 de chaque module de version le vérifient. À la
 *  release suivante, common monte au catalogue de celle-ci.
 *
 *  - minecraft                  : le jeu lui-même, fourni et câblé par Loom.
 *  - fabric-loader              : le chargeur : entrypoints et annotations de mixin.
 *  - fabric-api                 : les API haut niveau : events, registres, réseau.
 *                                 Loom en tire aussi les interfaces injectées
 *                                 (section 3 du plugin loom-module).
 *  - fabric-language-kotlin     : l'adaptateur Kotlin de Fabric ; il embarque le
 *                                 runtime Kotlin, kotlin-reflect,
 *                                 kotlinx-serialization et kotlinx-datetime.
 *  - kotlinx-serialization-json : la configuration en JSON. Fourni au runtime par
 *                                 fabric-language-kotlin, déclaré quand même pour
 *                                 compiler avec une version contrôlée.
 *  - storify                    : les fichiers JSON du mod, config.json et
 *                                 dev.json : l'écriture atomique, la création
 *                                 depuis les défauts ou depuis une ressource, le
 *                                 décodage qui nomme la ligne fautive. Ce sont les
 *                                 modules de version qui l'embarquent.
 *
 *  Aucune de ces dépendances ne sort de common : ses classes seules partent vers les
 *  modules de version (section 5).
 * ════════════════════════════════════════════════════════════════════════════════
 */
dependencies {
    minecraft(mc263.minecraft)

    implementation(mc.fabric.loader)
    implementation(mc263.fabric.api)
    implementation(mc.fabric.language.kotlin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.storify)
}

/*
Les ressources de common ne passent pas par ce module : chaque module de version
les traite avec les siennes, parce que fabric.mod.json s'y remplit pour sa version,
et que le mod doit les trouver dans un seul dossier. Les traiter aussi ici poserait
sur le classpath un second fabric.mod.json, gabarit non rempli.

common ne livre pas de jar : ses classes entrent dans celui de chaque version.

Les runs `client` et `server` que Loom crée pour tout module n'ont pas de sens ici,
sans mod ni fabric.mod.json : ils restent, inertes, et n'apparaissent pas dans
IntelliJ, Loom n'y générant les configurations de lancement que pour la racine.
*/
tasks.processResources { enabled = false }
tasks.jar { enabled = false }
tasks.named("sourcesJar") { enabled = false }

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 2 — LES TROIS ÉTAGES DE TEST
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Trois source sets de test, tous ici. Entre les deux premiers, c'est le
 *  COMPILATEUR qui tient la frontière.
 *
 *  ┌─ src/test ─ étage 0, joué ici ────────────────────────────────────────────┐
 *  │  La logique pure : l'arithmétique des coordonnées, les défauts de la      │
 *  │  configuration, la résolution et la préparation de la génération de       │
 *  │  VOYAGE, le renommage d'un identifiant par une copie, le garde-fou des    │
 *  │  copies. AUCUN accès à Minecraft, et ce n'est pas une convention : un     │
 *  │  import du jeu ne compile pas. Indépendant de la version du jeu, il ne    │
 *  │  se joue qu'une fois.                                                     │
 *  │  Tâche : gradlew :common:test                 Mesuré : 59 tests, 0,31 s   │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ┌─ src/testMC ─ étage 1, rejoué par chaque module de version ───────────────┐
 *  │  Le jeu amorcé par fabric-loader-junit : registres, blocs, et tout ce qui │
 *  │  ne fait que MENTIONNER un type du jeu, comme BlockPos. Le moteur des     │
 *  │  copies s'y éprouve contre le datapack vanilla de chaque version.         │
 *  │  Compilé ici, ses classes partent dans `testMCElements` (section 5).      │
 *  │  Tâche : gradlew :mc-<version>:testMC         Mesuré : 42 tests, 6 à 7 s  │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ┌─ src/gametest ─ étage 2, rejoué par chaque module de version ─────────────┐
 *  │  Un vrai serveur GameTest, sans fenêtre : les mixins appliqués, les       │
 *  │  traversées entre dimensions, la pose d'un portail. Le mod de test        │
 *  │  s'assemble ici en un jar, `gametestElements` (section 5). Deux runs,     │
 *  │  un mode de worldgen chacun : les fixtures du second sont dans            │
 *  │  src/gametest/fixtures.                                                   │
 *  │  Tâches : gradlew :mc-<version>:runGameTest, runGameTestVanilla           │
 *  │  Mesuré : 19 tests et 30 à 32 s par run                                   │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  ── CE QUE L'ÉTAGE 1 NE DONNE PAS : LES MIXINS ──────────────────────────────
 *  Mesuré, et refait sur deux cadres de test : `PortalShape` chargée depuis un test
 *  ne porte AUCUNE méthode de synthèse du mixin. Le log d'exécution montre pourtant
 *  le sous-système Mixin s'initialiser (Service=Knot/Fabric) : le mécanisme exact
 *  reste à élucider, seul le résultat mesuré fait foi. Tout ce qui passe par un
 *  mixin se vérifie en jeu, par script, ou par lecture du bytecode.
 *
 *  ── COUPER L'HÉRITAGE NE SUFFIT PAS ─────────────────────────────────────────
 *  Les deux lignes `setExtendsFrom(emptyList())` ci-dessous coupent ce que Gradle
 *  fait hériter par défaut à `testImplementation`. Elles sont NÉCESSAIRES, mais pas
 *  SUFFISANTES : Loom pose Minecraft directement sur le source set, sans passer par
 *  les configurations. La reprise du classpath est en section 3, et c'est là que la
 *  frontière devient réelle.
 *
 *  ── LES DÉPENDANCES DES TESTS ───────────────────────────────────────────────
 *  - junit-jupiter              : l'écriture et l'exécution des tests. L'agrégat
 *                                 porte l'api, les tests paramétrés et le moteur :
 *                                 rien d'autre à déclarer.
 *  - junit-platform-launcher    : le lanceur, étage 0 : l'héritage coupé perd celui
 *                                 que Gradle aurait posé sur le classpath
 *                                 d'exécution.
 *  - fabric-gametest-api        : le module GameTest de Fabric API, absent du jar
 *                                 agrégé : de quoi compiler l'étage 2. Chaque
 *                                 module de version pose le sien à l'exécution.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val testMC: SourceSet = sourceSets.create("testMC")
val gametest: SourceSet = sourceSets.create("gametest")

configurations {
    named("testImplementation") { setExtendsFrom(emptyList()) }
    named("testRuntimeOnly") { setExtendsFrom(emptyList()) }
}

dependencies {
    // ── Étage 0 : la logique pure, aucune dépendance au jeu ──────────────────
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // ── Étage 1 : de quoi compiler ; fabric-loader-junit vient au runtime, par module ──
    "testMCImplementation"(libs.junit.jupiter)

    // ── Étage 2 : de quoi compiler le mod de test ────────────────────────────
    "gametestCompileOnly"(mc263.fabric.gametest.api)
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 3 — LE CLASSPATH DES ÉTAGES 0, 1 ET 2
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Loom ajoute Minecraft DIRECTEMENT sur les source sets de test, sans passer par
 *  l'héritage des configurations, et il le fait en s'appliquant : ici, à
 *  l'application du plugin de convention, donc avant ce bloc. Couper `extendsFrom`
 *  ne retire donc rien ; reprendre le classpath ici, après lui, le fait.
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
 *  l'exécution : la tâche démarre alors sans savoir lancer quoi que ce soit, avec un
 *  message qui ne dit pas d'où vient le manque. C'est aussi pour cela que le lanceur
 *  est déclaré explicitement en section 2.
 *
 *  ── `testMC` ET `gametest` : `main` ET `client` ─────────────────────────────
 *  Ils compilent contre le code des deux source sets, et contre leurs classpath.
 *  Leur exécution, elle, se bâtit dans chaque module de version, contre son jeu.
 * ════════════════════════════════════════════════════════════════════════════════
 */
sourceSets {
    named("test") {
        /* Compilation et exécution se reprennent séparément : voir « LE PIÈGE DES DEUX CLASSPATH » ci-dessus. */
        val isGameJar: (File) -> Boolean = { it.name.startsWith("minecraft-") }
        compileClasspath = configurations["testCompileClasspath"].filter { !isGameJar(it) } + sourceSets["main"].output
        runtimeClasspath = output + sourceSets["main"].output + configurations["testRuntimeClasspath"].filter { !isGameJar(it) }
    }
    listOf(testMC, gametest).forEach { testSourceSet ->
        testSourceSet.compileClasspath += sourceSets["main"].compileClasspath + sourceSets["client"].compileClasspath + sourceSets["main"].output + sourceSets["client"].output
    }
}

tasks.test {
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

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 4 — L'ÉCRAN DE CONFIGURATION EN JEU, DÉPENDANCES FACULTATIVES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  ── `compileOnly`, C'EST-À-DIRE VRAIMENT FACULTATIF ─────────────────────────
 *  Ni embarquées, ni exigées au runtime, ni chargées par les runs vanilla, qui
 *  restent purs. Le mod fonctionne sans : l'écran apparaît seulement là où Mod Menu
 *  et Cloth Config sont installés. Terrain d'essai : l'instance Prism « modded »,
 *  qui les porte déjà.
 *
 *  ── NON TRANSITIF ───────────────────────────────────────────────────────────
 *  Ces deux jars suffisent à compiler. Leurs dépendances, dont une AUTRE version de
 *  Fabric API, n'ont rien à faire sur le classpath et masqueraient la nôtre.
 * ════════════════════════════════════════════════════════════════════════════════
 */
configurations.named("clientCompileOnly") { isTransitive = false }

dependencies {
    add("clientCompileOnly", mc263.modmenu)
    add("clientCompileOnly", mc263.cloth.config)
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 5 — CE QUE COMMON PUBLIE POUR LES MODULES DE VERSION
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Trois configurations consommables, sans dépendances : un module de version les
 *  lit par `project(path = ":common", configuration = ...)` et n'en reçoit que des
 *  fichiers, jamais le jeu ni la Fabric API de common.
 *
 *  - commonClasses       : les classes de main et de client : de quoi compiler,
 *                          lancer et assembler le mod (plugin de version, sections
 *                          2 et 5).
 *  - testMCElements      : les classes de l'étage 1, rejouées contre chaque
 *                          version.
 *  - gametestElements    : le mod de test, assemblé en jar : fabric.mod.json, tests
 *                          et mixin ensemble, il se pose tel quel sur le classpath
 *                          d'un serveur GameTest.
 *
 *  Chaque dossier de classes (un pour Java, un pour Kotlin, par source set) part en
 *  artefact à part, avec la sortie du source set qui le produit : le module de
 *  version qui le lit déclenche ainsi la compilation de common. Les dossiers sont
 *  connus dès la configuration ; leur contenu, lui, ne l'est qu'après la
 *  compilation, et rien ici ne le lit avant. Mesuré : publier
 *  `classesDirs.elements` d'un bloc échoue, parce que Gradle en interroge le
 *  contenu avant que compileKotlin ait tourné.
 * ════════════════════════════════════════════════════════════════════════════════
 */
fun ConfigurationPublications.classesOf(vararg sourceSetsToPublish: SourceSet) {
    sourceSetsToPublish.forEach { sourceSet ->
        sourceSet.output.classesDirs.files.forEach { classesDir -> artifact(classesDir) { builtBy(sourceSet.output) } }
    }
}

configurations.consumable("commonClasses") {
    outgoing.classesOf(sourceSets["main"], sourceSets["client"])
}

configurations.consumable("testMCElements") {
    outgoing.classesOf(testMC)
}

val gametestJar = tasks.register<Jar>("gametestJar") {
    description = "Assemble le mod de test de l'étage 2, joué par le serveur GameTest de chaque module de version"
    archiveClassifier = "gametest"
    from(gametest.output)
}

configurations.consumable("gametestElements") {
    outgoing.artifact(gametestJar)
}
