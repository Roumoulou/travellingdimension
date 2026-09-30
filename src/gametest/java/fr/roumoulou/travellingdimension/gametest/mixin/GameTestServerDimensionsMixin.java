package fr.roumoulou.travellingdimension.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Les dimensions des datapacks sur le serveur GameTest.
 *
 * <p>Le serveur GameTest de Mojang bâtit ses dimensions depuis le préréglage plat et un registre
 * de {@code LevelStem} VIDE ({@code new MappedRegistry(...).freeze()}) : les dimensions déclarées
 * par les datapacks, donc VOYAGE, n'y existent pas, et le mod le dit au démarrage. Un serveur
 * dédié, lui, cuit le même préréglage avec le registre des dimensions chargées depuis les
 * datapacks. Ce mixin, propre au mod de test et jamais livré, fait la même chose pour le serveur
 * GameTest : sans lui, aucune traversée n'est testable.
 *
 * <p>La cible est la lambda de {@code GameTestServer.create}, par son nom de synthèse, lu dans
 * la classe de 26.2 : une montée de version peut la renommer, et c'est ce mixin qui le dira.
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerDimensionsMixin {

    @WrapOperation(
            method = "lambda$create$1",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/WorldDimensions;bake(Lnet/minecraft/core/Registry;)Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;"
            )
    )
    private static WorldDimensions.Complete travellingdimension$bakeWithDatapackDimensions(
            WorldDimensions dimensions,
            Registry<LevelStem> emptyRegistry,
            Operation<WorldDimensions.Complete> original,
            // Les paramètres de la lambda, capturés pour atteindre le contexte de chargement.
            LevelSettings settings,
            WorldLoader.DataLoadContext context) {
        Registry<LevelStem> datapackDimensions = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
        return original.call(dimensions, datapackDimensions);
    }
}
