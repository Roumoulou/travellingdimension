/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  MODULE mc-26.3 — le mod pour Minecraft 26.3
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Ce que le plugin travellingdimension.game-version fait de ce module (le jar
 *  travellingdimension-2.8.0+26.3.jar, le mod en développement, les étages 1 et 2
 *  rejoués contre 26.3, la vérification de compatibilité) est décrit dans son
 *  en-tête, dans build-logic. Ici ne vit que ce qui tient à 26.3 :
 *
 *  ┌───────────────────────────────────────────────────────────────────────────┐
 *  │  1  les dépendances de son catalogue, mc263                               │
 *  │  2  Outfitter : ses environnements, ses serveurs GameTest, ses cibles     │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 *  Ses sources ne portent que ce que 26.3 ne partage pas avec les autres versions :
 *  son pont de version (GameVersionBridge263, déclaré dans META-INF/services) et le
 *  mixin dont la cible a changé en 26.3, avec sa configuration.
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
 *  SECTION 1 — LES DÉPENDANCES DE 26.3
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  - minecraft                  : le jeu, en 26.3.
 *  - fabric-api                 : la Fabric API publiée pour lui.
 *  - fabric-gametest-api        : le module GameTest de cette Fabric API, pour le
 *                                 serveur de l'étage 2.
 *  - modmenu, cloth-config      : ceux de 26.3, jamais embarqués : la vérification
 *                                 de compatibilité s'en sert pour éprouver l'écran
 *                                 de configuration de common contre eux (section 8
 *                                 du plugin de version).
 *
 *  Le reste (chargeur, Kotlin, Storify, le code de common) vient du plugin de
 *  version.
 * ════════════════════════════════════════════════════════════════════════════════
 */
dependencies {
    minecraft(mc263.minecraft)
    implementation(mc263.fabric.api)
    "gametestRuntimeOnly"(mc263.fabric.gametest.api)
    "clientCompileOnly"(mc263.modmenu)
    "clientCompileOnly"(mc263.cloth.config)
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  SECTION 2 — LES ENVIRONNEMENTS DE DÉVELOPPEMENT : OUTFITTER
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  Même mécanique que mc-26.2 (voir son build) : quatre environnements, les deux
 *  serveurs GameTest, deux cibles.
 *
 *  ── QUATRE ENVIRONNEMENTS, deux par deux ────────────────────────────────────
 *  `client` et `server`, vanilla purs, et leurs variantes moddées, dans mc-26.3\run\.
 *  Les moddés reçoivent le noyau de MDTK 26.3, depuis l'instance Prism que le
 *  machine.properties de ce module désigne : Outfitter lit sa version dans son
 *  mmc-pack.json, et ignore une instance d'une autre lignée.
 *
 *  L'entrepôt S:\18 a les profils 26.3, `vanilla` et `dev`, et ses maps : les clients
 *  reçoivent la sélection de outfitter.maps, les serveurs le monde que le profil
 *  `dev` désigne.
 *
 *  ── LES DEUX SERVEURS GAMETEST ──────────────────────────────────────────────
 *  `gameTest` et `gameTestVanilla`, déclarés comme dans mc-26.2 : ils tournent dans
 *  mc-26.3\run\game-test et mc-26.3\run\game-test-vanilla, repartent à neuf à chaque
 *  run, et ne demandent rien à l'entrepôt. Le second reçoit ses fixtures de common.
 *
 *  ── DEUX CIBLES ─────────────────────────────────────────────────────────────
 *  `serverPur`, le serveur dédié 26.3 du classeur (05-instances\server-pur-26.3),
 *  avec le panier `serverPurBundle`. `prism`, l'instance PrismLauncher MDTK de 26.3,
 *  porte son propre modpack : elle ne reçoit QUE le jar.
 * ════════════════════════════════════════════════════════════════════════════════
 */
outfitter {
    minecraftVersion = mc263.versions.minecraft
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
            directory = layout.settingsDirectory.dir("../../05-instances/server-pur-26.3/server")
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
    "serverPurBundle"(mc263.fabric.api)
    "serverPurBundle"(mc.fabric.language.kotlin)
}
