// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.nether

import fr.roumoulou.travellingdimension.config.ConfigManager

/**
 * **Les bornes de taille d'un portail du NETHER**, celles de vanilla ou celles de la
 * configuration.
 *
 * ## Pourquoi cet objet existe
 *
 * Mojang garde ses bornes dans `PortalShape`, en constantes **privées et inlinées** dans cinq
 * méthodes privées. Il n'y a donc rien à surcharger proprement : le mixin
 * [fr.roumoulou.travellingdimension.mixin.PortalShapeMixin] remplace chaque constante une par
 * une, et chaque remplacement passe par ici pour que la règle vive à un seul endroit.
 *
 * ## Ce qu'elles gouvernent
 *
 * Ces bornes servent à l'allumage d'un cadre, mais aussi à **revalider** un portail quand un
 * bloc voisin change. Couper `netherPortalFreeSize` après avoir bâti un 1x1 éteint donc ce
 * portail au premier changement à côté. C'est la contrepartie, elle est écrite dans la
 * documentation et dans l'infobulle du réglage.
 *
 * ## Ce qu'elles ne gouvernent pas
 *
 * Rien du calcul. La conversion de coordonnées, la recherche d'un portail d'arrivée, la portée
 * de vanilla : tout cela ignore la taille d'un portail. Seules les dimensions acceptées
 * changent.
 */
object NetherPortalSizes {

    /** Les valeurs de Mojang, celles qui s'appliquent réglage coupé. */
    const val VANILLA_MIN_WIDTH = 2
    const val VANILLA_MIN_HEIGHT = 3
    const val VANILLA_MAX = 21

    /** Largeur intérieure minimale : 2 comme vanilla, 1 quand les tailles libres sont permises. */
    @JvmStatic
    val minWidth: Int get() = if (free) 1 else VANILLA_MIN_WIDTH

    /** Hauteur intérieure minimale : 3 comme vanilla, 1 quand les tailles libres sont permises. */
    @JvmStatic
    val minHeight: Int get() = if (free) 1 else VANILLA_MIN_HEIGHT

    /** Taille intérieure maximale, largeur comme hauteur : 21 comme vanilla, sinon le réglage. */
    @JvmStatic
    val max: Int get() = if (free) ConfigManager.current.netherPortalMaxSize else VANILLA_MAX

    private val free: Boolean get() = ConfigManager.current.netherPortalFreeSize
}
