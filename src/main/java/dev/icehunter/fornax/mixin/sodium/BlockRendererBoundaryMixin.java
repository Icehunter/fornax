package dev.icehunter.fornax.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.icehunter.fornax.voxel.VoxelBoundaryCapture;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Observes the terrain platform bridge's final Fabric model emission, before raster culling.
 * The bridge is absent before its introduction in Sodium 0.9.2; those versions keep the trusted
 * static-model harvest. With no active voxel consumer the original call is unchanged.
 * Late culling follows each final cullFace. Custom cullTest group decisions on null-cull quads
 * cannot be reconstructed from a single emission. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.frapi.FRAPIEmitter", remap = false)
public class BlockRendererBoundaryMixin {
    // The bridge invokes the Fabric interface; BlockRenderer calls the platform service.
    @WrapOperation(method = "emitModel", at = @At(value = "INVOKE", target =
            "Lnet/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel;emitQuads("
            + "Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter;"
            + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;"
            + "Ljava/util/function/Predicate;)V"))
    private void fornax$captureBoundary(FabricBlockStateModel model, QuadEmitter emitter,
            BlockAndTintGetter world, BlockPos pos, BlockState state, RandomSource random,
            Predicate<Direction> cull, Operation<Void> original) {
        VoxelBoundaryCapture.emit(model, emitter, world, pos, state, cull,
                selectedCull -> original.call(model, emitter, world, pos, state, random, selectedCull));
    }
}
