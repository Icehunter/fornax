package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.material.MaterialScalarsHolder;
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
 * thread, once per section (re)build -- never per-frame.
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
            at = @At("HEAD"))
    private void fornax$harvestSection(ChunkBuildContext buildContext, CancellationToken cancellationToken,
                                        CallbackInfoReturnable<ChunkBuildOutput> cir) {
        SectionPos origin = this.renderContext.getOrigin();
        ClonedChunkSection[] neighborhood = this.renderContext.getSections();
        ClonedChunkSection center = findCenter(neighborhood, origin);
        if (center == null) {
            return; // shouldn't happen (the task's own origin section is always present), but never throw from a mixin hook
        }

        PalettedContainerRO<BlockState> blockData = center.getBlockData();
        // Never let a harvest failure abort Sodium's real mesh build (livefix, cave/edge-shimmer
        // regression, 2026-07-19): this hook is injected at HEAD of Sodium's own execute(), so an
        // uncaught exception here propagates exactly as if thrown from the start of that method --
        // Sodium's own real chunk-mesh-building body never runs, and that section's real terrain
        // silently never gets drawn (depth reads 0/"nothing here" for its whole screen footprint,
        // same as open sky). SectionHarvester.harvest walks every distinct block state's REAL baked
        // model (FaceColorResolver.resolveCrossGeometry/resolveCutoutRect, new this session) via
        // Minecraft's own model-manager APIs -- exactly the kind of call that can throw for an
        // edge-case block state this codebase hasn't seen yet. The `findCenter == null` check just
        // above already states the governing principle ("never throw from a mixin hook"); this
        // extends it to the much larger, less-audited harvest call. A caught failure here means that
        // ONE section's voxel occupancy/shadow data is stale/missing until the next successful
        // rebuild -- a small, contained gap -- instead of a whole missing render mesh masquerading
        // as open sky (which explained a real, live-reported bug: a bright procedural-sky disc and
        // sun-direction-correlated shimmer on cave walls, identical in every debug view including
        // ones that run before any fog/lighting code, because no terrain was drawn there at all).
        try {
            SectionHarvester.Result result = SectionHarvester.harvest(blockData,
                    MaterialScalarsHolder.current());
            VoxelWindow.onSectionHarvested(origin, result);
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
