package dev.icehunter.fornax.rt.vulkan;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanCommandPool;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import dev.icehunter.fornax.pass.compute.ComputePipelineBuilder;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pipeline.FramePacing;
import dev.icehunter.fornax.rt.CelestialFill;
import dev.icehunter.fornax.rt.RayTier;
import dev.icehunter.fornax.rt.mesh.CasterSource;
import dev.icehunter.fornax.rt.mesh.MeshGrid;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * The Vulkan half of the exact-mesh tier: keeps one acceleration structure over the renderer's
 * uploaded terrain and traces celestial visibility against it.
 *
 * <p>The frame's graphics stream carries only what must be there: the copy of a changed mesh's
 * packed vertices out of the renderer's arena (the arena is the graphics queue's), and the trace
 * against the structure that is live. Decoding, every bottom-level build and the top-level build
 * are recorded and submitted on the compute queue, waiting on a timeline value the graphics queue
 * signals once the copies are in, and signalling a later value when the build is complete. The
 * host polls that value; the frame that sees it passed waits for it on the GPU and adopts the new
 * structure, so a large rebuild overlaps the frames that render while it builds, and a frame
 * traces last build's structure rather than stalling for this one. One build is in flight at a
 * time; a change that lands while one is building is found by the next diff.
 *
 * <p>Per-mesh work is decoded per revision and cached: an unchanged mesh keeps its triangles and
 * its structure across builds, and only the top-level structure, which is cheap, is rebuilt when
 * the set or the grid origin changes. Bottom-level builds in one submission each get their own
 * scratch region, so the driver can run them side by side; only a batch that would exceed the
 * scratch budget waits for the one before it. Memory a build replaced waits for an encoder fence
 * from the frame that adopted the replacement: nothing here is destroyed until that frame's
 * submission has completed.
 *
 * <p>Render-thread confined.
 */
public final class MeshVulkanTracer implements AutoCloseable {

    /** Bytes of {@code MeshShadowConstants} in {@code rt_mesh_shadow.comp}: 2 x mat4 + vec4 + 5 scalars, std140. */
    public static final int CONSTANT_BYTES = 176;
    /** The decode kernel's push block: three device addresses as uvec2, a count, one word of pad. */
    public static final int DECODE_PUSH_BYTES = 32;
    /** One primitive record: surface word, tint word, three UV pairs. */
    public static final int PRIMITIVE_BYTES = 32;
    /** Three float3 positions. */
    public static final int POSITION_BYTES_PER_TRIANGLE = 36;
    public static final int DECODE_LOCAL_SIZE = 64;
    public static final int TRACE_LOCAL_SIZE = 8;

    /** {@code rt_mesh_shadow.comp}'s bindings, in binding order. */
    static final List<Integer> SHADOW_BINDINGS = List.of(
            VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
            KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,
            VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);

    /** {@code rt_ray_query.comp}'s bindings, in binding order. */
    static final List<Integer> QUERY_BINDINGS = List.of(
            KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,
            VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    /** The query kernel's push block: four words, a vec3 at 16, the encoding at 28. */
    public static final int QUERY_PUSH_BYTES = 32;
    public static final int QUERY_LOCAL_SIZE = 64;
    /** Descriptor sets for buffer queries, used round-robin: a pack may run several ray_query
     * passes a frame, and a set may not be rewritten while a submitted dispatch still reads it. */
    static final int QUERY_SETS = 16;

    private static final String DECODE_RESOURCE = "/assets/fornax/shaders_engine/rt_mesh_decode.comp";
    private static final String SHADOW_RESOURCE = "/assets/fornax/shaders_engine/rt_mesh_shadow.comp";
    private static final String QUERY_RESOURCE = "/assets/fornax/shaders_engine/rt_ray_query.comp";
    private static final int FRAMES = FramePacing.FRAMES_IN_FLIGHT;
    private static final long CLOSE_TIMEOUT_NANOS = 5_000_000_000L;
    /** A debug switch: {@code -Dfornax.rt.vulkan.alphaTest=false} commits every candidate without
     * the atlas cutout test. Read once, so no two frames disagree about holes. */
    static final boolean ALPHA_TEST = Boolean.parseBoolean(System.getProperty("fornax.rt.vulkan.alphaTest", "true"));

    /** A mesh's decoded triangles and its structure, valid while its source is unchanged. */
    private record Cached(CasterSource source, RtBuffer positions, RtBuffer primitives,
                          AccelerationStructures.Structure blas) {
    }

    private record Retiring(GpuFence fence, List<Runnable> frees) {
    }

    /** The structure the traces read: replaced whole when a build is adopted. */
    private record Live(AccelerationStructures.Structure tlas, RtBuffer instances, RtBuffer meshTable,
                        Map<CasterSource.Key, Cached> cache, int originX, int originY, int originZ) {
    }

    /**
     * A build recorded for the compute queue. It is submitted only once {@code copyFence}, the
     * frame that recorded the copies, has completed (or at once when nothing was copied), and
     * adopted once the timeline passes {@code doneValue}.
     */
    private static final class Pending {
        final VkCommandBuffer commands;
        final long copyValue;
        final long doneValue;
        final Live result;
        final List<Runnable> buildFrees;
        GpuFence copyFence;
        boolean submitted;

        Pending(VkCommandBuffer commands, long copyValue, long doneValue, Live result, List<Runnable> buildFrees) {
            this.commands = commands;
            this.copyValue = copyValue;
            this.doneValue = doneValue;
            this.result = result;
            this.buildFrees = buildFrees;
        }
    }

    /** Scratch one compute submission may hold at once. A burst of builds past this is split into
     * batches that wait for each other, not one allocation the size of the burst. 256 MB covers
     * hundreds of section meshes a batch. */
    static final long SCRATCH_BUDGET_BYTES = 256L << 20;
    /**
     * How long a cached mesh outlives its absence from the caster snapshot. A section the renderer
     * is re-meshing is missing from the snapshot for a frame or two. Dropped at once, the structure
     * has a hole where a wall was, and with a build in flight the hole stays live for the whole
     * round trip, every lamp ray behind it leaking. Held, the section comes back at the same
     * revision and costs nothing, or at a new one and is rebuilt. Two seconds: past any re-mesh,
     * and short enough that a section that left is gone before its stale shadow reads as wrong. */
    static final long ABSENCE_GRACE_NANOS = 2_000_000_000L;
    /**
     * The most triangles one build takes on, nearest meshes first; changed meshes past it wait for
     * the next build. Every 16 blocks the camera moves, a slab of sections crosses the window's
     * edge at once, and a build that takes them all lands their host allocation and compute-queue
     * build time in one frame. Half a million triangles is about ten dense section meshes, or a
     * few dozen ordinary ones: small enough that the build shares the GPU with the frame, large
     * enough that a slab drains in a handful of round trips. */
    static final long BUILD_BUDGET_TRIANGLES = 512L * 1024;

    private final RtAllocator allocator;
    private final VulkanDevice device;
    private final int scratchAlignment;
    private final ComputePipelineBuilder.CompiledComputePipeline decode;
    private final ComputePipelineBuilder.CompiledComputePipeline shadow;
    private final ComputePipelineBuilder.CompiledComputePipeline rayQuery;
    private final long descriptorPool;
    private final long[] sets = new long[FRAMES];
    private final long[] querySets = new long[QUERY_SETS];
    private int queryRing;
    private final RtBuffer[] constants = new RtBuffer[FRAMES];
    private final GpuSampler atlasSampler;
    private int ring;

    private final RtTimeline timeline;
    private final VulkanQueue computeQueue;
    private final VulkanCommandPool buildPool;
    private Live live;
    private Pending pending;
    /** The copy value {@link #schedule} allocated and the caller has not yet signalled. */
    private long copySignalDue;
    /** When each cached key was last in a snapshot; what {@link #ABSENCE_GRACE_NANOS} is measured from. */
    private final Map<CasterSource.Key, Long> lastSeenNanos = new java.util.HashMap<>();
    private final ArrayDeque<Retiring> retiring = new ArrayDeque<>();
    /** What the last adoption replaced, until {@link #retire} puts a fence behind it. */
    private List<Runnable> pendingFrees = new ArrayList<>();

    public MeshVulkanTracer(RtAllocator allocator) {
        this.allocator = allocator;
        this.device = allocator.device();
        this.scratchAlignment = AccelerationStructures.scratchAlignment(device);
        this.timeline = RtTimeline.create(device);
        this.computeQueue = device.computeQueue();
        this.buildPool = new VulkanCommandPool(device, computeQueue);
        ByteBuffer decodeSpirv = null, shadowSpirv = null, querySpirv = null;
        ComputePipelineBuilder.CompiledComputePipeline decodePipeline = null, shadowPipeline = null;
        try {
            decodeSpirv = ComputeShaderCompiler.compileToSpirv(readResource(DECODE_RESOURCE), "rt_mesh_decode.comp",
                    Shaderc.shaderc_glsl_compute_shader, ComputeShaderCompiler.SpirvTarget.VULKAN_1_2);
            shadowSpirv = ComputeShaderCompiler.compileToSpirv(readResource(SHADOW_RESOURCE), "rt_mesh_shadow.comp",
                    Shaderc.shaderc_glsl_compute_shader, ComputeShaderCompiler.SpirvTarget.VULKAN_1_2);
            querySpirv = ComputeShaderCompiler.compileToSpirv(readResource(QUERY_RESOURCE), "rt_ray_query.comp",
                    Shaderc.shaderc_glsl_compute_shader, ComputeShaderCompiler.SpirvTarget.VULKAN_1_2);
            decodePipeline = ComputePipelineBuilder.buildWithDescriptorLayout(device, decodeSpirv, List.of(), DECODE_PUSH_BYTES);
            // The shadow kernel declares no push constants; the layout still needs a nonzero range.
            shadowPipeline = ComputePipelineBuilder.buildWithDescriptorLayout(device, shadowSpirv, SHADOW_BINDINGS, 4);
            this.rayQuery = ComputePipelineBuilder.buildWithDescriptorLayout(device, querySpirv, QUERY_BINDINGS, QUERY_PUSH_BYTES);
            this.shadow = shadowPipeline;
            this.decode = decodePipeline;
        } catch (RuntimeException e) {
            for (ComputePipelineBuilder.CompiledComputePipeline made : new ComputePipelineBuilder.CompiledComputePipeline[] {decodePipeline, shadowPipeline}) {
                if (made != null) {
                    ComputePipelineBuilder.destroy(device, made.pipeline(), made.pipelineLayout(), made.descriptorSetLayout(), made.shaderModule());
                }
            }
            buildPool.destroy();
            timeline.close();
            throw e;
        } finally {
            if (decodeSpirv != null) MemoryUtil.memFree(decodeSpirv);
            if (shadowSpirv != null) MemoryUtil.memFree(shadowSpirv);
            if (querySpirv != null) MemoryUtil.memFree(querySpirv);
        }
        this.descriptorPool = createPoolAndSets();
        for (int i = 0; i < FRAMES; i++) {
            constants[i] = RtBuffer.create(allocator, CONSTANT_BYTES,
                    VK13.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT, "RT shadow constants");
        }
        // Nearest, clamped: the cutout test reads the exact texel the rasteriser's alpha test read.
        this.atlasSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
    }

    public RtAllocator allocator() {
        return allocator;
    }

    /** Whether a structure is live to trace. A build in flight is not one until it is adopted. */
    public boolean hasStructure() {
        return live != null;
    }

    /** The timeline the caller signals (copies) and waits on (adoption). */
    public long timelineSemaphore() {
        return timeline.semaphore();
    }

    /** Grid origin of the live structure: what a camera is rebased onto before a trace. */
    public int originX() { return live == null ? 0 : live.originX(); }
    public int originY() { return live == null ? 0 : live.originY(); }
    public int originZ() { return live == null ? 0 : live.originZ(); }
    public int meshCount() { return live == null ? 0 : live.cache().size(); }

    /**
     * Brings the structure up to date with {@code sources} at grid origin {@code (ox, oy, oz)}
     * without putting build work in the frame. The changed meshes' packed vertices are copied out
     * of the arena into {@code cmd}, the frame's graphics stream, because the arena is that
     * queue's. The decode and every structure build are recorded for the compute queue;
     * {@link #submitIfCopiesDone} submits them once the frame that carries the copies has
     * completed, waiting on the GPU for the timeline value that marks the copies. That value is
     * left in {@link #takeCopySignal} for the caller to signal on the frame's encoder once the
     * frame's command buffer is closed, and the fence that proves the frame complete comes through
     * {@link #copiesFenced}. Returns how many meshes went to the build, zero when nothing changed,
     * or -1 while an earlier build is in flight: the diff after that build is adopted finds the
     * change.
     */
    public int schedule(VkCommandBuffer cmd, List<CasterSource> sources, int ox, int oy, int oz,
                        int cameraSectionX, int cameraSectionY, int cameraSectionZ) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("a structure over no meshes is an empty scene; clear instead");
        }
        long now = System.nanoTime();
        for (CasterSource source : sources) lastSeenNanos.put(source.key(), now);
        if (pending != null) {
            return -1;
        }
        Map<CasterSource.Key, Cached> current = live == null ? Map.of() : live.cache();
        List<CasterSource> dirty = new ArrayList<>();
        Map<CasterSource.Key, Cached> next = new LinkedHashMap<>();
        for (CasterSource source : sources) {
            Cached existing = current.get(source.key());
            if (existing != null && existing.source().sameContent(source)) {
                next.put(source.key(), existing);
            } else {
                dirty.add(source);
                next.put(source.key(), null);
            }
        }
        // A cached mesh the snapshot did not name this time is kept for a while: see
        // ABSENCE_GRACE_NANOS. Its triangles and structure are its own, so nothing about the
        // arena it came from is read again.
        for (Map.Entry<CasterSource.Key, Cached> entry : current.entrySet()) {
            if (next.containsKey(entry.getKey())) continue;
            Long seen = lastSeenNanos.get(entry.getKey());
            if (seen != null && now - seen <= ABSENCE_GRACE_NANOS) {
                next.put(entry.getKey(), entry.getValue());
            } else {
                lastSeenNanos.remove(entry.getKey());
            }
        }
        // Nearest first, and only as much as one build's budget: a changed mesh past the budget
        // keeps its previous structure in the scene until a later build reaches it, and a new one
        // stays out until then. Both remain changed against the cache, so the next diff finds them.
        dirty.sort(Comparator.comparingLong(source -> sectionDistanceSq(source.key(), cameraSectionX, cameraSectionY, cameraSectionZ)));
        int[] triangles = new int[dirty.size()];
        for (int i = 0; i < triangles.length; i++) triangles[i] = dirty.get(i).triangleCount();
        int taken = budgetPrefix(triangles, BUILD_BUDGET_TRIANGLES);
        for (int i = taken; i < dirty.size(); i++) {
            CasterSource.Key key = dirty.get(i).key();
            Cached existing = current.get(key);
            if (existing != null) next.put(key, existing); else next.remove(key);
        }
        dirty = new ArrayList<>(dirty.subList(0, taken));
        boolean setChanged = !next.keySet().equals(current.keySet());
        boolean originChanged = live == null || ox != live.originX() || oy != live.originY() || oz != live.originZ();
        if (dirty.isEmpty() && !setChanged && !originChanged) {
            return 0;
        }

        List<Runnable> buildFrees = new ArrayList<>();
        List<RtBuffer> packedCopies = new ArrayList<>(dirty.size());
        long copyValue = 0;
        if (!dirty.isEmpty()) {
            // Copy every changed mesh's packed vertices out of the arena. Prior arena writes are
            // accepted uploads on this queue; the first barrier makes them visible to the copies,
            // the second makes the copies available before the semaphore carries them across.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                memoryBarrier(cmd, stack, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                        VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_ACCESS_TRANSFER_READ_BIT);
                for (CasterSource source : dirty) {
                    RtBuffer packed = RtBuffer.create(allocator, source.range().byteLength(),
                            VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "RT packed mesh");
                    packedCopies.add(packed);
                    buildFrees.add(() -> packed.destroy(allocator));
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                            .srcOffset(source.range().byteOffset()).dstOffset(0).size(source.range().byteLength());
                    VK13.vkCmdCopyBuffer(cmd, source.buffer().vkBuffer(), packed.buffer(), region);
                }
                memoryBarrier(cmd, stack, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK13.VK_ACCESS_MEMORY_READ_BIT);
            }
            copyValue = timeline.allocateValue();
            copySignalDue = copyValue;
        }
        long doneValue = timeline.allocateValue();
        pending = recordBuild(dirty, next, packedCopies, ox, oy, oz, copyValue, doneValue, buildFrees);
        if (copyValue == 0) {
            // Nothing to wait for: the meshes it references were built on this same queue.
            submit(pending);
        }
        return dirty.size();
    }

    /** How many of {@code triangles}, in order, fit one build: always at least one, so a single
     * mesh larger than the budget still builds, and past that as many as stay within it. */
    static int budgetPrefix(int[] triangles, long budget) {
        long total = 0;
        int taken = 0;
        while (taken < triangles.length) {
            total += triangles[taken];
            if (taken > 0 && total > budget) break;
            taken++;
        }
        return taken;
    }

    private static long sectionDistanceSq(CasterSource.Key key, int sx, int sy, int sz) {
        long dx = key.x() - sx, dy = key.y() - sy, dz = key.z() - sz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The fence of the frame that recorded the last {@link #schedule}'s copies. */
    public void copiesFenced(GpuFence fence) {
        if (pending == null || pending.submitted || pending.copyFence != null) {
            fence.close();
            return;
        }
        pending.copyFence = fence;
    }

    /**
     * Submits the recorded build once the frame that carries its copies has completed. Submitted
     * earlier, the build would wait on the compute queue for a value the graphics queue signals at
     * the end of a frame that itself waits on the graph's compute passes queued behind this build:
     * a cycle, and the frame never ends. Once the fence has passed the signal has too, so the
     * semaphore wait on this submission is satisfied before it is queued and only carries
     * visibility. Never blocks: a zero timeout is a poll.
     */
    public void submitIfCopiesDone() {
        if (pending == null || pending.submitted || pending.copyFence == null || !pending.copyFence.awaitCompletion(0L)) {
            return;
        }
        pending.copyFence.close();
        pending.copyFence = null;
        submit(pending);
    }

    private void submit(Pending build) {
        // The compute queue is shared with the graph's own passes; its submissions are
        // serialised by this lock everywhere in the engine.
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            try (VulkanQueue.Submission submission = computeQueue.beginSubmit()) {
                if (build.copyValue > 0) {
                    submission.waitSemaphore(timeline.semaphore(), build.copyValue, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
                }
                submission.executeCommands(build.commands);
                submission.signalSemaphore(timeline.semaphore(), build.doneValue, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            }
        }
        build.submitted = true;
    }

    /** The copy value the last {@link #schedule} allocated, once; zero when there is none. */
    public long takeCopySignal() {
        long value = copySignalDue;
        copySignalDue = 0;
        return value;
    }

    /**
     * Adopts the build in flight if the compute queue has signalled its completion. {@code
     * waitDone} receives the value the frame's graphics submission must wait for before it
     * traverses the new structure; the value has already passed, so the wait costs nothing, and
     * it is what makes the build's writes visible to this queue. Everything the previous
     * structure held that the new one does not is queued for {@link #retire}.
     */
    public boolean adoptIfReady(LongConsumer waitDone) {
        if (pending == null || !pending.submitted || timeline.signalledValue() < pending.doneValue) {
            return false;
        }
        waitDone.accept(pending.doneValue);
        for (Runnable free : pending.buildFrees) free.run();
        Live previous = live;
        live = pending.result;
        pending = null;
        buildPool.reset();
        if (previous != null) {
            for (Map.Entry<CasterSource.Key, Cached> entry : previous.cache().entrySet()) {
                Cached old = entry.getValue();
                if (live.cache().get(entry.getKey()) != old) pendingFrees.add(() -> free(old));
            }
            pendingFrees.add(() -> AccelerationStructures.destroy(allocator, previous.tlas()));
            pendingFrees.add(() -> previous.instances().destroy(allocator));
            pendingFrees.add(() -> previous.meshTable().destroy(allocator));
        }
        return true;
    }

    /**
     * Records the decode, the bottom-level builds and the top-level build into one command buffer
     * on the tier's own compute-family pool. Nothing is submitted here.
     */
    private Pending recordBuild(List<CasterSource> dirty, Map<CasterSource.Key, Cached> next, List<RtBuffer> packedCopies,
            int ox, int oy, int oz, long copyValue, long doneValue, List<Runnable> buildFrees) {
        // Sizes first: each build in a batch gets its own scratch region, so the driver may run
        // them side by side; a batch ends where the budget would be exceeded.
        List<AccelerationStructures.Sizes> blasSizes = new ArrayList<>(dirty.size());
        long[] scratchOffsets = new long[dirty.size()];
        boolean[] batchStarts = new boolean[dirty.size()];
        long batchExtent = 0, maxExtent = 0;
        for (int i = 0; i < dirty.size(); i++) {
            CasterSource source = dirty.get(i);
            AccelerationStructures.Sizes sizes = AccelerationStructures.blasSizes(device, source.triangleCount(),
                    !source.key().cutout());
            blasSizes.add(sizes);
            long need = alignUp(sizes.buildScratchBytes(), scratchAlignment);
            if (i > 0 && batchExtent + need > SCRATCH_BUDGET_BYTES) {
                batchStarts[i] = true;
                batchExtent = 0;
            }
            scratchOffsets[i] = batchExtent;
            batchExtent += need;
            maxExtent = Math.max(maxExtent, batchExtent);
        }
        AccelerationStructures.Sizes tlasSizes = AccelerationStructures.tlasSizes(device, next.size());
        maxExtent = Math.max(maxExtent, tlasSizes.buildScratchBytes());
        RtBuffer scratch = RtBuffer.create(allocator, AccelerationStructures.roundUp4(maxExtent + scratchAlignment),
                VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "RT build scratch");
        buildFrees.add(() -> scratch.destroy(allocator));
        long scratchBase = alignUp(scratch.deviceAddress(), scratchAlignment);

        VkCommandBuffer build = buildPool.allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK13.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int result = VK13.vkBeginCommandBuffer(build, begin);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkBeginCommandBuffer (RT build) failed: " + result);
            }
            if (!dirty.isEmpty()) {
                // Decode each copy into triangles and primitive records.
                VK13.vkCmdBindPipeline(build, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, decode.pipeline());
                ByteBuffer push = stack.malloc(DECODE_PUSH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                List<RtBuffer> positionsList = new ArrayList<>(dirty.size());
                List<RtBuffer> primitivesList = new ArrayList<>(dirty.size());
                for (int i = 0; i < dirty.size(); i++) {
                    CasterSource source = dirty.get(i);
                    int triangles = source.triangleCount();
                    RtBuffer positions = RtBuffer.create(allocator, (long) triangles * POSITION_BYTES_PER_TRIANGLE,
                            VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                            "RT mesh positions");
                    RtBuffer primitives = RtBuffer.create(allocator, (long) triangles * PRIMITIVE_BYTES,
                            VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "RT mesh primitives");
                    positionsList.add(positions);
                    primitivesList.add(primitives);
                    push.clear();
                    push.putLong(0, packedCopies.get(i).deviceAddress());
                    push.putLong(8, positions.deviceAddress());
                    push.putLong(16, primitives.deviceAddress());
                    push.putInt(24, triangles);
                    push.putInt(28, 0);
                    VK13.vkCmdPushConstants(build, decode.pipelineLayout(), VK13.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                    VK13.vkCmdDispatch(build, (triangles + DECODE_LOCAL_SIZE - 1) / DECODE_LOCAL_SIZE, 1, 1);
                }
                AccelerationStructures.barrierComputeToBuild(build);
                for (int i = 0; i < dirty.size(); i++) {
                    CasterSource source = dirty.get(i);
                    AccelerationStructures.Structure blas = AccelerationStructures.create(allocator,
                            KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, blasSizes.get(i), "RT mesh BLAS");
                    // Only a new batch reuses scratch, so only a new batch waits for earlier builds.
                    if (batchStarts[i]) AccelerationStructures.barrierBuildToBuild(build);
                    // A solid-pass mesh is opaque: the hardware commits its triangles without the
                    // kernel's alpha test. A cutout-pass mesh is not, so its candidates reach the loop.
                    AccelerationStructures.recordBlasBuild(build, blas, positionsList.get(i).deviceAddress(),
                            source.triangleCount(), scratchBase + scratchOffsets[i], !source.key().cutout());
                    next.put(source.key(), new Cached(source, positionsList.get(i), primitivesList.get(i), blas));
                }
            }

            // Top-level: one instance per mesh at its grid-relative origin, custom index = its slot
            // in the table the trace kernel reads primitive addresses from.
            int count = next.size();
            RtBuffer newInstances = RtBuffer.createHostVisible(allocator, (long) count * InstanceRecord.BYTES,
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, "RT instances");
            RtBuffer newTable = RtBuffer.createHostVisible(allocator, (long) count * 8,
                    VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "RT mesh table");
            ByteBuffer instanceBytes = newInstances.mappedBytes();
            ByteBuffer tableBytes = newTable.mappedBytes();
            int slot = 0;
            for (Map.Entry<CasterSource.Key, Cached> entry : next.entrySet()) {
                CasterSource.Key key = entry.getKey();
                Cached cached = entry.getValue();
                InstanceRecord.write(instanceBytes, slot * InstanceRecord.BYTES,
                        MeshGrid.sectionOffset(key.x(), ox), MeshGrid.sectionOffset(key.y(), oy), MeshGrid.sectionOffset(key.z(), oz),
                        slot, InstanceRecord.FLAG_TRIANGLE_FACING_CULL_DISABLE, cached.blas().deviceAddress());
                tableBytes.putLong(slot * 8, cached.primitives().deviceAddress());
                slot++;
            }
            AccelerationStructures.Structure newTlas = AccelerationStructures.create(allocator,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, tlasSizes, "RT mesh TLAS");
            if (!dirty.isEmpty()) AccelerationStructures.barrierBuildToBuild(build);
            AccelerationStructures.recordTlasBuild(build, newTlas, newInstances.deviceAddress(), count, scratchBase);
            AccelerationStructures.barrierBuildToCompute(build);
            result = VK13.vkEndCommandBuffer(build);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer (RT build) failed: " + result);
            }
            return new Pending(build, copyValue, doneValue, new Live(newTlas, newInstances, newTable, next, ox, oy, oz), buildFrees);
        }
    }

    /**
     * Queues everything the last rebuild replaced behind a fence for the current frame, created
     * only when there is something to free. An encoder fence completes with the frame's normal
     * submission, so nothing has to be flushed to carry it.
     */
    public void retire(Supplier<GpuFence> fences) {
        if (pendingFrees.isEmpty()) {
            return;
        }
        retiring.addLast(new Retiring(fences.get(), pendingFrees));
        pendingFrees = new ArrayList<>();
    }

    /**
     * Traces one frame's celestial visibility into {@code outputImage}, a {@code GENERAL}-layout
     * RGBA32F storage image of {@code resolution} square, against the live structure.
     *
     * @param cameraX the camera rebased onto this structure's grid origin
     */
    public void trace(VkCommandBuffer cmd, long atlasImage, long atlasView, long outputImage, long outputView,
            int resolution, CelestialFill request, float cameraX, float cameraY, float cameraZ) {
        if (live == null) {
            throw new IllegalStateException("no structure to trace; adopt a build first or clear");
        }
        AccelerationStructures.Structure tlas = live.tlas();
        RtBuffer meshTable = live.meshTable();
        int slot = ring;
        ring = (ring + 1) % FRAMES;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer block = stack.malloc(CONSTANT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            float[] inverse = request.inverseLightVp();
            float[] light = request.lightVp();
            for (int i = 0; i < 16; i++) {
                block.putFloat(i * 4, inverse[i]);
                block.putFloat(64 + i * 4, light[i]);
            }
            block.putFloat(128, cameraX).putFloat(132, cameraY).putFloat(136, cameraZ).putFloat(140, 0f);
            block.putFloat(144, request.radiusBlocks());
            block.putFloat(148, request.bias());
            block.putInt(152, resolution);
            block.putFloat(156, request.filterGuardUv());
            // Copied into G on every traced texel: what tells a reader this answer came from
            // uploaded meshes rather than from a voxel approximation.
            block.putInt(160, RayTier.HARDWARE_MESH.ordinal());
            block.putInt(164, ALPHA_TEST ? 1 : 0);
            for (int i = 168; i < CONSTANT_BYTES; i += 4) block.putInt(i, 0);
            VK13.vkCmdUpdateBuffer(cmd, constants[slot].buffer(), 0, block);
            memoryBarrier(cmd, stack, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_UNIFORM_READ_BIT);
            // The atlas is Blaze3D's, kept in GENERAL; animated frames are uploaded by transfer
            // earlier in the frame, so make every prior write visible to the sample.
            imageBarrier(cmd, stack, atlasImage, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_SHADER_READ_BIT);
            imageBarrier(cmd, stack, outputImage, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_SHADER_WRITE_BIT);

            long set = sets[slot];
            VkDescriptorBufferInfo.Buffer uniform = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(constants[slot].buffer()).offset(0).range(CONSTANT_BYTES);
            VkWriteDescriptorSetAccelerationStructureKHR structure = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType$Default()
                    .pAccelerationStructures(stack.longs(tlas.handle()));
            VkDescriptorImageInfo.Buffer atlas = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(((VulkanGpuSampler) atlasSampler).vkSampler()).imageView(atlasView)
                    .imageLayout(VK13.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer output = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(0L).imageView(outputView).imageLayout(VK13.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer table = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(meshTable.buffer()).offset(0).range(meshTable.sizeBytes());
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(uniform);
            writes.get(1).sType$Default().pNext(structure).dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlas);
            writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(output);
            writes.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(table);
            VK13.vkUpdateDescriptorSets(device.vkDevice(), writes, null);

            VK13.vkCmdBindPipeline(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, shadow.pipeline());
            VK13.vkCmdBindDescriptorSets(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, shadow.pipelineLayout(), 0,
                    stack.longs(set), null);
            int groups = (resolution + TRACE_LOCAL_SIZE - 1) / TRACE_LOCAL_SIZE;
            VK13.vkCmdDispatch(cmd, groups, groups, 1);
        }
    }

    /**
     * Answers one batch of pack rays against the live structure, in place in the pack's own
     * buffers: no copy in, no copy out. Records the acquire barrier (the requests were written by
     * an earlier pass, the hits were cleared by the graph), the dispatch, and a release barrier
     * for whoever reads the hits next on this queue. A reader on the compute queue is ordered by
     * the caller's full submit, which a barrier cannot do.
     *
     * @param originX the camera rebased onto this structure's grid origin
     */
    public void answerQuery(VkCommandBuffer cmd, dev.icehunter.fornax.rt.BufferQuery query, int tier,
            long atlasImage, long atlasView, float originX, float originY, float originZ) {
        if (live == null) {
            throw new IllegalStateException("no structure to query");
        }
        AccelerationStructures.Structure tlas = live.tlas();
        RtBuffer meshTable = live.meshTable();
        int slot = queryRing;
        queryRing = (queryRing + 1) % QUERY_SETS;
        long requestBytes = dev.icehunter.fornax.metalfx.rt.RayQueryAbi.requestByteSize(query.rayCount());
        long hitBytes = dev.icehunter.fornax.metalfx.rt.RayQueryAbi.hitByteSize(query.rayCount());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Everything written before this point on this queue, whatever wrote it: the graph's
            // clear of the hits, a graphics-stream compute pass's requests, an earlier query's hits.
            memoryBarrier(cmd, stack, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_SHADER_READ_BIT | VK13.VK_ACCESS_SHADER_WRITE_BIT);
            imageBarrier(cmd, stack, atlasImage, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_SHADER_READ_BIT);

            long set = querySets[slot];
            VkWriteDescriptorSetAccelerationStructureKHR structure = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType$Default()
                    .pAccelerationStructures(stack.longs(tlas.handle()));
            VkDescriptorBufferInfo.Buffer requests = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(query.requestBuffer()).offset(0).range(requestBytes);
            VkDescriptorBufferInfo.Buffer hits = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(query.hitBuffer()).offset(0).range(hitBytes);
            VkDescriptorImageInfo.Buffer atlas = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(((VulkanGpuSampler) atlasSampler).vkSampler()).imageView(atlasView)
                    .imageLayout(VK13.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer table = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(meshTable.buffer()).offset(0).range(meshTable.sizeBytes());
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
            writes.get(0).sType$Default().pNext(structure).dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(requests);
            writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(hits);
            writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlas);
            writes.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(table);
            VK13.vkUpdateDescriptorSets(device.vkDevice(), writes, null);

            ByteBuffer push = stack.malloc(QUERY_PUSH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            push.putInt(0, dev.icehunter.fornax.metalfx.rt.RayQueryAbi.ABI_VERSION);
            push.putInt(4, query.rayCount());
            push.putInt(8, tier);
            // Fill mode: this batch may already carry a higher tier's answers.
            push.putInt(12, 1);
            push.putFloat(16, originX).putFloat(20, originY).putFloat(24, originZ);
            push.putInt(28, query.atlasUvEncoding().wireValue());
            VK13.vkCmdBindPipeline(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, rayQuery.pipeline());
            VK13.vkCmdBindDescriptorSets(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, rayQuery.pipelineLayout(), 0,
                    stack.longs(set), null);
            VK13.vkCmdPushConstants(cmd, rayQuery.pipelineLayout(), VK13.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK13.vkCmdDispatch(cmd, (query.rayCount() + QUERY_LOCAL_SIZE - 1) / QUERY_LOCAL_SIZE, 1, 1);

            // The pack reads these hits from a later pass on this queue or the other one.
            memoryBarrier(cmd, stack, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_SHADER_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK13.VK_ACCESS_MEMORY_READ_BIT);
        }
    }

    /** An empty scene: every texel is a skipped ray, R = 1 and no validity, the same as Metal's clear. */
    public static void clearEmpty(VkCommandBuffer cmd, long outputImage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            imageBarrier(cmd, stack, outputImage, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_ACCESS_TRANSFER_WRITE_BIT);
            VkClearColorValue value = VkClearColorValue.calloc(stack);
            value.float32(0, 1f).float32(1, 0f).float32(2, 0f).float32(3, 0f);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK13.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK13.vkCmdClearColorImage(cmd, outputImage, VK13.VK_IMAGE_LAYOUT_GENERAL, value, range);
        }
    }

    /** After a trace or a clear: make the output readable by the transfer that publishes it. */
    public static void prepareOutputForCopy(VkCommandBuffer cmd, long outputImage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            imageBarrier(cmd, stack, outputImage,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_ACCESS_SHADER_WRITE_BIT | VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_ACCESS_TRANSFER_READ_BIT);
        }
    }

    /** Frees everything whose frame has completed. Never waits: a zero timeout is a poll. */
    public void drainRetired() {
        while (!retiring.isEmpty() && retiring.peekFirst().fence().awaitCompletion(0L)) {
            Retiring done = retiring.removeFirst();
            for (Runnable free : done.frees()) free.run();
            done.fence().close();
        }
    }

    @Override
    public void close() {
        // The caller has waited for the device to go idle, so every fence is complete; waiting on
        // each here is what proves it before anything is freed. Frees never fenced belong to a
        // rebuild whose frame never submitted, which the idle wait covers too.
        while (!retiring.isEmpty()) {
            Retiring done = retiring.removeFirst();
            if (!done.fence().awaitCompletion(CLOSE_TIMEOUT_NANOS)) {
                throw new IllegalStateException("ray tracing retirement fence did not complete before close");
            }
            for (Runnable free : done.frees()) free.run();
            done.fence().close();
        }
        for (Runnable free : pendingFrees) free.run();
        pendingFrees = new ArrayList<>();
        // A build still in flight completes on the compute queue before anything it shares with
        // the live structure is freed; waiting here is the one host wait, and it is teardown.
        if (pending != null) {
            if (pending.submitted) {
                timeline.waitFor(pending.doneValue, CLOSE_TIMEOUT_NANOS);
            }
            if (pending.copyFence != null) pending.copyFence.close();
            for (Runnable free : pending.buildFrees) free.run();
            freeStructure(pending.result, live == null ? Map.of() : live.cache());
            pending = null;
        }
        if (live != null) freeStructure(live, Map.of());
        live = null;
        buildPool.destroy();
        timeline.close();
        for (RtBuffer block : constants) block.destroy(allocator);
        atlasSampler.close();
        VK13.vkDestroyDescriptorPool(device.vkDevice(), descriptorPool, null);
        ComputePipelineBuilder.destroy(device, rayQuery.pipeline(), rayQuery.pipelineLayout(), rayQuery.descriptorSetLayout(), rayQuery.shaderModule());
        ComputePipelineBuilder.destroy(device, shadow.pipeline(), shadow.pipelineLayout(), shadow.descriptorSetLayout(), shadow.shaderModule());
        ComputePipelineBuilder.destroy(device, decode.pipeline(), decode.pipelineLayout(), decode.descriptorSetLayout(), decode.shaderModule());
    }

    // --- private -----------------------------------------------------------------------------------

    /** Frees a structure and every cached mesh of its own, keeping those {@code shared} still holds. */
    private void freeStructure(Live structure, Map<CasterSource.Key, Cached> shared) {
        for (Map.Entry<CasterSource.Key, Cached> entry : structure.cache().entrySet()) {
            if (shared.get(entry.getKey()) != entry.getValue()) free(entry.getValue());
        }
        AccelerationStructures.destroy(allocator, structure.tlas());
        structure.instances().destroy(allocator);
        structure.meshTable().destroy(allocator);
    }

    private void free(Cached cached) {
        AccelerationStructures.destroy(allocator, cached.blas());
        cached.positions().destroy(allocator);
        cached.primitives().destroy(allocator);
    }

    private long createPoolAndSets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // One pool for both layouts: the shadow set's five bindings FRAMES times, the query
            // set's five bindings QUERY_SETS times, summed per type.
            java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
            for (int type : SHADOW_BINDINGS) counts.merge(type, FRAMES, Integer::sum);
            for (int type : QUERY_BINDINGS) counts.merge(type, QUERY_SETS, Integer::sum);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(counts.size(), stack);
            int n = 0;
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                sizes.get(n++).type(entry.getKey()).descriptorCount(entry.getValue());
            }
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(FRAMES + QUERY_SETS).pPoolSizes(sizes);
            LongBuffer poolOut = stack.mallocLong(1);
            int result = VK13.vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, poolOut);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateDescriptorPool (RT shadow) failed: " + result);
            }
            long pool = poolOut.get(0);
            LongBuffer layouts = stack.mallocLong(FRAMES);
            for (int i = 0; i < FRAMES; i++) layouts.put(i, shadow.descriptorSetLayout());
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(layouts);
            LongBuffer setsOut = stack.mallocLong(FRAMES);
            result = VK13.vkAllocateDescriptorSets(device.vkDevice(), allocInfo, setsOut);
            if (result != VK13.VK_SUCCESS) {
                VK13.vkDestroyDescriptorPool(device.vkDevice(), pool, null);
                throw new IllegalStateException("vkAllocateDescriptorSets (RT shadow) failed: " + result);
            }
            for (int i = 0; i < FRAMES; i++) sets[i] = setsOut.get(i);

            LongBuffer queryLayouts = stack.mallocLong(QUERY_SETS);
            for (int i = 0; i < QUERY_SETS; i++) queryLayouts.put(i, rayQuery.descriptorSetLayout());
            VkDescriptorSetAllocateInfo queryAlloc = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(queryLayouts);
            LongBuffer queryOut = stack.mallocLong(QUERY_SETS);
            result = VK13.vkAllocateDescriptorSets(device.vkDevice(), queryAlloc, queryOut);
            if (result != VK13.VK_SUCCESS) {
                VK13.vkDestroyDescriptorPool(device.vkDevice(), pool, null);
                throw new IllegalStateException("vkAllocateDescriptorSets (RT query) failed: " + result);
            }
            for (int i = 0; i < QUERY_SETS; i++) querySets[i] = queryOut.get(i);
            return pool;
        }
    }

    static long alignUp(long value, int alignment) {
        return (value + alignment - 1) & -(long) alignment;
    }

    private static String readResource(String path) {
        try (InputStream in = MeshVulkanTracer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("engine shader resource missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("engine shader resource unreadable: " + path, e);
        }
    }

    private static void memoryBarrier(VkCommandBuffer cmd, MemoryStack stack, int srcStage, int srcAccess,
            int dstStage, int dstAccess) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                .sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        VK13.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, barrier, null, null);
    }

    /** GENERAL to GENERAL: Blaze3D keeps its images there, and so does this tier. */
    private static void imageBarrier(VkCommandBuffer cmd, MemoryStack stack, long image, int srcStage, int srcAccess,
            int dstStage, int dstAccess) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(VK13.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK13.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(VK13.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK13.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }
}
