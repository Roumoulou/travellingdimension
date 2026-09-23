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
import java.util.zip.ZipFile

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
 *  vit hors du classeur et se règle donc dans `machine.properties`, JAMAIS versionné.
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
 *  - prismInstanceDir     : l'instance PrismLauncher MDTK (import du .mrpack core-solo).
 *                           Elle sert DEUX fois : source des mods des runs moddés (11.2)
 *                           et cible de deployToPrism, qui n'y pousse que le jar. Chemin
 *                           réglé par prism_instance_dir dans `machine.properties`, un
 *                           fichier propre à la machine et jamais versionné. Absente, la
 *                           configuration passe quand même : chaque tâche concernée le
 *                           dit, et les runs moddés démarrent avec ce qu'ils ont.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val targetJavaVersion = libs.versions.java.get().toInt()
val serverInstancesDir = layout.projectDirectory.dir("../../02-local-server-instances")
val runDir = layout.projectDirectory.dir("run")
val serverPurDir = serverInstancesDir.dir("server-pur/server")
/*
Le chemin de l'instance Prism est propre à CHAQUE machine : il ne peut donc pas vivre
dans `gradle.properties`, qui est versionné, ni dans le gradle.properties utilisateur,
que setup-pc.ps1 réécrit depuis son modèle SkyChest. Il vit dans `machine.properties`,
nommé ainsi le 2026-09-12 : l'ancien nom, local.properties, est le marqueur historique
des projets Android, et le plugin Android d'IntelliJ revendiquait le projet à cause de
lui, sabotant la synchronisation Gradle. PackTool, lui, garde un local.properties pour
sa clé d'API CurseForge.
*/
fun localProperty(cle: String): String? {
    val fichier = layout.projectDirectory.file("machine.properties").asFile
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
 *  ── UNE MAP = UN DOSSIER-MONDE, RANGÉ PAR CATÉGORIE ─────────────────────────
 *  Une map de l'entrepôt est le monde lui-même : `level.dat` à sa racine, et son
 *  readme dans le même dossier s'il existe. Le nom de la map est le nom du
 *  dossier. L'entrepôt classe ses maps par provenance (`homemade\`,
 *  `downloaded\`) : un dossier de premier niveau SANS `level.dat` est une
 *  catégorie, et ce sont ses enfants qu'on scanne. Même convention que le
 *  Maps.kt de PackTool, et les catégories restent transparentes : profile.json
 *  désigne une map par son NOM seul, jamais par sa catégorie. Les dossiers
 *  `_...` (archives, corbeilles) sont ignorés, et tout le reste aussi.
 * ════════════════════════════════════════════════════════════════════════════════
 */
val warehouseMcVersion = "26.2"
val favoritesDir = File("S:/18/00-my-minecraft-favorites-configs/$warehouseMcVersion")
val warehouseMapsDir = File("S:/18/05-maps/$warehouseMcVersion")

fun estUneMap(dossier: File): Boolean = File(dossier, "level.dat").exists()

fun warehouseWorlds(): List<Pair<String, File>> {
    val racine = warehouseMapsDir.listFiles { f: File -> f.isDirectory && !f.name.startsWith("_") }?.toList() ?: emptyList()
    val (maps, categories) = racine.partition(::estUneMap)
    val dansCategories = categories.flatMap { it.listFiles { f: File -> f.isDirectory && estUneMap(f) }?.toList() ?: emptyList() }
    return (maps + dansCategories).map { it.name to it }
}

/*
LA SÉLECTION DES MAPS DE DEV. L'entrepôt porte plus de mondes que le mod n'en
utilise : `dev_maps` liste, par nom exact séparé de virgules, celles que les CLIENTS
de dev reçoivent dans leurs saves. gradle.properties décide pour le projet,
machine.properties surcharge pour le poste. Clé absente ou vide : toutes. Un nom
introuvable est signalé, jamais fatal, et le monde du SERVEUR n'en dépend pas : il
reste déclaré par le profil de l'entrepôt. Lue à la CONFIGURATION : toucher
`project` pendant une tâche est déprécié (voir la note de la section 12.1).
*/
val devMaps: List<String>? = (localProperty("dev_maps") ?: project.findProperty("dev_maps") as? String)
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

fun selectedWorlds(): List<Pair<String, File>> {
    val toutes = warehouseWorlds()
    val declarees = devMaps ?: return toutes
    /* Entrepôt absent ou vide : le message « entrepôt introuvable » a déjà tout dit,
       inutile de signaler chaque nom de la sélection comme introuvable. */
    if (toutes.isEmpty()) return toutes
    val parNom = toutes.toMap()
    val (trouvees, introuvables) = declarees.partition { it in parNom }
    introuvables.forEach { println("[maps] dev_maps déclare « $it » : introuvable dans l'entrepôt, ignorée") }
    return trouvees.map { it to parNom.getValue(it) }
}

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
 *  Ces quatre propriétés définissent l'identité du mod et de l'artefact produit.
 *  Elles sont lues depuis gradle.properties, pas d'ici : chacune se monte à UN
 *  seul endroit, et `processResources` injecte ce qui doit l'être dans
 *  `fabric.mod.json`.
 *
 *  - modId                : l'identifiant Fabric du mod, clé mod_id. Déclaré UNE
 *                           fois : processResources l'injecte dans fabric.mod.json,
 *                           et le build s'en sert partout (exclusion de la sync
 *                           11.2, noms des jars de déploiement, bloc mods de Loom,
 *                           groupes de tâches). Pour dériver un nouveau projet de
 *                           celui-ci : changer mod_id ici, puis renommer à la main
 *                           ce qui vit dans les SOURCES (packages, fichier
 *                           <id>.mixins.json, dossier assets/<id>/).
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
    group = "$modId-dev"
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
/*
LES LOGS DES RUNS, trois clés indépendantes, servies par le dossier `log4j/`. La
config log4j que Loom génère plafonne la console et latest.log à
${sys:fabric.log.level} (info par défaut), et debug.log reçoit TOUJOURS tout ;
`log4j/levels.xml`, fusionné par-dessus (voir loom.log4jConfigs ci-dessous), donne au
logger du mod ses propres plafonds, et un `log4j/format-<nom>.xml` peut remplacer
l'habillage de la console. D'où : `dev_log_level` règle la console de tout le monde,
`dev_mod_log_level` celle du SEUL mod (son debug ou son trace sans le bruit des
autres), `dev_log_format` choisit la mise en forme (vide = celle de Loom ; `compact`
ou `details`, héritées d'Enhanced Terminal Logging). gradle.properties décide,
machine.properties surcharge par poste, -P dépanne ponctuellement. Lues à la
CONFIGURATION, comme dev_maps.
*/
val devLogLevel: String? = (localProperty("dev_log_level") ?: project.findProperty("dev_log_level") as? String)?.takeIf { it.isNotBlank() }
val devModLogLevel: String? = (localProperty("dev_mod_log_level") ?: project.findProperty("dev_mod_log_level") as? String)?.takeIf { it.isNotBlank() }
val devLogFormat: String? = (localProperty("dev_log_format") ?: project.findProperty("dev_log_format") as? String)?.takeIf { it.isNotBlank() }

loom {
    splitEnvironmentSourceSets()

    /* Le montage des niveaux, toujours ; puis le format de console choisi, s'il y en a un : voir les en-têtes des fichiers. */
    log4jConfigs.from(file("log4j/levels.xml"))
    devLogFormat?.let { nom ->
        val fichier = file("log4j/format-$nom.xml")
        require(fichier.isFile) { "dev_log_format=$nom : log4j/format-$nom.xml introuvable (formats disponibles : compact, details)" }
        log4jConfigs.from(fichier)
    }

    mods {
        register(modId) {
            sourceSet("main")
            sourceSet("client")
        }
    }

    /*
    QUATRE environnements, deux par deux.

    Les deux premiers sont VANILLA PURS : aucun mod tiers, Loom charge le mod depuis
    le classpath. Ce sont eux la référence, celle qui dit ce que voit un joueur
    n'ayant QUE ce mod. Ils ne changent jamais.

    Les deux suivants portent le NOYAU MDTK, copié depuis l'instance Prism du poste
    par les tâches de la section 11.2 et filtré par side. Ils servent à éprouver le
    mod au milieu de ceux qu'on utilise vraiment, sans quitter Gradle ni lancer
    PrismLauncher.
    */
    runs {
        /* Les deux robinets de niveaux de log, sur les QUATRE runs : voir le commentaire au-dessus du bloc loom. */
        configureEach {
            devLogLevel?.let { systemProperties.put("fabric.log.level", it) }
            devModLogLevel?.let { systemProperties.put("$modId.log.level", it) }
        }
        named("client") {
            runDirectory.set(layout.projectDirectory.dir("run/client"))
        }
        named("server") {
            runDirectory.set(layout.projectDirectory.dir("run/server"))
        }
        create("clientModded") {
            client()
            runDirectory.set(layout.projectDirectory.dir("run/client-modded"))
        }
        create("serverModded") {
            server()
            runDirectory.set(layout.projectDirectory.dir("run/server-modded"))
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
 *  Deux fabriques de tâches, une par nature de contenu.
 *
 *  ┌───────────────────────────────────────────────────────────────────────────┐
 *  │  11.1  les RÉGLAGES DE BASE, depuis l'entrepôt S:\18                      │
 *  │  11.2  les MODS, depuis l'instance Prism MDTK                             │
 *  │  11.3  le CONTENU DU MODPACK : packs, datapacks, réglages                 │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  Les quatre environnements et ce qu'ils reçoivent :
 *
 *      run/client          profil vanilla                       aucun mod
 *      run/server          profil dev                           aucun mod
 *      run/client-modded   profil vanilla   +  noyau MDTK, sides client et both
 *      run/server-modded   profil dev       +  noyau MDTK, sides server et both
 *
 *  Elles produisent huit tâches, branchées sur leur run en 12.3.
 *
 *  ── CE QU'UN CLONE REDONNE, ET CE QU'IL NE REDONNE PLUS ─────────────────────
 *  Le mod et ses runs vanilla se re-préparent depuis un simple clone : Loom
 *  télécharge tout. Les runs moddés, eux, suivent l'instance Prism MDTK du poste
 *  (11.2), comme leurs packs et réglages suivent déjà S:\17 (11.3) : un poste
 *  sans instance a des runs moddés nus, et chaque tâche le dit. Le verrou
 *  versionné qui garantissait les mods depuis un clone a été retiré le
 *  2026-09-12 : il ne se régénérait pas (la commande prévue n'a jamais été
 *  écrite), l'instance, elle, se réimporte en un geste.
 *
 *  ── LE MARQUEUR, ET POURQUOI IL A DEUX CONDITIONS ───────────────────────────
 *  `.setup-done` empêche d'écraser ce qui a été réglé en jeu. Mais on vérifie AUSSI
 *  qu'un fichier clé est présent : si l'environnement a été vidé à la main, le
 *  marqueur seul empêcherait la remise en place, Minecraft régénérerait un
 *  `eula=false` et le serveur s'arrêterait aussitôt sans dire pourquoi.
 *
 *  ── LE DOSSIER `mods` DES RUNS VANILLA N'EST PAS GÉRÉ ───────────────────────
 *  Un jar déposé à la main dans `run/client/mods` y reste : c'est ainsi qu'on isole
 *  un mod suspect. Seuls les runs MODDÉS voient leur `mods/` tenu par le build, et
 *  seul celui-là fait le ménage.
 * ════════════════════════════════════════════════════════════════════════════════
 */
fun capitalized(text: String) = text.replaceFirstChar { it.uppercase() }

/**
 *  ── 11.1 — Les réglages, depuis l'entrepôt ─────────────────────────────────
 *
 *  Le suffixe est DONNÉ et non déduit du profil : `run/client-modded` prend le
 *  même profil `vanilla` que `run/client`, et deux tâches de même nom ne
 *  peuvent pas coexister.
 */
fun registerSyncConfigs(
    env: String,
    profile: String,
    runSub: String,
    suffix: String = "",
    packsDeLEntrepot: Boolean = true,
): TaskProvider<Task> {
    val favBase = File(favoritesDir, profile)
    val envRun = runDir.dir(runSub)

    return tasks.register("sync${capitalized(env)}Configs$suffix") {
        group = "$modId-setup"
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
                selectedWorlds().forEach { (name, world) ->
                    copy {
                        from(world)
                        into(envRun.dir("saves/$name"))
                    }
                }
                /*
                UNE SEULE SOURCE DE PACKS PAR ENVIRONNEMENT. Les runs vanilla les
                prennent à l'entrepôt ; les runs moddés les prennent à MDTK, en
                11.3, parce qu'un run moddé EST MDTK. Sans ce garde-fou, le jour où
                le profil `vanilla` de l'entrepôt recevra des packs de jeu, les runs
                moddés en auraient de deux provenances, et l'ordre de chargement
                d'options.txt ne voudrait plus rien dire.
                */
                if (packsDeLEntrepot) {
                    copy {
                        from(File(favBase, "client/resourcepacks"))
                        into(envRun.dir("resourcepacks"))
                    }
                    copy {
                        from(File(favBase, "client/shaderpacks"))
                        into(envRun.dir("shaderpacks"))
                    }
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

// Les quatre environnements : le client sur le profil vanilla, le serveur sur le profil dev
val syncClientConfigs = registerSyncConfigs("client", "vanilla", "client")
val syncServerConfigs = registerSyncConfigs("server", "dev", "server")
val syncClientConfigsModded = registerSyncConfigs("client", "vanilla", "client-modded", "Modded", packsDeLEntrepot = false)
val syncServerConfigsModded = registerSyncConfigs("server", "dev", "server-modded", "Modded")

/**
 *  ── 11.2 — Les mods, depuis l'instance Prism MDTK ──────────────────────────
 *
 *  La source est l'instance PrismLauncher du poste, un import du `.mrpack`
 *  core-solo de MDTK : PrismLauncher a déjà résolu et téléchargé chaque jar,
 *  empreintes vérifiées, à l'import. Rien à télécharger ni à vérifier ici : on
 *  COPIE, en filtrant par side. Un run moddé EST l'instance MDTK de ce poste,
 *  et la résolution des versions appartient tout entière à la chaîne du
 *  modpack : mdtk-data.json, packwiz, .mrpack, instance.
 *
 *  Le verrou versionné qui jouait ce rôle (`mods-core.lock.json`) a été retiré
 *  le 2026-09-12 : il ne se régénérait pas (la commande prévue n'a jamais été
 *  écrite) et figeait une sortie de MDTK en source de vérité d'un autre dépôt.
 *
 *  ── LE SIDE SE LIT DANS LE JAR ──────────────────────────────────────────────
 *  Le champ `environment` du fabric.mod.json : `*` partout (c'est aussi la
 *  valeur par défaut de la spécification quand le champ manque), `client` ou
 *  `server`. La fiche de curation n'est pas la bonne source ici : elle dit où
 *  un mod est VOULU, le jar dit où il SAIT tourner. Le cas mesuré : Global
 *  Packs, fiché server, se déclare `*`, et le run client en a besoin (serveur
 *  intégré, datapacks globaux) ; le filtre par fiche l'en privait.
 *
 *  Le filtrage reste une nécessité, pas un confort : la majorité du noyau est
 *  client-only, et pousser ces jars sur le serveur de dev le ferait planter au
 *  chargement. `client-modded` reçoit client et `*`, `server-modded` reçoit
 *  server et `*`.
 *
 *  ── TROIS MODS NE SONT JAMAIS COPIÉS ────────────────────────────────────────
 *  `fabric-api` et `fabric-language-kotlin` : déclarés en `implementation` en
 *  section 5, Loom les met déjà sur le classpath d'exécution ; les copier
 *  ferait deux mods de même identifiant et le chargeur refuserait de démarrer.
 *  Mais la vraie raison n'est pas le doublon, c'est la JUSTESSE : un run de
 *  développement s'exécute contre l'API que le mod a COMPILÉE, celle du
 *  catalogue, et non celle que le modpack a choisie, qui monte plus vite.
 *
 *  Et `travellingdimension` LUI-MÊME : deployToPrism pousse
 *  `travellingdimension-dev-latest.jar` dans cette même instance, le copier en
 *  retour ramènerait le mod en double face au classpath, même refus de
 *  démarrer.
 *
 *  Mod Menu et Cloth Config, eux, sont en `clientCompileOnly` (section 9), donc
 *  ABSENTS du classpath d'exécution. Que l'instance les fournisse est un gain :
 *  il rend l'écran de configuration du mod testable dans le run moddé.
 *
 *  ── LES EXCLUSIONS DÉCLARÉES : `dev_mods_exclude` ──────────────────────────
 *  La clé écarte, par identifiant fabric.mod.json, les mods qui cassent les
 *  runs sans casser l'instance : elle reste complète, les runs s'en passent.
 *  Déclarée dans gradle.properties (le pourquoi de chaque entrée y vit),
 *  surchargée par poste dans machine.properties, lue à la CONFIGURATION comme
 *  dev_maps. Un jar déjà posé devient indésirable et le ménage le retire.
 *
 *  ── DÉGRADATION VOULUE ──────────────────────────────────────────────────────
 *  Instance absente (clé non posée, instance pas encore créée, autre machine) :
 *  la tâche le dit et ne touche à RIEN, les jars déjà en place restent. Le run
 *  démarre avec ce qu'il a, au lieu d'échouer. Même philosophie que l'entrepôt
 *  et que PackTool.
 *
 *  ── CE QUI DÉCLENCHE UNE RÉINSTALLATION ─────────────────────────────────────
 *  L'attendu se recalcule depuis l'INSTANCE à chaque lancement : un mod ajouté,
 *  retiré ou monté de version (le nom du jar change) réveille la tâche tout
 *  seul, là où le verrou exigeait une régénération que rien n'outillait. Et
 *  comme avant : un jar attendu manquant ou un jar indésirable la réveillent
 *  aussi. Le marqueur `.mods-core-done` liste les jars posés : c'est la trace
 *  datée de ce qui tournait dans cet environnement.
 */
data class ModInstalle(
    val fichier: String,
    val id: String?,
    val env: String,
    val taille: Long,
)

/* Voir « TROIS MODS NE SONT JAMAIS COPIÉS » dans le chapeau : ces deux-là plus le
   mod lui-même (modId, section 3). Identifiants fabric.mod.json. */
val fournisParLoom = setOf("fabric-api", "fabric-language-kotlin")

/* Voir « LES EXCLUSIONS DÉCLARÉES » dans le chapeau ; le pourquoi de chaque entrée
   vit dans gradle.properties, à côté de la clé. */
val exclusDesRuns: Set<String> = ((localProperty("dev_mods_exclude") ?: project.findProperty("dev_mods_exclude") as? String) ?: "")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

val instanceModsDir = File(prismInstanceDir, "mods")

/*
Deux jars du parc (ETF, EMF) écrivent leur fabric.mod.json avec commentaires et
virgules finales : le parseur LAX de Groovy avale les premiers, la passe d'effacement
les secondes. Mesuré le 2026-09-12 sur 262 jars de trois instances réelles : zéro
illisible. Un jar tout de même illisible est traité en `*` et signalé, jamais bloquant.
*/
fun lireModDuJar(jar: File): ModInstalle = runCatching {
    ZipFile(jar).use { zip ->
        val manifeste = zip.getEntry("fabric.mod.json")
            ?: return@use ModInstalle(jar.name, null, "*", jar.length())
        val texte = zip.getInputStream(manifeste).bufferedReader(Charsets.UTF_8).readText()
        val sansVirgulesFinales = texte.replace(Regex(",\\s*(?=[}\\]])"), "")
        @Suppress("UNCHECKED_CAST")
        val racine = groovy.json.JsonSlurper().setType(groovy.json.JsonParserType.LAX)
            .parseText(sansVirgulesFinales) as Map<String, Any?>
        val env = (racine["environment"] as? String)?.takeIf { it == "client" || it == "server" } ?: "*"
        ModInstalle(jar.name, racine["id"] as? String, env, jar.length())
    }
}.getOrElse { ModInstalle(jar.name, null, "*", jar.length()) }

/* Les jars actifs de l'instance ; un mod désactivé dans Prism (renommé `.jar.disabled`) est ignoré. */
fun modsDeLInstance(): List<ModInstalle>? {
    if (prismInstanceDir.path.isEmpty() || !instanceModsDir.isDirectory) return null
    return instanceModsDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }?.map { lireModDuJar(it) } ?: emptyList()
}

fun registerSyncModsCore(env: String, runSub: String): TaskProvider<Task> {
    val envRun = runDir.dir(runSub)

    return tasks.register("sync${capitalized(env)}ModsCore") {
        group = "$modId-setup"
        description = "Copie le noyau MDTK de l'instance Prism vers run/$runSub (sides $env et *)"

        doLast {
            val tous = modsDeLInstance()
            if (tous == null) {
                println("[$runSub] instance Prism MDTK introuvable : le run démarre avec les mods déjà en place, rien n'est retiré.")
                println("[$runSub] pour la brancher : importer le .mrpack core-solo dans PrismLauncher, puis poser")
                println("[$runSub] prism_instance_dir dans machine.properties, à la racine du projet (voir README).")
                return@doLast
            }

            val copiables = tous.filter { (it.env == "*" || it.env == env) && it.id !in fournisParLoom && it.id != modId }
            val (exclus, voulus) = copiables.partition { it.id in exclusDesRuns }
            val fournis = tous.filter { it.id in fournisParLoom }
            val illisibles = tous.filter { it.id == null }
            val modsDir = envRun.dir("mods").asFile
            modsDir.mkdirs()

            /* Le ménage d'abord : un jar qui n'est plus dans l'instance n'a plus rien à faire ici. */
            val attendus = voulus.map { it.fichier }.toSet()
            modsDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
                ?.filter { it.name !in attendus }
                ?.forEach { périmé ->
                    println("[$runSub] retiré : ${périmé.name}")
                    périmé.delete()
                }

            /* La copie est locale et re-vérifiable à volonté : la taille suffit à détecter un jar tronqué. */
            var posés = 0
            var octets = 0L
            voulus.forEach { mod ->
                val source = File(instanceModsDir, mod.fichier)
                val cible = File(modsDir, mod.fichier)
                if (cible.exists() && cible.length() == source.length()) return@forEach
                source.copyTo(cible, overwrite = true)
                posés++
                octets += mod.taille
            }

            println("[$runSub] noyau MDTK : ${voulus.size} mods depuis l'instance, $posés posé(s) (${octets / 1024} Ko copiés)")
            if (fournis.isNotEmpty()) {
                println("[$runSub] écartés, déjà fournis par Loom au classpath : ${fournis.joinToString(", ") { it.fichier }}")
            }
            tous.filter { it.id == modId }.forEach {
                println("[$runSub] écarté, c'est le mod lui-même, déployé là par deployToPrism : ${it.fichier}")
            }
            exclus.forEach {
                println("[$runSub] écarté par dev_mods_exclude : ${it.fichier} (le pourquoi vit dans gradle.properties)")
            }
            val idsPresents = tous.mapNotNull { it.id }.toSet()
            exclusDesRuns.filter { it !in idsPresents }.forEach {
                println("[$runSub] dev_mods_exclude déclare « $it » : absent de l'instance, ignoré")
            }
            if (illisibles.isNotEmpty()) {
                println("[$runSub] ATTENTION : fabric.mod.json illisible, side supposé `*` : ${illisibles.joinToString(", ") { it.fichier }}")
            }

            /* La liste des jars posés fait du marqueur la trace datée de l'environnement. */
            envRun.file(".mods-core-done").asFile.writeText(
                "Noyau MDTK copié le ${LocalDateTime.now()} depuis $instanceModsDir\n" +
                    voulus.joinToString("") { "  ${it.fichier}\n" } +
                    "Supprimer ce fichier (ou lancer gradlew resetDevEnvs) pour recopier.\n"
            )
        }

        /*
        L'attendu se recalcule depuis l'instance à chaque lancement : voir « CE QUI
        DÉCLENCHE UNE RÉINSTALLATION » dans le chapeau. Instance absente : la tâche
        s'exécute pour le dire, et ne touche à rien.
        */
        onlyIf {
            val tous = modsDeLInstance() ?: return@onlyIf true
            val attendus = tous
                .filter { (it.env == "*" || it.env == env) && it.id !in fournisParLoom && it.id != modId && it.id !in exclusDesRuns }
                .map { it.fichier }
                .toSet()
            val modsDir = envRun.dir("mods").asFile
            val complet = attendus.all { File(modsDir, it).exists() }
            val propre = modsDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
                ?.all { it.name in attendus } ?: true
            !envRun.file(".mods-core-done").asFile.exists() || !complet || !propre
        }
    }
}

val syncClientModsCore = registerSyncModsCore("client", "client-modded")
val syncServerModsCore = registerSyncModsCore("server", "server-modded")

/**
 *  ── 11.3 — Le contenu du modpack, par PackTool ─────────────────────────────
 *
 *  UN RUN MODDÉ EST MDTK, pas un mélange. Ce que le modpack déclare fait foi :
 *  ses texture packs, ses shaders, ses datapacks et ses réglages. L'entrepôt
 *  S:\18 garde ce que MDTK ne fournit pas, et lui seul : le `server.properties`
 *  de test, l'`eula.txt`, l'`options.txt` de base que le moteur de réglages
 *  patche ensuite, et les mondes.
 *
 *  Le moteur de PackTool sait viser N'IMPORTE QUEL dossier d'instance, et un
 *  dossier de run en est un : `Instance.resolve` accepte un chemin complet, et
 *  `mcDir` éprouve la racine avant `minecraft/`.
 *
 *      packs       les texture packs et les shaders du projet
 *      datapacks   les zips, dans le dossier global lu par Global Packs
 *      settings    les réglages documentés, limités aux mods réellement installés,
 *                  appliqués en convergence sur deux lancements (voir plus bas)
 *
 *  `settings sync` plutôt que `apply` : il se limite à Minecraft et aux mods
 *  qu'il voit, en lisant les fabric.mod.json des jars. D'où l'ordre imposé plus
 *  bas, les mods d'abord.
 *
 *  ── ON N'IMBRIQUE PAS GRADLE DANS GRADLE ────────────────────────────────────
 *  Le geste documenté passe par `PackTool\gradlew ... run`, ce qui recompilerait
 *  PackTool à chaque démarrage de run et lierait le lancement du jeu à l'état de
 *  ses sources. On vise donc le binaire produit par son plugin `application`, via
 *  un simple ProcessBuilder. Il se fabrique UNE fois :
 *
 *      PackTool\gradlew -p PackTool installDist
 *
 *  ── DÉGRADATION VOULUE, ET RÉESSAI ──────────────────────────────────────────
 *  Binaire absent : la tâche dit quoi faire et rend la main, le jeu démarre nu.
 *  PackTool en erreur : on n'écrit PAS le marqueur, donc le prochain lancement
 *  réessaie au lieu de croire le travail fait.
 *
 *  ── POURQUOI UN MARQUEUR PAR NATURE DE CONTENU ──────────────────────────────
 *  Ces commandes écrivent en place. Sans marqueur, chaque démarrage écraserait ce
 *  qu'on vient de régler en jeu. Un marqueur par nature permet de rejouer les
 *  réglages sans recopier trente-sept mégaoctets de packs, et `resetDevEnvs` les
 *  repose tous.
 */
/* PackTool vit sous l'atelier depuis le rangement de S:\17 du 2026-09-09, dans son main-project\ depuis la mise au standard du 2026-09-21 ; l'ancien S:\17\_V\PackTool est mort. */
val packToolDir = File("S:/17/TheModpackCreator/main-project/PackTool")
val packToolExe = File(packToolDir, "build/install/PackTool/bin/PackTool.bat")

/**
 *  L'invocation partagée. Rend la sortie de PackTool s'il a fait son travail,
 *  `null` s'il est absent ou s'il a échoué : dans ces deux cas l'appelant
 *  n'écrit pas son marqueur.
 */
fun lancerPackTool(runSub: String, quoi: String, arguments: List<String>): String? {
    if (!packToolExe.isFile) {
        println("[$runSub] $quoi : NON appliqué, PackTool n'est pas installé.")
        println("[$runSub]   une fois  : cd \"${packToolDir.path.replace('/', '\\')}\"  puis  .\\gradlew installDist")
        println("[$runSub]               (installDist fabrique un lanceur autonome, pour ne pas recompiler")
        println("[$runSub]                PackTool à chaque démarrage de run)")
        println("[$runSub] le run démarre sans, rien n'est cassé.")
        return null
    }

    /*
    LE RÉPERTOIRE DE TRAVAIL N'EST PAS UN DÉTAIL. `Projets.resolve` cherche un
    projet parmi les dossiers FRÈRES de `user.dir`, en supposant tourner depuis
    PackTool lui-même. Sans ce `directory(...)`, le processus hérite du répertoire
    du démon Gradle, donc de ce projet-ci, et PackTool répond « projet inconnu ».

    On passe l'identifiant `mdtk` et non le chemin du dossier, que `resolve`
    accepterait aussi : l'identifiant est stable, alors que le dossier s'appelle
    « MDTK 2026 » et changera d'année.

    L'ENCODAGE SE FORCE, IL NE SE DEVINE PAS. Hors console, la JVM du fils
    écrirait dans la page de codes de Windows et ses accents arriveraient en
    charabia. Le script du plugin `application` honore `JAVA_OPTS` : on lui impose
    l'UTF-8 en sortie, et on lit en UTF-8. Les deux bouts sont alors d'accord.
    */
    val constructeur = ProcessBuilder(listOf(packToolExe.absolutePath, "mdtk") + arguments)
        .directory(packToolDir)
        .redirectErrorStream(true)
    constructeur.environment()["JAVA_OPTS"] =
        "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

    val processus = constructeur.start()
    val sortie = processus.inputStream.bufferedReader(Charsets.UTF_8).readText()
    val code = processus.waitFor()
    sortie.lineSequence().filter { it.isNotBlank() }.forEach { println("[$runSub] $it") }

    if (code != 0) {
        println("[$runSub] ATTENTION : PackTool a rendu le code $code sur « $quoi ». Marqueur NON écrit, le prochain lancement réessaiera.")
        return null
    }
    return sortie
}

fun registerPackToolTask(
    nom: String,
    quoi: String,
    runSub: String,
    marqueurNom: String,
    arguments: (String) -> List<String>,
): TaskProvider<Task> {
    val envRun = runDir.dir(runSub)

    return tasks.register(nom) {
        group = "$modId-setup"
        description = "$quoi de MDTK dans run/$runSub (PackTool, S:\\17)"

        val marqueur = envRun.file(marqueurNom).asFile
        onlyIf { !marqueur.exists() }

        doLast {
            lancerPackTool(runSub, quoi, arguments(envRun.asFile.absolutePath)) ?: return@doLast
            marqueur.writeText(
                "$quoi : appliqué le ${LocalDateTime.now()}\n" +
                    "Supprimer ce fichier (ou lancer gradlew resetDevEnvs) pour recommencer.\n"
            )
        }
    }
}

/*
Le `-y` de `packs` n'est pas une commodité : sans lui, la commande attend une
confirmation sur l'entrée standard, et un processus fils sans console resterait
bloqué indéfiniment.

Les texture packs et les shaders ne concernent que le client. Les datapacks vont
aux deux : un run client porte un serveur intégré, et `datapacksMode: global` les
fait charger par Global Packs dans tous les mondes.
*/
val syncClientPacksModded = registerPackToolTask(
    "syncClientPacksModded", "Texture packs et shaders", "client-modded", ".packs-done",
) { dir -> listOf("packs", dir, "-y") }

val syncClientDatapacksModded = registerPackToolTask(
    "syncClientDatapacksModded", "Datapacks", "client-modded", ".datapacks-done",
) { dir -> listOf("datapacks", dir) }

val syncServerDatapacksModded = registerPackToolTask(
    "syncServerDatapacksModded", "Datapacks", "server-modded", ".datapacks-done",
) { dir -> listOf("datapacks", dir) }

/*
LA CONVERGENCE DES RÉGLAGES, EN DEUX LANCEMENTS. Les mods ne génèrent leurs fichiers
de configuration qu'au premier lancement du jeu : au lancement 1, le run part sur les
défauts d'usine et le jeu écrit ses fichiers ; au lancement 2, `settings sync` applique
les réglages documentés de mdtk-settings.json sur des fichiers qui existent enfin.
C'est le « premier lancement à vide » du wizard, absorbé par la chaîne. Tant que le
bilan de PackTool compte des réglages « dans fichiers absents » et que ce compte
baisse, le marqueur n'est pas posé et le lancement suivant réapplique ; compte nul ou
stable (un fichier qui ne se génère jamais ne doit pas bloquer), on scelle. Le compte
en cours vit dans `.settings-pending`.

LA SOURCE DE VÉRITÉ EST mdtk-settings.json, JAMAIS L'ÉTAT DE L'INSTANCE. Un transplant
des configs de l'instance a été essayé le 2026-09-13 et retiré le jour même : il
copiait de l'état non curé, jusqu'à l'enableShaders d'Iris qui allumait les shaders
dans le run. Défauts d'usine plus réglages documentés : ce que le JSON ne dit pas, le
run ne le porte pas, et c'est ainsi que le run révèle ce que la capture n'a pas
encore documenté.
*/
fun registerSettingsTask(nom: String, runSub: String, side: String): TaskProvider<Task> {
    val envRun = runDir.dir(runSub)

    return tasks.register(nom) {
        group = "$modId-setup"
        description = "Réglages de MDTK dans run/$runSub (PackTool, S:\\17)"

        val marqueur = envRun.file(".settings-done").asFile
        onlyIf { !marqueur.exists() }

        doLast {
            val sortie = lancerPackTool(runSub, "Réglages", listOf("settings", "sync", envRun.asFile.absolutePath, side)) ?: return@doLast
            val absents = Regex("(\\d+) dans fichiers absents").find(sortie)?.groupValues?.get(1)?.toInt() ?: 0
            val attente = envRun.file(".settings-pending").asFile
            val precedent = attente.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

            if (absents > 0 && (precedent == null || absents < precedent)) {
                attente.writeText("$absents\n")
                println("[$runSub] réglages : $absents fichier(s) de config pas encore généré(s) par le jeu : nouvelle passe au prochain lancement.")
                return@doLast
            }

            attente.delete()
            if (absents > 0) {
                println("[$runSub] réglages : $absents fichier(s) toujours absent(s) et compte stable : on scelle (resetDevEnvs pour recommencer).")
            }
            marqueur.writeText(
                "Réglages : appliqués le ${LocalDateTime.now()}\n" +
                    "Supprimer ce fichier (ou lancer gradlew resetDevEnvs) pour recommencer.\n"
            )
        }
    }
}

val syncClientSettingsModded = registerSettingsTask("syncClientSettingsModded", "client-modded", "client")
val syncServerSettingsModded = registerSettingsTask("syncServerSettingsModded", "server-modded", "server")

/*
L'ordre : l'environnement, puis les mods, puis ce qui en dépend. Les datapacks aussi
dépendent des MODS, appris à la dure sur un environnement vierge : PackTool reconnaît
sa cible par son dossier mods\, et sans lui il refuse (« dossier mods introuvable »)
en sortant pourtant en code 0, donc le marqueur se posait pour rien.
*/
syncClientPacksModded.configure { dependsOn(syncClientConfigsModded) }
syncClientDatapacksModded.configure { dependsOn(syncClientConfigsModded, syncClientModsCore) }
syncServerDatapacksModded.configure { dependsOn(syncServerConfigsModded, syncServerModsCore) }
syncClientSettingsModded.configure { dependsOn(syncClientConfigsModded, syncClientModsCore, syncClientPacksModded) }
syncServerSettingsModded.configure { dependsOn(syncServerConfigsModded, syncServerModsCore) }

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
 *  │  12.3  branchements sur les quatre runs                                   │
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
     *  Lancer un run prépare son environnement d'abord. Les marqueurs
     *  `.setup-done` et `.mods-core-done` font que la préparation ne coûte qu'une
     *  fois. Un run moddé prépare DEUX choses : ses réglages, puis ses mods.
     */
    named("runClient") { dependsOn(syncClientConfigs) }
    named("runServer") { dependsOn(syncServerConfigs) }
    /*
    Un run moddé ne déclare QU'UNE dépendance, celle des réglages, qui tire les
    deux autres derrière elle : les réglages ne valent que sur des mods déjà
    installés dans un environnement déjà préparé. La chaîne complète est donc
    configs, puis mods, puis réglages.
    */
    named("runClientModded") { dependsOn(syncClientSettingsModded, syncClientDatapacksModded) }
    named("runServerModded") { dependsOn(syncServerSettingsModded, syncServerDatapacksModded) }

    /**
     *  ── 12.4 — Remise à zéro ───────────────────────────────────────────────
     *
     *  Deux portées volontairement distinctes : `resetDevEnvs` refait les réglages
     *  et GARDE les mondes, `resetDevWorlds` jette les mondes. Les confondre ferait
     *  perdre un terrain d'essai en voulant corriger un fichier de configuration.
     */
    register<Delete>("resetDevEnvs") {
        group = "$modId-setup"
        description = "Force la re-synchronisation des quatre environnements (garde les mondes)"
        listOf("client", "server", "client-modded", "server-modded").forEach { sub ->
            delete(
                runDir.file("$sub/.setup-done"), runDir.dir("$sub/config"),
                runDir.file("$sub/.mods-core-done"), runDir.file("$sub/.settings-done"),
                runDir.file("$sub/.packs-done"), runDir.file("$sub/.datapacks-done"),
                runDir.file("$sub/.settings-pending"),
            )
        }
        doLast { println("marqueurs supprimés : la prochaine exécution re-synchronisera") }
    }

    register<Delete>("resetDevWorlds") {
        group = "$modId-setup"
        description = "Supprime les mondes de dev des quatre environnements"
        delete(
            runDir.dir("client/saves"), runDir.dir("server/world"),
            runDir.dir("client-modded/saves"), runDir.dir("server-modded/world"),
        )
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
        group = "$modId-dev"
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
            rename { "$modId-dev-latest.jar" }
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

    /*
    La seconde cible du 12.5 : QUE le jar, jamais les dépendances. Et le jar poussé
    ici ne REVIENT jamais dans les runs moddés : la sync 11.2, qui copie depuis cette
    même instance, exclut l'identifiant du mod.
    */
    register<Copy>("deployToPrism") {
        group = "$modId-dev"
        description = "Compile le mod et l'installe dans les mods de l'instance Prism (prism_instance_dir)"

        dependsOn(jarFinal)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        from(jarFinal.flatMap { it.archiveFile }) {
            rename { "$modId-dev-latest.jar" }
        }
        into(File(prismInstanceDir, "mods"))

        doFirst {
            check(prismInstanceDir.path.isNotEmpty() && prismInstanceDir.exists()) {
                "Instance Prism introuvable : \"$prismInstanceDir\". Poser prism_instance_dir dans machine.properties, à la racine du projet (voir README)."
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
        group = "$modId-setup"
        description = "Installe properties et eula dans l'instance de serveur locale (depuis l'entrepôt S:\\18)"

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE // ne réécrase pas l'existant

        from(File(favoritesDir, "dev/server/server.properties"))
        from(File(favoritesDir, "dev/server/eula.txt"))
        into(serverPurDir)

        doLast { println("instance de serveur préparée : ${serverPurDir.asFile}") }
    }
}
