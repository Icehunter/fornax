package dev.icehunter.fornax.pass.voxel;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandPool;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.pack.graph.BufferInstance;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.pipeline.PersistentPipelineCache;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.profile.FrameProfiler;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Engine-owned debug view: DDA-raymarches the harvested brick grid on the shared compute queue and
 * blits the result over the whole native frame, bypassing whatever pack is loaded -- mirrors {@code
 * ReconstructPass}'s "hardcoded engine pass, not pack-declared" shape (a static {@link RenderPipeline}
 * for the presentation blit, invoked from {@code GameRendererMixin}). This is the milestone's real
 * acceptance test: the first place Tasks 1-11 (harvest, window, upload) produce a visible screen.
 *
 * <p>It is also the first pass in the codebase to allocate and bind a real {@code VkDescriptorSet} to
 * real storage buffers -- {@code ComputePipelineBuilder} only ever built a descriptor set LAYOUT
 * before this. The whole compute half is raw Vulkan (Blaze3D has no compute abstraction), verified
 * struct-by-struct against {@code lwjgl-vulkan-3.4.1} via {@code javap}; the display half is entirely
 * Blaze3D-native ({@code CommandEncoder.writeToTexture} into an engine texture, then a screenquad
 * blit) so it never fights Blaze3D's own image-layout tracking.
 *
 * <p><b>Threading.</b> Both entry points ({@link #onFrame} from {@code GraphRunner.finish}, {@link
 * #presentIfEnabled} from {@code GameRendererMixin} RETURN) run on the render thread, so this class's
 * own state is single-threaded. The only cross-thread hazard is that the raymarch READS the occupancy,
 * payload and palette buffers while Sodium worker threads may be WRITING them (via {@code
 * BrickGridUpload.uploadSlot}); the dispatch therefore reads the buffer handles, updates its
 * descriptor, and submits all inside {@link
 * VulkanComputeBackend#SHARED_QUEUE_LOCK} -- the same lock those uploads and {@code
 * TargetRegistry}'s buffer lifecycle take -- so no upload can free, reassign, or partially-write the
 * buffer mid-dispatch.
 *
 * <p>Task 12 proved shape only (occupancy + DDA), shading hits by entered-face. Task 13 binds the
 * payload (per-voxel palette index) and palette (per-face color + packed partial-shape boxes) buffers
 * too, so the shader emits real per-face color and runs a real ray-AABB test against a PARTIAL voxel's
 * sub-boxes. Cross-billboard and alpha-porous shapes remain out of scope (see the task-13 report).
 */
public final class VoxelDebugRaymarchPass {
    private static final String SHADER_RESOURCE = "/assets/fornax/shaders_engine/voxel_debug_raymarch.comp";
    private static final int PUSH_CONSTANT_BYTES = 112; // mat4(64) + vec4(16) + ivec4(16) + ivec4(16)
    private static final int LOCAL_SIZE = 8;
    // 16 sections (diameter 33, 35937 slots) = ~790 MiB at Standard detail -- comfortably under the
    // 1060 3GB floor with room left for everything else the game needs. Raised from 12 (2026-07-26,
    // live report): 12 was a real but overly-conservative choice; 16 is the real number this floor
    // supports at Standard, not an arbitrary round-up. HIGH_DETAIL_RADIUS_CEILING (6) is unaffected
    // and unaffected BY this -- it already independently caps High detail well inside this ceiling.
    private static final int RADIUS_CEILING = 16;

    /** Hard reach cap applied ONLY while {@code LIGHT_CELL_DETAIL} is High (16 cells/axis, 65,536 B of
     * light volume alone per slot -- 8x Standard's 8,192 B; total per-slot cost across every brick-grid
     * buffer -- occupancy/payload/faceSeal/palette/summary/lightVolume -- is 80,392 B at High vs.
     * 23,048 B at Standard; see {@code BrickGridUpload} for the individual buffer sizes these sum).
     * At radius 6 (diameter 13, 2197 slots) that's 2197 * 80,392 =~ 168 MiB total, comfortably "fine"
     * even against the project's low-end target card's 3 GiB budget; RADIUS_CEILING (16, raised from
     * 12 2026-07-26) uncapped would instead reach diameter 33 -- 35937 slots, =~ 2.69 GiB total, for
     * the brick-grid buffers ALONE, on the same card -- before any framebuffer/shadow-map/texture-
     * atlas VRAM, so this cap stays load-bearing at the new ceiling too. (Figures corrected
     * 2026-07-26, audit-caught: the previous version of this comment cited internally inconsistent
     * numbers -- 49152 B in prose vs. 32768 B in its own worked arithmetic -- neither of which matched
     * the current code; screens.toml's parallel 214/488 MiB figures were the same stale numbers and
     * are corrected alongside this one.) This is a mechanical clamp, not a UI warning: the pack has no
     * way to express "these two options can't both be maxed" in its option UI (see
     * docs/superpowers/specs/2026-07-20-feature-dependency-tree.md), so a silent bad combination
     * (High detail + Very Long reach) would otherwise blow VRAM with nothing telling the user why.
     * Clamping here means the combination simply cannot occur, regardless of what u_LightReach is set
     * to -- {@link #currentRadius()} is the single place both this and RADIUS_CEILING are enforced. */
    private static final int HIGH_DETAIL_RADIUS_CEILING = 6;

    // Debug-only DDA cost scales with real pixel count -- on a true Retina backing store
    // (confirmed live: 3456x2168, NOT SSAA/render-scale) up to 800 steps/thread across ~7.5M
    // threads measured at ~25ms of GPU time alone. This view exists to verify voxel-grid shape
    // (Task 12's own scope note), not to represent final shaded output or final perf -- Task 13
    // replaces this whole raymarch's shading model, so tuning this exact shader's resolution
    // permanently is the right call rather than paying full native-res cost for a debug tool.
    // Halving each dimension (quarter the pixels) measured 30fps -> 70fps live; the blit already
    // upscales via NEAREST sampling (see BLIT_PIPELINE), so the visual cost is a blockier debug
    // image, not a functional regression.
    private static final int DEBUG_RESOLUTION_DIVISOR = 2;

    // Frames the raymarch keeps in flight. This runs EVERY frame (not the occasional one-shot the shared
    // compute infra's submit+waitIdle was designed for), so a full-queue waitIdle after each dispatch
    // forced a hard CPU/GPU stall with zero overlap. Instead we double-buffer: each frame submits into the
    // CURRENT ring slot with an explicit VkFence and PRESENTS the OTHER slot's already-completed pixels
    // from the previous frame -- so the CPU never blocks on the dispatch it just queued. Mirrors PassTimer's
    // frames-in-flight ring: by the time a slot is reused, its prior fence has long since signaled, so the
    // wait guarding reuse is near-instant, never a whole-queue barrier. Two slots is the minimum that
    // decouples submit from readback (one being written by the GPU, one being read by the host); the debug
    // view's 1-frame presentation lag is imperceptible.
    private static final int FRAMES_IN_FLIGHT = 2;
    private static final long FENCE_WAIT_TIMEOUT = 0xFFFF_FFFF_FFFF_FFFFL; // UINT64_MAX -> block only until signaled
    private static final long TIMING_LOG_INTERVAL_FRAMES = 120; // ~4s at 30fps -- root-cause diagnostic, see presentIfEnabled

    // Screenquad blit that presents the compute result -- mirrors SsaaDownsamplePass's shape, minus
    // the settings uniform (this blit needs none).
    private static final BindGroupLayout BLIT_BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .build();

    private static final RenderPipeline BLIT_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BLIT_BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "voxel_debug_blit"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/voxel_debug_blit"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    // --- Per-frame streaming state (render thread) ---
    private static boolean active;
    private static int currentDiameter = -1;
    private static long lastCameraSection = Long.MIN_VALUE; // packed section coord, sentinel = never centered
    private static boolean cameraCaptured;

    // --- Streaming telemetry (M1 white-flash diagnosis) --------------------------------------------
    // VoxelWindow's harvest/clear counters are monotonic session totals (see their own doc); this
    // class is the render-thread caller that turns them into per-frame deltas for the profiler HUD --
    // the same single-threaded call site (onFrame, once per frame) that already drives recenterAndResync.
    private static long prevSyncHarvestedTotal;
    private static long prevAsyncHarvestedTotal;
    private static long prevClearedTotal;
    private static final Matrix4f capturedInvViewProj = new Matrix4f();
    private static float capturedCamX, capturedCamY, capturedCamZ;
    private static int capturedCenterX, capturedCenterY, capturedCenterZ;

    // --- Cached GPU resources (render thread) ---
    @Nullable
    private static VulkanComputeBackend backend;
    private static boolean pipelineBuilt;
    private static long pipeline, pipelineLayout, descriptorSetLayout, shaderModule;
    private static long descriptorPool;

    /**
     * One frame-in-flight slot. The descriptor set and command pool are per-slot (each is size-independent,
     * built once with the pipeline): a shared descriptor set couldn't be re-pointed while a previous frame's
     * still-executing command buffer references it, and the shared command pool's only reset is whole-pool
     * (TRANSIENT, no per-buffer reset), which would clobber another slot's in-flight buffer. The output
     * buffer + its completion fence are size-dependent (rebuilt on resize, see {@link #ensureOutputBuffer}).
     */
    private static final class RingSlot {
        long outputBuffer, outputAllocation, outputMappedPtr;
        long fence;              // signals when this slot's dispatch completes; 0 until first ensureOutputBuffer
        long descriptorSet;      // built once in ensurePipeline
        @Nullable
        VulkanCommandPool commandPool; // built once in ensurePipeline; reset before each reuse
        boolean submitted;       // a dispatch is (or was) in flight for this slot's current buffer/fence
    }

    private static final RingSlot[] ring = new RingSlot[FRAMES_IN_FLIGHT];

    static {
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            ring[i] = new RingSlot();
        }
    }

    private static int outputWidth, outputHeight;
    private static long frameIndex; // advances every present; slot = frameIndex % FRAMES_IN_FLIGHT

    @Nullable
    private static GpuTexture debugTexture;
    @Nullable
    private static GpuTextureView debugTextureView;
    private static int textureWidth, textureHeight;

    private VoxelDebugRaymarchPass() {
    }

    /**
     * Per-frame streaming update, called from {@code GraphRunner.finish} (render thread, pack active)
     * with this frame's real camera matrices. Manages the toroidal window whenever the {@code
     * VOXEL_RAYMARCH} debug view is selected OR {@code voxelGridNeededByPack} is true (a currently
     * enabled pack compute pass reads the brick voxel grid, e.g. {@code rt_shadow} -- see {@code
     * GraphRunner.anyEnabledComputePassReadsVoxelGrid}); otherwise detaches and no-ops. The window
     * radius derives from the player's real render distance (clamped), never a hardcoded constant.
     */
    public static void onFrame(TargetRegistry registry, ChunkRenderMatrices matrices,
                               double camX, double camY, double camZ, boolean voxelGridNeededByPack) {
        if (FornaxConfig.get().debugView != GBufferDebugView.VOXEL_RAYMARCH && !voxelGridNeededByPack) {
            disable();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int radius = currentRadius();
        ensureGridAllocated(registry);
        active = true;
        VoxelWindow.attachRegistry(registry);

        int sectionX = SectionPos.blockToSectionCoord(camX);
        int sectionY = SectionPos.blockToSectionCoord(camY);
        int sectionZ = SectionPos.blockToSectionCoord(camZ);
        long sectionKey = SectionPos.asLong(sectionX, sectionY, sectionZ);
        // The window is always centered on the camera's section; capture it every frame so the shader
        // can bound the DDA to in-window slots (see the bound check in voxel_debug_raymarch.comp).
        capturedCenterX = sectionX;
        capturedCenterY = sectionY;
        capturedCenterZ = sectionZ;

        // Recenter + resync ONLY when the camera's section actually changed (Task 10's flagged
        // performance note) -- recenterAndResync harvests only the newly-exposed shell on a section
        // cross (the bulk of the window keeps its data untouched), falling back to a full-window scan
        // only for large jumps / radius changes / first enable. The camera's real look direction feeds
        // the harvest-throughput fix's priority ordering (front-facing slots harvested synchronously
        // first) -- see VoxelWindow.recenterAndResync's own doc.
        Level level = mc.level;
        if (sectionKey != lastCameraSection && level != null) {
            Vector3fc forward = mc.gameRenderer.mainCamera().forwardVector();
            VoxelWindow.recenterAndResync(sectionX, sectionY, sectionZ, radius, level,
                    forward.x(), forward.y(), forward.z());
            lastCameraSection = sectionKey;
        }

        publishVoxelTelemetry();

        // Capture this frame's camera for presentIfEnabled: invViewProj maps NDC -> camera-relative
        // world (Minecraft renders terrain camera-relative), so the shader adds cameraPos separately.
        capturedInvViewProj.set(matrices.projection()).mul(matrices.modelView()).invert();
        capturedCamX = (float) camX;
        capturedCamY = (float) camY;
        capturedCamZ = (float) camZ;
        cameraCaptured = true;
    }

    /**
     * Publishes this frame's voxel-streaming telemetry into {@link GraphRunner#frameProfiler()} as HUD
     * value rows (see {@link dev.icehunter.fornax.profile.FrameProfiler#recordValue}), so a live
     * screenshot of the profiler overlay can distinguish the M1 white-flash bug's three candidate
     * causes: a harvest backlog ({@code voxel_pending} staying high), per-frame churn/re-clearing
     * ({@code voxel_cleared} nonzero while the camera is stationary -- the smoking gun), or submission
     * cadence ({@code voxel_sync}/{@code voxel_async} vs. how large the exposed shell actually was).
     * Called once per frame from {@link #onFrame}, right after that frame's own {@code
     * recenterAndResync} call (if any) -- so this frame's deltas include whatever that call just did.
     */
    private static void publishVoxelTelemetry() {
        long syncTotal = VoxelWindow.syncHarvestedTotal();
        long asyncTotal = VoxelWindow.asyncHarvestedTotal();
        long clearedTotal = VoxelWindow.clearedTotal();

        FrameProfiler profiler = GraphRunner.frameProfiler();
        profiler.recordValue("voxel_pending", VoxelWindow.pendingSlots());
        profiler.recordValue("voxel_sync", syncTotal - prevSyncHarvestedTotal);
        profiler.recordValue("voxel_async", asyncTotal - prevAsyncHarvestedTotal);
        profiler.recordValue("voxel_cleared", clearedTotal - prevClearedTotal);
        profiler.recordValue("voxel_pop", VoxelWindow.populationFraction() * 100.0);

        prevSyncHarvestedTotal = syncTotal;
        prevAsyncHarvestedTotal = asyncTotal;
        prevClearedTotal = clearedTotal;
    }

    private static final int BLOCKS_PER_SECTION = 16;

    /** Default colored-light reach in CHUNKS when no pack option is present -- matches
     * {@code light_propagate.comp}'s own declared default for {@code u_LightReach} (2026-07-26,
     * chunks conversion: the pack option's raw value is chunks now, this engine fallback must match
     * that unit exactly since both feed the same {@code * BLOCKS_PER_SECTION} multiply below). 12
     * chunks = {@link #RADIUS_CEILING} sections: the option-absent value must reproduce the
     * pre-option behavior exactly -- radius resolved up to the ceiling. */
    private static final float DEFAULT_LIGHT_REACH_CHUNKS = 12.0f;

    /** The window radius (in sections), derived from the pack's {@code u_LightReach} option (CHUNKS,
     * falling back to {@link #DEFAULT_LIGHT_REACH_CHUNKS} when no pack option is loaded, multiplied
     * up to blocks here -- the engine side of the "store the small integer, multiply where it's
     * consumed" contract the shader side follows too) and clamped by the player's real render
     * distance, {@link #RADIUS_CEILING}, and -- while {@code LIGHT_CELL_DETAIL} is High -- {@link
     * #HIGH_DETAIL_RADIUS_CEILING} too (see that constant's own doc comment) -- the single source of
     * truth {@link #onFrame} and {@link #ensureGridAllocated} both read, so the two never drift into
     * computing a different diameter for the same frame. */
    private static int currentRadius() {
        int renderDistanceChunks = Minecraft.getInstance().options.renderDistance().get();
        PackOptionsBuffer options = GraphRunner.optionsBuffer();
        float reachChunks = options != null
                ? options.get("u_LightReach", DEFAULT_LIGHT_REACH_CHUNKS)
                : DEFAULT_LIGHT_REACH_CHUNKS;
        float reachBlocks = reachChunks * BLOCKS_PER_SECTION;
        // High Light Detail caps reach independently of whatever u_LightReach is set to -- see
        // HIGH_DETAIL_RADIUS_CEILING's own doc comment for the memory math this prevents.
        boolean highDetail = BrickGridUpload.lightCellsPerSectionAxis() == BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH;
        int ceiling = highDetail ? Math.min(RADIUS_CEILING, HIGH_DETAIL_RADIUS_CEILING) : RADIUS_CEILING;
        // FornaxSettings#voxelReachIgnoresRenderDistance: passing `ceiling` here instead of the real
        // render-distance value is equivalent to dropping the render-distance term from clampSections'
        // min() entirely -- the ceiling always wins that comparison against itself, so u_LightReach is
        // honored up to RADIUS_CEILING/HIGH_DETAIL_RADIUS_CEILING alone, same as clampSections' own
        // "render distance >= reach" case already does today.
        int renderDistanceTerm = FornaxConfig.get().voxelReachIgnoresRenderDistance ? ceiling : renderDistanceChunks;
        return clampSections(reachBlocks, renderDistanceTerm, ceiling);
    }

    /**
     * The window radius in sections: the user's colored-light reach (blocks -> sections, rounded up),
     * clamped BOTH by the player's render distance and by {@link #RADIUS_CEILING} (the engine VRAM cap,
     * which always wins), never below 1. Pure -- the single source of truth {@code onFrame} and
     * {@code ensureGridAllocated} both read via {@link #currentRadius()}.
     */
    static int clampSections(float reachBlocks, int renderDistanceSections, int ceiling) {
        int reachSections = (int) Math.ceil(reachBlocks / BLOCKS_PER_SECTION);
        return Math.max(1, Math.min(Math.min(reachSections, renderDistanceSections), ceiling));
    }

    /** Ensures the brick-grid GPU buffers exist at the current render-distance-derived size -- callable
     * BEFORE runner build (GraphRunner.ensureRunnersBuilt) so a compute pass binding voxelOccupancy never
     * races the allocation by one frame (the boot/rebuild "has no built runner" ERROR burst, root-caused
     * live: prepare() built runners at frame start while onFrame() only allocated at frame end). Also
     * called from {@link #onFrame} itself (the single source of truth for the (re)allocation decision --
     * see {@link #currentRadius()}), so a radius change picked up mid-session still (re)allocates exactly
     * once per diameter, regardless of which caller notices it first this frame. */
    public static void ensureGridAllocated(TargetRegistry registry) {
        int diameter = 2 * currentRadius() + 1;
        // (Re)allocate the brick-grid buffers only on first enable or a radius change -- not per frame.
        if (!active || diameter != currentDiameter) {
            BrickGridUpload.ensureAllocated(registry, diameter);
            currentDiameter = diameter;
            lastCameraSection = Long.MIN_VALUE; // force a recenter+resync at the new size
        }
    }

    /**
     * The diameter (in sections) the brick-grid buffers -- including {@link
     * BrickGridUpload#LIGHT_VOLUME_TARGET} -- are ACTUALLY allocated for right now, or {@code -1}
     * before this session's first {@link #ensureGridAllocated} call. This is the single source of
     * truth for a caller (e.g. {@code GraphRunner}'s emitter-light dispatch) that needs to size a
     * dispatch or push-constant window against what the light-volume buffer really holds THIS frame:
     * {@link VoxelWindow#currentState()}'s diameter can lag this by up to one frame, because the
     * window is only recentered at the very END of {@code GraphRunner.finish} ({@link #onFrame}'s
     * {@code recenterAndResync} call), whereas the buffer itself is already (re)sized at frame START
     * in {@code prepare() -> ensureRunnersBuilt() -> ensureGridAllocated()}. On a render-distance
     * DECREASE that lag means {@code VoxelWindow.currentState().diameter()} is stale-LARGER than the
     * just-shrunk buffer for this whole frame -- dispatching a compute pass against it would compute
     * cell/slot indices past the buffer's real capacity, a one-frame out-of-bounds storage-buffer
     * write (Vulkan UB). See I-1 in {@code .superpowers/sdd/el-task-5-review.md}. {@code
     * currentDiameter} is always updated in the SAME call that (re)allocates the buffer to that exact
     * size ({@link #ensureGridAllocated}), so reading it here can never observe a diameter the buffer
     * doesn't actually hold.
     */
    public static int allocatedDiameter() {
        return currentDiameter;
    }

    /** Detaches the window and resets streaming state -- called when the debug view is off. Cheap and
     * idempotent, so it can run every frame the view is deselected. */
    public static void disable() {
        // Drain (wait on, do NOT tear down) every ring slot whose dispatch is still in flight before
        // returning. presentIfEnabled releases SHARED_QUEUE_LOCK with the dispatch still executing (it
        // reads the occupancy buffer, gated only by the slot fence 1-2 frames later), so unlike every
        // other compute submitter this pass is NOT guaranteed idle by the time the lock is released.
        // GraphRunner.closeCurrent calls disable() immediately before registry.close() destroys that
        // occupancy buffer -- so without this drain a pack/pipeline reload mid-dispatch would free the
        // buffer while the GPU is still reading it (use-after-free). We only wait here; the buffers and
        // fences persist so the resource-caching contract across a debug-view toggle is unchanged.
        VulkanComputeBackend b = backend;
        if (b != null) {
            VulkanDevice device = b.device();
            for (RingSlot slot : ring) {
                if (slot.submitted && slot.fence != 0) {
                    VK13.vkWaitForFences(device.vkDevice(), slot.fence, true, FENCE_WAIT_TIMEOUT);
                }
            }
        }

        if (!active) {
            return;
        }
        active = false;
        currentDiameter = -1;
        lastCameraSection = Long.MIN_VALUE;
        cameraCaptured = false;
        VoxelWindow.attachRegistry(null);
    }

    /**
     * Dispatches the raymarch and blits it over {@code nativeTarget}, called from {@code
     * GameRendererMixin} at {@code renderLevel} RETURN (where {@code mainRenderTarget} is the final
     * native target). No-ops unless the debug view is active, this frame's camera was captured, and
     * the occupancy buffer exists -- so a frame where the pack was mid-teardown simply presents the
     * pack's own output untouched.
     */
    public static void presentIfEnabled(RenderTarget nativeTarget) {
        if (!active || !cameraCaptured || FornaxConfig.get().debugView != GBufferDebugView.VOXEL_RAYMARCH) {
            return;
        }
        TargetRegistry registry = VoxelWindow.attachedRegistry();
        if (registry == null) {
            return;
        }
        if (!ensureBackend() || !ensurePipeline()) {
            return;
        }

        // Dispatch at a fraction of nativeTarget's real resolution -- see DEBUG_RESOLUTION_DIVISOR's
        // own doc comment for why this is a permanent, intentional choice for this debug-only view.
        int width = nativeTarget.width / DEBUG_RESOLUTION_DIVISOR;
        int height = nativeTarget.height / DEBUG_RESOLUTION_DIVISOR;
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!ensureDebugTexture(width, height)) {
            return;
        }

        VulkanComputeBackend b = backend;
        if (b == null) {
            return; // ensureBackend() just succeeded, but keep the compiler's null-flow happy
        }
        VulkanDevice device = b.device();

        // This frame writes into writeSlot; we present displaySlot, which was submitted last frame. They are
        // distinct buffers, so the readback below never races the dispatch we queue here.
        RingSlot writeSlot = ring[(int) (frameIndex % FRAMES_IN_FLIGHT)];
        RingSlot displaySlot = ring[(int) ((frameIndex + FRAMES_IN_FLIGHT - 1) % FRAMES_IN_FLIGHT)];

        // Temporary root-cause diagnostic (perf investigation, not a permanent feature): PassTimer can't
        // bracket this pass's raw vkQueueSubmit (it only writes GPU timestamps into Blaze3D's own command
        // buffer), so wall-clock the three places this method can actually stall the render thread. Sampled
        // every Nth frame to keep logging cheap; distinguishes "GPU can't keep up with the dispatch" (large
        // recycle/present waits) from "CPU-side submission itself is expensive" (large lock-section time).
        boolean timingSample = (frameIndex % TIMING_LOG_INTERVAL_FRAMES) == 0;
        long tRecycleStart = timingSample ? System.nanoTime() : 0L;

        // Recycle writeSlot's OWN prior GPU work (its dispatch from FRAMES_IN_FLIGHT frames ago) before we
        // overwrite its buffer/descriptor. This is private per-slot state -- no shared queue or occupancy
        // handle is touched -- so it needs no lock, and at steady state the fence signaled long ago (a full
        // ring of frames has elapsed), making this a near-instant check rather than a whole-queue barrier.
        if (writeSlot.submitted && writeSlot.fence != 0) {
            VK13.vkWaitForFences(device.vkDevice(), writeSlot.fence, true, FENCE_WAIT_TIMEOUT);
            VK13.vkResetFences(device.vkDevice(), writeSlot.fence);
            writeSlot.submitted = false;
        }
        VulkanCommandPool writePool = writeSlot.commandPool;
        if (writePool != null) {
            writePool.reset(); // safe: any command buffer it held was drained by the fence wait above
        }
        long tRecycleEnd = timingSample ? System.nanoTime() : 0L;

        // Critical section: occupancy handle read + descriptor update + submit must be atomic against worker
        // uploads and registry buffer lifecycle -- but NOT the fence wait above or the readback below, which
        // touch only this pass's private buffers. (ensureOutputBuffer's resize path allocates via the shared
        // VMA allocator, so it stays inside too; it is a rare, non-per-frame path.)
        long tLockStart = timingSample ? System.nanoTime() : 0L;
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (!ensureOutputBuffer(b, width, height)) {
                return;
            }
            BufferInstance occupancy = registry.getBuffer(BrickGridUpload.OCCUPANCY_TARGET);
            BufferInstance payload = registry.getBuffer(BrickGridUpload.PAYLOAD_TARGET);
            BufferInstance palette = registry.getBuffer(BrickGridUpload.PALETTE_TARGET);
            if (occupancy == null || payload == null || palette == null) {
                return; // not allocated (or torn down) -- present the pack's own frame instead
            }
            updateDescriptorSet(device, writeSlot.descriptorSet, occupancy.vkBuffer(), writeSlot.outputBuffer,
                    payload.vkBuffer(), palette.vkBuffer());
            submitDispatch(b, writeSlot, width, height);
            writeSlot.submitted = true;
        }
        long tLockEnd = timingSample ? System.nanoTime() : 0L;
        frameIndex++;

        // Present the previous slot's already-completed pixels. Its output buffer is private to this pass, so
        // no lock is needed; its fence is one frame old, so at steady state this wait returns immediately. A
        // resize (which rebuilds every slot's buffer) clears submitted, so we simply skip a stale slot and
        // let the pack's own frame show through until the ring re-warms.
        if (!displaySlot.submitted || displaySlot.fence == 0) {
            return;
        }
        long tPresentWaitStart = timingSample ? System.nanoTime() : 0L;
        VK13.vkWaitForFences(device.vkDevice(), displaySlot.fence, true, FENCE_WAIT_TIMEOUT);
        long tPresentWaitEnd = timingSample ? System.nanoTime() : 0L;
        // Make the compute writes visible to the host read below on non-coherent memory too.
        Vma.vmaInvalidateAllocation(device.vma(), displaySlot.outputAllocation, 0, VK13.VK_WHOLE_SIZE);
        ByteBuffer pixels = MemoryUtil.memByteBuffer(displaySlot.outputMappedPtr, width * height * 4);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(debugTexture, pixels, 0, 0, 0, 0, width, height);

        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax Voxel Debug Blit",
                nativeTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(BLIT_PIPELINE);
            pass.bindTexture("u_Source", debugTextureView, sampler);
            pass.draw(3, 1, 0, 0); // full-screen triangle from gl_VertexID, same as SsaaDownsamplePass
        }

        if (timingSample) {
            int groupsX = (width + LOCAL_SIZE - 1) / LOCAL_SIZE;
            int groupsY = (height + LOCAL_SIZE - 1) / LOCAL_SIZE;
            int maxStepsForLog = Math.min(currentDiameter * 16 * 2, 2048);
            FornaxMod.LOGGER.info(
                    "[Fornax] VoxelDebugRaymarchPass timing: recycleWaitMs={} lockSectionMs={} presentWaitMs={} threads={} maxSteps={} diameter={}",
                    (tRecycleEnd - tRecycleStart) / 1e6, (tLockEnd - tLockStart) / 1e6,
                    (tPresentWaitEnd - tPresentWaitStart) / 1e6, groupsX * groupsY * LOCAL_SIZE * LOCAL_SIZE,
                    maxStepsForLog, currentDiameter);
        }
    }

    // --- GPU resource management (all render thread) ---

    private static boolean ensureBackend() {
        if (backend == null) {
            backend = VulkanComputeBackend.tryCreate();
        }
        return backend != null;
    }

    private static boolean ensurePipeline() {
        if (pipelineBuilt) {
            return true;
        }
        VulkanComputeBackend b = backend;
        if (b == null) {
            return false;
        }
        ByteBuffer spirv;
        try {
            spirv = ComputeShaderCompiler.compileToSpirv(readShaderSource(), "voxel_debug_raymarch.comp");
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax] VoxelDebugRaymarchPass: shader compile failed", e);
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanDevice device = b.device();

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer out = stack.mallocLong(1);
            checkVk(VK13.vkCreateShaderModule(device.vkDevice(), moduleInfo, null, out), "vkCreateShaderModule");
            shaderModule = out.get(0);

            // Four storage buffers: binding 0 = occupancy (in), 1 = output pixels (out),
            // 2 = payload/palette-indices (in), 3 = palette table (in). 2 and 3 are new in Task 13:
            // the shader needs the per-voxel palette index AND the palette entries to resolve real color.
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                bindings.get(i).binding(i)
                        .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK13.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            checkVk(VK13.vkCreateDescriptorSetLayout(device.vkDevice(), layoutInfo, null, out), "vkCreateDescriptorSetLayout");
            descriptorSetLayout = out.get(0);

            // Push constant range for the camera block -- ComputePipelineBuilder.build deliberately
            // omits push constants, so this pass builds its own pipeline layout to add the range.
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK13.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_CONSTANT_BYTES);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            checkVk(VK13.vkCreatePipelineLayout(device.vkDevice(), pipelineLayoutInfo, null, out), "vkCreatePipelineLayout");
            pipelineLayout = out.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK13.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo pipelineInfo = VkComputePipelineCreateInfo.calloc(stack)
                    .sType$Default().stage(stageInfo).layout(pipelineLayout);
            checkVk(VK13.vkCreateComputePipelines(device.vkDevice(), PersistentPipelineCache.handle(),
                    VkComputePipelineCreateInfo.create(pipelineInfo.address(), 1), null, out), "vkCreateComputePipelines");
            pipeline = out.get(0);

            // One descriptor pool holding a set per ring slot (four storage buffers each); each set is
            // allocated once and re-updated (not reallocated) each frame it becomes current.
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4 * FRAMES_IN_FLIGHT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(FRAMES_IN_FLIGHT).pPoolSizes(poolSizes);
            checkVk(VK13.vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, out), "vkCreateDescriptorPool");
            descriptorPool = out.get(0);

            // One descriptor set + one command pool per ring slot. Both are size-independent, so they live
            // with the pipeline (not the resize-scoped output buffers): a slot needs its own descriptor set
            // (a shared one can't be re-pointed while another slot's command buffer is still executing) and
            // its own command pool (the pool's only reset is whole-pool, which would clobber a sibling slot's
            // in-flight buffer).
            LongBuffer setLayouts = stack.mallocLong(FRAMES_IN_FLIGHT);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                setLayouts.put(i, descriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(setLayouts);
            LongBuffer sets = stack.mallocLong(FRAMES_IN_FLIGHT);
            checkVk(VK13.vkAllocateDescriptorSets(device.vkDevice(), allocInfo, sets), "vkAllocateDescriptorSets");
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                ring[i].descriptorSet = sets.get(i);
                ring[i].commandPool = new VulkanCommandPool(device, b.computeQueue());
            }
        } finally {
            MemoryUtil.memFree(spirv);
        }
        pipelineBuilt = true;
        FornaxMod.LOGGER.info("[Fornax] VoxelDebugRaymarchPass: compute pipeline + descriptor set built");
        return true;
    }

    private static void updateDescriptorSet(VulkanDevice device, long descriptorSet, long occupancyBuffer,
                                            long outBuffer, long payloadBuffer, long paletteBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer occInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(occupancyBuffer).offset(0).range(VK13.VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer outInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(outBuffer).offset(0).range(VK13.VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer payloadInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(payloadBuffer).offset(0).range(VK13.VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer paletteInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(paletteBuffer).offset(0).range(VK13.VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(occInfo);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(outInfo);
            writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(payloadInfo);
            writes.get(3).sType$Default().dstSet(descriptorSet).dstBinding(3).descriptorCount(1)
                    .descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(paletteInfo);
            VK13.vkUpdateDescriptorSets(device.vkDevice(), writes, null);
        }
    }

    /**
     * Records this frame's DDA dispatch into {@code slot}'s command buffer and submits it with {@code
     * slot.fence} as the completion signal. Unlike the shared compute infra's {@code beginSubmit()} (which
     * hardcodes a null fence and is meant for occasional one-shot dispatch), this bypasses the abstraction
     * and calls {@code vkQueueSubmit} directly on the raw queue handle so the fence can be attached -- that
     * fence, not a whole-queue {@code waitIdle}, is how the next frames learn this dispatch has finished.
     * Caller holds {@code SHARED_QUEUE_LOCK} and has already reset {@code slot}'s command pool and updated
     * its descriptor set.
     */
    private static void submitDispatch(VulkanComputeBackend b, RingSlot slot, int width, int height) {
        int groupsX = (width + LOCAL_SIZE - 1) / LOCAL_SIZE;
        int groupsY = (height + LOCAL_SIZE - 1) / LOCAL_SIZE;
        int maxSteps = Math.min(currentDiameter * 16 * 2, 2048);

        VkCommandBuffer cmd = slot.commandPool.allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_CONSTANT_BYTES);
            capturedInvViewProj.get(0, push); // 16 floats, column-major, bytes 0..63 (absolute; position unchanged)
            push.putFloat(64, capturedCamX);
            push.putFloat(68, capturedCamY);
            push.putFloat(72, capturedCamZ);
            push.putFloat(76, 0.0f);
            push.putInt(80, currentDiameter);
            push.putInt(84, width);
            push.putInt(88, height);
            push.putInt(92, maxSteps);
            push.putInt(96, capturedCenterX);
            push.putInt(100, capturedCenterY);
            push.putInt(104, capturedCenterZ);
            push.putInt(108, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            VK13.vkBeginCommandBuffer(cmd, beginInfo);
            VK13.vkCmdBindPipeline(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK13.vkCmdBindDescriptorSets(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    new long[]{slot.descriptorSet}, null);
            VK13.vkCmdPushConstants(cmd, pipelineLayout, VK13.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK13.vkCmdDispatch(cmd, groupsX, groupsY, 1);

            // Compute-shader writes -> host read of the mapped output buffer.
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK13.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK13.VK_ACCESS_HOST_READ_BIT);
            VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_PIPELINE_STAGE_HOST_BIT,
                    0, barrier, null, null);
            VK13.vkEndCommandBuffer(cmd);

            // Direct submit with an explicit fence -- Blaze3D's Submission.close() always submits with a null
            // fence (verified via javap), so we go straight to vkQueueSubmit on the raw queue handle instead.
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
            checkVk(VK13.vkQueueSubmit(b.computeQueue().vkQueue(), submitInfo, slot.fence), "vkQueueSubmit");
        }
    }

    /**
     * Ensures every ring slot has an output buffer + completion fence sized for {@code width x height},
     * rebuilding the whole ring on a resize. The size-independent per-slot descriptor sets and command pools
     * are NOT touched here -- they belong to the pipeline (see {@link #ensurePipeline}). Runs inside {@code
     * SHARED_QUEUE_LOCK}; the resize path is rare (native resolution change), never the per-frame path.
     */
    private static boolean ensureOutputBuffer(VulkanComputeBackend b, int width, int height) {
        if (ring[0].outputBuffer != 0 && outputWidth == width && outputHeight == height) {
            return true;
        }
        destroyOutputBuffer(b); // drains in-flight fences, then frees every slot's buffer + fence
        long sizeBytes = (long) width * height * 4;
        VulkanDevice device = b.device();
        for (RingSlot slot : ring) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default().size(sizeBytes).usage(VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer bufferOut = stack.mallocLong(1);
                PointerBuffer allocationOut = stack.mallocPointer(1);
                VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
                int result = Vma.vmaCreateBuffer(device.vma(), bufferInfo, allocInfo, bufferOut, allocationOut, info);
                if (result != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] VoxelDebugRaymarchPass: vmaCreateBuffer (output) failed with VkResult {}", result);
                    destroyOutputBuffer(b); // roll back any slots already built this pass so the ring stays consistent
                    return false;
                }
                slot.outputBuffer = bufferOut.get(0);
                slot.outputAllocation = allocationOut.get(0);
                slot.outputMappedPtr = info.pMappedData();

                // Unsignaled: the first submit into each slot needs an unsignaled fence, and the per-frame
                // path gates its wait/reset on `submitted`, so no slot ever waits on a never-submitted fence.
                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
                LongBuffer fenceOut = stack.mallocLong(1);
                int fenceResult = VK13.vkCreateFence(device.vkDevice(), fenceInfo, null, fenceOut);
                if (fenceResult != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] VoxelDebugRaymarchPass: vkCreateFence failed with VkResult {}", fenceResult);
                    destroyOutputBuffer(b);
                    return false;
                }
                slot.fence = fenceOut.get(0);
                slot.submitted = false;
            }
        }
        outputWidth = width;
        outputHeight = height;
        return true;
    }

    private static void destroyOutputBuffer(VulkanComputeBackend b) {
        VulkanDevice device = b.device();
        for (RingSlot slot : ring) {
            // A slot's buffer/fence may still be referenced by an in-flight dispatch; drain it before freeing.
            if (slot.submitted && slot.fence != 0) {
                VK13.vkWaitForFences(device.vkDevice(), slot.fence, true, FENCE_WAIT_TIMEOUT);
            }
            if (slot.outputBuffer != 0) {
                Vma.vmaDestroyBuffer(device.vma(), slot.outputBuffer, slot.outputAllocation);
                slot.outputBuffer = 0;
                slot.outputAllocation = 0;
                slot.outputMappedPtr = 0;
            }
            if (slot.fence != 0) {
                VK13.vkDestroyFence(device.vkDevice(), slot.fence, null);
                slot.fence = 0;
            }
            slot.submitted = false;
        }
        outputWidth = 0;
        outputHeight = 0;
    }

    /**
     * Document-safe: no wait-idle needed before the destroy below, unlike OpaqueDepth/GBufferManager/
     * ShadowMapManager/WaterSurfaceManager/MipchainRunner/TargetRegistry's texture-teardown paths
     * (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own doc for that hazard). This texture
     * is never bound to a COMPUTE-queue submission -- the raymarch dispatch writes into writeSlot's
     * raw output buffer, not this texture; this texture is only ever written via {@code
     * CommandEncoder.writeToTexture} and sampled via a graphics {@code RenderPass} (both above, in
     * {@link #presentIfEnabled}), i.e. GRAPHICS-queue-only, which Blaze3D's own per-submission
     * destruction ring already covers. Also low-frequency in practice: this rebuild only runs while
     * the VOXEL_RAYMARCH debug view is toggled on, at DEBUG_RESOLUTION_DIVISOR'd resolution.
     */
    private static boolean ensureDebugTexture(int width, int height) {
        if (debugTexture != null && textureWidth == width && textureHeight == height) {
            return true;
        }
        if (debugTextureView != null) {
            debugTextureView.close();
            debugTextureView = null;
        }
        if (debugTexture != null) {
            debugTexture.close();
            debugTexture = null;
        }
        GpuTexture texture = RenderSystem.getDevice().createTexture("Fornax Voxel Debug",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        debugTexture = texture;
        debugTextureView = RenderSystem.getDevice().createTextureView(texture);
        textureWidth = width;
        textureHeight = height;
        return true;
    }

    private static String readShaderSource() {
        try (InputStream in = VoxelDebugRaymarchPass.class.getResourceAsStream(SHADER_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing engine shader resource " + SHADER_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read engine shader resource " + SHADER_RESOURCE, e);
        }
    }

    private static void checkVk(int result, String call) {
        if (result != VK13.VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }
}
