package fr.roumoulou.travellingdimension.mixin;

import fr.roumoulou.travellingdimension.config.ConfigManager;
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Option de config `structures = false` : désactive la génération des structures
 * (villages, donjons, ...) uniquement dans la dimension de voyage.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "createStructures", at = @At("HEAD"), cancellable = true)
    private void travellingdimension$skipStructuresInTravelDimension(
            RegistryAccess registryAccess,
            ChunkGeneratorStructureState state,
            StructureManager structureManager,
            ChunkAccess centerChunk,
            StructureTemplateManager structureTemplateManager,
            ResourceKey<Level> level,
            CallbackInfo ci
    ) {
        if (!ConfigManager.INSTANCE.getCurrent().getStructures()
                && TravelDimensionKeys.INSTANCE.getTRAVEL_LEVEL().equals(level)) {
            ci.cancel();
        }
    }
}
