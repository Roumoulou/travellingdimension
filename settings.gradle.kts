/*
════════════════════════════════════════════════════════════════════════════════
 OÙ VIVENT LES DÉPÔTS, ET POURQUOI AUCUN N'EST ICI
════════════════════════════════════════════════════════════════════════════════

`pluginManagement` SERT, et il est le seul bloc de dépôts de ce fichier. La
résolution des plugins est SÉPARÉE de celle des dépendances : c'est par lui que
Loom lui-même est trouvé, et sans lui rien ne démarre.

`dependencyResolutionManagement { repositories { } }` ne servirait à rien EN
L'ÉTAT, et c'est mesuré. En appliquant Loom dans `build.gradle.kts`, six dépôts
atterrissent dans le PROJET, ses trois caches plus Fabric, Mojang et mavenCentral.
Or `PREFER_PROJECT`, le défaut de Gradle, ne fusionne pas : il regarde si le projet
a des dépôts, et si oui il ignore ceux d'ici. Entièrement, sans rien journaliser.

Le masquage est donc TOTAL et SILENCIEUX : déclarer mavenCentral côté projet ne
masque pas seulement le mavenCentral d'ici, ça masque aussi tout le reste.

    Conclusion en vigueur : tous les dépôts de dépendances se déclarent dans
    `build.gradle.kts`, et lui seul.

──────────────────────────────────────────────────────────────────────────────
 LA SORTIE EXISTE, ELLE A UN PRIX, ELLE N'EST PAS RETENUE
──────────────────────────────────────────────────────────────────────────────

Loom publie un greffon de dépôts autonome, `net.fabricmc.fabric-loom-repositories`.
Appliqué dans le bloc `plugins` de CE fichier, il pousse ses six dépôts ici au lieu
du projet. Éprouvé sur ce projet : le projet tombe alors à ZÉRO dépôt, on peut
passer en `FAIL_ON_PROJECT_REPOS`, et le build reste vert.

Ce serait plus propre sur trois points : une seule liste, plus aucun doublon, et un
garde-fou qui fait ÉCHOUER le build si un dépôt réapparaît côté projet.

Ce qui le fait écarter : le greffon met Loom au classpath depuis ici, donc
`build.gradle.kts` ne peut plus l'appliquer avec une version, donc **la version de
Loom quitte le catalogue `mc` et se duplique dans ce fichier**. Deux endroits à
monter à chaque version de Loom, et rien qui vérifie qu'ils restent d'accord.

La discipline des catalogues pèse plus lourd que deux dépôts en double. À rouvrir
si le projet devient multi-module, où la centralisation reprendrait le dessus.
════════════════════════════════════════════════════════════════════════════════
*/

pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
    }
}

/*
Deux catalogues de versions, séparés par leur rythme de changement.

`libs` est chargé automatiquement depuis `gradle/libs.versions.toml` : l'outillage
du projet, Kotlin, Java, JUnit, la sérialisation.

`mc` rassemble tout ce qui suit les versions du jeu, Loom compris. Monter d'une
version de Minecraft se fait alors dans un seul fichier, sans toucher au reste.
*/
dependencyResolutionManagement {
    versionCatalogs {
        create("mc") {
            from(files("gradle/minecraft.versions.toml"))
        }
    }
}

rootProject.name = "TravellingDimension"
