package fr.roumoulou.travellingdimension.portal

import net.minecraft.util.StringRepresentable
import net.minecraft.world.item.DyeColor

/**
 * Couleur d'un portail de voyage, posée au colorant.
 *
 * C'est un **lien explicite** entre deux portails : quand deux portails de couleur
 * identique existent des deux côtés, la traversée les relie, quelles que soient les
 * règles automatiques. Cela répond au seul cas que le calcul ne peut pas trancher :
 * plusieurs portails partageant une destination, et l'envie de choisir lequel.
 *
 * [NONE] est l'état par défaut, celui d'un portail qui vient d'être allumé : la
 * couleur ne s'invente pas, elle se pose.
 *
 * La couleur vit dans l'état du bloc, donc dans la sauvegarde du monde, visible et
 * inspectable comme n'importe quel bloc. Aucun registre parallèle à maintenir.
 *
 * ## Pourquoi une palette maison plutôt que celle des colorants
 *
 * Le colorant reste l'objet vanilla : ce sont les seize colorants du jeu, aucun objet
 * nouveau à fabriquer. Seul le **rendu** est retravaillé, et il devait l'être.
 *
 * Un portail se teinte par multiplication : la couleur affichée est celle de la texture
 * multipliée par la teinte. La texture d'origine étant franchement violette (moyenne
 * mesurée R=112 V=70 B=171, le vert plafonnant à 27 %), aucune teinte ne pouvait en
 * sortir un vert ou un jaune vif : tout revenait à des violets plus ou moins sales, et
 * rouge, orange et magenta finissaient indiscernables.
 *
 * La texture a donc été neutralisée en niveaux de gris (valeur = canal le plus fort,
 * transparence intacte), et l'améthyste d'origine est rendue par la teinte de [NONE].
 * Un portail non teinté a donc exactement l'aspect d'un portail d'améthyste, et les seize
 * couleurs s'affichent telles quelles.
 *
 * Les six couleurs marquées [recommended] occupent les six sommets saturés du cube RVB :
 * elles sont aussi éloignées les unes des autres qu'il est possible, y compris pour un
 * daltonisme courant, où la clarté les sépare encore. Les dix autres restent utilisables,
 * mais se ressemblent forcément davantage entre elles.
 */
enum class PortalTint(
    private val serialized: String,
    val dye: DyeColor?,
    /** Couleur du rendu, 0xRRGGBB. Voir le commentaire de classe : c'est une palette maison. */
    private val rgb: Int,
    /** Fait partie des six couleurs franchement distinctes, celles à utiliser en priorité. */
    val recommended: Boolean = false,
) : StringRepresentable {

    /**
     * Pas de lien. Le violet améthyste mesuré sur la texture d'origine : à l'écran,
     * un portail non teinté est identique à ce qu'il a toujours été.
     */
    NONE("none", null, 0xAF76FF),

    // --- Les six couleurs franchement distinctes ---------------------------------
    RED("red", DyeColor.RED, 0xFF2B2B, recommended = true),
    YELLOW("yellow", DyeColor.YELLOW, 0xFFE01F, recommended = true),
    LIME("lime", DyeColor.LIME, 0x36FF3C, recommended = true),
    CYAN("cyan", DyeColor.CYAN, 0x18E8FF, recommended = true),
    BLUE("blue", DyeColor.BLUE, 0x3A5BFF, recommended = true),
    MAGENTA("magenta", DyeColor.MAGENTA, 0xFF35D2, recommended = true),

    // --- Les dix autres, volontairement décalées en clarté pour ne pas brouiller ---
    // Les quatre neutres sont volontairement très espacées en clarté : c'est leur seul
    // critère de distinction. Le noir reste le plus sombre, mais pas au point de rendre
    // le portail invisible — sur une texture translucide il s'effaçait complètement.
    WHITE("white", DyeColor.WHITE, 0xF5F5F5),
    LIGHT_GRAY("light_gray", DyeColor.LIGHT_GRAY, 0x969696),
    GRAY("gray", DyeColor.GRAY, 0x5E5E5E),
    BLACK("black", DyeColor.BLACK, 0x3E3E3E),
    ORANGE("orange", DyeColor.ORANGE, 0xFF8A0F),
    BROWN("brown", DyeColor.BROWN, 0x7A4A22),
    PINK("pink", DyeColor.PINK, 0xFFA8C8),
    LIGHT_BLUE("light_blue", DyeColor.LIGHT_BLUE, 0x8FD3FF),
    GREEN("green", DyeColor.GREEN, 0x1F8F2E),
    PURPLE("purple", DyeColor.PURPLE, 0x7A2BC4);

    override fun getSerializedName(): String = serialized

    /** Un portail sans couleur ne participe à aucun lien explicite. */
    val isLink: Boolean get() = this != NONE

    /**
     * Teinte du rendu, en ARGB opaque.
     *
     * [NONE] a sa propre teinte, et non la valeur `-1` de « pas de teinte » : c'est elle
     * qui redonne son violet à la texture désormais grise.
     */
    fun argb(): Int = (0xFF shl 24) or rgb

    companion object {
        private val BY_DYE: Map<DyeColor, PortalTint> =
            entries.mapNotNull { tint -> tint.dye?.let { it to tint } }.toMap()

        fun of(dye: DyeColor): PortalTint = BY_DYE.getValue(dye)
    }
}
