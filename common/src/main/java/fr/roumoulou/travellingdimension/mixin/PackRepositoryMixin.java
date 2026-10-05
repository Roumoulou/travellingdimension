// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.mixin;

import fr.roumoulou.travellingdimension.dimension.WorldgenPacks;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Les copies préparées dans le dépôt de datapacks : tout dépôt bâti autour de la source vanilla
 * du jeu reçoit en plus celle du mod (voir WorldgenPacks). Sans copie préparée, rien n'est ajouté.
 *
 * <p>La cible est le constructeur, et non les fabriques de {@code ServerPacksSource} : l'écran de
 * création d'un monde bâtit son dépôt lui-même, sans passer par elles. Le constructeur est le
 * seul point commun au serveur dédié, au monde existant, au serveur GameTest et à cet écran.
 */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableSet;copyOf([Ljava/lang/Object;)Lcom/google/common/collect/ImmutableSet;")
    )
    private Object[] travellingdimension$addPreparedCopies(Object[] sources) {
        return WorldgenPacks.withPreparedCopies(sources);
    }
}
