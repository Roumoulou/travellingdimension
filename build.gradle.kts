/**
 * ════════════════════════════════════════════════════════════════════════════════
 *  BUILD SCRIPT — Travelling Dimension
 * ════════════════════════════════════════════════════════════════════════════════
 *
 *  La racine ne construit rien. Les modules et leur rôle sont décrits dans
 *  settings.gradle.kts ; ce fichier n'a qu'un rôle, et il est vital : poser les
 *  plugins des modules sur le classpath de la racine, sans les appliquer
 *  (`apply false`), pour qu'ils soient chargés UNE fois et partagés par tous les
 *  modules.
 *
 *  ── SANS LUI, DEUX LOOM SE RENCONTRENT ──────────────────────────────────────
 *  Chaque module chargerait build-logic, donc Loom, dans son propre classloader. Or
 *  un module de version touche au Loom de common, quand il réunit leurs source sets
 *  en un seul mod : deux copies de Loom se rencontrent alors, et la classe de l'une
 *  ne reconnaît pas celle de l'autre. Mesuré :
 *
 *      Failed to setup Minecraft, java.lang.ClassCastException: class
 *      net.fabricmc.loom.extension.LoomGradleExtensionImpl_Decorated cannot be cast to
 *      class net.fabricmc.loom.LoomGradleExtension (... loader @3dd3e675 ...
 *      loader @74bee9d5)
 *
 *  Outfitter suit le même chemin : il est compilé contre Loom, et doit voir le même.
 *
 *  Plugins, chargés sans être appliqués :
 *    - travellingdimension.game-version   et avec lui tout build-logic : l'autre
 *                                         plugin de convention, Loom, Kotlin et
 *                                         mod-publish-plugin
 *    - fr.moulou.outfitter                les environnements de développement et
 *                                         les déploiements
 * ════════════════════════════════════════════════════════════════════════════════
 */

plugins {
    id("travellingdimension.game-version") apply false
    alias(libs.plugins.outfitter) apply false
}
