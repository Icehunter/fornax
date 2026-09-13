package dev.icehunter.fornax.metalfx.rt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.BlockAtlasView;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.mixin.sodium.RenderSectionManagerAccessor;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;
import dev.icehunter.fornax.pass.shadow.ShadowCasterLists;
import dev.icehunter.fornax.pipeline.TerrainMeshRevision;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Receiving-distance shadows from accepted GPU meshes, independent of voxel harvesting.
 * All native resources are render-thread confined. The full relevant loaded caster volume supplies
 * both backends; receiver selection happens in the pack after independent depth publication. */
public final class TerrainShadowPass {
    private record Source(MeshShadowTracer.Key key, long revision, VulkanGpuBuffer buffer,
                          TerrainMeshSelection.VertexRange range) {}
    private record Copy(Source source, MetalRtGeometry.ExportedBuffer buffer) {}
    private static final Map<MeshShadowTracer.Key, Copy> copies = new HashMap<>();
    private static MeshShadowTracer tracer;
    private static VulkanMetalInterop.SharedTimeline timeline;
    private static VulkanMetalInterop.InteropImage atlas, depth;
    private static long nextValue = 1, lastValue;
    private static boolean failed, reportedActive;

    private TerrainShadowPass() {}

    public static void resetFrame() {
        ShadowFrameState.setRtDistance(0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_distance_blocks", 0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_meshes", 0);
    }

    /** Called after clear + light-camera commit, before globals upload or any terrain shadow draw. */
    public static void render(RenderSectionManager manager, double x, double y, double z, int resolution, float shadowDistance) {
        resetFrame();
        float radius = Math.min(GraphRunner.rayTracedShadowDistanceBlocks(), shadowDistance);
        if (failed || !MetalRtSupport.isAvailableFor(radius > 0)) {
            TerrainShadowResult.invalidate();
            if (tracer != null && !failed) close();
            return;
        }
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null || !(BlockAtlasView.texture() instanceof VulkanGpuTexture atlasSource)) {
            TerrainShadowResult.invalidate();
            return;
        }
        try {
            // Metadata and arena handles are read together on the owning render thread, after this
            // frame's accepted uploads. A failed validation must not leave holes in raster coverage.
            List<Source> sources = snapshot(manager, x, y, z, ShadowFrameState.current());
            if (sources == null || sources.isEmpty()) {
                TerrainShadowResult.invalidate();
                if (tracer != null) close();
                return;
            }
            ensureImages(device, atlasSource, resolution);
            if (timeline == null) timeline = VulkanMetalInterop.createSharedTimeline(device);
            if (tracer == null) tracer = new MeshShadowTracer();

            Set<MeshShadowTracer.Key> retained = new HashSet<>();
            List<Copy> dirty = new ArrayList<>();
            boolean retired = false;
            for (Source source : sources) {
                retained.add(source.key);
                Copy old = copies.get(source.key);
                if (old == null || !sameSource(old.source, source)) {
                    if (!retired) { await(device); retired = true; }
                    // Native BLAS only reads decoded data after its construction; input buffers can
                    // retire once the previous Metal dispatch and Vulkan copy-back have completed.
                    Copy copy = new Copy(source, MetalRtGeometry.createExportedBuffer(device, source.range.byteLength()));
                    copies.put(source.key, copy);
                    if (old != null) MetalRtGeometry.destroyOne(device, old.buffer);
                    dirty.add(copy);
                }
            }
            if (!retained.containsAll(copies.keySet())) {
                if (!retired) await(device);
                var it = copies.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    if (!retained.contains(entry.getKey())) {
                        MetalRtGeometry.destroyOne(device, entry.getValue().buffer);
                        it.remove();
                    }
                }
            }

            var encoder = device.createCommandEncoder();
            long value = nextValue;
            nextValue += 3; // Vulkan input / Metal output / Vulkan copy-back on one timeline.
            VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    // Includes prior accepted arena transfer writes before raw copies. No renderer
                    // buffer address is retained as a substitute for the mutation revision stamp.
                    var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                            .srcAccessMask(VK13.VK_ACCESS_MEMORY_WRITE_BIT)
                            .dstAccessMask(VK13.VK_ACCESS_TRANSFER_READ_BIT);
                    VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                            VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
                    for (Copy copy : dirty) {
                        var region = VkBufferCopy.calloc(1, stack)
                                .srcOffset(copy.source.range.byteOffset()).dstOffset(0).size(copy.source.range.byteLength());
                        VK13.vkCmdCopyBuffer(cmd, copy.source.buffer.vkBuffer(), copy.buffer.vkBuffer(), region);
                    }
                    // Animated alpha changes in-place without reallocating the atlas. Copy each
                    // active frame so leaves and animated cutouts use current texture contents.
                    VulkanMetalInterop.prepareGeneralTransferRead(cmd, stack, atlasSource.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                    VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, atlas);
                    VulkanMetalInterop.copyImage(cmd, stack, atlasSource.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                            atlas.image, atlas.layout, VK13.VK_IMAGE_ASPECT_COLOR_BIT, atlas.width, atlas.height);
                    VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, atlas);
                    VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, depth);
                }
            });
            encoder.signalSemaphore(timeline.vkSemaphore, value, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            encoder.submit();
            lastValue = value;
            // Arena relocation/free does not know about the raw vkCmdCopyBuffer. Complete copies
            // before returning to the owning renderer, only on mesh-change frames, not every frame.
            if (!dirty.isEmpty()) await(device);

            // Stable nearby grid origin preserves float precision without rebasing TLAS each step.
            // Sixteen-section grid cells keep near coordinates small; an arithmetic precision
            // choice only, independent of the pack's visible radius.
            int ox = ((int) Math.floor(x / 256)) * 256;
            int oy = ((int) Math.floor(y / 256)) * 256;
            int oz = ((int) Math.floor(z / 256)) * 256;
            List<MeshShadowTracer.Mesh> meshes = new ArrayList<>(sources.size());
            for (Source source : sources) {
                var key = source.key;
                Copy copy = copies.get(key);
                meshes.add(new MeshShadowTracer.Mesh(key, source.revision, copy.buffer.mtlBuffer(),
                        Math.toIntExact(source.range.vertexCount()), (float) ((long) key.x() * 16 - ox), (float) ((long) key.y() * 16 - oy), (float) ((long) key.z() * 16 - oz)));
            }
            Matrix4f light = ShadowFrameState.current();
            tracer.trace(VulkanMetalInterop.metalCommandQueue(), meshes, atlas.mtlTexture, depth.mtlTexture,
                    resolution, new Matrix4f(light).invert().get(new float[16]), light.get(new float[16]),
                    (float) (x - ox), (float) (y - oy), (float) (z - oz), radius,
                    ShadowFrameState.currentBias(), GraphRunner.rayTracedShadowFilterGuardUv(resolution), timeline.mtlSharedEvent, value, value + 1);
            // Do not enqueue a wait when submission throws. Later asynchronous GPU/device faults
            // remain device failures; returning here certifies submission, not completion.
            encoder = device.createCommandEncoder();
            encoder.waitSemaphore(timeline.vkSemaphore, value + 1, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    long destination = ((VulkanGpuTexture) TerrainShadowResult.texture()).vkImage();
                    VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, depth);
                    VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                    VulkanMetalInterop.copyImage(cmd, stack, depth.image, depth.layout, destination,
                            VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_ASPECT_COLOR_BIT, resolution, resolution);
                    VulkanMetalInterop.finishInteropTransferRead(cmd, stack, depth);
                    VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                }
            });
            encoder.signalSemaphore(timeline.vkSemaphore, value + 2, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            encoder.submit();
            lastValue = value + 2;
            // Queue order guarantees the graph sees this frame's completed copy. A shader can only
            // use it where A certifies a traced texel and its receiving position is inside the range.
            TerrainShadowResult.published();
            ShadowFrameState.setRtDistance(radius);
            GraphRunner.frameProfiler().recordValue("rt_shadow_distance_blocks", radius);
            GraphRunner.frameProfiler().recordValue("rt_shadow_meshes", meshes.size());
            if (!reportedActive) {
                FornaxMod.LOGGER.info("[Fornax] Mesh RT shadows active: {} blocks receiving distance, {} terrain meshes; separate sun/moon depth", radius, meshes.size());
                reportedActive = true;
            }
        } catch (com.mojang.blaze3d.GpuDeviceLossException | dev.icehunter.fornax.util.GpuFatalException e) {
            throw e;
        } catch (RuntimeException e) {
            resetFrame();
            failed = true;
            FornaxMod.LOGGER.error("[Fornax] Mesh RT shadows disabled until pack reload; using full raster shadows", e);
            TerrainShadowResult.invalidate();
        }
    }

    private static boolean sameSource(Source a, Source b) {
        return a.revision == b.revision && a.buffer == b.buffer && a.range.equals(b.range);
    }

    private static List<Source> snapshot(RenderSectionManager manager, double x, double y, double z, Matrix4f light) {
        List<Source> result = new ArrayList<>();
        var regions = ((RenderSectionManagerAccessor) manager).fornax$getRegions();
        Vector4f scratch = new Vector4f();
        for (RenderRegion region : regions.getLoadedRegions()) {
            // The light volume, not the receiving range: a distant blocker can shadow nearby
            // ground. Bounds include FornaxChunkVertex's packed [-8,24] model overhang.
            if (!ShadowCasterLists.aabbIntersectsShadowVolume(light,
                    (long) region.getChunkX() * 16 - 8 - x, (long) region.getChunkY() * 16 - 8 - y,
                    (long) region.getChunkZ() * 16 - 8 - z,
                    ((long) region.getChunkX() + RenderRegion.REGION_WIDTH) * 16 + 8 - x,
                    ((long) region.getChunkY() + RenderRegion.REGION_HEIGHT) * 16 + 8 - y,
                    ((long) region.getChunkZ() + RenderRegion.REGION_LENGTH) * 16 + 8 - z, scratch)) continue;
            var resources = region.getResources();
            if (resources == null) continue;
            GpuBuffer geometry = resources.getGeometryBuffer();
            for (int local = 0; local < RenderRegion.REGION_SIZE; local++) {
                int sx = region.getChunkX() + LocalSectionIndex.unpackX(local);
                int sy = region.getChunkY() + LocalSectionIndex.unpackY(local);
                int sz = region.getChunkZ() + LocalSectionIndex.unpackZ(local);
                if (!ShadowCasterLists.aabbIntersectsShadowVolume(light,
                        (long) sx * 16 - 8 - x, (long) sy * 16 - 8 - y, (long) sz * 16 - 8 - z,
                        (long) sx * 16 + 24 - x, (long) sy * 16 + 24 - y, (long) sz * 16 + 24 - z, scratch)) continue;
                for (boolean cutout : new boolean[] {false, true}) {
                    var storage = region.getStorage(cutout ? DefaultTerrainRenderPasses.CUTOUT : DefaultTerrainRenderPasses.SOLID);
                    if (storage == null) continue;
                    long pointer = storage.getDataPointer(local);
                    if (pointer == 0) continue;
                    long[] counts = new long[7]; // public ModelQuadFacing.COUNT ABI: six axes + unassigned.
                    long total = 0;
                    for (int i = 0; i < counts.length; i++) {
                        counts[i] = SectionRenderDataUnsafe.getVertexCount(pointer, i);
                        total += counts[i];
                    }
                    if (total == 0) continue;
                    if (!(geometry instanceof VulkanGpuBuffer buffer)
                            || (geometry.usage() & GpuBuffer.USAGE_COPY_SRC) == 0
                            || !((Object) storage instanceof TerrainMeshRevision stamps)) return null;
                    var range = TerrainMeshSelection.validRange(SectionRenderDataUnsafe.getBaseVertex(pointer),
                            counts, geometry.size(), SectionRenderDataUnsafe.isLocalIndex(pointer));
                    if (range.isEmpty() || range.get().vertexCount() > Integer.MAX_VALUE) return null;
                    result.add(new Source(new MeshShadowTracer.Key(sx, sy, sz, cutout), stamps.fornax$revision(local), buffer, range.get()));
                }
            }
        }
        return result;
    }

    private static void ensureImages(VulkanDevice device, VulkanGpuTexture source, int resolution) {
        int format = VulkanMetalInterop.mapFormat(source.getFormat().toString());
        if (atlas == null || atlas.width != source.getWidth(0) || atlas.height != source.getHeight(0) || atlas.vkFormat != format) {
            await(device);
            VulkanMetalInterop.destroyImage(device, atlas);
            atlas = null;
            atlas = VulkanMetalInterop.createImage(device, source.getWidth(0), source.getHeight(0), format,
                    VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        }
        if (depth == null || depth.width != resolution) {
            await(device);
            VulkanMetalInterop.destroyImage(device, depth);
            depth = null;
            depth = VulkanMetalInterop.createImage(device, resolution, resolution, VK13.VK_FORMAT_R32G32B32A32_SFLOAT,
                    VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_STORAGE_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK13.VK_IMAGE_ASPECT_COLOR_BIT);

        }
    }

    private static void await(VulkanDevice device) {
        if (timeline != null && lastValue > 0) VulkanMetalInterop.waitTimeline(device, timeline, lastValue);
    }

    public static void close() {
        resetFrame();
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device != null) {
            await(device);
            if (tracer != null) tracer.close();
            tracer = null;
            for (Copy copy : copies.values()) MetalRtGeometry.destroyOne(device, copy.buffer);
            copies.clear();
            VulkanMetalInterop.destroyImage(device, atlas);
            VulkanMetalInterop.destroyImage(device, depth);
            if (timeline != null) VK13.vkDestroySemaphore(device.vkDevice(), timeline.vkSemaphore, null);
        }
        TerrainShadowResult.invalidate();
        atlas = depth = null; timeline = null;
        lastValue = 0; nextValue = 1; failed = reportedActive = false;
    }
}
