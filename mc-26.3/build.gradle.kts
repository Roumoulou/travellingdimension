/*
════════════════════════════════════════════════════════════════════════════════
 MODULE mc-26.3 : le mod pour Minecraft 26.3
════════════════════════════════════════════════════════════════════════════════

Ce que le plugin travellingdimension.game-version fait de ce module (le jar
travellingdimension-2.8.0+26.3.jar, le mod en développement, les étages 1 et 2
rejoués contre 26.3) est décrit dans son en-tête, dans build-logic. Ici ne vit que
ce qui tient à 26.3 :

  1  les dépendances de son catalogue, mc263
  2  ses environnements de développement et sa cible de déploiement

Ses sources ne portent que ce que 26.3 ne partage pas avec les autres versions :
son pont de version (GameVersionBridge263, déclaré dans META-INF/services) et le
mixin dont la cible a changé en 26.3, avec sa configuration.

Plugins, sans version : la racine les a chargés (build.gradle.kts de la racine).
  - travellingdimension.game-version   le module de version (build-logic)
  - fr.moulou.outfitter                les environnements de développement et les
                                       déploiements (S:\16\_V\Outfitter, consommé
                                       en build composite)
════════════════════════════════════════════════════════════════════════════════
*/

plugins {
    id("travellingdimension.game-version")
    id("fr.moulou.outfitter")
}

/*
────────────────────────────────────────────────────────────────────────────────
 1. LES DÉPENDANCES DE 26.3
────────────────────────────────────────────────────────────────────────────────

Le jeu, la Fabric API publiée pour lui, et le module GameTest de cette Fabric API
pour le serveur de l'étage 2. Mod Menu et Cloth Config de 26.3, jamais embarqués :
la vérification de compatibilité s'en sert pour éprouver l'écran de configuration
de common contre eux (section 8 du plugin de version). Le reste (chargeur, Kotlin,
Storify, le code de common) vient du plugin de version.
*/
dependencies {
    minecraft(mc263.minecraft)
    implementation(mc263.fabric.api)
    "gametestRuntimeOnly"(mc263.fabric.gametest.api)
    "clientCompileOnly"(mc263.modmenu)
    "clientCompileOnly"(mc263.cloth.config)
}

/*
────────────────────────────────────────────────────────────────────────────────
 2. LES ENVIRONNEMENTS DE DÉVELOPPEMENT : OUTFITTER
────────────────────────────────────────────────────────────────────────────────

Même mécanique que mc-26.2 (voir son build), avec ce que 26.3 n'a pas encore.

── DEUX ENVIRONNEMENTS, VANILLA PURS ─────────────────────────────────────────────
`client` et `server`, dans mc-26.3\run\. Pas d'environnement moddé : MDTK, le
modpack qui les nourrit, n'existe qu'en 26.2. Ils arriveront avec une instance
MDTK en 26.3, qu'un machine.properties propre à ce module désignera.

L'entrepôt S:\18 n'a pas encore de dossier 26.3 : sans lui, `client` démarre à nu
(Minecraft génère ses propres réglages) et `server` refuse de démarrer faute
d'eula.txt, ce qu'Outfitter annonce. Un profil 26.3 dans l'entrepôt lève les deux.

── UNE CIBLE : LE SERVEUR DÉDIÉ ──────────────────────────────────────────────────
`serverPur`, le serveur dédié 26.3 du classeur (05-instances\server-pur-26.3), avec
le panier `serverPurBundle`. Pas de cible `prism` : l'instance de référence du poste
est MDTK, en 26.2, et elle recevrait un jar 26.3.
*/
outfitter {
    minecraftVersion = mc263.versions.minecraft
    environments {
        register("client") { client(); profile = "vanilla" }
        register("server") { server(); profile = "dev" }
    }
    deployTargets {
        register("serverPur") {
            directory = layout.settingsDirectory.dir("../../05-instances/server-pur-26.3/server")
            profile = "dev"
        }
    }
}

/* Le panier du serveur dédié : ces deux jars et rien d'autre (la configuration n'est pas transitive), aux versions que le mod a compilées. */
dependencies {
    "serverPurBundle"(mc263.fabric.api)
    "serverPurBundle"(mc.fabric.language.kotlin)
}
