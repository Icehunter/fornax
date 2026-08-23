package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

/**
 * Plain static side channel -- not a mixin -- carrying per-frame GPU buffer handles that {@code
 * DefaultChunkRenderer.render(...)} and {@code SodiumWorldRenderer}'s frame pipeline need but
 * cannot receive as method parameters without widening signatures Fornax does not own:
 * <ul>
 *   <li>{@code pbrSettingsBuffer}: {@code render(...)} is declared on the {@code ChunkRenderer}
 *   interface with an exact 9-parameter signature, and {@code ShaderChunkRenderer} is its only
 *   implementor -- widening that interface for one implementor to carry a 10th parameter is
 *   unnecessary risk, so {@code DefaultChunkRendererRenderMixin} reads the buffer from here
 *   instead of from a parameter.</li>
 *   <li>{@code uniformBuffer}: {@code FramePipeline}'s {@code computeSsao()}/{@code traceSsr()}/
 *   {@code resolveGBuffer()} need the same {@code GpuBuffer} that {@code SodiumWorldRenderer}'s
 *   private {@code uniformBufferManager} field holds; {@code SodiumWorldRenderer} exposes no
 *   public getter for that field.</li>
 * </ul>
 *
 * <p>Populated once per pass by {@code SodiumWorldRendererRenderLayerMixin}'s {@code @Inject} at
 * {@code HEAD} of {@code SodiumWorldRenderer.renderLayer(...)}, which has direct field access to
 * {@code uniformBufferManager} via {@code @Shadow} (it is mixed directly into {@code
 * SodiumWorldRenderer}). Consumed later the same frame by {@code DefaultChunkRendererRenderMixin}
 * (during the SOLID/CUTOUT/TRANSLUCENT draws themselves) and by {@code FramePipeline} (after the
 * opaque draws, driven by {@code SodiumWorldRendererOrchestrationMixin}'s {@code @Inject(RETURN)}
 * on {@code drawChunkLayer}).
 *
 * <p><b>Plain static fields, not {@link ThreadLocal}:</b> every writer and every reader of this
 * class runs on the client render thread only. {@code SodiumWorldRenderer.renderLayer}/{@code
 * drawChunkLayer}/{@code DefaultChunkRenderer.render} touch no {@code Thread}/{@code
 * ExecutorService} type at all -- the only executor Sodium's renderer touches anywhere is {@code
 * RenderSectionManager.getBuilder()}'s background chunk-mesh-build queue (a completely separate
 * subsystem from the per-frame draw path these methods run). Vanilla Minecraft's own {@code
 * LevelRenderer.renderLevel(...)}, the sole caller of {@code drawChunkLayer}, is itself only ever
 * invoked from the client's main render thread -- a standing invariant of Minecraft's renderer
 * architecture that {@code SodiumWorldRendererOrchestrationMixin} already relies on for its own
 * {@code FramePipeline} static state. A {@code ThreadLocal} would add indirection for a hazard
 * that provably cannot occur here.
 */
public final class ChunkRenderContextHolder {
    // Sodium 0.9.1: UniformBufferManager sub-allocates per-frame uniforms from a dynamically
    // sized ring buffer and getUniformBuffer() returns a GpuBufferSlice -- the OFFSET is
    // load-bearing (this frame's data lives mid-buffer), so the slice is carried whole and
    // consumers bind buffer+offset+length, never the raw buffer at offset 0.
    private static GpuBufferSlice uniformBuffer;
    private static GpuBuffer pbrSettingsBuffer;

    private ChunkRenderContextHolder() {
    }

    public static void set(GpuBufferSlice uniformBuffer, GpuBuffer pbrSettingsBuffer) {
        ChunkRenderContextHolder.uniformBuffer = uniformBuffer;
        ChunkRenderContextHolder.pbrSettingsBuffer = pbrSettingsBuffer;
    }

    public static GpuBufferSlice getUniformBuffer() {
        return uniformBuffer;
    }

    public static GpuBuffer getPbrSettingsBuffer() {
        return pbrSettingsBuffer;
    }
}
