package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.voxel.VoxelWindow;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Queues each section's voxel-grid harvest the moment Sodium (re)builds it, reusing Sodium's own
 * change detection (a block edit already triggers a rebuild; this piggybacks on that event) rather
 * than building a second one. {@link VoxelWindow#queueMeshTriggeredHarvest} hands it to its own
 * background worker, which groups repeat events for one section, never inline on Sodium's own
 * chunk-build worker thread.
 *
 * <p><b>Must not harvest inline on Sodium's own meshing thread.</b> The worker reads vanilla's
 * section storage after the mesh event and sorts the fixed baked parts it collects into the voxel
 * palette. The queue bounds that work and cleans it up, but makes no promise that block-model work
 * on other threads is held apart; see {@link VoxelWindow}'s method doc and
 * {@code docs/ARCHITECTURE.md} section 12.
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
        // No pack has attached a voxel-grid registry, or the window has zero radius: nothing will
        // ever read this section's harvest, so skip queuing it entirely. This is also the only way
        // to keep this hook a true no-op in the documented "no pack active" default state.
        if (!VoxelWindow.needsHarvest()) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return; // never reached mid-frame in practice; never throw from a mixin hook regardless
        }
        VoxelWindow.queueMeshTriggeredHarvest(level, this.renderContext.getOrigin());
    }
}
