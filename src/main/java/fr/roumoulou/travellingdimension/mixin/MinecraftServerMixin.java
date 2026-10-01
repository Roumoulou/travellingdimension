// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.roumoulou.travellingdimension.dev.DevWorld;
import fr.roumoulou.travellingdimension.dimension.GeneratorSwapper;
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys;
import fr.roumoulou.travellingdimension.dimension.WorldgenSelector;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Worldgen configurable : au moment de la création des mondes, le LevelStem de la
 * dimension de voyage est remplacé par un générateur construit depuis la config
 * (voir GeneratorSwapper). Le registre reste intact ; en cas d'erreur, le stem
 * d'origine (JSON vanilla large biomes) est utilisé tel quel.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @WrapOperation(
            method = "createLevels",
            at = @At(value = "NEW", target = "net/minecraft/server/level/ServerLevel")
    )
    private ServerLevel travellingdimension$swapTravelGenerator(
            MinecraftServer server,
            Executor executor,
            LevelStorageSource.LevelStorageAccess storageAccess,
            ServerLevelData levelData,
            ResourceKey<Level> dimension,
            LevelStem stem,
            boolean isDebug,
            long biomeZoomSeed,
            List<CustomSpawner> customSpawners,
            boolean tickTime,
            Operation<ServerLevel> original
    ) {
        if (TravelDimensionKeys.INSTANCE.getTRAVEL_LEVEL().equals(dimension)) {
            stem = GeneratorSwapper.swapTravelGenerator(server, stem);
            // Seed dédié : le zoom de biomes doit suivre le même seed que la génération.
            Long dedicatedSeed = WorldgenSelector.getDimensionSeed();
            if (dedicatedSeed != null) {
                biomeZoomSeed = BiomeManager.obfuscateSeed(dedicatedSeed);
            }
        } else if (Level.OVERWORLD.equals(dimension)) {
            // Superflat de développement - sans effet hors runClient / runServer.
            stem = DevWorld.flattenOverworld(server, stem);
        }
        return original.call(server, executor, storageAccess, levelData, dimension, stem, isDebug, biomeZoomSeed, customSpawners, tickTime);
    }
}
