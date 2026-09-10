package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.voxel.SectionHarvester;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Harvests each section's real block data for the voxel grid the moment Sodium (re)builds it --
 * reusing Sodium's own change-detection (a block edit already triggers a rebuild; this just piggybacks
 * on that event) rather than building a second one. Runs on Sodium's background chunk-build worker
 * thread, once per section (re)build, never per frame. It sits just after BlockRenderCache.init:
 * at the head of execute the slice still holds the previous task, and the tint and light need this
 * one.
 *
 * <p><b>Injection target note:</b> this method exists twice in the compiled class due to generic
 * erasure -- the real method returning {@code ChunkBuildOutput}, and a compiler-generated bridge
 * returning the erased {@code BuilderTaskOutput}. Verified via {@code javap -v} against
 * sodium-fabric-0.9.0+mc26.2.jar: the real method is {@code (Lnet/caffeinemc/mods/sodium/client/render/
 * chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)
 * Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;} with flags {@code
 * ACC_PUBLIC} only, while the bridge shares the same name but returns {@code BuilderTaskOutput} and
 * carries {@code ACC_BRIDGE | ACC_SYNTHETIC}. This mixin's {@code @Inject} targets the real method by
 * its full descriptor (not the bare name "execute") so Mixin's target resolution can never land on the
 * bridge instead.
 */
@Mixin(ChunkBuilderMeshingTask.class)
public abstract class ChunkBuilderMeshingTaskMixin {
    @Shadow
    private ChunkRenderContext renderContext;

    @Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
            + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/"
                    + "BlockRenderCache;init(Lnet/caffeinemc/mods/sodium/client/world/cloned/ChunkRenderContext;)V",
                    shift = At.Shift.AFTER))
    private void fornax$harvestSection(ChunkBuildContext buildContext, CancellationToken cancellationToken,
                                        CallbackInfoReturnable<ChunkBuildOutput> cir) {
        SectionPos origin = this.renderContext.getOrigin();
        ClonedChunkSection[] neighborhood = this.renderContext.getSections();
        ClonedChunkSection center = findCenter(neighborhood, origin);
        if (center == null) {
            return; // shouldn't happen (the task's own origin section is always present), but never throw from a mixin hook
        }

        PalettedContainerRO<BlockState> blockData = center.getBlockData();
        // Never throw from this hook. It runs inside Sodium's own execute(), so a throw leaves the
        // section undrawn and its depth reads 0, like open sky. harvest() walks real baked models
        // through Minecraft's model APIs and can throw on a block state this code has not seen; a
        // caught failure costs one section's voxel data until the next rebuild.
        try {
            // Sodium's own world slice. Without it grass, leaves and vines are stored atlas-grey.
            SectionHarvester.Result result = SectionHarvester.harvestCurrent(blockData,
                    buildContext.cache.getWorldSlice(),
                    origin.minBlockX(), origin.minBlockY(), origin.minBlockZ());
            if (result != null) VoxelWindow.onSectionHarvested(origin, result);
        } catch (Throwable t) {
            FornaxMod.LOGGER.error("Voxel harvest failed for section {} -- Sodium's own mesh build "
                    + "still proceeds, but this section's voxel occupancy/shadow data stays stale "
                    + "until the next successful (re)harvest", origin, t);
        }
    }

    private static ClonedChunkSection findCenter(ClonedChunkSection[] neighborhood, SectionPos origin) {
        for (ClonedChunkSection section : neighborhood) {
            if (section.getPosition().equals(origin)) {
                return section;
            }
        }
        return null;
    }
}
