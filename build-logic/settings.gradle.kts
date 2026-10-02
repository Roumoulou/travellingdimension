/*
Le build des plugins de convention du mod, inclus par le settings.gradle.kts de la
racine (pluginManagement) : ses plugins s'appliquent par leur id, sans version.

Il lit les catalogues du build principal, pour que Loom et Kotlin n'y aient qu'une
version, celle des catalogues : `libs` et `mc` sont les mêmes fichiers, vus d'ici.
*/

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
        create("mc") {
            from(files("../gradle/minecraft.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
