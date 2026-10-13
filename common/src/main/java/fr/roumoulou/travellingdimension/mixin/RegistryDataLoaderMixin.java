// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import fr.roumoulou.travellingdimension.dimension.WorldgenLoadWatch;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.ReportedException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le garde-fou des copies autour du chargement des registres (voir WorldgenLoadWatch) : le témoin
 * de chargement d'une copie se pose quand le jeu charge les registres du monde avec elle, et se
 * lève quand ce chargement réussit.
 *
 * <p>La cible est la méthode par laquelle passe tout chargement des registres depuis les
 * datapacks : le serveur dédié, le monde existant, le serveur GameTest, et l'écran de création
 * d'un monde, qui charge sans serveur. Un événement de serveur ne verrait pas cet écran.
 *
 * <p>La seconde injection lit les erreurs que le jeu rapporte quand un chargement échoue : elles
 * disent si la copie est en cause. {@code logErrors} est privée ; elle porte le même nom et la
 * même signature en 26.1.2, 26.2 et 26.3.
 */
@Mixin(RegistryDataLoader.class)
public abstract class RegistryDataLoaderMixin {

    @ModifyReturnValue(
            method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN")
    )
    private static CompletableFuture<RegistryAccess.Frozen> travellingdimension$watchTheCopies(
            CompletableFuture<RegistryAccess.Frozen> loading,
            ResourceManager resources,
            List<HolderLookup.RegistryLookup<?>> lookups,
            List<RegistryDataLoader.RegistryData<?>> registries,
            Executor executor
    ) {
        return WorldgenLoadWatch.watch(loading, resources, registries);
    }

    @Inject(method = "logErrors", at = @At("RETURN"))
    private static void travellingdimension$readTheErrors(Map<ResourceKey<?>, Exception> errors, CallbackInfoReturnable<ReportedException> callback) {
        WorldgenLoadWatch.errorsReported(callback.getReturnValue(), errors.keySet());
    }
}
