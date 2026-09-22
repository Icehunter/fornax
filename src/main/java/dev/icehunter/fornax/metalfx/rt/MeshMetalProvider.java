package dev.icehunter.fornax.metalfx.rt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.BlockAtlasView;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.mixin.sodium.RenderSectionManagerAccessor;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;
import dev.icehunter.fornax.pipeline.VulkanPartialFlush;
import dev.icehunter.fornax.pass.shadow.ShadowCasterLists;
import dev.icehunter.fornax.pipeline.TerrainMeshRevision;
import dev.icehunter.fornax.rt.CelestialFill;
import dev.icehunter.fornax.rt.BufferQuery;
import dev.icehunter.fornax.rt.RayProvider;
import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayReadiness;
import dev.icehunter.fornax.rt.RayTier;
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

/**
 * Celestial visibility at {@link RayTier#HARDWARE_MESH}: hardware traversal of the exact chunk
 * meshes the renderer already uploaded, with no voxel approximation anywhere in the path.
 *
 * <p>The highest tier of the cascade, and the one the router runs first, at the frame point where
 * the terrain draw can overlap the trace. Everything it leaves unanswered, because the receiver
 * fell outside the cylinder or outside the warp domain, is what the tiers below it exist to fill.
 *
 * <p>All native resources are render-thread confined. The full relevant loaded caster volume
 * supplies the trace; receiver selection happens in the pack after depth publication.
 */
public final class MeshMetalProvider implements RayProvider {
    private record Source(MeshShadowTracer.Key key, long revision, VulkanGpuBuffer buffer,
                          TerrainMeshSelection.VertexRange range) {}
    private record Copy(Source source, MetalRtGeometry.ExportedBuffer buffer) {}
    /** Input buffers of the live structure. Replaced as a whole when a new structure takes
     * over; a build in progress keeps its own map, pendingCopies. */
    private Map<MeshShadowTracer.Key, Copy> copies = new HashMap<>();
    private MeshShadowTracer tracer;
    private VulkanMetalInterop.SharedTimeline timeline;
    private VulkanMetalInterop.InteropImage atlas, depth;
    private long nextValue = 1, lastValue;
    private boolean failed, reportedActive;
    /**
     * The receiving radius the log last reported. A one-shot activation line goes stale the moment
     * the pack's distance slider moves, and the reader has no way to tell a stale line from a
     * current one, which is exactly the wrong property for the line people diagnose from.
     */
    private float reportedRadius = Float.NaN;
    /** This frame's caster source, handed over on the render thread. Cleared every frame. */
    private RenderSectionManager casters;

    /**
     * The grid origin and structure this tier's debug view traces against, set at the last
     * promotion. The debug view can lag a frame behind this tier's own trace, and it is not
     * the voxel window's.
     */
    private long debugTlas;
    private float debugOriginX, debugOriginY, debugOriginZ;
    /** The camera in the grid-rebased frame the structure is built in. A ray-query caller's origin
     * is camera-relative, so this is what puts it where the geometry is. */
    private float structureCameraX, structureCameraY, structureCameraZ;
    /**
     * The shared event value this tier's Metal work signals once its structures are built and its
     * own trace is done. Anything else that reads those structures has to wait for it: they are
     * built in this tier's command buffer, and a later command buffer on the same queue may begin
     * before an earlier one finishes.
     */
    private long debugEvent, debugEventValue;

    /**
     * This frame's traced image, handed to the tier below to fill and publish. Null when this tier
     * did not trace, which is the signal for the tier below to start from its own cleared image.
     */
    private CascadeImage cascade;

    /** Buffer-form queries, lazily built: a pack that declares none never allocates any of this. */
    private RayQueryInterop rayQueries;

    /** Tracks which structure is live and when the next one takes over. Tested on its own in
     * {@code StructureSwapTest}. Every handle it returns is freed through this class. */
    private StructureSwap swap = new StructureSwap();
    /** The Metal objects behind the structure swap.live() names: its BLAS, vertex and primitive
     * buffers, and its TLAS. Null until the first promotion. */
    private MeshShadowTracer.Built liveBuilt;
    /** The grid origin live's instances are placed against. It can differ from this frame's own
     * origin until the next structure takes over. */
    private float liveOriginX, liveOriginY, liveOriginZ;
    /** The highest timeline value any trace against live has used. Live's buffers free only once
     * a newer structure passes this value. Metal frees a buffer once the GPU finishes with it,
     * but Vulkan does not. Freeing one here while a read is still running crashes the process. */
    private long liveTraceValue;
    /** A structure that is built but not yet live, with the buffers and grid origin it was
     * built against. Null when nothing is mid-build. A new build never starts while one is
     * already outstanding (see traceFrame's diff gate). This clears only at promotion or at
     * close()/teardown. */
    private MeshShadowTracer.Built pendingBuilt;
    private Map<MeshShadowTracer.Key, Copy> pendingCopies;
    private float pendingOriginX, pendingOriginY, pendingOriginZ;
    private long pendingBuildValue;
    /** Buffers swap.retire() is tracking, keyed by the vkBuffer handle swap hands back on drain.
     * StructureSwap only knows opaque numbers; this map turns one back into the object
     * destroyOne needs. */
    private final Map<Long, Copy> retiringCopies = new HashMap<>();

    public MeshMetalProvider() {
    }

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

    /**
     * Per-frame availability is only the failure latch here. Whether this tier answers at all also
     * depends on the pack's receiving distance, which arrives with the request rather than at the
     * top of the frame, so that gate lives in the fill and keeps its own invalidation.
     */
    @Override
    public RayReadiness readiness() {
        return failed
                ? RayReadiness.notReady("a previous trace failed; raster owns shadows until pack reload")
                : RayReadiness.answering();
    }

    /**
     * Hands over this frame's caster source. Called on the render thread at the point where the
     * renderer's uploads for the frame are complete, which is the only point the mesh metadata and
     * the arena handles can be read together.
     */
    public void captureCasters(RenderSectionManager manager) {
        this.casters = manager;
    }

    @Override
    public void fillCelestialVisibility(CelestialFill request) {
        if (casters == null) {
            // Nothing handed this frame's caster source over, so there is nothing to trace and no
            // reason to believe last frame's image. Raster owns the frame.
            TerrainShadowResult.invalidate();
            return;
        }
        long started = System.nanoTime();
        try {
            traceFrame(request);
        } finally {
            // System.nanoTime reports nanoseconds; convert elapsed CPU time to milliseconds.
            GraphRunner.frameProfiler().record("RT shadows CPU", (System.nanoTime() - started) * 1e-6);
        }
    }

    /**
     * Answers a batch against the structures this tier already built for the frame's shadows. It
     * adds no build of its own: a ray query rides the acceleration structure the shadow trace
     * produced, which is why the tier is free to answer at all.
     */
    @Override
    public void answer(BufferQuery query) {
        if (debugTlas == 0) {
            // Nothing built this frame, so every record stays at tier 0 for the tier below. Not a
            // failure: a frame with no caster meshes has nothing exact to say.
            return;
        }
        if (rayQueries == null) {
            rayQueries = new RayQueryInterop();
        }
        rayQueries.answer(query, tier().ordinal(), debugTlas, residentResources(),
                atlas != null ? atlas.mtlTexture : 0L,
                structureCameraX, structureCameraY, structureCameraZ);
    }

    @Override
    public void beginFrame() {
        casters = null;
        // Clears only this frame's output (debugTlas, the debug event, the cascade handoff). It
        // frees nothing and leaves liveBuilt and swap alone. Without it a frame that stops early
        // would publish last frame's image and camera.
        forgetDebugScene();
        resetFrame();
    }

    private void resetFrame() {
        dev.icehunter.fornax.pass.shadow.ShadowFrameState.setRtDistance(0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_distance_blocks", 0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_meshes", 0);
        GraphRunner.frameProfiler().recordValue("rt_shadow_dirty_meshes", 0);
    }

    /** Called after clear + light-camera commit, before globals upload or any terrain shadow draw. */
    private void traceFrame(CelestialFill request) {
        RenderSectionManager manager = casters;
        double x = request.cameraX(), y = request.cameraY(), z = request.cameraZ();
        int resolution = request.resolution();
        float radius = request.radiusBlocks();
        // A zero receiving distance is a pack that did not subscribe, not a pack that wants an
        // empty answer, so it short-circuits ahead of any device probe.
        if (failed || !MetalRtSupport.isAvailableFor(radius > 0)) {
            TerrainShadowResult.invalidate();
            if (tracer != null && !failed) close();
            forgetDebugScene();
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
            Matrix4f light = new Matrix4f().set(request.lightVp());
            long phase = System.nanoTime();
            List<Source> sources = snapshot(manager, x, y, z, light);
            if (sources == null || sources.isEmpty()) {
                TerrainShadowResult.invalidate();
                if (tracer != null) close();
                forgetDebugScene();
                return;
            }
            phase = record("RT mesh snapshot CPU", phase);
            ensureImages(device, atlasSource, resolution);
            if (timeline == null) timeline = VulkanMetalInterop.createSharedTimeline(device);
            if (tracer == null) tracer = new MeshShadowTracer();

            // Swap point. Promote the pending build only when its own command buffer reports
            // done. The shared event value cannot say that: the Vulkan side signals the same
            // event every frame from another queue, so the counter can pass a build that is
            // still running.
            boolean ready;
            try {
                ready = pendingBuilt != null && tracer.isBuildComplete(pendingBuilt);
            } catch (RuntimeException e) {
                // isBuildComplete throws only when the build failed. Nothing will read
                // pendingBuilt's or pendingCopies' buffers again, so both free here instead of
                // waiting for close().
                if (pendingBuilt != null) {
                    tracer.discardBuild(pendingBuilt);
                    for (Copy copy : pendingCopies.values()) MetalRtGeometry.destroyOne(device, copy.buffer);
                    pendingBuilt = null;
                    pendingCopies = null;
                }
                throw e;
            }
            swap.promoteIfReady(ready);
            // ready already means pendingBuilt is not null, so it also says whether this call
            // promoted. Comparing handles instead would miss a promotion when the rebuilt TLAS
            // reuses the same native pointer.
            boolean promoted = ready;
            if (promoted) {
                tracer.promote(pendingBuilt);
                List<Copy> orphaned = new ArrayList<>();
                for (var entry : copies.entrySet()) {
                    if (pendingCopies.get(entry.getKey()) != entry.getValue()) orphaned.add(entry.getValue());
                }
                // liveTraceValue still holds the old structure's last trace value here. That is
                // the value any reader of its buffers must pass before they are safe to free.
                queueRetireCopies(orphaned, liveTraceValue);
                copies = pendingCopies;
                liveBuilt = pendingBuilt;
                liveOriginX = pendingOriginX; liveOriginY = pendingOriginY; liveOriginZ = pendingOriginZ;
                liveTraceValue = pendingBuildValue;
                pendingBuilt = null;
                pendingCopies = null;
            }
            // signaledValue reads the shared event's counter without waiting. Only the retire
            // queue below uses it. Promotion above depends on the build command buffer's own
            // status, not on this event.
            long signalled = Objc.msgSendLong(timeline.mtlSharedEvent, Objc.selector("signaledValue"));
            drainRetiredCopies(device, signalled);
            GraphRunner.frameProfiler().recordValue("rt_shadow_structure_swaps", promoted ? 1 : 0);

            // This picks a new grid origin near the camera so nearby float coordinates stay small
            // and precise. Grid cells are sixteen sections wide; the size does not depend on the
            // pack's visible radius.
            int ox = ((int) Math.floor(x / 256)) * 256;
            int oy = ((int) Math.floor(y / 256)) * 256;
            int oz = ((int) Math.floor(z / 256)) * 256;

            // A build never starts while one is already outstanding. Otherwise, diffing against
            // a stale live would flag the same sources dirty every frame. Each build would be
            // discarded before it is ever promoted, starving under steady camera motion. The
            // next diff waits for that build to promote, then runs against live as it then stands.
            boolean needsBuild = false;
            Set<MeshShadowTracer.Key> retained = new HashSet<>();
            if (pendingBuilt == null) {
                boolean contentDirty = false;
                for (Source source : sources) {
                    retained.add(source.key);
                    Copy existing = copies.get(source.key);
                    if (existing == null || !sameSource(existing.source, source)) contentDirty = true;
                }
                needsBuild = contentDirty || !retained.equals(copies.keySet())
                        || (liveBuilt != null && (ox != liveOriginX || oy != liveOriginY || oz != liveOriginZ));
            }
            phase = record("RT mesh diff CPU", phase);

            List<Copy> dirty;
            Map<MeshShadowTracer.Key, Copy> candidateCopies = null;
            if (needsBuild) {
                candidateCopies = new HashMap<>(copies);
                List<Copy> created = new ArrayList<>();
                for (Source source : sources) {
                    Copy old = copies.get(source.key);
                    if (old == null || !sameSource(old.source, source)) {
                        Copy copy = new Copy(source, MetalRtGeometry.createExportedBuffer(device, source.range.byteLength()));
                        candidateCopies.put(source.key, copy);
                        created.add(copy);
                    }
                }
                candidateCopies.keySet().retainAll(retained);
                dirty = created;
            } else {
                dirty = List.of();
            }
            GraphRunner.frameProfiler().recordValue("rt_shadow_dirty_meshes", dirty.size());

            var encoder = device.createCommandEncoder();
            long copyValue = nextValue++;
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
            phase = record("RT mesh record CPU", phase);
            encoder.signalSemaphore(timeline.vkSemaphore, copyValue, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            // The handoff is the timeline signal reaching Metal. A full submit also waits for
            // resource retirement, which this path does not need: nothing below host-waits on it.
            ((VulkanPartialFlush) encoder).fornax$flushPending();
            phase = record("RT mesh submit CPU", phase);
            // This updates after each stage commits, not only at the end. An exception in a later
            // stage must not leave it short of what close() has to wait for.
            lastValue = copyValue;

            long buildValue = 0;
            if (needsBuild) {
                List<MeshShadowTracer.Mesh> meshes = new ArrayList<>(sources.size());
                for (Source source : sources) {
                    Copy copy = candidateCopies.get(source.key);
                    meshes.add(new MeshShadowTracer.Mesh(source.key, source.revision, copy.buffer.mtlBuffer(),
                            Math.toIntExact(source.range.vertexCount()),
                            (float) ((long) source.key.x() * 16 - ox), (float) ((long) source.key.y() * 16 - oy),
                            (float) ((long) source.key.z() * 16 - oz)));
                }
                phase = record("RT mesh instances CPU", phase);
                buildValue = nextValue++;
                try {
                    pendingBuilt = tracer.encodeBuild(VulkanMetalInterop.metalCommandQueue(), meshes,
                            timeline.mtlSharedEvent, copyValue, buildValue);
                } catch (RuntimeException e) {
                    // The floor here is buildValue, not copyValue. encodeBuild can throw after its
                    // own command buffer is already committed, since Map.copyOf, List.copyOf or
                    // retain can still fail there. When that happens, the decode reading these
                    // buffers needs buildValue signaled, not only copyValue, before they are safe
                    // to free. buildValue is always at least copyValue, so it is correct even when
                    // nothing was submitted.
                    queueRetireCopies(dirty, buildValue);
                    throw e;
                }
                // Set together with pendingBuilt, before the check below can throw. If it throws,
                // pendingBuilt is already set, and close()'s discardPending() must find these
                // fields consistent. Otherwise it crashes partway through teardown and leaks the
                // tracer, atlas, depth and timeline.
                pendingCopies = candidateCopies;
                pendingOriginX = ox; pendingOriginY = oy; pendingOriginZ = oz;
                pendingBuildValue = buildValue;
                // needsBuild is only true when pendingBuilt is null (see the diff gate above). So
                // nothing was offered since the last promotion or teardown, and this call must
                // return 0. A nonzero return would mean a pending build was silently discarded
                // here instead of through discardPending(). That is the stale-handle hazard this
                // gate exists to rule out.
                long discardedByOffer = swap.offer(pendingBuilt.structure());
                if (discardedByOffer != 0) throw new IllegalStateException(
                        "offer() discarded a live pending structure " + discardedByOffer
                                + "; a build started while one was already outstanding");
                lastValue = buildValue;
                phase = record("RT mesh build CPU", phase);
            }
            GraphRunner.frameProfiler().recordValue("rt_shadow_structure_pending", pendingBuilt != null ? 1 : 0);

            long traceValue = 0;
            if (liveBuilt != null) {
                structureCameraX = (float) (x - liveOriginX);
                structureCameraY = (float) (y - liveOriginY);
                structureCameraZ = (float) (z - liveOriginZ);
                traceValue = nextValue++;
                tracer.encodeVisibilityTrace(VulkanMetalInterop.metalCommandQueue(), liveBuilt,
                        atlas.mtlTexture, depth.mtlTexture, resolution, request.inverseLightVp(), request.lightVp(),
                        structureCameraX, structureCameraY, structureCameraZ, radius,
                        request.bias(), request.filterGuardUv(), timeline.mtlSharedEvent, copyValue, traceValue);
                record("RT mesh trace CPU", phase);
                // The Metal dispatch. Every other timer here measures the CPU that records it.
                double traceGpu = tracer.lastGpuMillis();
                if (!Double.isNaN(traceGpu)) GraphRunner.frameProfiler().record("RT mesh trace GPU", traceGpu);
                lastValue = traceValue;
                liveTraceValue = traceValue;
                debugTlas = liveBuilt.structure();
                debugOriginX = liveOriginX; debugOriginY = liveOriginY; debugOriginZ = liveOriginZ;
                debugEvent = timeline.mtlSharedEvent;
                debugEventValue = traceValue;
                // No copy-back here: this image becomes the cascade's image, and the tier below
                // fills the rest and publishes once. That costs one Vulkan submit for both tiers
                // instead of two. Copying to TerrainShadowResult and reading it back cost more
                // than the ray tracing itself.
                cascade = new CascadeImage(depth, timeline.mtlSharedEvent, traceValue, resolution);
                dev.icehunter.fornax.pass.shadow.ShadowFrameState.raiseRtDistance(radius);
                GraphRunner.frameProfiler().recordValue("rt_shadow_distance_blocks", radius);
                GraphRunner.frameProfiler().recordValue("rt_shadow_meshes", sources.size());
                if (!reportedActive || radius != reportedRadius) {
                    FornaxMod.LOGGER.info(
                            "[Fornax] Mesh RT shadows active: {} blocks receiving distance, {} terrain meshes; "
                                    + "separate sun/moon depth", radius, sources.size());
                    reportedActive = true;
                    reportedRadius = radius;
                }
            } else {
                // A build may be under way, but nothing is ready to trace yet. This happens on the
                // first frame after load, or right after a teleport repopulates the caster set.
                // Raster owns this frame; the test view shows magenta instead of a partial
                // structure.
                cascade = null;
                TerrainShadowResult.invalidate();
            }
        } catch (com.mojang.blaze3d.GpuDeviceLossException | dev.icehunter.fornax.util.GpuFatalException e) {
            throw e;
        } catch (RuntimeException e) {
            resetFrame();
            failed = true;
            FornaxMod.LOGGER.error("[Fornax] Mesh RT shadows disabled until pack reload; using full raster shadows", e);
            TerrainShadowResult.invalidate();
            // A throw after debugTlas and cascade were set this frame must not leave them
            // describing a failed frame.
            forgetDebugScene();
        }
    }

    /**
     * Discards a structure that is built but not live, only from {@link #close()}. Its build
     * command buffer already committed, so its Metal objects are safe to free right away (see
     * {@link MeshShadowTracer#discardBuild}). Buffers not shared with live are queued for freeing
     * the same way as any other retirement (see {@link #queueRetireCopies}).
     */
    private void discardPending() {
        tracer.discardBuild(pendingBuilt);
        // pendingBuilt and pendingCopies are always set together, so this is never null in
        // practice. The check is defensive: cheap insurance against a bug leaving one null.
        if (pendingCopies != null) {
            List<Copy> orphaned = new ArrayList<>();
            for (var entry : pendingCopies.entrySet()) {
                if (copies.get(entry.getKey()) != entry.getValue()) orphaned.add(entry.getValue());
            }
            queueRetireCopies(orphaned, pendingBuildValue);
        }
        pendingBuilt = null;
        pendingCopies = null;
    }

    /** Queues buffers for {@link #drainRetiredCopies}, keyed by vkBuffer handle. This lets a later
     * drain turn swap's plain number back into the object destroyOne needs. */
    private void queueRetireCopies(List<Copy> retired, long afterValue) {
        if (retired.isEmpty()) return;
        List<Long> handles = new ArrayList<>(retired.size());
        for (Copy copy : retired) {
            handles.add(copy.buffer.vkBuffer());
            retiringCopies.put(copy.buffer.vkBuffer(), copy);
        }
        swap.retire(handles, afterValue);
    }

    /** Frees every Vulkan-exported buffer whose queued retirement value is reached. */
    private void drainRetiredCopies(VulkanDevice device, long signalledValue) {
        for (long handle : swap.drainRetired(signalledValue)) {
            Copy copy = retiringCopies.remove(handle);
            if (copy != null) MetalRtGeometry.destroyOne(device, copy.buffer);
        }
    }

    /**
     * The structure and frame the scene debug traces when it is pointed at chunk meshes, or null
     * when this tier has not built one. Read on the render thread inside the same frame.
     */
    private void forgetDebugScene() {
        debugTlas = 0;
        debugEvent = 0;
        debugEventValue = 0;
        cascade = null;
    }

    /**
     * The celestial image this tier traced into, the event that says when the trace is done, and
     * the resolution it was traced at. The tier below fills the same image and publishes it.
     */
    public record CascadeImage(VulkanMetalInterop.InteropImage image, long readyEvent,
            long readyValue, int resolution) {}

    /** This frame's traced image, or null when this tier did not trace. */
    public CascadeImage cascadeImage() {
        return cascade;
    }

    /**
     * Delivers this tier's own image when no lower tier did. Normally the voxel tier publishes,
     * because it writes the same image last; this is the path for a frame where that tier sat out
     * or failed. Without it a working mesh trace would reach the pack as nothing at all, and the
     * stale image left behind reads as valid.
     */
    @Override
    public boolean publishCelestialVisibility() {
        CascadeImage image = cascade;
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        GpuTexture texture = TerrainShadowResult.texture();
        if (image == null || device == null || texture == null) {
            return false;
        }
        long destination = ((VulkanGpuTexture) texture).vkImage();
        var encoder = device.createCommandEncoder();
        // Signalled by this tier's own trace, early in the frame, so the wait is satisfied here.
        encoder.waitSemaphore(timeline.vkSemaphore, image.readyValue(), VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, image.image());
                VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.copyImage(cmd, stack, image.image().image, image.image().layout,
                        destination, VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                        image.resolution(), image.resolution());
                VulkanMetalInterop.finishInteropTransferRead(cmd, stack, image.image());
                VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
            }
        });
        // This value comes from nextValue, not from readyValue() + 1. Every value signaled on this
        // timeline must come from that one counter. A later frame's nextValue could otherwise
        // reuse the same number. Vulkan forbids signaling the same timeline value twice. A wait
        // keyed to that value could then return for the wrong work.
        long published = nextValue++;
        encoder.signalSemaphore(timeline.vkSemaphore, published, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        // Same handoff as the trace above, same reason: the reader is later on the graphics queue,
        // which a partial flush keeps in order, and the retirement wait is not part of the handoff.
        ((VulkanPartialFlush) encoder).fornax$flushPending();
        lastValue = published;
        TerrainShadowResult.published();
        return true;
    }

    public DebugScene debugScene() {
        return debugTlas == 0 ? null
                : new DebugScene(debugTlas, debugOriginX, debugOriginY, debugOriginZ,
                        residentResources(), debugEvent, debugEventValue);
    }

    /** A structure plus the grid origin its instances are positioned against. */
    public record DebugScene(long structure, float originX, float originY, float originZ,
            java.util.List<Long> resources, long readyEvent, long readyValue) {}

    /** Every handle the structure refers to; an encoder cannot see through it to them. */
    private java.util.List<Long> residentResources() {
        java.util.List<Long> out = new ArrayList<>(copies.size() * 3);
        if (tracer == null) {
            return out;
        }
        tracer.appendResidentResources(out);
        return out;
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

    private void ensureImages(VulkanDevice device, VulkanGpuTexture source, int resolution) {
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

    /**
     * Times one phase of the fill and hands back the clock for the next.
     *
     * <ul>
     *   <li>snapshot: reading the caster set out of the renderer.
     *   <li>diff: deciding which meshes changed.
     *   <li>record and submit: the Vulkan command buffer, which carries a full atlas copy.
     *   <li>instances: building the per-mesh list the tracer takes.
     *   <li>trace: the Metal encode and dispatch.
     * </ul>
     *
     * <p>One span cannot say which phase a cost belongs to.
     */
    private static long record(String label, long from) {
        long at = System.nanoTime();
        // System.nanoTime reports nanoseconds; the profiler takes milliseconds.
        GraphRunner.frameProfiler().record(label, (at - from) * 1e-6);
        return at;
    }

    private void await(VulkanDevice device) {
        if (timeline != null && lastValue > 0) VulkanMetalInterop.waitTimeline(device, timeline, lastValue);
    }

    @Override
    public void close() {
        forgetDebugScene();
        if (rayQueries != null) {
            rayQueries.close();
            rayQueries = null;
        }
        resetFrame();
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device != null) {
            await(device);
            // The wait above means every buffer here, live, pending, or already queued for
            // freeing, is safe to free right away. discardPending() only queues its orphaned buffers.
            // The retiringCopies loop below frees them, the same as any other queued retirement
            // once the wait makes it safe.
            if (pendingBuilt != null) discardPending();
            if (tracer != null) tracer.close();
            tracer = null;
            for (Copy copy : copies.values()) MetalRtGeometry.destroyOne(device, copy.buffer);
            for (Copy copy : retiringCopies.values()) MetalRtGeometry.destroyOne(device, copy.buffer);
            copies.clear();
            retiringCopies.clear();
            VulkanMetalInterop.destroyImage(device, atlas);
            VulkanMetalInterop.destroyImage(device, depth);
            if (timeline != null) VK13.vkDestroySemaphore(device.vkDevice(), timeline.vkSemaphore, null);
        }
        TerrainShadowResult.invalidate();
        atlas = depth = null; timeline = null;
        swap = new StructureSwap();
        liveBuilt = null; liveOriginX = liveOriginY = liveOriginZ = 0; liveTraceValue = 0;
        pendingBuilt = null; pendingCopies = null; pendingOriginX = pendingOriginY = pendingOriginZ = 0; pendingBuildValue = 0;
        lastValue = 0; nextValue = 1; failed = reportedActive = false; reportedRadius = Float.NaN;
    }
}
