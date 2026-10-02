/*
════════════════════════════════════════════════════════════════════════════════
 LE MOD EN MODULES : UN CODE COMMUN, UN MODULE PAR VERSION DU JEU
════════════════════════════════════════════════════════════════════════════════

Les modules sont des enfants directs de cette racine :

  common     le code partagé et ses tests, compilés UNE fois, contre la dernière
             release servie (26.3). Il ne livre aucun jar.
  mc-26.1    ce qui tient à la lignée 26.1 : le pont de version, les mixins dont la
             cible change d'une version à l'autre, ses environnements. Il compile
             contre 26.1.2, la dernière release de sa lignée, assemble son jar et
             rejoue contre son jeu les tests du jeu (étages 1 et 2).
  mc-26.2    la même chose pour 26.2.
  mc-26.3    la même chose pour 26.3.

Le nom d'un module de version est un contrat : `mc-<lignée>`. Le plugin de
convention travellingdimension.game-version vérifie que le Minecraft que le module
déclare appartient à cette lignée, et tire de ce Minecraft la version du jar
(2.8.0+26.1.2, 2.8.0+26.2...).

La racine ne construit rien : `gradlew build` lancé ici joue le build de chaque
module, donc l'étage 0 des tests une fois (common), et les étages 1 et 2 une fois
par version du jeu.

Le code commun suit la dernière RELEASE de Minecraft, jamais un snapshot, une
pre-release ou une release candidate. À chaque release, common monte au catalogue
de celle-ci, et ce que les versions plus anciennes n'ont pas, ou nomment autrement,
part dans leur pont de version. Servir une version de plus : son catalogue
gradle/mc-<lignée>.versions.toml et sa ligne dans versionCatalogs, un module
mc-<lignée> calqué sur un autre, puis ce que la compilation, la vérification de
compatibilité (checkCommonCompatibility) et les tests révèlent. En abandonner une :
retirer son module et son catalogue.

════════════════════════════════════════════════════════════════════════════════
 OÙ VIVENT LES DÉPÔTS, ET POURQUOI AUCUN N'EST ICI
════════════════════════════════════════════════════════════════════════════════

`pluginManagement` SERT, et il est le seul bloc de dépôts de ce fichier. La
résolution des plugins est SÉPARÉE de celle des dépendances : c'est par lui que
Loom lui-même est trouvé et qu'Outfitter et build-logic sont inclus, et sans lui
rien ne démarre.

`dependencyResolutionManagement { repositories { } }` ne servirait à rien EN
L'ÉTAT, et c'est mesuré. En s'appliquant à un module, Loom y pose six dépôts, ses
trois caches plus Fabric, Mojang et mavenCentral. Or `PREFER_PROJECT`, le défaut de
Gradle, ne fusionne pas : il regarde si le projet a des dépôts, et si oui il ignore
ceux d'ici. Entièrement, sans rien journaliser.

    Conclusion en vigueur : tous les dépôts de dépendances se déclarent dans le
    plugin de convention travellingdimension.loom-module (build-logic), et lui seul.

Le passage en multi-module rouvrait la question : une déclaration par module
aurait multiplié les listes. Le plugin de convention la referme, une seule liste,
appliquée à chaque module. La sortie par le greffon `fabric-loom-repositories`
reste écartée pour sa raison d'origine : elle sortirait la version de Loom du
catalogue `mc` pour la dupliquer ici.
════════════════════════════════════════════════════════════════════════════════
*/

pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
    }

    /*
    BUILD-LOGIC, les plugins de convention du mod (travellingdimension.loom-module et
    travellingdimension.game-version), écrits en scripts Kotlin précompilés. Inclus
    ici, ils s'appliquent par leur id, sans version : c'est build-logic qui fixe celles
    de Loom et de Kotlin, depuis les catalogues.
    */
    includeBuild("build-logic")

    /*
    OUTFITTER, le plugin des environnements de développement, en build composite. La
    propriété `outfitter_source` (gradle.properties, ou -Poutfitter_source=repsy) choisit
    la source : `composite`, le défaut, inclut le projet Gradle du plugin, voisin de ce
    classeur, et le build tourne contre ses sources fraîches, sans publication ; `repsy`
    prend l'artefact publié sur Repsy, à la version du catalogue libs (éprouvé le
    2026-10-02 sur la 0.3.0-SNAPSHOT). En composite, Gradle ignore cette version (mesuré
    sur 9.7.1). Le chemin est relatif parce que ce fichier est versionné. Un clone qui n'a
    pas le voisin passe en repsy : en composite, l'inclusion d'un dossier absent échoue
    (« Included build ... does not exist », mesuré), elle ne retombe pas d'elle-même sur
    Repsy. Un build inclus garde ses propres dépôts : Outfitter continue de compiler
    contre Loom depuis Fabric, sans rien poser ici.
    */
    val outfitterSource = providers.gradleProperty("outfitter_source").getOrElse("composite")
    require(outfitterSource == "composite" || outfitterSource == "repsy") { "outfitter_source vaut 'composite' ou 'repsy', pas '$outfitterSource'" }
    if (outfitterSource == "composite") {
        includeBuild("../../../Outfitter/main-project/Outfitter")
    } else {
        repositories { maven("https://repo.repsy.io/roumoulou/maven") { name = "Repsy" } }
    }
}

/*
Les catalogues de versions, séparés par leur rythme de changement.

`libs` est chargé automatiquement depuis `gradle/libs.versions.toml` : l'outillage
du projet, Kotlin, Java, JUnit, la sérialisation, Storify, Outfitter.

`mc` porte la chaîne Fabric commune à toutes les versions servies : Loom, le
chargeur et l'adaptateur Kotlin.

Un catalogue par lignée du jeu porte ce qui suit CETTE lignée : Minecraft, la
Fabric API publiée pour lui et son module GameTest, les mods de l'écran de
configuration. Les clés sont les mêmes d'un catalogue de version à l'autre, et
chaque module lit le sien en accesseurs typés (`mc262.fabric.api`) ; common lit
celui de la dernière release. Un catalogue vaut pour tout le build : c'est le
module qui choisit lequel il lit.
*/
dependencyResolutionManagement {
    versionCatalogs {
        create("mc") {
            from(files("gradle/minecraft.versions.toml"))
        }
        create("mc261") {
            from(files("gradle/mc-26.1.versions.toml"))
        }
        create("mc262") {
            from(files("gradle/mc-26.2.versions.toml"))
        }
        create("mc263") {
            from(files("gradle/mc-26.3.versions.toml"))
        }
    }
}

rootProject.name = "TravellingDimension"

include("common", "mc-26.1", "mc-26.2", "mc-26.3")
