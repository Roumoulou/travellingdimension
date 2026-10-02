// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion.mixin;

import fr.roumoulou.travellingdimension.config.ConfigManager;
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Option de config `mobDensity` : multiplicateur de densité des mobs dans la
 * dimension de voyage.
 *
 * Le plafond global de spawn par catégorie est `maxParChunk * spawnableChunkCount / 289`
 * (cf. NaturalSpawner.SpawnState#canSpawnForCategoryGlobal). Multiplier le nombre de
 * chunks "spawnables" transmis à createState multiplie donc linéairement tous les
 * plafonds, uniquement pour la dimension de voyage.
 *
 * Mixin de la lignée 26.1, dans le module mc-26.1 : ses deux cibles y ont les signatures
 * de 26.2, mais changent en 26.3 (`tickChunks` perd son `long`, `createState` prend le
 * `ServerLevel` au lieu des entités), chaque module de version porte donc la sienne.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @ModifyArg(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"
            ),
            index = 0
    )
    private int travellingdimension$scaleMobCapInTravelDimension(int spawnableChunkCount) {
        if (TravelDimensionKeys.INSTANCE.getTRAVEL_LEVEL().equals(this.level.dimension())) {
            double density = ConfigManager.INSTANCE.getCurrent().getMobDensity();
            if (density != 1.0) {
                return (int) Math.round(spawnableChunkCount * density);
            }
        }
        return spawnableChunkCount;
    }
}
