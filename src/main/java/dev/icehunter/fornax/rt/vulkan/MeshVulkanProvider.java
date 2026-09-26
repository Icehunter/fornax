package dev.icehunter.fornax.rt.vulkan;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.BlockAtlasView;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.pack.graph.FornaxTextureUsage;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;
import dev.icehunter.fornax.rt.BufferQuery;
import dev.icehunter.fornax.rt.CasterCapture;
import dev.icehunter.fornax.rt.CelestialFill;
import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayReadiness;
import dev.icehunter.fornax.rt.RayRouter;
import dev.icehunter.fornax.rt.RayTier;
import dev.icehunter.fornax.rt.mesh.CasterSource;
import dev.icehunter.fornax.rt.mesh.MeshGrid;
import dev.icehunter.fornax.rt.mesh.TerrainCasterSnapshot;
import dev.icehunter.fornax.util.GpuFatalException;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;

import java.util.List;

/**
 * Celestial visibility at {@link RayTier#HARDWARE_MESH} on a Vulkan device with ray query:
 * hardware traversal of the exact chunk meshes the renderer already uploaded.
 *
 * <p>The Vulkan twin of {@code MeshMetalProvider}. The frame's graphics stream carries the copies
 * of changed meshes and the trace; {@link MeshVulkanTracer} builds on the compute queue, and the
 * frame adopts a finished build through a timeline wait. The image it traces into is its own;
 * {@link #publishCelestialVisibility()} copies it into {@code TerrainShadowResult} late in the
 * frame, where the pack first reads it.
 *
 * <p>Buffer-form queries are answered in place against the same structure the celestial fill
 * built this frame. A pack that declares queries without ray-traced shadows (or with a zero
 * receiving distance) gets a structure built from a camera window instead, once per frame, so a
 * pack that only wants GI or lamp queries is not starved by a shadow subscription it never made.
 *
 * <p>All native resources are render-thread confined.
 */
public final class MeshVulkanProvider implements CasterCapture {

    private RenderSectionManager casters;
    private boolean failed;
    private MeshVulkanTracer tracer;
    private GpuTexture output;
    private GpuTextureView outputView;
    private int outputResolution;
    private boolean traced;
    /** Whether {@link #ensureStructureForQuery} already ran this frame: several ray_query passes
     * attempt the build once, not once each. */
    private boolean queryBuildAttempted;
    private boolean reportedActive;
    private float reportedRadius = Float.NaN;
    @Override
    public RayTier tier() {
        return RayTier.HARDWARE_MESH;
    }

    /**
     * Both kinds. The kernel commits the nearest intersection and reads back its distance, flags,
     * surface word, atlas UV and normal, which is what closest hit means; a visibility caller reads
     * the same record and ignores the rest.
     */
    @Override
    public boolean answers(RayQueryKind kind) {
        return kind != null;
    }

    @Override
    public RayReadiness readiness() {
        if (failed) {
            return RayReadiness.notReady("a previous trace failed; raster owns shadows until pack reload");
        }
        String reason = VulkanRtSupport.unavailableReason();
        return reason == null ? RayReadiness.answering() : RayReadiness.notReady(reason);
    }

    @Override
    public void captureCasters(RenderSectionManager manager) {
        this.casters = manager;
    }

    @Override
    public void beginFrame() {
        casters = null;
        traced = false;
        queryBuildAttempted = false;
        ShadowFrameState.setRtDistance(0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_distance_blocks", 0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_meshes", 0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_dirty_meshes", 0);
    }

    @Override
    public void fillCelestialVisibility(CelestialFill request) {
        if (casters == null) {
            TerrainShadowResult.invalidate();
            return;
        }
        long started = System.nanoTime();
        try {
            traceFrame(request);
        } finally {
            GraphRunner.frameProfiler().record("RT shadows CPU", (System.nanoTime() - started) * 1e-6);
        }
    }

    @Override
    public void answer(BufferQuery query) {
        if (failed || query.rayCount() <= 0) {
            return;
        }
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null
                || !(BlockAtlasView.texture() instanceof VulkanGpuTexture atlasTexture)
                || !(BlockAtlasView.view() instanceof VulkanGpuTextureView atlasView)) {
            return;
        }
        try {
            if (tracer != null) adopt(device.createCommandEncoder());
            if ((tracer == null || !tracer.hasStructure()) && !queryBuildAttempted) {
                ensureStructureForQuery(device);
            }
            if (tracer == null || !tracer.hasStructure()) {
                // Nothing built this frame, so every record stays at tier 0 for the pack's own
                // fallback. Not a failure: a frame with no caster meshes has nothing exact to say.
                return;
            }
            if (query.atlasUvEncoding() == dev.icehunter.fornax.rt.AtlasUvEncoding.TEXEL_U16) {
                query.atlasUvEncoding().validateAtlasDimensions(atlasTexture.getWidth(0), atlasTexture.getHeight(0));
            }
            // The live render camera, rebased onto the structure's grid: the caller's origins are
            // camera-relative and the geometry is not.
            net.minecraft.world.phys.Vec3 camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
            float ox = MeshGrid.rebase(camera.x, tracer.originX());
            float oy = MeshGrid.rebase(camera.y, tracer.originY());
            float oz = MeshGrid.rebase(camera.z, tracer.originZ());
            int tier = tier().ordinal();
            // Recorded into the frame's own stream and left there: the passes that read these hits
            // run in the graphics stream behind it, and a reader the graph places on the compute
            // queue waits through GraphRunner's own handoff. A submit or flush here would host-wait
            // on the transient ring once per query.
            var encoder = device.createCommandEncoder();
            VulkanMetalInterop.recordIntoStream(encoder, cmd ->
                    tracer.answerQuery(cmd, query, tier, atlasTexture.vkImage(), atlasView.vkImageView(), ox, oy, oz));
        } catch (GpuDeviceLossException | GpuFatalException e) {
            throw e;
        } catch (RuntimeException e) {
            failed = true;
            FornaxMod.LOGGER.error("[Fornax] Vulkan mesh RT ray queries disabled until pack reload; queries left unanswered", e);
        }
    }

    /**
     * Builds the structure for a query when the celestial fill left none this frame: RT shadows
     * off, or a zero receiving distance, with the pack's GI or lamp passes still declaring buffer
     * queries. Selects casters around the live camera instead of the sun's light volume, which
     * this path has none of. Runs at most once per frame.
     */
    private void ensureStructureForQuery(VulkanDevice device) {
        queryBuildAttempted = true;
        RenderSectionManager manager = casters;
        if (manager == null) {
            return;
        }
        net.minecraft.world.phys.Vec3 camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
        double x = camera.x, y = camera.y, z = camera.z;
        List<CasterSource> sources = TerrainCasterSnapshot.snapshot(manager, x, y, z,
                TerrainCasterSnapshot.cameraWindow(TerrainCasterSnapshot.QUERY_REACH_BLOCKS));
        if (sources == null || sources.isEmpty()) {
            return;
        }
        ensureTracer(device);
        int ox = MeshGrid.origin(x), oy = MeshGrid.origin(y), oz = MeshGrid.origin(z);
        var encoder = device.createCommandEncoder();
        adopt(encoder);
        int sx = sectionOf(x), sy = sectionOf(y), sz = sectionOf(z);
        VulkanMetalInterop.recordIntoStream(encoder, cmd -> tracer.schedule(cmd, sources, ox, oy, oz, sx, sy, sz));
        signalCopies(encoder);
        tracer.retire(encoder::createFence);
        tracer.drainRetired();
    }

    /** The section a block coordinate sits in; what a build's nearest-first order is measured from. */
    private static int sectionOf(double coordinate) {
        return (int) Math.floor(coordinate / 16.0);
    }

    /**
     * Adopts a finished build before anything this frame records against the structure. The wait
     * it places on the frame's submission is for a value the compute queue has already signalled,
     * so it orders without stalling. It must precede the frame's recording, because Blaze3D closes
     * the current command buffer to place it.
     */
    private void adopt(com.mojang.blaze3d.vulkan.VulkanCommandEncoder encoder) {
        tracer.submitIfCopiesDone();
        tracer.adoptIfReady(value -> encoder.waitSemaphore(tracer.timelineSemaphore(), value,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT));
    }

    /** After the frame's recording: the copies it recorded are what the compute queue's build waits for. */
    private void signalCopies(com.mojang.blaze3d.vulkan.VulkanCommandEncoder encoder) {
        long value = tracer.takeCopySignal();
        if (value > 0) {
            encoder.signalSemaphore(tracer.timelineSemaphore(), value, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            // The build is submitted only once this frame has completed: see submitIfCopiesDone.
            tracer.copiesFenced(encoder.createFence());
        }
    }

    @Override
    public boolean publishCelestialVisibility() {
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        GpuTexture destination = TerrainShadowResult.texture();
        if (!traced || device == null || destination == null || output == null) {
            return false;
        }
        long source = ((VulkanGpuTexture) output).vkImage();
        long target = ((VulkanGpuTexture) destination).vkImage();
        int resolution = outputResolution;
        var encoder = device.createCommandEncoder();
        VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                MeshVulkanTracer.prepareOutputForCopy(cmd, source);
                VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, target, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.copyImage(cmd, stack, source, VK13.VK_IMAGE_LAYOUT_GENERAL,
                        target, VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_ASPECT_COLOR_BIT, resolution, resolution);
                VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, target, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
            }
        });
        // The pack reads this image later in the same graphics stream, so queue order is the
        // handoff; nothing is flushed.
        TerrainShadowResult.published();
        return true;
    }

    @Override
    public void close() {
        traced = false;
        if (tracer != null || output != null) {
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        }
        if (tracer != null) {
            tracer.close();
            tracer = null;
        }
        if (outputView != null) outputView.close();
        if (output != null) output.close();
        outputView = null;
        output = null;
        outputResolution = 0;
        TerrainShadowResult.invalidate();
        ShadowFrameState.setRtDistance(0);
        failed = false;
        reportedActive = false;
        reportedRadius = Float.NaN;
    }

    // --- private -----------------------------------------------------------------------------------

    private void traceFrame(CelestialFill request) {
        RenderSectionManager manager = casters;
        float radius = request.radiusBlocks();
        if (failed || radius <= 0) {
            // A zero receiving distance is a pack that did not subscribe to shadows this frame.
            TerrainShadowResult.invalidate();
            return;
        }
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null
                || !(BlockAtlasView.texture() instanceof VulkanGpuTexture atlasTexture)
                || !(BlockAtlasView.view() instanceof VulkanGpuTextureView atlasView)) {
            TerrainShadowResult.invalidate();
            return;
        }
        try {
            double x = request.cameraX(), y = request.cameraY(), z = request.cameraZ();
            int resolution = request.resolution();
            long phase = System.nanoTime();
            Matrix4f light = new Matrix4f().set(request.lightVp());
            TerrainCasterSnapshot.RegionFilter filter = TerrainCasterSnapshot.lightVolume(light);
            if (RayRouter.queryDemand()) {
                // One structure for both the sun shadow and a pack's own rays: see the Metal tier.
                filter = TerrainCasterSnapshot.union(filter,
                        TerrainCasterSnapshot.cameraWindow(TerrainCasterSnapshot.QUERY_REACH_BLOCKS));
            }
            List<CasterSource> sources = TerrainCasterSnapshot.snapshot(manager, x, y, z, filter);
            phase = record("RT mesh snapshot CPU", phase);
            if (sources == null) {
                // A caster that could not be read is a hole in the shadow; raster owns the frame.
                TerrainShadowResult.invalidate();
                return;
            }
            ensureTracer(device);
            ensureOutput(device, resolution);
            int ox = MeshGrid.origin(x), oy = MeshGrid.origin(y), oz = MeshGrid.origin(z);
            long outputImage = ((VulkanGpuTexture) output).vkImage();
            long outputImageView = ((VulkanGpuTextureView) outputView).vkImageView();
            int[] queued = new int[1];
            var encoder = device.createCommandEncoder();
            adopt(encoder);
            VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
                if (!sources.isEmpty()) {
                    queued[0] = tracer.schedule(cmd, sources, ox, oy, oz, sectionOf(x), sectionOf(y), sectionOf(z));
                }
                if (sources.isEmpty() || !tracer.hasStructure()) {
                    // Nothing live yet, or nothing to trace: the cleared image certifies nothing,
                    // and the pack reads that as raster until the first build is adopted.
                    MeshVulkanTracer.clearEmpty(cmd, outputImage);
                    return;
                }
                // The live structure's grid, not this frame's: a build in flight may carry a new
                // origin, and the camera is rebased onto whatever is being traversed.
                int lx = tracer.originX(), ly = tracer.originY(), lz = tracer.originZ();
                tracer.trace(cmd, atlasTexture.vkImage(), atlasView.vkImageView(), outputImage, outputImageView,
                        resolution, request, MeshGrid.rebase(x, lx), MeshGrid.rebase(y, ly), MeshGrid.rebase(z, lz));
            });
            signalCopies(encoder);
            // Everything an adoption replaced is freed once the frame's own submission completes:
            // an encoder fence rides that submission, so no flush is needed to carry it, and the
            // pack's readers follow in the same stream. Nothing is flushed or submitted here:
            // either one host-waits on the transient ring.
            tracer.retire(encoder::createFence);
            tracer.drainRetired();
            record("RT mesh record CPU", phase);

            traced = true;
            GraphRunner.frameProfiler().recordValue("rt_shadow_dirty_meshes", Math.max(queued[0], 0));
            if (sources.isEmpty() || !tracer.hasStructure()) {
                // Nothing traced certifies nothing: the cleared image publishes with zero
                // validity everywhere, which the pack reads as raster.
                return;
            }
            ShadowFrameState.raiseRtDistance(radius);
            GraphRunner.frameProfiler().recordValue("rt_shadow_distance_blocks", radius);
            GraphRunner.frameProfiler().recordValue("rt_shadow_meshes", tracer.meshCount());
            if (!reportedActive || radius != reportedRadius) {
                FornaxMod.LOGGER.info("[Fornax] Vulkan mesh RT shadows active: {} blocks receiving distance, {} terrain meshes; "
                                + "selection: {}; query window {}",
                        radius, sources.size(), TerrainCasterSnapshot.lastStats(), RayRouter.queryDemand() ? "on" : "off");
                reportedActive = true;
                reportedRadius = radius;
            }
        } catch (GpuDeviceLossException | GpuFatalException e) {
            throw e;
        } catch (RuntimeException e) {
            failed = true;
            traced = false;
            FornaxMod.LOGGER.error("[Fornax] Vulkan mesh RT shadows disabled until pack reload; using full raster shadows", e);
            TerrainShadowResult.invalidate();
        }
    }

    private void ensureTracer(VulkanDevice device) {
        if (tracer == null) {
            tracer = new MeshVulkanTracer(RtAllocator.forDevice(device));
        }
    }

    /** The tier's own RGBA32F image: a storage image the kernel writes and the publish copies out. */
    private void ensureOutput(VulkanDevice device, int resolution) {
        if (output != null && outputResolution == resolution) {
            return;
        }
        if (output != null) {
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
            outputView.close();
            output.close();
            output = null;
            outputView = null;
        }
        GpuTexture next = device.createTexture("Fornax Vulkan RT mesh shadow",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | FornaxTextureUsage.STORAGE,
                GpuFormat.RGBA32_FLOAT, resolution, resolution, 1, 1);
        GpuTextureView view;
        try {
            view = device.createTextureView(next);
            // VRAM is not zero-filled, and the clear settles the image into the layout every
            // barrier here assumes.
            device.createCommandEncoder().clearColorTexture(next, new Vector4f());
        } catch (RuntimeException e) {
            next.close();
            throw e;
        }
        output = next;
        outputView = view;
        outputResolution = resolution;
    }

    private static long record(String label, long from) {
        long at = System.nanoTime();
        GraphRunner.frameProfiler().record(label, (at - from) * 1e-6);
        return at;
    }
}
