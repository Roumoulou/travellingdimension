// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import net.minecraft.core.BlockPos

/**
 * La transformation de coordonnées entre l'OVERWORLD et VOYAGE, et la distance qui sert à
 * départager les portails candidats.
 *
 * ```
 * X_ideal = floorDiv(X_overworld, ratio)      X_ideal = X_voyage * ratio
 * Z_ideal = floorDiv(Z_overworld, ratio)      Z_ideal = Z_voyage * ratio
 * Y_ideal = Y                                 Y_ideal = Y
 * ```
 *
 * Ces formules s'appliquent à l'**ancre** du portail source
 * ([TravelPortalShape.centre]), et à rien d'autre.
 *
 * ## La division est plancher, y compris en négatif
 *
 * On garde la partie entière et on jette le reste. X143 vise X8, X16 vise X1, X-16 vise
 * X-1, X-33 vise X-3. Jamais d'arrondi au plus proche : la division plancher est ce que
 * fait le NETHER, et elle garantit que les cases font exactement `ratio` blocs partout, y
 * compris à cheval sur zéro. Une division qui tronquerait vers zéro donnerait une case
 * centrale deux fois plus large.
 *
 * ## La perte d'information est assumée
 *
 * Seize colonnes d'OVERWORLD consécutives tombent sur la même colonne de VOYAGE. Un
 * aller-retour ne ramène donc pas nécessairement au portail de départ, exactement comme
 * dans le NETHER. [PortalMemory] le corrige pour qui l'active.
 *
 * ## La distance se mesure toujours en blocs d'OVERWORLD
 *
 * C'est la règle centrale de la sélection ([distanceSquared]). Dans VOYAGE, un bloc
 * horizontal vaut `ratio` blocs d'OVERWORLD et un bloc vertical en vaut **un**, le Y
 * n'étant jamais compressé. Les additionner tels quels reviendrait à additionner des
 * kilomètres et des centimètres, et ferait préférer un portail à la bonne altitude mais à
 * des centaines de blocs de sa cible. On ramène donc tous les écarts à l'unité commune
 * avant de comparer, c'est-à-dire au nombre de blocs que le voyageur devra vraiment
 * parcourir en arrivant.
 */
object PortalCoordinates {

    /** **OVERWORLD vers VOYAGE** : division plancher par le ratio. */
    fun overworldToTravel(coord: Int, ratio: Int): Int = Math.floorDiv(coord, ratio)

    /** **VOYAGE vers OVERWORLD** : multiplication exacte par le ratio. */
    fun travelToOverworld(coord: Int, ratio: Int): Int = Math.multiplyExact(coord, ratio)

    /** Coin minimal de la case de `ratio` blocs contenant [coord]. */
    fun cellCorner(coord: Int, ratio: Int): Int = overworldToTravel(coord, ratio) * ratio

    /**
     * Combien de blocs d'OVERWORLD vaut un bloc horizontal de cette dimension : `ratio`
     * dans VOYAGE, 1 dans l'OVERWORLD. Le Y ne passe jamais par là, il n'est pas compressé.
     */
    fun horizontalScale(inTravel: Boolean, ratio: Int): Int = if (inTravel) ratio else 1

    /**
     * **Le rayon de recherche symétrique.** Un rayon de `overworldRadius` blocs dans
     * l'OVERWORLD vaut `overworldRadius / ratio` blocs dans VOYAGE : c'est le même carré de
     * monde, vu aux deux échelles.
     *
     * Cette égalité n'est pas une élégance, c'est une **condition de bon fonctionnement**.
     * Avec un rayon de VOYAGE trop grand, un portail trouvé à l'aller ne retrouve pas son
     * partenaire au retour : on ressort loin de chez soi et un portail parasite naît.
     * Contre-exemple, avec un rayon de 16 en VOYAGE au ratio 16 : un portail d'OVERWORLD en
     * X0 et un portail de VOYAGE en X12 se voient à l'aller, 12 étant sous 16 ; au retour le
     * point idéal tombe en X192 (OVERWORLD) et X0 est à 192 blocs, hors des 128 de portée.
     *
     * Elle ne ferme pas tout à fait l'aller-retour : **au bord de la portée, un trou reste,
     * et il est assumé.** L'ancre du portail source peut se trouver jusqu'à `ratio - 1` blocs
     * après le coin de sa case, et cet écart s'ajoute au retour. Aux défauts : une ancre en
     * X-15 (OVERWORLD) rejoint un portail en X-9 (VOYAGE), à 8 blocs (VOYAGE) du point idéal
     * X-1 ; au retour le point idéal tombe en X-144 (OVERWORLD), et l'ancre est à 129 blocs
     * pour une portée de 128. Le rayon sans trou serait
     * `(overworldRadius - (ratio - 1)) / ratio`, 7 aux défauts.
     */
    fun symmetricTravelRadius(overworldRadius: Int, ratio: Int): Int =
        maxOf(1, overworldRadius / ratio)

    /**
     * **La distance qui départage les candidats**, au carré, entre deux positions d'une
     * même dimension, exprimée en blocs d'OVERWORLD.
     *
     * Le carré suffit : seul l'ordre compte, et il évite une racine par candidat.
     *
     * [verticalWeight] multiplie l'écart vertical APRÈS la mise à l'unité commune. À 1.0 le
     * comportement est neutre. Il n'a d'usage réel que côté OVERWORLD, pour empêcher un
     * portail au fond d'une caverne de battre un portail de surface un peu plus loin ; côté
     * VOYAGE la mise à l'unité commune a déjà réduit le Y à sa juste part.
     *
     * Conséquence recherchée : quand plusieurs portails partagent la **même colonne**, seul
     * le Y les distingue, donc c'est l'étage le plus proche qui gagne. Des étages bâtis à la
     * main aux mêmes X et Z se répondent ainsi dans les deux sens, sans colorant. Et un
     * écart d'altitude ne l'emporte jamais sur un écart horizontal qui coûte plus de trajet
     * réel.
     */
    fun distanceSquared(
        from: BlockPos,
        to: BlockPos,
        inTravel: Boolean,
        ratio: Int,
        verticalWeight: Double = 1.0,
    ): Double {
        val scale = horizontalScale(inTravel, ratio).toDouble()
        val dx = (from.x - to.x).toDouble() * scale
        val dz = (from.z - to.z).toDouble() * scale
        val dy = (from.y - to.y).toDouble() * verticalWeight
        return dx * dx + dy * dy + dz * dz
    }
}
