// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.roumoulou.travellingdimension.config.ConfigManager;
import fr.roumoulou.travellingdimension.nether.NetherPortalBuilder;
import fr.roumoulou.travellingdimension.nether.NetherPortalLinks;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Liens de couleur sur les portails du Nether vanilla.
 *
 * <p>L'intervention est aussi petite qu'elle peut l'être : on enveloppe le SEUL appel par
 * lequel vanilla choisit un portail d'arrivée déjà existant, et on lui substitue le
 * partenaire coloré quand il y en a un. Tout le reste du chemin de Mojang est intact :
 * conversion des coordonnées, mesure du rectangle, création d'un portail quand rien n'est
 * trouvé, placement de l'entité à l'arrivée, son et ticket de chunk.
 *
 * <p>Sans couleur posée, {@code preferredExit} rend {@code null} et l'appel d'origine part
 * tel quel : le jeu se comporte exactement comme sans le mod. C'est la garantie recherchée,
 * et c'est pour ça que le crochet est ici plutôt qu'autour de la téléportation entière.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @WrapOperation(
            method = "getExitPortal",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/portal/PortalForcer;findClosestPortalPosition(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/level/border/WorldBorder;)Ljava/util/Optional;"
            )
    )
    private Optional<BlockPos> travellingdimension$preferColourLink(
            PortalForcer forcer,
            BlockPos target,
            boolean destinationIsNether,
            WorldBorder border,
            Operation<Optional<BlockPos>> original,
            // Les paramètres de getExitPortal, capturés pour savoir QUI voyage et D'OÙ.
            ServerLevel destinationLevel,
            Entity entity,
            BlockPos entryPos,
            BlockPos targetAgain,
            boolean destinationIsNetherAgain,
            WorldBorder borderAgain) {

        BlockPos linked = NetherPortalLinks.INSTANCE.preferredExit(
                destinationLevel, entity, entryPos, target, destinationIsNether, border);

        if (linked != null) {
            return Optional.of(linked);
        }
        return original.call(forcer, target, destinationIsNether, border);
    }

    /**
     * Le portail est créé AU POINT IDÉAL, et non là où le jeu trouve de la place.
     *
     * <p>{@code PortalForcer.createPortal} balaie une large zone à la recherche d'un endroit
     * jugé convenable, et se rabat sur un emplacement approximatif s'il n'en trouve pas : le
     * portail peut donc naître très loin du point calculé, sans qu'on puisse le prévoir. On
     * lui substitue une construction à la coordonnée exacte, avec dégagement et plateforme,
     * exactement comme dans la dimension de VOYAGE.
     *
     * <p>La TAILLE du portail d'où l'on part est recopiée quand {@code netherPortalCopySize}
     * le demande, d'où le passage de {@code entity} et {@code entryPos} : c'est tout ce qu'il
     * faut pour mesurer la source, l'entité n'ayant pas encore bougé à cet instant.
     *
     * <p>Le réglage coupé, l'appel d'origine repart tel quel et le jeu reprend son
     * comportement habituel.
     */
    @WrapOperation(
            method = "getExitPortal",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/portal/PortalForcer;createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;"
            )
    )
    private Optional<BlockUtil.FoundRectangle> travellingdimension$createAtIdealPoint(
            PortalForcer forcer,
            BlockPos target,
            Direction.Axis axis,
            Operation<Optional<BlockUtil.FoundRectangle>> original,
            // Les paramètres de getExitPortal, dont le niveau où l'on bâtit.
            ServerLevel destinationLevel,
            Entity entity,
            BlockPos entryPos,
            BlockPos targetAgain,
            boolean destinationIsNetherAgain,
            WorldBorder borderAgain) {

        if (!ConfigManager.INSTANCE.getCurrent().getNetherPortalPlacement()) {
            return original.call(forcer, target, axis);
        }
        return Optional.of(
                NetherPortalBuilder.INSTANCE.build(destinationLevel, target, axis, entity, entryPos));
    }
}
