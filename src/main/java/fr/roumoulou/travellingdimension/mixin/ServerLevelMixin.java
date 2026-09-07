package fr.roumoulou.travellingdimension.mixin;

import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys;
import fr.roumoulou.travellingdimension.dimension.WorldgenSelector;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seed dédié de la dimension de voyage : getSeed() alimente le RandomState (bruit de
 * génération) et l'état des structures (ChunkMap). Le remplacer UNIQUEMENT pour cette
 * dimension découple totalement son terrain du seed du monde : un terrain connu et
 * navigable autour de (0,0), identique sur tous les mondes.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void travellingdimension$useDedicatedSeed(CallbackInfoReturnable<Long> cir) {
        Long seed = WorldgenSelector.getDimensionSeed();
        if (seed != null) {
            ServerLevel self = (ServerLevel) (Object) this;
            if (TravelDimensionKeys.INSTANCE.getTRAVEL_LEVEL().equals(self.dimension())) {
                cir.setReturnValue(seed);
            }
        }
    }
}
