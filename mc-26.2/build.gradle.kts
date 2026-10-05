/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  MODULE mc-26.2 — le mod pour Minecraft 26.2
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Ce que le plugin travellingdimension.game-version fait de ce module (le jar
 *  travellingdimension-2.8.0+26.2.jar, le mod en développement, les étages 1 et 2
 *  rejoués contre 26.2, la vérification de compatibilité) est décrit dans son
 *  en-tête, dans build-logic. Ici ne vit que ce qui tient à 26.2 :
 *
 *  ┌───────────────────────────────────────────────────────────────────────────┐
 *  │  1  les dépendances de son catalogue, mc262                               │
 *  │  2  Outfitter : ses environnements, ses serveurs GameTest, ses cibles     │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  Ses sources ne portent que ce que 26.2 ne partage pas avec les autres versions :
 *  son pont de version (GameVersionBridge262, déclaré dans META-INF/services) et le
 *  mixin dont la cible change en 26.3, avec sa configuration.
 *
 *  Plugins, sans version : la racine les a chargés (build.gradle.kts de la racine).
 *    - travellingdimension.game-version   le module de version (build-logic)
 *    - fr.moulou.outfitter                les environnements de développement et
 *                                         les déploiements (S:\16\_V\Outfitter,
 *                                         consommé en build composite)
 * ════════════════════════════════════════════════════════════════════════════════
 */

plugins {
    id("travellingdimension.game-version")
    id("fr.moulou.outfitter")
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 1 — LES DÉPENDANCES DE 26.2
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  - minecraft                  : le jeu, en 26.2.
 *  - fabric-api                 : la Fabric API publiée pour lui.
 *  - fabric-gametest-api        : le module GameTest de cette Fabric API, pour le
 *                                 serveur de l'étage 2.
 *  - modmenu, cloth-config      : ceux de 26.2, jamais embarqués : la vérification
 *                                 de compatibilité s'en sert pour éprouver l'écran
 *                                 de configuration de common contre eux (section 8
 *                                 du plugin de version).
 *
 *  Le reste (chargeur, Kotlin, Storify, le code de common) vient du plugin de
 *  version.
 * ════════════════════════════════════════════════════════════════════════════════
 */
dependencies {
    minecraft(mc262.minecraft)
    implementation(mc262.fabric.api)
    "gametestRuntimeOnly"(mc262.fabric.gametest.api)
    "clientCompileOnly"(mc262.modmenu)
    "clientCompileOnly"(mc262.cloth.config)
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 2 — LES ENVIRONNEMENTS DE DÉVELOPPEMENT : OUTFITTER
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Le plugin Outfitter (S:\16\_V\Outfitter, consommé en build composite : voir
 *  settings.gradle.kts) prépare chaque environnement avant son run, serveur
 *  GameTest compris, et déploie le jar. Ce bloc ne déclare que ce qui est propre à
 *  ce module : la version de Minecraft, qui choisit le dossier de l'entrepôt S:\18,
 *  les quatre environnements avec leur profil, les deux serveurs GameTest, les deux
 *  cibles de déploiement et le panier du serveur dédié. Tout le reste vient des clés
 *  `outfitter.*` : gradle.properties de la racine pour ce qui est propre au projet
 *  (maps, monde du serveur, exclusions, logs), machine.properties pour ce qui est
 *  propre au poste ou à la personne (l'entrepôt S:\18, PackTool, le joueur).
 *  Outfitter lit le machine.properties de la racine, puis celui de ce module, qui le
 *  surcharge clé par clé et désigne l'instance Prism de 26.2.
 *
 *  ── QUATRE ENVIRONNEMENTS, deux par deux ────────────────────────────────────
 *  `client` et `server` sont VANILLA PURS : aucun mod tiers, Loom charge le mod
 *  depuis le classpath. Ce sont eux la référence, celle qui dit ce que voit un
 *  joueur n'ayant QUE ce mod. `clientModded` et `serverModded` reçoivent le noyau
 *  MDTK de l'instance Prism de 26.2, filtré par side, puis les packs, datapacks et
 *  réglages de MDTK par PackTool : un environnement moddé EST l'instance MDTK 26.2
 *  du poste. Outfitter lit la version de l'instance dans son mmc-pack.json, et
 *  ignore une instance d'une autre lignée. Le dossier est run\<nom-en-kebab-case>
 *  DANS ce module (mc-26.2\run\client) : les mondes d'une version ne se mélangent
 *  pas à ceux d'une autre.
 *
 *  ── LES DEUX SERVEURS GAMETEST : CRÉÉS PAR LE PLUGIN DE VERSION, DÉCLARÉS ICI ─
 *  `gameTest` et `gameTestVanilla`, les serveurs de l'étage 2, sont les runs que le
 *  plugin de version crée (sa section 7), et qu'il refuse de laisser sans cette
 *  déclaration. Outfitter fait tourner chacun dans son dossier, mc-26.2\run\game-test
 *  et mc-26.2\run\game-test-vanilla, hors de build\, le remet à neuf avant chaque run
 *  (`fresh<Serveur>` : world\ et config\ retirés) et range le rapport de ses tests
 *  dans build\test-results\<serveur>\TEST-<serveur>.xml. Ils ne reçoivent rien de
 *  l'entrepôt ni de l'instance : un clone sans S:\18 les rejoue tels quels.
 *
 *  `gameTest` naît de la configuration par défaut. `gameTestVanilla` reçoit ses
 *  fixtures, un dossier de common qui reproduit celui du serveur et qu'Outfitter y
 *  recopie après le neuf (`syncGameTestVanillaFixtures`) : sa configuration,
 *  `worldgen` à `vanilla`, et le datapack que le mod charge depuis le dossier du jeu.
 *
 *  Pour ne jouer qu'une partie des tests, un motif à jokers sur leur identifiant,
 *  <mod>:<classe>_<méthode> en snake_case, l'argument entre guillemets : sans eux,
 *  PowerShell le coupe au point.
 *
 *      gradlew :mc-26.2:runGameTest "-Poutfitter.gametest_filter=travellingdimension-gametest:nether_portal_*"
 *
 *  ── DEUX CIBLES, et elles ne reçoivent PAS la même chose ────────────────────
 *  `serverPur`, le serveur dédié 26.2 du classeur (05-instances\server-pur-26.2),
 *  n'a pas de modpack : il reçoit le jar ET le panier `serverPurBundle`, Fabric API
 *  et FLK aux versions de ce module, sans leurs dépendances. Son chemin part de la
 *  racine du build, pas de ce module. `prism`, l'instance PrismLauncher MDTK de 26.2,
 *  porte son propre modpack : elle ne reçoit QUE le jar, et la tâche avertit si
 *  Fabric API ou FLK semblent absents de ses mods ; Outfitter refuse de déployer dans
 *  une instance d'une autre lignée.
 *
 *  Les tâches, groupe `outfitter` : sync<Env>Profile, Worlds, Mods, Packs,
 *  Datapacks, Settings, Fixtures, fresh<Env>, prepare<Env>, deployTo<Cible>, setup<Cible>,
 *  resetEnvironments, resetWorlds, outfitterLog4jConfigs. Le détail, condition,
 *  geste et marqueur de chacune : la doc du plugin, et
 *  01-docs\technical-docs\02-finalized\taches-de-developpement.md.
 * ════════════════════════════════════════════════════════════════════════════════
 */
outfitter {
    minecraftVersion = mc262.versions.minecraft
    environments {
        register("client") { client(); profile = "vanilla" }
        register("server") { server(); profile = "dev" }
        register("clientModded") { client(); profile = "vanilla"; modded = true }
        register("serverModded") { server(); profile = "dev"; modded = true }
    }
    gameTests {
        register("gameTest")
        register("gameTestVanilla") { fixtures = layout.settingsDirectory.dir("common/src/gametest/fixtures/gameTestVanilla") }
    }
    deployTargets {
        register("serverPur") {
            directory = layout.settingsDirectory.dir("../../05-instances/server-pur-26.2/server")
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
    "serverPurBundle"(mc262.fabric.api)
    "serverPurBundle"(mc.fabric.language.kotlin)
}
