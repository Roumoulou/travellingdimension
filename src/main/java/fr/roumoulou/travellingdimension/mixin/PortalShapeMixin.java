package fr.roumoulou.travellingdimension.mixin;

import fr.roumoulou.travellingdimension.nether.NetherPortalSizes;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Les tailles de portail hors vanilla dans le NETHER (reglage {@code netherPortalFreeSize}).
 *
 * <p>Mojang garde ses bornes en constantes PRIVEES et INLINEES : {@code MIN_WIDTH} vaut 2,
 * {@code MIN_HEIGHT} vaut 3, le maximum vaut 21, et le compilateur les a semees directement dans
 * le corps de six methodes privees. Il n'y a donc ni champ a surcharger ni methode a envelopper,
 * seulement des constantes a remplacer une par une.
 *
 * <p><b>Ce que chaque injection touche</b>, verifie au desassemblage de la classe 26.2 :
 *
 * <pre>
 * calculateBottomLeft                                21 (profondeur du balayage vers le bas)
 * calculateWidth                   2 (min largeur)   21 (max largeur)
 * getDistanceUntilEdgeAboveFrame                     21 (borne du balayage horizontal)
 * calculateHeight                  3 (min hauteur)   21 (max hauteur)
 * getDistanceUntilTop                                21 (deux fois : boucle et valeur de repli)
 * isValid                          2                 21 (deux fois : largeur et hauteur)
 *                                  3
 * </pre>
 *
 * <p>Chaque constante n'apparait qu'aux endroits voulus dans sa methode, donc aucune injection
 * n'a besoin d'ordinal. Les occurrences multiples de 21 sont toutes des maximums, et sont donc
 * toutes a remplacer.
 *
 * <p><b>Reglage coupe, ce mixin ne change rien</b> : {@link NetherPortalSizes} rend alors
 * exactement les valeurs de Mojang. Le crochet est pose en permanence, mais il est transparent
 * tant que personne n'a demande autre chose.
 *
 * <p><b>La contrepartie, dite franchement</b> : ces bornes servent aussi a REVALIDER un portail
 * quand un bloc voisin change. Couper le reglage apres avoir bati un 1x1 eteint donc ce portail
 * au premier changement a cote. C'est ecrit dans l'infobulle du reglage et dans la documentation.
 */
@Mixin(PortalShape.class)
public abstract class PortalShapeMixin {

    // ── Le coin de depart ────────────────────────────────────────────────────

    /**
     * La descente qui cherche la rangee du bas, bornee par {@code Math.max(plancher, y - 21)}.
     *
     * <p>C'est la PREMIERE chose que fait {@code findAnyShape}, avant toute mesure. Laissee a 21
     * alors que le maximum est monte a 41, elle arrete la descente 21 blocs sous le bloc examine :
     * un portail plus haut que 21, revalide par un bloc de sa moitie haute, ne retrouve jamais son
     * coin, sa forme est jugee invalide, et il s'eteint. La borne doit donc suivre le maximum comme
     * les autres.
     */
    @ModifyConstant(method = "calculateBottomLeft", constant = @Constant(intValue = 21))
    private static int travellingdimension$scanDown(int vanilla) {
        return NetherPortalSizes.getMax();
    }

    // ── La largeur ───────────────────────────────────────────────────────────

    @ModifyConstant(method = "calculateWidth", constant = @Constant(intValue = 2))
    private static int travellingdimension$minWidth(int vanilla) {
        return NetherPortalSizes.getMinWidth();
    }

    @ModifyConstant(method = "calculateWidth", constant = @Constant(intValue = 21))
    private static int travellingdimension$maxWidth(int vanilla) {
        return NetherPortalSizes.getMax();
    }

    @ModifyConstant(method = "getDistanceUntilEdgeAboveFrame", constant = @Constant(intValue = 21))
    private static int travellingdimension$scanWidth(int vanilla) {
        return NetherPortalSizes.getMax();
    }

    // ── La hauteur ───────────────────────────────────────────────────────────

    @ModifyConstant(method = "calculateHeight", constant = @Constant(intValue = 3))
    private static int travellingdimension$minHeight(int vanilla) {
        return NetherPortalSizes.getMinHeight();
    }

    @ModifyConstant(method = "calculateHeight", constant = @Constant(intValue = 21))
    private static int travellingdimension$maxHeight(int vanilla) {
        return NetherPortalSizes.getMax();
    }

    @ModifyConstant(method = "getDistanceUntilTop", constant = @Constant(intValue = 21))
    private static int travellingdimension$scanHeight(int vanilla) {
        return NetherPortalSizes.getMax();
    }

    // ── La validation finale ─────────────────────────────────────────────────

    @ModifyConstant(method = "isValid", constant = @Constant(intValue = 2))
    private int travellingdimension$validMinWidth(int vanilla) {
        return NetherPortalSizes.getMinWidth();
    }

    @ModifyConstant(method = "isValid", constant = @Constant(intValue = 3))
    private int travellingdimension$validMinHeight(int vanilla) {
        return NetherPortalSizes.getMinHeight();
    }

    @ModifyConstant(method = "isValid", constant = @Constant(intValue = 21))
    private int travellingdimension$validMax(int vanilla) {
        return NetherPortalSizes.getMax();
    }
}
