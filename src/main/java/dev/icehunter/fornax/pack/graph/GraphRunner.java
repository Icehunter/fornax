package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.compat.SkyModCompat;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackDiscovery;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.ParticleSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.LightCellStrideContract;
import dev.icehunter.fornax.pack.LightListStrideContract;
import dev.icehunter.fornax.pack.PaletteStrideContract;
import dev.icehunter.fornax.pack.ShaderImports;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.layout.DefineRewriter;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pipeline.SlotReachabilityCensus;
import dev.icehunter.fornax.pipeline.WaterActorUpload;
import dev.icehunter.fornax.pack.layout.PackOptionsLayout;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.pack.layout.VanillaAssetOverrides;
import dev.icehunter.fornax.pack.layout.VanillaShaderOverrides;
import dev.icehunter.fornax.mixin.sodium.ShaderChunkRendererAccessor;
import dev.icehunter.fornax.pack.material.MaterialInclude;
import dev.icehunter.fornax.pack.material.MaterialResolution;
import dev.icehunter.fornax.pack.material.MaterialSnippets;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.ssaa.SsaaManager;
import dev.icehunter.fornax.pass.taa.CameraJitter;
import dev.icehunter.fornax.pass.voxel.VoxelDebugRaymarchPass;
import dev.icehunter.fornax.pass.water.WaterSurfaceManager;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.PrecipClipmapUpload;
import dev.icehunter.fornax.voxel.SurfaceFluidClipmapUpload;
import dev.icehunter.fornax.voxel.VoxelWindow;
import dev.icehunter.fornax.pipeline.CelestialSprites;
import dev.icehunter.fornax.pipeline.ChunkRenderContextHolder;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.pipeline.MemoryWatchdog;
import dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import dev.icehunter.fornax.pipeline.GBufferReadbackDiagnostic;
import dev.icehunter.fornax.pipeline.GeometryInputs;
import dev.icehunter.fornax.pipeline.NoiseTexture;
import dev.icehunter.fornax.pipeline.OpaqueDepth;
import dev.icehunter.fornax.pipeline.PreviousFrameCameraTransform;
import dev.icehunter.fornax.pipeline.SceneHistory;
import dev.icehunter.fornax.pipeline.SkyFrameState;
import dev.icehunter.fornax.pipeline.SkyProbe;
import dev.icehunter.fornax.pipeline.SkyReprojection;
import dev.icehunter.fornax.profile.FrameProfiler;
import dev.icehunter.fornax.profile.PassTimer;
import dev.icehunter.fornax.util.SunDirection;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.vulkan.VK13;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Interprets a loaded pack's {@link GraphSpec}: {@link #prepare} allocates the frame's G-buffer and
 * pack targets, {@link #finish} runs each {@code fullscreen}/{@code mipchain}/{@code copy} pass in
 * the graph's declared order (the {@code geometry} pass is a no-op here -- Sodium's own opaque
 * terrain draws already ran into the shared {@link GBufferManager} instance by the time
 * {@link #finish} is called).
 *
 * <p>{@link #isActive()} gates {@code SodiumWorldRendererOrchestrationMixin}'s dispatch: {@code
 * FornaxConfig.get().shadersEnabled} (on by default) AND a pack graph is currently loaded. With no
 * pack active, every hook this class drives is a no-op -- opaque terrain renders as plain,
 * undeferred vanilla Sodium (see {@code ShaderChunkRendererShaderLocationMixin} and its sibling
 * deferred-pipeline mixins, all gated on {@link #isActive()} too).
 *
 * <p>All GPU-touching construction ({@link TargetRegistry} target allocation, the per-pass
 * {@link FullscreenPassRunner}/{@link MipchainRunner} pipeline objects) is deferred to {@link
 * #prepare()}, which only ever runs during actual frame rendering (a live {@code GpuDevice}
 * guaranteed) -- {@link #rebuild} itself (called from mod init, before any device exists) does
 * nothing but parse/validate the graph and hand fresh sources to {@link RuntimeShaderPack}.
 *
 * <p>Runtime-toggle gating (SSAO/TAA/SSR quality) rides a pack's own {@code enabled_if} (compile
 * options only, by design -- see {@code GraphValidator}) entirely -- {@link #enabledAtCompile} is
 * the only gate {@link #finish} applies per pass. The one exception is engine-side (non-pack) code
 * that still needs to know whether a compile option is on -- e.g. camera jitter should not run with
 * nothing to resolve it when a pack's TAA option is off -- see {@link #isCompileOptionEnabled}.
 */
public final class GraphRunner {
    @Nullable
    private static PackModel currentPack;
    @Nullable
    private static TargetRegistry registry;
    // Pack-shipped static texture assets ([textures.*] in graph.toml, e.g. waterWaveNormal) --
    // bookkeeping-only at rebuild() (mirrors `registry` above: rebuild() can run before any GPU
    // device exists), actual decode+upload deferred to ensureLoaded() in prepare(), torn down
    // alongside `registry` in closeCurrent(). See PackTextureRegistry's own doc.
    @Nullable
    private static PackTextureRegistry packTextureRegistry;
    @Nullable
    private static PackOptionsBuffer optionsBuffer;
    @Nullable
    private static PackOptionsLayout pendingOptionsLayout;
    private static Map<String, Float> pendingRuntimeDefaults = Map.of();
    /** Set by {@link #updateRuntimeValues}, consumed by {@link #flushPendingRuntimeValues} at the
     * frame boundary. Writing the ring from the settings-apply event itself is what threw
     * "Cannot wait on a fence for the current submit" and took the whole pass chain down. */
    private static boolean runtimeValuesDirty = false;
    private static Map<String, Integer> compileValues = Map.of();
    // Verbatim snapshot of the LAST rebuild()'s own (pre-engine-overlay) arguments -- see
    // ensureRunnersBuilt()'s FX_COMPUTE self-heal for why this is retained (replaying a rebuild once
    // computeAvailable becomes truthfully known, without a second shader-source read from disk).
    private static Map<String, String> lastRebuildShaderSources = Map.of();
    private static Map<String, Integer> lastRebuildPackCompileValues = Map.of();

    private static final Map<String, FullscreenPassRunner> fullscreenRunners = new LinkedHashMap<>();
    // Execution is keyed by pass name; resource resolution is keyed by the declared target name.
    // Keeping both indexes lets packs name a mipchain pass independently from the image it builds.
    private static final Map<String, MipchainRunner> mipchainRunners = new LinkedHashMap<>();
    private static final Map<String, MipchainRunner> mipchainTargets = new LinkedHashMap<>();
    private static final Map<String, ComputePassRunner> computeRunners = new LinkedHashMap<>();
    private static final Map<String, ParticlePassRunner> particleRunners = new LinkedHashMap<>();
    private static final Map<String, TemporalPassRunner> temporalRunners = new LinkedHashMap<>();
    private static boolean runnersBuilt;
    // Incremented by every rebuild()/closeCurrent() -- identifies which rebuild a given
    // RuntimeShaderPack.reload() future belongs to, so a SUPERSEDED rebuild's future landing late
    // can never wrongly mark a NEWER rebuild's sources ready (see rebuild()'s own doc comment).
    // Render-thread only, like every other static here.
    private static long rebuildGeneration;
    // True only once the CURRENT rebuild's resource-pack reload has actually landed -- see
    // rebuild()'s doc comment on why ensureRunnersBuilt() must not build (and thereby lazily
    // compile) pass pipelines before this flips. volatile because the Minecraft executor callback
    // that flips it runs via thenRunAsync, which is not guaranteed to be the render thread at the
    // point of the write even though it always ends up executing there.
    private static volatile boolean sourcesReady;
    // Pass names already reported by logMissingRunnerOnce() -- reset on every rebuild/teardown so a
    // NEW pack session's first miss is loud again. Render-thread only, like every runner map here.
    private static final Set<String> missingRunnerLogged = new HashSet<>();
    private static boolean packDeclaresDepthCopyback;

    // Pure-JVM rolling stats; the FrameProfiler OBJECT lives for the mod's whole session, independent
    // of which pack (if any) is loaded (like opaqueDepth below -- see that field's own doc). Its
    // per-label sample windows, however, ARE cleared on every pack teardown (closeCurrent()): a pass
    // name's enabled_if can flip permanently false on a rebuild (option toggle, pack switch), and
    // without a reset its last rolling avg/p95 would otherwise render forever on the HUD as if still
    // live -- see FrameProfiler.reset()'s own doc for the staleness bug this fixes. passTimer is the
    // GPU-facing producer that feeds it -- lazily built once a device exists (see
    // ensureRunnersBuilt()).
    private static final FrameProfiler frameProfiler = new FrameProfiler();
    private static final ThreadLocal<Vector3f> PASS_SUN_DIRECTION =
            ThreadLocal.withInitial(Vector3f::new);
    @Nullable
    private static PassTimer passTimer;
    @Nullable
    private static VulkanComputeBackend computeBackend;

    // Engine-owned, sampleable D32 copy of the opaque G-buffer depth (see OpaqueDepth's own doc).
    // The OpaqueDepth OBJECT itself lives for the mod's whole session (like frameProfiler above),
    // but its GPU texture/view are freed on every pack teardown (closeCurrent()) and lazily
    // reallocated by prepare()'s ensureSize/clear-to-FAR_CLEAR the next time a pack is active again
    // -- mirroring registry/optionsBuffer's own pack-scoped GPU lifetime, so a pack switch or "None"
    // unload never leaves this copy's VRAM allocated with nothing left to free it. See
    // closeCurrent()'s own comment for why this needs no rebuild()/RendererReload chaining.
    private static final OpaqueDepth opaqueDepth = new OpaqueDepth();

    // Per-frame resolved views for each geometry slot's declared `inputs`: outer index = the slot's
    // ordinal, inner index i = that slot's i-th declared input (undeclared trailing slots stay null --
    // see geometryInputView's own noise-default fallback). Refreshed once per frame by
    // refreshGeometryInputViews(), called from prepare() -- which runs before Sodium's own opaque
    // terrain draw, today's only consumer -- and cleared on pack teardown (closeCurrent()) so a
    // torn-down registry's already-closed TargetInstance views can never be handed to a draw that
    // happens to land in the gap between unload and the next prepare().
    //
    // Slot-indexed rather than a single shared array because each slot binds its own u_GeomInput0..N;
    // GeometrySlot.TERRAIN is the only row anything reads today, but resolving every declared slot
    // keeps the runner's behavior independent of which slots happen to render yet.
    private static final GpuTextureView[][] geometryInputViews =
            new GpuTextureView[GeometrySlot.values().length][GeometryInputs.RESERVED];

    private GraphRunner() {
    }

    /** The live {@link OpaqueDepth} instance -- resolved by {@link GraphInputResolver} against {@link OpaqueDepth#NAME}. */
    public static OpaqueDepth opaqueDepth() {
        return opaqueDepth;
    }

    /** The active pack's {@link PackTextureRegistry}, or {@code null} if no pack is loaded --
     * resolved by {@link GraphInputResolver} against a bare (non-{@code builtin.}) pack-texture
     * name, and consulted by {@link FullscreenPassRunner}'s LINEAR + REPEAT sampler special-case. */
    @Nullable
    public static PackTextureRegistry packTextureRegistry() {
        return packTextureRegistry;
    }

    /** The active pack's live {@link TargetRegistry}, or {@code null} if no pack is loaded -- lets
     * {@link dev.icehunter.fornax.pass.debug.GraphTargetDebugPass} present a genuine pack graph
     * target without owning a second copy of the registry lifecycle. */
    @Nullable
    public static TargetRegistry registry() {
        return registry;
    }

    /**
     * The resolved view for {@code slot}'s {@code index}-th declared geometry input, or {@link
     * NoiseTexture#getView()} when that slot's pass declares fewer than {@code index + 1} inputs, no
     * pass claims the slot, or that input's resolution is transiently unavailable this frame (a gated
     * target mid-disable, a registry not yet built) -- a safe, non-garbage default, never the live
     * resource of a target that might not exist. {@code index} must be in {@code [0,
     * GeometryInputs.RESERVED)}; every draw binds every reserved index regardless of pack activity
     * (the bind group shape is process-wide fixed -- see {@link GeometryInputs}'s own doc), so this
     * is called unconditionally by {@code DefaultChunkRendererTextureBindMixin} on every terrain
     * draw. {@link NoiseTexture#getView()} itself can still be {@code null} before any GPU device
     * exists; the mixin's own bind site handles that remaining case by skipping the bind entirely
     * rather than passing a null view.
     */
    public static GpuTextureView geometryInputView(GeometrySlot slot, int index) {
        GpuTextureView v = geometryInputViews[slot.ordinal()][index];
        return v != null ? v : NoiseTexture.getView();
    }

    /**
     * Re-resolves every reserved geometry-input index for every geometry slot the active pack claims.
     * {@link GraphValidator} already refuses both more inputs than {@link GeometryInputs#RESERVED} and
     * two passes claiming one slot, so this neither truncates nor has to pick a winner. Called once
     * per frame from {@link #prepare()} -- BEFORE Sodium's own opaque terrain draw runs, this array's
     * only reader today -- so a pack that resolves a declared input's target (or history slot, or a
     * mipchain's full chain view) gets this frame's freshly (re)sized view, not a stale one from
     * before {@link TargetRegistry}'s own {@code ensureSize} ran earlier in the same {@link
     * #prepare()} call. A resolution failure (declared target compile-disabled/not yet allocated, e.g.
     * mid pack-reload) is caught per-input and left {@code null} -- {@link
     * #geometryInputView(GeometrySlot, int)} falls back to noise for that index rather than
     * propagating the failure into a frame that must still render something.
     */
    /** One warning per unresolved input, so a persistent failure is visible without flooding. */
    private static final java.util.Set<String> reportedUnresolvedInputs =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void refreshGeometryInputViews() {
        clearGeometryInputViews();
        TargetRegistry r = registry;
        PackModel pack = currentPack;
        if (r == null || pack == null) {
            return;
        }
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() != PassType.GEOMETRY) {
                continue;
            }
            GeometrySlot slot = p.slot() == null ? GeometrySlot.DEFAULT : p.slot();
            GpuTextureView[] views = geometryInputViews[slot.ordinal()];
            List<String> inputs = p.inputs();
            int count = Math.min(inputs.size(), GeometryInputs.RESERVED);
            for (int i = 0; i < count; i++) {
                try {
                    views[i] = GraphInputResolver.resolveView(inputs.get(i), r, mipchainTargets);
                } catch (RuntimeException e) {
                    views[i] = null; // transiently unresolved -- geometryInputView falls back to noise
                }
                // Say so, once per input. The noise fallback is the right BIND -- a null view would
                // fail the draw outright -- but it is the wrong DATA, and a shader cannot tell the
                // difference: it reads plausible values and produces confidently wrong geometry. A
                // pack whose declared input never resolves was previously indistinguishable from one
                // whose shader maths was wrong, which cost a long debugging session chasing the
                // latter while the former was the actual fault.
                if (views[i] == null) {
                    String key = slot.token() + "#" + i + "=" + inputs.get(i);
                    if (reportedUnresolvedInputs.add(key)) {
                        FornaxMod.LOGGER.warn("[Fornax] Geometry input '{}' for slot '{}' did not"
                                + " resolve -- that binding is NOISE, not the data the shader expects."
                                + " Anything reading it will render wrongly rather than not at all.",
                                inputs.get(i), slot.token());
                    }
                }
            }
        }
    }

    /**
     * Drops every slot's resolved views back to null, so {@link #geometryInputView(GeometrySlot, int)}
     * falls back to noise until the next {@link #refreshGeometryInputViews()}. Used both at the head
     * of that refresh and on teardown paths, where the point is that an already-closed
     * {@code TargetInstance} view must never survive into a later draw.
     */
    private static void clearGeometryInputViews() {
        for (GpuTextureView[] views : geometryInputViews) {
            Arrays.fill(views, null);
        }
    }

    /**
     * Monotonic pack-rebuild generation, stamped into terrain shader constants by
     * ShaderChunkRendererConstantsMixin as a cache-busting define: Blaze3D's DEVICE-level
     * shader-module cache is keyed on (Identifier, type, defines) WITHOUT the source text, so
     * clearing Sodium's pipeline cache alone still recompiled pipelines against the STALE cached
     * SPIR-V module from before a pack republish -- observed as the gAlbedoRaw attachment staying
     * black even after a full relaunch. A generation-unique define changes the device cache key
     * itself, guaranteeing a fresh GLSL->SPIR-V compile for every republish. Render-thread read;
     * monotonic under closeCurrent's own bumps too (any monotonic value busts the key).
     */
    public static long shaderCacheGeneration() {
        return rebuildGeneration;
    }

    public static boolean isActive() {
        // The LATCHED activity flag, not the live config: prepare()/finish() must agree frame-for-
        // frame with the terrain mixins (all latch readers) about whether terrain is drawing into
        // the G-buffer -- reading shadersEnabled live here would stop the resolve pass the instant
        // a toggle applies, one renderer-reload boundary before terrain actually stops rendering
        // deferred. currentPack is re-checked because a pack unload (registry destroyed) can
        // precede its renderer reload within one apply call; the null guard keeps that window safe.
        return FornaxRenderState.isActive() && currentPack != null;
    }

    /** The currently active pack, or {@code null} if none is loaded -- the pack settings UI reads this to know what's active. */
    @Nullable
    public static PackModel currentPack() {
        return currentPack;
    }

    // Stashed drawChunkLayer arguments, used only when graph execution is deferred past the solid
    // feature draws (see deferGraphUntilAfterSolidFeatures). Written at the end of the opaque terrain
    // layer, consumed immediately after FeatureRenderDispatcher.PreparedFrame.executeSolid() -- both
    // on the render thread, within one frame.
    private static ChunkRenderMatrices deferredMatrices;
    private static double deferredX;
    private static double deferredY;
    private static double deferredZ;

    /**
     * Whether the graph must run AFTER vanilla's solid feature draws rather than at the end of the
     * opaque terrain layer.
     *
     * <p>Deferred shading requires every G-buffer writer to have drawn before anything resolves the
     * G-buffer. While terrain was the only geometry a pack could shade, running the graph at the end
     * of the terrain layer satisfied that trivially. A pack claiming entities (or any other non-terrain
     * slot) breaks it: those draws happen later in the frame, so the resolve would consume a G-buffer
     * they had not yet written to and their geometry would silently never appear -- observed exactly
     * that way, with the deferred pass demonstrably applied and nothing on screen.
     *
     * <p>Conditional rather than unconditional on purpose. Moving the graph changes when
     * {@code opaqueDepth} is captured and when the sceneHistory slots swap, so a pack that claims only
     * terrain keeps the timing it has always had, and only packs that actually need the later slot pay
     * for the change.
     */
    public static boolean deferGraphUntilAfterSolidFeatures() {
        PackModel pack = currentPack;
        if (pack == null) {
            return false;
        }
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() != PassType.GEOMETRY) {
                continue;
            }
            GeometrySlot slot = p.slot() == null ? GeometrySlot.DEFAULT : p.slot();
            if (slot != GeometrySlot.TERRAIN) {
                return true;
            }
        }
        return false;
    }

    /** Stashes this frame's terrain draw parameters for a deferred {@link #finish}. */
    public static void stashDeferredFinish(ChunkRenderMatrices matrices, double x, double y, double z) {
        deferredMatrices = matrices;
        deferredX = x;
        deferredY = y;
        deferredZ = z;
    }

    /**
     * Runs the graph with the parameters stashed earlier this frame. No-ops when the terrain layer did
     * not run (a frame with nothing to draw), rather than resolving against a stale camera.
     */
    public static void finishDeferred() {
        ChunkRenderMatrices matrices = deferredMatrices;
        if (matrices == null) {
            return;
        }
        deferredMatrices = null;
        finish(matrices, deferredX, deferredY, deferredZ);
    }

    /**
     * The extension-less, {@code shaders/}-stripped program path the active pack declares for {@code
     * slot}, or {@code null} when no pack is active or no pass claims that slot. Pairs with {@code
     * RuntimeShaderPack.NAMESPACE} to build the vertex/fragment {@link
     * net.minecraft.resources.Identifier} a pipeline compiles against -- e.g. a pass declaring
     * {@code program = "shaders/blocks/terrain"} yields {@code "blocks/terrain"}, so the pipeline
     * resolves {@code fornax_runtime:blocks/terrain}.
     *
     * <p>Returning {@code null} is the "this slot draws vanilla" signal, not an error: most slots go
     * unclaimed in most packs, and the caller falls back to whatever it would have compiled anyway.
     */
    @Nullable
    public static String geometryProgramPath(GeometrySlot slot) {
        return geometryProgramPath(currentPack, slot);
    }

    /**
     * {@link #geometryProgramPath(GeometrySlot)} against an explicit pack rather than the active one
     * -- a pure function of {@code (pack, slot)}, so the resolution rules can be exercised without
     * standing up renderer state.
     */
    @Nullable
    public static String geometryProgramPath(@Nullable PackModel pack, GeometrySlot slot) {
        if (pack == null) {
            return null;
        }
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() != PassType.GEOMETRY) {
                continue;
            }
            GeometrySlot passSlot = p.slot() == null ? GeometrySlot.DEFAULT : p.slot();
            if (passSlot != slot) {
                continue;
            }
            String program = p.program();
            if (program == null || program.isBlank()) {
                return null;
            }
            String noPrefix = program.startsWith("shaders/") ? program.substring("shaders/".length()) : program;
            int dot = noPrefix.lastIndexOf('.');
            return dot < 0 ? noPrefix : noPrefix.substring(0, dot);
        }
        return null;
    }

    /**
     * The shader files of {@code pass} that must receive the {@code u_PackOptions} block, which for a
     * geometry pass means: both stages if the pass is FORWARD, and nothing at all otherwise.
     *
     * <p><b>Why a FORWARD geometry pass needs it.</b> Its program composites into the
     * already-tonemapped frame, so it has to reproduce the pack's own display transform to land in the
     * same colour space as the pixels it blends with -- and exposure, the tonemap curve's contrast and
     * the grade are all RUNTIME options. Without the block it cannot. {@code DeferredGeometryPipelines}
     * declares the matching bind-group entry on exactly the same condition; declaring one without the
     * other is a bind-group mismatch rather than a missing feature, so both gates key on
     * {@link GeometrySlot#rendersForward()} and neither may drift.
     *
     * <p><b>Why a DEFERRED geometry pass must NOT get it.</b> Its bind group is Sodium's, and Sodium's
     * does not carry {@code u_PackOptions} -- a block declared in {@code terrain.fsh} would be a
     * uniform with no backing binding, which is a compile failure at the first terrain draw with
     * nothing in the log naming the cause. That is precisely why terrain's POM tunables are bridged
     * through {@code u_PbrSettings} push constants instead. Widening this to every geometry pass would
     * break the four passes that render today.
     *
     * <p>Both stages, like the PARTICLES branch and for the same reason: one pipeline, one set of bind
     * group layouts, both stages see them. A stage that never references the block simply carries an
     * unused one, which is free.
     *
     * <p>Extracted and made pure on the {@code wantsSunAndDebugParams} precedent -- the cloud-midnight
     * bug was a pass missing from a list exactly like this one, and the fix was to make the list a
     * function a test could interrogate.
     */
    public static List<String> forwardGeometryShaderPaths(PassSpec pass) {
        if (pass.type() != PassType.GEOMETRY) {
            return List.of();
        }
        GeometrySlot slot = pass.slot() == null ? GeometrySlot.DEFAULT : pass.slot();
        String program = pass.program();
        if (!slot.rendersForward() || program == null || program.isBlank()) {
            return List.of();
        }
        // `program` is a STEM, not a file: graph.toml declares
        // `program = "shaders/blocks/banner_patterns"` and the two stages hang off it. A trailing
        // extension is stripped the same way geometryProgramPath does, so a pack that writes one
        // anyway resolves to the same pair rather than to "...fsh.fsh". The result is keyed
        // pack-root-relative, matching PackDiscovery.readShaderSources exactly.
        int dot = program.lastIndexOf('.');
        String stem = dot < 0 ? program : program.substring(0, dot);
        return List.of(stem + ".fsh", stem + ".vsh");
    }

    /** The active pack's live runtime-options mirror, or {@code null} if none is loaded -- the settings UI's sliders write here. */
    @Nullable
    public static PackOptionsBuffer optionsBuffer() {
        return optionsBuffer;
    }

    /** Rolling per-pass GPU timing stats, fed by {@link #finish}'s pass loop -- a future HUD reads this. */
    public static FrameProfiler frameProfiler() {
        return frameProfiler;
    }

    /**
     * Union of pass names built by the CURRENT rebuild (fullscreen + mipchain + compute + particles) -- belt-and-
     * suspenders alongside {@link #closeCurrent()}'s {@code frameProfiler.reset()}: {@link
     * ProfilerOverlay} filters its snapshot down to this set (plus the always-on frame/geometry-dwell
     * labels, which are never pass names) so a HUD reading taken in the narrow window between a
     * teardown and the next rebuild's runners landing can't show a label from neither the old nor the
     * new pass set. Cheap: three small keySet unions, called only from the HUD's throttled (~4 Hz)
     * refresh, never per-frame.
     */
    public static Set<String> activePassNames() {
        Set<String> names = new HashSet<>(fullscreenRunners.size() + mipchainRunners.size()
                + computeRunners.size() + particleRunners.size());
        names.addAll(fullscreenRunners.keySet());
        names.addAll(mipchainRunners.keySet());
        names.addAll(computeRunners.keySet());
        names.addAll(particleRunners.keySet());
        return names;
    }

    /**
     * The engine-guaranteed {@link SceneHistory#TARGET} this pack's registry currently has
     * allocated, or {@code null} if no pack is active (or its runners aren't built yet). {@code
     * GameRendererMixin}'s post-frame copy hook reads this every frame, under every method,
     * regardless of which passes the active pack's own graph declares.
     */
    @Nullable
    public static TargetInstance sceneHistoryTarget() {
        TargetRegistry r = registry;
        return r == null ? null : r.get(SceneHistory.TARGET);
    }

    /**
     * Deactivates the current pack (falls back to plain vanilla Sodium) -- the "None" pack selection,
     * and the fallback every load-failure path (see {@code PackReload.reload}) rolls back to.
     *
     * <p>Also clears {@link RuntimeShaderPack}'s published {@code vanillaOverrides} -- leaving a
     * stale entry behind would keep a vanilla core-shader override (e.g. the curved lightmap) being
     * served under the {@code minecraft} namespace with no active pack to attribute it to -- the
     * same "invisible when off" invariant {@link #republishVanillaOverride()}'s own doc comment
     * explains for the master-toggle case. The {@code fornax_runtime} sources stay published: the
     * resource reload this fires eagerly recompiles every registered pipeline (ShaderManager.apply),
     * including Sodium's still-fornax-flavored terrain pipeline -- the renderer reload that reverts
     * it to stock Sodium shaders is chained to run only AFTER the reload completes (see {@code
     * ShaderPacksScreen.applyChanges}) -- so clearing sources here yields "Couldn't find source for
     * fornax_runtime:blocks/terrain" -> invalid pipeline -> crash at the next chunk draw. Stale-but-
     * published sources with no active pack are harmless and were the status quo before vanilla
     * overrides existed.
     * Returns the resource-reload completion future so a caller (e.g. {@code
     * ShaderPacksScreen.applyChanges}) can chain a subsequent {@code RendererReload.request()} on
     * it, exactly like {@link #rebuild}'s own returned future.
     */
    public static CompletableFuture<Void> unload() {
        closeCurrent();
        MaterialResolution.refresh(); // currentPack is now null -- clears the stale material lookup
        return RuntimeShaderPack.getInstance().clearVanillaOverrides();
    }

    /**
     * Re-publishes the active pack's vanilla-shader override from the {@code fornax_runtime} sources
     * {@link RuntimeShaderPack} already has installed and this session's live {@link #compileValues},
     * without re-reading the pack's shader sources from disk -- the counterpart to {@code
     * RuntimeShaderPack.clearVanillaOverrides()} for the master shaders-enabled toggle flipping back
     * ON with the pack selection unchanged (see {@code ShaderPacksScreen.applyChanges}): nothing else
     * calls {@link #rebuild} or {@link #unload} on that specific transition, so without this the
     * vanilla override cleared by the OFF transition would simply never come back until some
     * unrelated event (a different pack pick, a pack-settings apply, an aaMethod change) happened to
     * trigger a real rebuild. The binary vanilla-asset override (see {@link VanillaAssetOverrides})
     * has no in-memory snapshot to re-publish from -- {@link RuntimeShaderPack} doesn't retain the
     * pack-relative bytes it was fed, only the already-translated vanilla-path map -- so this
     * re-derives it via {@code PackDiscovery.readTextureOverrides(currentPack.root())}, a small,
     * infrequent disk re-read gated on the exact same transition.
     *
     * <p>Self-contained safety check (not just relying on the caller): a no-op, returning an
     * already-completed future, whenever no pack is loaded or the master switch is actually still
     * off -- mirroring {@link #rebuild}'s own {@code shadersEnabled} gate on {@code vanillaOverrides}
     * and {@code vanillaBinaryOverrides}, so this can never republish an override the "invisible when
     * off" invariant forbids.
     */
    public static CompletableFuture<Void> republishVanillaOverride() {
        if (currentPack == null || !FornaxConfig.get().shadersEnabled) {
            return CompletableFuture.completedFuture(null);
        }
        RuntimeShaderPack pack = RuntimeShaderPack.getInstance();
        Map<String, String> vanillaOverrides = VanillaShaderOverrides.extract(pack.sourcesSnapshot(), compileValues);
        Map<String, byte[]> vanillaBinaryOverrides = VanillaAssetOverrides.extract(
                PackDiscovery.readTextureOverrides(currentPack.root()), compileValues);
        return pack.reload(pack.sourcesSnapshot(), vanillaOverrides, vanillaBinaryOverrides);
    }

    /**
     * Replaces the active pack's runtime option values in one write -- both the live
     * {@code u_PackOptions} GPU buffer (when it exists) and the pending map {@link
     * #ensureRunnersBuilt()} seeds a not-yet-built buffer from, so a value applied before the first
     * frame after a (re)build isn't silently lost. The one write path settings-apply uses for
     * runtime-only edits (no shader text change, no rebuild).
     */
    public static void updateRuntimeValues(Map<String, Float> values) {
        Map<String, Float> merged = new LinkedHashMap<>(pendingRuntimeDefaults);
        merged.putAll(values);
        pendingRuntimeDefaults = merged;
        // DEFERRED, NOT WRITTEN HERE. This is called from settings-apply -- a UI event that can land
        // at any point relative to the render loop, including part-way through a frame whose passes
        // have already bound u_PackOptions. PackOptionsBuffer.writeCurrent() rotates the ring before
        // mapping, and rotating into a slot whose fence belongs to the in-flight submit throws
        // "Cannot wait on a fence for the current submit" out of MappableRingBuffer.
        //
        // That exception did not merely fail the write. It escaped through
        // FullscreenPassRunner.runFrame's setUniform call, was caught by the runner's own
        // resolve-failure handler, and PERMANENTLY DISABLED the pass for the rest of the runner's
        // lifetime -- observed live taking down resolve, tonemap, water_composite,
        // underwater_refraction and every SSR/SSAO pass at once, from a single slider drag. The
        // frame kept rendering with most of the pipeline switched off, which reads as a washed-out
        // untonemapped scene rather than as an error, so it was reported as a shader bug.
        //
        // The flag is flushed from prepare(), which is the frame boundary and the only point where
        // rotating the ring is safe.
        runtimeValuesDirty = true;
    }

    /**
     * Writes any pending runtime option values to the GPU. Called from {@link #prepare} at the frame
     * boundary -- see {@link #updateRuntimeValues} for why this cannot happen at the point the value
     * actually changes.
     */
    private static void flushPendingRuntimeValues() {
        if (!runtimeValuesDirty || optionsBuffer == null) {
            return;
        }
        runtimeValuesDirty = false;
        optionsBuffer.writeAll(pendingRuntimeDefaults);
    }

    /**
     * Installs {@code pack} as the active graph: allocates {@link TargetRegistry} (pure bookkeeping;
     * its own {@code ensureSize} internally no-ops until a GPU device exists, mirroring every
     * hardcoded manager's device-availability guard) and hands {@code shaderSources} (rewritten per
     * {@code compileValues}) to {@link RuntimeShaderPack}. No GPU call happens here -- {@link
     * #prepare()} lazily builds the actual GPU-backed pass runners and {@link PackOptionsBuffer} once
     * a device exists (see {@link #ensureRunnersBuilt()}), since both truly touch the GPU
     * immediately on construction and {@code rebuild} runs from mod init, before any device exists.
     * {@code shaderSources} is required separately from {@code pack} because {@link PackModel}
     * retains only the scanned option table, not the raw GLSL text {@link DefineRewriter} needs.
     *
     * <p>Returns {@link RuntimeShaderPack#reload}'s completion future: the moment the republished
     * sources are actually visible to the shader manager. A caller that follows a rebuild with
     * {@code RendererReload.request()} must chain the request on this future, never call it
     * directly -- see the reload javadoc for the crash this sequencing prevents.
     *
     * <p>{@link #ensureRunnersBuilt()} has the SAME hazard for the pack graph's own fullscreen/
     * mipchain/compute pass runners, not just terrain: a pass runner's {@code RenderPipeline} names
     * its fragment shader by {@code Identifier} only, and Blaze3D compiles that shader lazily on
     * first bind against whatever the shader manager currently resolves it to -- if this future
     * hasn't landed yet, that is still the PREVIOUS compile-value snapshot's text. Two (or more)
     * pass variants that share one shader file behind different {@code enabled_if}s but declare a
     * DIFFERENT number of bind-group inputs (e.g. {@code resolve}/{@code resolve_hdr}/{@code
     * resolve_hdr_el} sharing {@code gbuffer_resolve.fsh}, gating {@code u_Input10}/{@code
     * u_Input11} on {@code HDR_ENABLE}/{@code EMITTER_LIGHTS}) can then build a pipeline whose
     * bind-group shape doesn't match the still-stale compiled shader text -- Blaze3D logs "Couldn't
     * compile pipeline ...: Unable to find shader defined uniform" and marks that pipeline object
     * permanently invalid; the NEXT frame's rebind of the same (never-valid) object then throws
     * {@code IllegalStateException: Pipeline is not valid}, an unrecoverable crash (live-caught: a
     * Settings Reset that toggled both HDR_ENABLE and EMITTER_LIGHTS off in one Apply). This is why
     * {@code ensureRunnersBuilt()} gates on {@link #sourcesReady} rather than {@link #runnersBuilt}
     * alone -- set here, generation-guarded so a superseded rebuild's future landing late can never
     * mark a NEWER rebuild's (different) sources ready.
     */
    public static CompletableFuture<Void> rebuild(PackModel pack, Map<String, String> shaderSources,
                                Map<String, Integer> newCompileValues, Map<String, Float> runtimeValues) {
        // loadShaderSources doesn't include the generated materials.glsl (it only walks disk files),
        // so splice it back in here the same way PackDiscovery.loadFrom does at initial load.
        Map<String, String> withMaterials = new LinkedHashMap<>(shaderSources);
        withMaterials.put(MaterialInclude.PATH,
                MaterialInclude.generate(pack.categories(), MaterialSnippets.read(pack)));
        shaderSources = withMaterials;
        // Sources are re-read from disk on every rebuild (compile-option applies included), so
        // re-validate their include imports each time, not just at discovery -- an unresolvable
        // #moj_import otherwise degrades to a silently-broken composed shader (see ShaderImports).
        ShaderImports.validate(shaderSources);
        PaletteStrideContract.validate(shaderSources);
        LightCellStrideContract.validate(shaderSources);
        LightListStrideContract.validate(shaderSources);

        // Snapshot of this call's own arguments -- NOT the engine-overlaid compileValues field below
        // -- so ensureRunnersBuilt()'s FX_COMPUTE self-heal (see its own doc comment) can replay this
        // exact rebuild once a real computeBackend becomes known, without needing to re-read the
        // pack's shader sources from disk or re-derive its compile-option values a second way.
        lastRebuildShaderSources = shaderSources;
        lastRebuildPackCompileValues = newCompileValues;

        closeCurrent();
        // This rebuild's own identity -- captured into the lambda below so a LATER rebuild (whose
        // own closeCurrent() already reset sourcesReady to false again) can never be marked ready by
        // THIS rebuild's future landing after it, e.g. two Applies fired in quick succession.
        long generation = ++rebuildGeneration;

        currentPack = pack;
        // Deferred geometry variants embed the OLD pack's program identifiers, so every one of them
        // is stale the moment the active pack changes. Nothing else clears them, and a stale variant
        // renders the previous pack's shader with no error to say so.
        dev.icehunter.fornax.pipeline.DeferredGeometryPipelines.invalidate();
        // Read the cached, session-lifetime computeBackend field (built once in
        // ensureRunnersBuilt(), like passTimer) rather than probing VulkanComputeBackend.tryCreate()
        // fresh here -- rebuild() can run before any device exists (see this class's own javadoc),
        // and a fresh probe would construct and immediately leak a throwaway VulkanCommandPool on
        // every single pack (re)build. Computed once and reused at both EngineDefines call sites
        // below.
        boolean computeAvailable = computeBackend != null;
        // Engine facts overlay (never merge under) the pack's own compile values -- see
        // EngineDefines' own doc comment -- so a pack's enabled_if/GLSL can react to FX_TAA/
        // FX_UPSCALE/FX_METHOD_*/FX_COMPUTE regardless of whether the pack itself declares those
        // names.
        Map<String, Integer> withEngineDefines = new LinkedHashMap<>(newCompileValues);
        withEngineDefines.putAll(EngineDefines.forMethod(FornaxConfig.get().aaMethod, computeAvailable));
        compileValues = Map.copyOf(withEngineDefines);
        // Light Detail tier: pushed into BrickGridUpload here, the same place/cadence compileValues
        // itself is finalized, so every reader of BrickGridUpload.lightCellsPerSectionAxis() this
        // rebuild generation (this class's own computeDispatchOverride included) sees the SAME tier --
        // a pack without the option (or an absent value) falls back to 0 == Standard, today's exact
        // behavior. See BrickGridUpload.setLightCellDetailTier's own doc comment.
        BrickGridUpload.setLightCellDetailTier(compileValues.getOrDefault("LIGHT_CELL_DETAIL", 0));
        // Engine-guaranteed sceneHistory target -- packs never declare it themselves (see
        // SceneHistory's own doc comment); injected here, not into PackModel itself, so
        // TargetRegistry/TargetPlan allocate and ping-pong it exactly like any pack-declared
        // history target. Idempotent: harmless if called again on a graph that already has it.
        GraphSpec graphWithSceneHistory = SceneHistory.injectInto(pack.graph());
        registry = TargetRegistry.create(graphWithSceneHistory, compileValues);
        packTextureRegistry = PackTextureRegistry.create(pack.root(), pack.graph().textures());
        packDeclaresDepthCopyback = computePackDeclaresDepthCopyback(graphWithSceneHistory);

        pendingOptionsLayout = PackOptionsLayout.build(List.copyOf(pack.options().values()));
        Map<String, Float> defaults = new LinkedHashMap<>();
        for (PackOption o : pack.options().values()) {
            if (o.type() == OptionType.RUNTIME) {
                try {
                    defaults.put(o.name(), Float.parseFloat(o.defaultValue()));
                } catch (NumberFormatException ignored) {
                    // Non-numeric default (shouldn't happen for v0.1's float-only runtime options) --
                    // leave it unset; PackOptionsBuffer.writeCurrent() skips absent keys.
                }
            }
        }
        // Caller-supplied values (the per-pack saved file, or a settings session's staged edits)
        // overlay pack defaults, so the first frame after a (re)build renders with the player's own
        // tuning rather than pack defaults until a UI touch resyncs them.
        defaults.putAll(runtimeValues);
        pendingRuntimeDefaults = defaults;

        Map<String, String> compileValuesAsStrings = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : compileValues.entrySet()) {
            compileValuesAsStrings.put(e.getKey(), String.valueOf(e.getValue()));
        }

        // u_PackOptions is bound into every FULLSCREEN pass's bind group unconditionally (see
        // FullscreenPassRunner.build()), but never into a geometry pass's own (Sodium-owned) bind
        // group or a mipchain pass's minimal u_Input0/u_PassParams-only one -- so the block
        // declaration is prepended only to shader files a fullscreen pass actually uses, right after
        // each file's own leading #version line (GLSL requires #version to stay the very first
        // token). Pack shaders therefore never hand-write this block themselves; they just reference
        // its members (e.g. u_SsaoRadius) as bare globals.
        Set<String> fullscreenShaderPaths = new HashSet<>();
        // Maps a shader source path -> the exact u_PackOptions GLSL block text to prepend to it (absent
        // = that shader gets none). Every FULLSCREEN pass's shader shares ONE no-binding block (Blaze3D
        // resolves u_PackOptions in its bind group by name, not position). A COMPUTE pass binds
        // u_PackOptions as a reserved UNIFORM_BUFFER descriptor at a POSITIONAL binding (see
        // ComputePassRunner.PACK_OPTIONS_INPUT / combinedBindingOrder), so its block must instead carry
        // that exact binding number -- computed per-pass here, since a future graph could in principle
        // give two compute shaders different binding indices for the same reserved input name. A
        // compute pass whose inputs don't list "packOptions" at all gets no block, regardless of
        // whether the pack declares runtime options. The engine FX_* #define preamble stays
        // FULLSCREEN-only (compute passes don't consume it).
        Map<String, String> packOptionsBlockByShader = new LinkedHashMap<>();
        // A pack with zero runtime options must not get the block at all -- an empty uniform block is
        // illegal GLSL. FullscreenPassRunner still binds the (min-16-byte) buffer; a shader that never
        // declares the block simply ignores the binding.
        boolean hasRuntimeOptions = !pendingOptionsLayout.offsets().isEmpty();
        String fullscreenPackOptionsBlock = hasRuntimeOptions ? pendingOptionsLayout.glslBlock() : null;
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() == PassType.GEOMETRY) {
                // The FOURTH branch: forward geometry passes. See forwardGeometryShaderPaths, which
                // holds the rule so it can be exercised without standing up a renderer.
                //
                // The NO-BINDING block text -- the same form FULLSCREEN uses, not COMPUTE's positional
                // one. The forward variant's bind group is a Blaze3D BindGroupLayout, which resolves
                // u_PackOptions by NAME; a hardcoded `binding = N` would assert a descriptor index
                // nothing here assigns.
                if (hasRuntimeOptions && fullscreenPackOptionsBlock != null) {
                    for (String path : forwardGeometryShaderPaths(p)) {
                        packOptionsBlockByShader.put(path, fullscreenPackOptionsBlock);
                    }
                }
                continue;
            }
            if (p.shader() == null) {
                continue;
            }
            if (p.type() == PassType.FULLSCREEN) {
                fullscreenShaderPaths.add(p.shader());
                if (fullscreenPackOptionsBlock != null) {
                    packOptionsBlockByShader.put(p.shader(), fullscreenPackOptionsBlock);
                }
            } else if (p.type() == PassType.COMPUTE && hasRuntimeOptions) {
                int binding = ComputePassRunner.combinedBindingOrder(p).indexOf(ComputePassRunner.PACK_OPTIONS_INPUT);
                if (binding >= 0) {
                    packOptionsBlockByShader.put(p.shader(), pendingOptionsLayout.glslBlock(binding));
                }
            } else if (p.type() == PassType.PARTICLES && hasRuntimeOptions) {
                // Same positional-binding rule as COMPUTE above, applied to BOTH stages: a particles
                // pass's descriptor set is shared by its vertex and fragment shaders (one set layout,
                // every binding visible to both -- see ParticlePipelineBuilder), so the block text is
                // identical for the two files and either may declare it. Whichever one doesn't simply
                // carries an unused block, which is free.
                int binding = ParticlePassRunner.bindingOrder(p).indexOf(ComputePassRunner.PACK_OPTIONS_INPUT);
                ParticleSpec particles = p.particles();
                if (binding >= 0 && particles != null) {
                    String block = pendingOptionsLayout.glslBlock(binding);
                    packOptionsBlockByShader.put(p.shader(), block);
                    packOptionsBlockByShader.put(particles.vertexShader(), block);
                }
            }
        }
        // Engine AA/upscale facts as literal #defines, so a fullscreen pass's GLSL can #ifdef
        // FX_UPSCALE etc directly -- unconditionally prepended to every fullscreen shader (harmless
        // when a pass never references any FX_* name).
        String enginePreamble = EngineDefines.glslPreamble(FornaxConfig.get().aaMethod, computeAvailable);

        Map<String, String> rewritten = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : shaderSources.entrySet()) {
            String rewrittenSource = DefineRewriter.rewrite(e.getValue(), pack.options(), compileValuesAsStrings);
            // Source keys are pack-root-relative ("shaders/post/ssao.fsh" -- see
            // PackDiscovery.readShaderSources), matching PassSpec.shader() verbatim.
            String packOptionsBlock = packOptionsBlockByShader.get(e.getKey());
            if (packOptionsBlock != null) {
                rewrittenSource = insertAfterFirstLine(rewrittenSource, packOptionsBlock);
            }
            if (fullscreenShaderPaths.contains(e.getKey())) {
                rewrittenSource = insertAfterFirstLine(rewrittenSource, enginePreamble);
            }
            rewritten.put(e.getKey(), rewrittenSource);
        }
        // Vanilla core-shader overrides (see VanillaShaderOverrides' own doc comment): extracted from
        // the SAME `rewritten` map and `compileValues` this rebuild just used for every other shader,
        // so an override's #define-driven gate (e.g. LIGHTMAP_CURVES) and its own splice content
        // agree with whatever this rebuild actually resolved -- never a stale or re-derived snapshot.
        // `compileValues` here already carries the engine-defines overlay (see above) merged over the
        // pack's own resolved compile values, exactly mirroring what DefineRewriter.rewrite just used
        // per-file via compileValuesAsStrings.
        //
        // Binary vanilla-asset overrides (see VanillaAssetOverrides' own doc comment, e.g. the
        // celestial textures gated on CELESTIAL_TEXTURES) are read fresh from `pack.root()` -- unlike
        // shader text they never flow through the rewrite loop above (no #define rewriting applies to
        // a PNG) -- but resolved against this SAME `compileValues`, for the identical reason: an
        // override's gate must agree with what this rebuild actually resolved.
        //
        // Both are gated on the master shadersEnabled switch as a second, independent layer (not just
        // relying on every caller to already be off/unload()ed): rebuild() itself is reachable while
        // the switch is off (e.g. PackEditSession applying a pack-settings edit, or PackReload.reload
        // at boot with a pack configured but shaders disabled) whenever a pack is loaded but latched
        // inactive, and any of those must still never let a vanilla override (core-shader or binary
        // asset) reach RuntimeShaderPack's minecraft-namespace maps while off -- the "invisible when
        // off" invariant (finding 1).
        Map<String, String> vanillaOverrides = FornaxConfig.get().shadersEnabled
                ? VanillaShaderOverrides.extract(rewritten, compileValues)
                : Map.of();
        Map<String, byte[]> vanillaBinaryOverrides = FornaxConfig.get().shadersEnabled
                ? VanillaAssetOverrides.extract(PackDiscovery.readTextureOverrides(pack.root()), compileValues)
                : Map.of();
        CompletableFuture<Void> sourcesVisible =
                RuntimeShaderPack.getInstance().reload(rewritten, vanillaOverrides, vanillaBinaryOverrides);
        // ensureRunnersBuilt() must not build (and thereby lazily compile) a single pass pipeline
        // before this lands -- see this method's own doc comment for the crash that guards against.
        // Generation-guarded: if a NEWER rebuild() has already run by the time this callback fires
        // (closeCurrent() already bumped rebuildGeneration again), this stale completion must not
        // mark the newer rebuild's sources ready.
        sourcesVisible.thenRunAsync(() -> {
            if (shouldMarkSourcesReady(generation, rebuildGeneration)) {
                sourcesReady = true;
                // Terrain-side counterpart of the ensureRunnersBuilt() sourcesReady gate: Sodium's
                // static TerrainRenderPass -> RenderPipeline
                // cache is otherwise cleared only at renderer recreation (initRenderer), so after a
                // pack-content republish the terrain pipelines' freshness depended entirely on
                // vanilla's clearPipelineCache interleaving inside the async resource reload --
                // implicit, unowned, and silent when it fails: the Vulkan backend's setPipeline has
                // NO "color attachment count must match" check (bytecode-verified against the real
                // MC 26.2 jar -- getOrCompilePipeline + isValid + vkCmdBindPipeline, nothing else),
                // so a terrain pipeline compiled against a pre-republish shader snapshot binds fine
                // and simply never writes the outputs the current text declares (live-caught as
                // gAlbedoRaw / attachment 5 arriving black at resolve). Clearing here --
                // generation-guarded, on the client executor between frames, the exact safety
                // initRenderer's clear has -- makes the first terrain draw after the republish
                // recompile SOLID/CUTOUT/shadow against the just-landed text, unconditionally.
                // See .superpowers/sdd/ecv2-attachment-fix-report.md.
                ShaderChunkRendererAccessor.fornax$getPrograms().clear();
            }
        }, Minecraft.getInstance()).exceptionally(t -> {
            FornaxMod.LOGGER.error("[Fornax] GraphRunner: resource reload failed after rebuild; "
                    + "pack graph pass runners will not (re)build until the next successful rebuild", t);
            return null;
        });

        // Direct block ids resolve immediately from currentPack's own categories; tag members may
        // still be empty until the world's tags are bound (see FornaxMod's TAGS_LOADED listener,
        // which refreshes this same lookup again once they are).
        MaterialResolution.refresh();
        return sourcesVisible;
    }

    /** Mirrors {@code FramePipeline.prepareGBufferForOpaquePass()}. */
    public static void prepare(ChunkRenderMatrices matrices, double x, double y, double z) {
        // One log line every 5s, no per-frame cost. Sampled BEFORE the isActive() gate so a session
        // is measured whether or not a pack is loaded -- "does it climb with no pack active too" is
        // itself one of the answers worth having. See MemoryWatchdog.
        MemoryWatchdog.sample();
        if (!isActive()) {
            // Every terrain draw binds all GeometryInputs.RESERVED slots regardless of pack activity
            // (the bind group shape is process-wide fixed -- see GeometryInputs' own doc), so a stale
            // resolved view from a since-unloaded pack must never survive to the next draw; falling
            // back to noise (via geometryInputView's own null-coalesce) is always safe here.
            clearGeometryInputViews();
            // This early return -- gated on the LATCHED isActive(), not live config -- also skips
            // opaqueDepth.ensureSize below, exactly like it skips registry/mipchain sizing: the copy
            // only exists while a pack actually drives rendering. closeCurrent() has already freed it
            // by the time this runs with no pack active (pack unload, or mid pack-switch), so there is
            // no stale-sized texture sitting here unused; the next prepare() with a pack active
            // reallocates and re-clears it via ensureSize's own MoltenVK garbage-VRAM guard.
            return;
        }

        int width = Minecraft.getInstance().gameRenderer.mainRenderTarget().width;
        int height = Minecraft.getInstance().gameRenderer.mainRenderTarget().height;
        // Render size and output size are threaded separately so an OUTPUT-basis target (sceneHistory)
        // sizes off native resolution independently of render resolution. mainRenderTarget itself is
        // NOT a valid source for output size: SsaaManager/GameRendererMixin swap it to the off-screen
        // render-scale target before this runs (larger under SSAA, smaller under TAAU), so its own
        // width/height is this frame's RENDER size, never the display's true native size. Output size
        // instead comes from SsaaManager's captured native window size (set every frame at the HEAD of
        // GameRenderer.renderLevel, before any swap) -- this is the fix for a latent bug: before this
        // sourcing change, sceneHistory (OUTPUT-basis) was sized off mainRenderTarget too, so under
        // SSAA it was allocated at the SUPERSAMPLED size instead of native, and the end-of-frame copy's
        // Math.min(target, sceneHistory) clamp silently wrote only a native-sized sub-region of that
        // oversized texture -- the rest never got a value, a stale gap in UV space every consumer
        // samples across. Now that output sizing is genuinely native, sceneHistory shrinks to native
        // under SSAA too and that gap closes; SSAA's own render size is unaffected.
        int outputWidth = SsaaManager.nativeWidth();
        int outputHeight = SsaaManager.nativeHeight();

        GBufferManager.ensureSize(width, height);
        GBufferManager.beginFrame();
        opaqueDepth.ensureSize(width, height);
        if (packTextureRegistry != null) {
            packTextureRegistry.ensureLoaded();
        }
        // WaterSurfaceManager is NOT sized here, unlike GBufferManager/opaqueDepth -- its allocation
        // must stay gated on SSR_WATER_MODE > 1 so a low-end GPU pays zero deferred-water VRAM when
        // the mode is off. It is instead sized (and cleared) from
        // SodiumWorldRendererOrchestrationMixin#fornax$renderWaterPrepass, mirroring
        // ShadowMapManager's own per-call ensureSize/clear pattern -- see that method's own doc.

        TargetRegistry r = registry;
        if (r != null) {
            r.ensureSize(width, height, outputWidth, outputHeight);
            // Voxel water reflection SSBO: re-checked EVERY frame against this frame's live render
            // resolution, exactly like every sibling render-basis resource just above (ensureBufferSize
            // already no-ops when the requested size matches the current allocation, so this costs
            // nothing once steady-state) -- NOT a one-shot snapshot taken only the first time a pack
            // build runs. It used to live inside ensureRunnersBuilt()'s one-shot-latched body, sized
            // once at whatever render resolution happened to be live at that single moment; the compute
            // dispatch group count, the kernel's own index math, and water_composite's consumer index
            // math all instead read the LIVE render resolution every frame (computeDispatchOverride,
            // computeParams -- see their own doc comments), so under TAA (render resolution pinned to
            // native for the method's whole lifetime) the mismatch could never surface, but under TAAU
            // (render resolution is a LIVE per-frame value that also transiently reverts to native for
            // one frame around every pack rebuild -- see SsaaManager/GameRendererMixin) the frozen
            // capacity and the live index math would disagree, corrupting this SSBO with a genuine
            // std430 out-of-bounds write the instant live resolution ever exceeded the frozen snapshot
            // -- live-caught as a screen-wide solid-blue frame with WORLD_REFLECTIONS on under TAAU
            // (see .superpowers/sdd/taau-break-diagnosis.md). Freed only when it was actually allocated
            // (registry.getBuffer != null): calling VoxelWaterReflBuffer.free on a never-allocated
            // buffer would vmaCreateBuffer a 0-byte buffer (invalid Vulkan usage, see that method's own
            // doc) -- the common case, since this pass defaults off (manual-only "Beyond" tier,
            // Zero-Custom law).
            if (graphHasEnabledPass("voxel_water_refl")) {
                VoxelWaterReflBuffer.ensureAllocated(r, width, height);
            } else if (r.getBuffer(VoxelWaterReflBuffer.TARGET) != null) {
                VoxelWaterReflBuffer.free(r);
            }
            // The DDA sun-shadow prototype's own intermediate SSBO (SunShadowVoxelBuffer) was
            // retired when sun_shadow_voxel/sun_shadow_resolve (compute + blit) collapsed into ONE
            // fullscreen fragment pass, "sun_shadow" (queue-topology fix) -- it now writes the real
            // celestialVisVoxel r8 target directly, exactly like every other fullscreen pass, so
            // there is no engine-sized buffer left to (re)allocate here.

            // Analytic light list SSBO: FIXED size (not
            // render-resolution-dependent like VoxelWaterReflBuffer above), so no width/height args --
            // but still re-checked every rebuild, same graphHasEnabledPass-gated allocate/free shape,
            // so a pack that disables ANALYTIC_LIGHTS frees it rather than leaking a permanent 6 KB
            // SSBO. ensureBufferSize no-ops once already at this size, so this costs nothing once
            // steady-state (same "no-op once allocated" contract every sibling buffer target relies on).
            if (graphHasEnabledPass("light_list_build")) {
                AnalyticLightListBuffer.ensureAllocated(r);
            } else if (r.getBuffer(AnalyticLightListBuffer.TARGET) != null) {
                AnalyticLightListBuffer.free(r);
            }

            // Per-column precipitation type (see PrecipClipmapBuffer). Gated on the TARGET NAME rather
            // than on a pass name, unlike the two above, because it is a data source and not a pass's
            // scratch space -- any pass that reads it needs it filled, and the engine should not have
            // to know which pass a given pack put it in.
            //
            // FILLED HERE TOO, not on a later hook, and the ordering is load-bearing twice over. The
            // allocation must precede ensureRunnersBuilt below or ComputePassRunner.descriptorTypeFor
            // classifies the name as unknown and throws, aborting EVERY runner in that build attempt
            // (see the ordering law at ensureRunnersBuilt's own gate). And the fill must precede this
            // frame's graph execution, or the field a compute pass reads is a frame stale for no
            // reason -- unlike snowField, which is one frame old by design.
            //
            // currentPack is re-null-checked rather than relying on isActive()'s guarantee at the top
            // of this method, matching graphHasEnabledPass's own discipline: a pack unload can destroy
            // the registry ahead of its renderer reload, and that window is exactly where a static
            // field read hundreds of lines from its guard goes wrong.
            if (currentPack != null
                    && anyEnabledPassReadsPrecipClipmap(currentPack.graph(), compileValues)) {
                PrecipClipmapBuffer.ensureAllocated(r);
                PrecipClipmapUpload.onFrame(r);
            } else if (r.getBuffer(PrecipClipmapBuffer.TARGET) != null) {
                PrecipClipmapBuffer.free(r);
            }

            // Generic surface-fluid data for pack-owned simulations. The CPU harvest is bounded by
            // SURFACE_FLUID_DETAIL, while the transfer itself is recorded into the first consuming
            // compute pass's existing frames-in-flight command buffer (no per-frame host wait).
            if (currentPack != null
                    && anyEnabledPassReadsSurfaceFluidClipmap(currentPack.graph(), compileValues)) {
                SurfaceFluidClipmapBuffer.ensureAllocated(r);
                SurfaceFluidClipmapUpload.onFrame(r,
                        compileValues.getOrDefault("SURFACE_FLUID_DETAIL",
                                SurfaceFluidClipmapBuffer.TIER_STANDARD));
            } else if (r.getBuffer(SurfaceFluidClipmapBuffer.TARGET) != null) {
                SurfaceFluidClipmapBuffer.free(r);
            }

            // The bodies touching water near the camera, for pack-owned simulations that react to
            // more than the one actor the globals block can carry. Collection is bounded by
            // WaterActorBuffer.MAX_ACTORS and runs only while some enabled pass actually binds it,
            // so a pack that does not ask for it pays neither the entity sweep nor the allocation.
            if (currentPack != null
                    && anyEnabledPassReadsWaterActors(currentPack.graph(), compileValues)) {
                WaterActorBuffer.ensureAllocated(r);
                WaterActorUpload.onFrame(r);
            } else if (r.getBuffer(WaterActorBuffer.TARGET) != null) {
                WaterActorBuffer.free(r);
            }
        }
        ensureRunnersBuilt();
        // AFTER ensureRunnersBuilt, which is what lazily creates optionsBuffer, and BEFORE any pass
        // binds u_PackOptions this frame. This is the only safe point to rotate the ring.
        flushPendingRuntimeValues();
        for (MipchainRunner m : mipchainRunners.values()) {
            m.ensureSize(width, height, outputWidth, outputHeight);
        }
        // AFTER registry/mipchain sizing above, BEFORE Sodium's own opaque terrain draw (this frame's
        // only reader of geometryInputViews) runs -- so a declared input resolves against this
        // frame's freshly (re)sized target, never a stale one from before ensureSize ran.
        refreshGeometryInputViews();

        runPreOpaqueLightingCompute(matrices, x, y, z, width, height);
    }

    /**
     * Runs the voxel-light producers before Sodium begins the opaque terrain render pass.
     *
     * <p>These passes consume the voxel window uploaded at the end of the preceding frame and do not
     * consume any current-frame graphics output. Running them here lets the final enabled producer
     * signal one semaphore whose graphics wait is inserted before opaque rendering begins. Because
     * every producer submits to the same compute queue in graph order, that single signal covers the
     * complete inject/propagate/list-reset/list-build chain. This closes the compute-write to
     * fragment-read dependency without splitting an active Apple tile render encoder.
     */
    private static void runPreOpaqueLightingCompute(ChunkRenderMatrices matrices,
                                                    double x, double y, double z,
                                                    int width, int height) {
        PackModel pack = currentPack;
        TargetRegistry r = registry;
        PackOptionsBuffer options = optionsBuffer;
        if (pack == null || r == null || options == null || computeBackend == null) {
            return;
        }

        List<PassSpec> runnable = pack.graph().passes().stream()
                .filter(GraphRunner::isPreOpaqueLightingComputePass)
                .filter(GraphRunner::enabledAtCompile)
                .filter(p -> computeRunners.containsKey(p.name()))
                .toList();

        // Null until Sodium's first terrain draw of the session has published a slice, and
        // unavoidably so at THIS point in the frame: prepare() runs before any terrain draw, so what
        // is live here is the PREVIOUS frame's slice (or nothing at all, on the first frame). No pass
        // reaching this loop declares the reserved 'globals' input today -- these are the four
        // engine-name-keyed lighting producers -- but it is passed through rather than hardcoded null
        // so that a future one is skipped by ComputePassRunner.run's own guard instead of binding
        // whatever this happened to be.
        GpuBufferSlice globals = ChunkRenderContextHolder.getUniformBuffer();

        for (int i = 0; i < runnable.size(); i++) {
            PassSpec p = runnable.get(i);
            ComputePassRunner runner = computeRunners.get(p.name());
            if (runner == null) {
                continue; // guarded by the collection filter; defensive against a concurrent rebuild
            }
            boolean finalProducer = i == runnable.size() - 1;
            // Only the final producer signals: every producer submits to the same compute queue in
            // graph order, so one semaphore covers the whole chain (see this method's own doc). The
            // consumers of these lighting buffers are fragment shaders, so FRAGMENT is the earliest
            // stage the graphics queue has to wait at.
            long graphicsWaitStages = finalProducer ? VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT : 0;
            runner.run(r, computeParams(p, width, height), options, globals,
                    computeExtraPushConstants(p, matrices, x, y, z),
                    computeDispatchOverride(p), false, graphicsWaitStages);
        }
    }

    /** Mirrors {@code FramePipeline.finishOpaque(...)}. */
    public static void finish(ChunkRenderMatrices matrices, double x, double y, double z) {
        if (!isActive()) {
            return;
        }
        TargetRegistry r = registry;
        PackModel pack = currentPack;
        PackOptionsBuffer options = optionsBuffer;
        if (r == null || pack == null || options == null) {
            return;
        }
        GBuffer gbuffer = GBufferManager.getInstance();
        if (gbuffer == null) {
            return;
        }
        GBufferManager.clearIfNoWriterForResolve();
        // Which of this pack's claimed geometry slots actually receive draws. Ticked here because
        // this is the one method that runs exactly once per rendered frame with the pack in hand;
        // the census itself reports once, well after load. See SlotReachabilityCensus for why the
        // loader structurally cannot answer this, and why the weather pass was inert for its whole
        // life while validating perfectly.
        SlotReachabilityCensus.onFrame(pack);
        // VRAM-to-CPU ground truth for the AO/albedo/normal attachments, gated on the profiler
        // overlay so it costs nothing in normal play -- see its own doc comment.
        GBufferReadbackDiagnostic.maybeLog(gbuffer);
        // One-shot crosshair readback of sceneHdr, valid only with ENV_SPEC_RATIO selected -- see
        // EnvSpecularRatioReadback's own doc comment for why this reads a named pack target via
        // TargetRegistry rather than a GBuffer attachment.
        EnvSpecularRatioReadback.maybeLog(r);
        GpuBufferSlice globals = ChunkRenderContextHolder.getUniformBuffer();
        if (globals == null) {
            return;
        }

        int width = gbuffer.getWidth();
        int height = gbuffer.getHeight();

        PassTimer timer = passTimer;
        if (timer != null) {
            timer.beginFrame();
            timer.bracketBegin(FrameProfiler.LABEL_FRAME);
            // Sodium's terrain draws already completed before finish() was ever called, so this bracket
            // does NOT measure terrain rendering itself -- it measures GraphRunner's own dwell in the
            // graph's geometry slot (the loop iteration that recognizes and skips the geometry pass),
            // ending the instant the first such slot is skipped below. Hence the honest label -- see
            // FrameProfiler.LABEL_TERRAIN's own comment.
            timer.bracketBegin(FrameProfiler.LABEL_TERRAIN);
        }
        // Closed at the FIRST geometry slot only: a graph is free to declare zero or several geometry
        // slots, and PassTimer poisons the frame on any mispaired end, so this bracket must close
        // exactly once -- at the first slot, or after the loop if the graph declares none.
        boolean geometryDwellClosed = false;

        for (PassSpec p : pack.graph().passes()) {
            if (p.type() == PassType.GEOMETRY) {
                if (timer != null && !geometryDwellClosed) {
                    timer.bracketEnd(FrameProfiler.LABEL_TERRAIN);
                    geometryDwellClosed = true;
                }
                continue; // Sodium's own opaque draws already ran -- the graph just names this slot.
            }
            if (isPreOpaqueLightingComputePass(p)) {
                // Submitted from prepare(), before opaque rendering begins, so its final semaphore
                // wait cannot split the active Apple tile render encoder.
                continue;
            }
            if (!enabledAtCompile(p)) {
                continue;
            }
            // COMPUTE passes are bracketed with a CPU wall-clock measurement below instead of
            // PassTimer's GPU-timestamp bracket -- see the COMPUTE case's own comment for why a GPU
            // timestamp pair straddling this call cannot observe a raw-Vulkan compute submission's
            // real cost (root-caused live: sun_shadow_voxel's profiler row read a permanent 0.00ms
            // even while its synchronous fence-wait demonstrably blocked the render thread).
            boolean gpuTimestampBracket = timer != null && p.type() != PassType.COMPUTE;
            if (gpuTimestampBracket) {
                timer.bracketBegin(p.name());
            }
            switch (p.type()) {
                case FULLSCREEN -> {
                    FullscreenPassRunner runner = fullscreenRunners.get(p.name());
                    if (runner != null) {
                        runner.run(r, mipchainTargets, globals, options, computeParams(p, width, height));
                    } else {
                        logMissingRunnerOnce(p.name());
                    }
                }
                case MIPCHAIN -> {
                    MipchainRunner runner = mipchainRunners.get(p.name());
                    if (runner != null) {
                        runner.run(r, mipchainTargets);
                    } else {
                        logMissingRunnerOnce(p.name());
                    }
                }
                case COPY -> CopyRunner.run(p, r);
                case COMPUTE -> {
                    ComputePassRunner runner = computeRunners.get(p.name());
                    if (runner != null) {
                        if (computeBackend != null) {
                            // Lighting compute is submitted from prepare(), before opaque rendering.
                            // The only compute pass left in this graph position with a current-frame
                            // graphics dependency is voxel_water_refl, which keeps its legacy host wait.
                            boolean synchronousWait = isVoxelWaterReflPass(p);
                            long cpuStart = synchronousWait ? System.nanoTime() : 0L;
                            runner.run(r, computeParams(p, width, height), options, globals,
                                    computeExtraPushConstants(p, matrices, x, y, z),
                                    computeDispatchOverride(p), synchronousWait,
                                    graphicsWaitStagesFor(p, pack.graph()));
                            if (synchronousWait) {
                                frameProfiler.record(p.name(), (System.nanoTime() - cpuStart) / 1_000_000.0);
                            }
                        }
                    } else {
                        logMissingRunnerOnce(p.name());
                    }
                }
                case PARTICLES -> {
                    ParticlePassRunner runner = particleRunners.get(p.name());
                    if (runner != null) {
                        runner.run(r, mipchainTargets, globals, options, computeParams(p, width, height));
                    } else {
                        logMissingRunnerOnce(p.name());
                    }
                }
                case TEMPORAL -> {
                    TemporalPassRunner runner = temporalRunners.get(p.name());
                    if (runner != null) {
                        runner.run(r, mipchainTargets, computeParams(p, width, height));
                    } else {
                        logMissingRunnerOnce(p.name());
                    }
                }
                case GEOMETRY -> {
                }
            }
            if (gpuTimestampBracket) {
                timer.bracketEnd(p.name());
            }
        }

        if (timer != null) {
            if (!geometryDwellClosed) {
                timer.bracketEnd(FrameProfiler.LABEL_TERRAIN); // graph declared no geometry slot
            }
            timer.bracketEnd(FrameProfiler.LABEL_FRAME);
            timer.endFrame();
        }

        // Finish-opaque capture of the engine-owned builtin.depth_opaque copy -- see OpaqueDepth's own
        // doc for why this must be a copy (the live G-buffer depth is bound for depth-testing during
        // the translucent draw that follows this method, so sampling it directly there is a Vulkan
        // hazard). Order relative to the fallback copy-back below is immaterial: both read the same
        // G-buffer depth this frame.
        opaqueDepth.capture(gbuffer.getDepthTexture(), width, height);

        if (!packDeclaresDepthCopyback) {
            // Fallback: a pack that declares no depth copy-back still gets correct vanilla translucent
            // depth (never ship corrupt output). Packs that DO declare it control its ordering instead.
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    gbuffer.getDepthTexture(),
                    Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture(),
                    0, 0, 0, 0, 0, width, height);
        }

        r.swapHistory();

        // Engine-owned voxel debug raymarch streaming update: manages the toroidal window (recenter/
        // resync/upload) and captures this frame's camera while the VOXEL_RAYMARCH debug view is
        // selected OR a currently enabled pack compute pass reads the voxel grid (e.g. rt_shadow) --
        // see anyEnabledComputePassReadsVoxelGrid; a no-op detach otherwise. The actual debug-view
        // dispatch + present happens later, at GameRendererMixin's renderLevel RETURN, once
        // mainRenderTarget is the final native target.
        VoxelDebugRaymarchPass.onFrame(r, matrices, x, y, z, anyEnabledComputePassReadsVoxelGrid(pack.graph(), compileValues));

        // Sky reprojection: this frame's NDC->previous-NDC map for infinitely distant content -- the
        // motion vector the temporal reconstruct uses wherever the G-buffer wrote none, because
        // gMotion is a G-buffer attachment and a sky pixel carries its cleared zero (see
        // SkyReprojection). Committed here because this is the one site that runs exactly once per
        // frame with this frame's model-view in hand and still ahead of the reconstruct pass at
        // renderLevel RETURN. The UN-JITTERED projection, not matrices.projection(): gMotion is
        // expressed in a jitter-free basis (terrain.vsh subtracts each frame's own jitter before
        // differencing) and the sky motion has to agree with it.
        SkyReprojection.commit(CameraJitter.currentUnjitteredProjection(), matrices.modelView());

        // LAST, with the same arguments FramePipeline.finishOpaque itself receives -- see its own
        // javadoc for why this must commit after this frame's opaque draws/uniform upload, not before.
        PreviousFrameCameraTransform.commit(new CameraTransform(x, y, z), matrices.projection(), matrices.modelView());
    }

    private static void ensureRunnersBuilt() {
        if (runnersBuilt || currentPack == null || registry == null) {
            return;
        }
        if (!sourcesReady) {
            // retry next frame -- the current rebuild()'s resource-pack reload hasn't landed yet, so
            // the shader manager may still resolve a pass's fragment shader against the PREVIOUS
            // compile-value snapshot's text. Building (and, via the pass loop's first run(), lazily
            // compiling) a pipeline now risks the exact bind-group/shader-text mismatch documented on
            // rebuild() -- an unrecoverable "Pipeline is not valid" crash one frame later, not just
            // stale visuals. Every already-enabled pass simply has no runner in the meantime, which
            // finish()'s pass loop already treats as a normal, logged-once, non-fatal skip.
            return;
        }
        if (RenderSystem.tryGetDevice() == null) {
            return; // retry next frame -- mirrors every hardcoded manager's own device-availability guard
        }

        if (passTimer == null) {
            passTimer = new PassTimer(frameProfiler);
        }

        if (computeBackend == null) {
            computeBackend = VulkanComputeBackend.tryCreate();
            // Null on the GL backend or before any device exists yet -- retried next call, same as
            // passTimer's own lazy-build guard; never treated as a hard failure.
        }

        // FX_COMPUTE self-heal. rebuild() overlays EngineDefines.forMethod's FX_COMPUTE fact using
        // WHATEVER computeBackend read as at that exact call (see rebuild()'s own doc comment on why
        // it reads the cached field rather than probing fresh) -- but the pack's very first rebuild()
        // always runs from FornaxMod's boot-time loadConfiguredPack(), fired from inside Minecraft's
        // own constructor before any GpuDevice exists (see FornaxMod's own doc comment on why it
        // passes fixed BOOTSTRAP_WIDTH/HEIGHT), so computeBackend is unconditionally null there and
        // FX_COMPUTE bakes to 0 into that rebuild's compileValues AND every fullscreen shader's
        // spliced-in engine #define preamble -- regardless of what the session's real hardware/
        // backend can actually do. Nothing else re-derives compileValues once a real device (and
        // therefore a real computeBackend, created lazily right above) shows up a few frames later --
        // a rebuild only happens again on an explicit pack switch or a compile-dirty settings apply/
        // aaMethod change (see PackReload/PackEditSession/SettingsApplyRouter) -- so on a session
        // where the persisted pack values already have an FX_COMPUTE-gated option enabled (e.g.
        // WORLD_REFLECTIONS + SSR_WATER_MODE > 3) and the player never happens to dirty a COMPILE
        // option this session, every enabled_if referencing FX_COMPUTE stays permanently wrong for
        // the pack's ENTIRE session lifetime. Live-caught: the voxel water reflection arm silently
        // never activated while its plain-arm sibling's `!(... && FX_COMPUTE)` negation stayed
        // permanently true instead, on a fresh relaunch with Beyond+World Reflections already saved.
        //
        // Fires at most once per rebuild: replays the exact same PackModel/shaderSources/pack-only
        // compileValues the LAST rebuild() call used (cached by rebuild() itself, see
        // lastRebuildShaderSources/lastRebuildPackCompileValues) so this needs no second disk read or
        // re-derivation of the pack's own compile-option values -- only the engine-overlay fact
        // changes. Gated on the graph actually referencing FX_COMPUTE anywhere (targets or passes) so
        // a pack that never uses it never pays this extra rebuild's cost. Returns immediately after:
        // the replayed rebuild() already reset sourcesReady/runnersBuilt, so this method must retry
        // from its own top-of-method guards next frame, exactly like the sourcesReady branch above.
        // Self-limiting: the replayed rebuild() reads the SAME now-non-null computeBackend, so the
        // two sides agree afterward and this branch never fires again for this pack activation.
        if (computeBackend != null && compileValues.getOrDefault("FX_COMPUTE", 0) == 0
                && currentPack != null && graphReferencesEngineCompute(currentPack.graph())) {
            rebuild(currentPack, lastRebuildShaderSources, lastRebuildPackCompileValues, pendingRuntimeDefaults);
            return;
        }

        if (optionsBuffer == null && pendingOptionsLayout != null) {
            optionsBuffer = new PackOptionsBuffer(pendingOptionsLayout);
            optionsBuffer.writeAll(pendingRuntimeDefaults);
        }

        // Allocate the voxel brick-grid buffers BEFORE building pass runners, not after: a compute pass
        // that reads voxelOccupancy (e.g. rt_shadow) must find it already allocated the moment its own
        // runner is built below, or ComputePassRunner.build() throws "neither an allocated buffer nor
        // texture target" and the catch below aborts EVERY runner this frame (root-caused live: this used
        // to happen only via VoxelDebugRaymarchPass.onFrame(), called from GraphRunner.finish() -- one
        // frame AFTER this method runs at frame start -- so the very first frame of any session/rebuild
        // guaranteed the race and shrieked the whole ERROR burst; it then silently succeeded next frame).
        if (anyEnabledComputePassReadsVoxelGrid(currentPack.graph(), compileValues)) {
            VoxelDebugRaymarchPass.ensureGridAllocated(registry);
        }

        // Voxel water reflection SSBO allocation/free itself now lives in prepare() (see its own doc
        // comment there) -- re-checked every frame against the live render resolution, not a one-shot
        // snapshot here. By the time this method reaches runner build below, prepare() has already
        // (re)allocated it this frame (prepare() calls ensureRunnersBuilt() AFTER its own
        // VoxelWaterReflBuffer.ensureAllocated call), so ComputePassRunner.build/FullscreenPassRunner.build
        // still find it already allocated at the size this frame's dispatch/texelFetch expects -- the
        // same ordering guarantee the old one-shot call here used to provide, just re-derived every
        // frame instead of frozen at first build.

        Map<String, FullscreenPassRunner> fullscreen = new LinkedHashMap<>();
        Map<String, MipchainRunner> mipchain = new LinkedHashMap<>();
        Map<String, ComputePassRunner> compute = new LinkedHashMap<>();
        Map<String, ParticlePassRunner> particles = new LinkedHashMap<>();
        Map<String, TemporalPassRunner> temporal = new LinkedHashMap<>();
        try {
            for (PassSpec p : currentPack.graph().passes()) {
                if (!enabledAtCompile(p)) {
                    // A compile-disabled pass's output target may legitimately not exist (targets
                    // carry enabled_if too -- e.g. a half-res SSR variant only allocated on its own
                    // quality tier), so building its runner would throw "target not allocated" and
                    // abort EVERY pass runner, retrying forever (live-caught: the whole post chain
                    // never ran). Compile values only change via rebuild(), which clears all runners
                    // first, so skipping here can never leave a stale-disabled runner behind.
                    continue;
                }
                switch (p.type()) {
                    case FULLSCREEN -> fullscreen.put(p.name(), FullscreenPassRunner.build(p, registry));
                    case MIPCHAIN -> {
                        TargetSpec targetSpec = p.target() == null ? null : currentPack.graph().targets().get(p.target());
                        if (targetSpec == null) {
                            throw new IllegalStateException("mipchain pass '" + p.name() + "' names no allocated target");
                        }
                        mipchain.put(p.name(), MipchainRunner.build(p, targetSpec));
                    }
                    case COMPUTE -> {
                        if (computeBackend != null) {
                            compute.put(p.name(), ComputePassRunner.build(p, computeBackend, registry,
                                    extraPushConstantBytesFor(p),
                                    computeStorageWriteNeedsGraphicsDrain(p, currentPack.graph(), compileValues)));
                        }
                    }
                    case PARTICLES -> {
                        // Same computeBackend gate the COMPUTE arm uses: a particles pass is a
                        // raw-Vulkan pipeline, so it simply does not exist on the GL backend (or
                        // before a device does). No runner means finish() logs the pass as missing
                        // once and skips it, exactly like a compute pass in the same situation.
                        if (computeBackend != null) {
                            particles.put(p.name(), ParticlePassRunner.build(p, computeBackend, registry));
                        }
                    }
                    case TEMPORAL -> {
                        TargetSpec outSpec = currentPack.graph().targets().get(p.outputs().get(0));
                        if (outSpec == null) {
                            throw new IllegalStateException("temporal pass '" + p.name()
                                    + "' names no allocated output target");
                        }
                        temporal.put(p.name(), TemporalPassRunner.build(p,
                                TargetFormat.parse(outSpec.format(), p.outputs().get(0), "graph.toml")));
                    }
                    case COPY, GEOMETRY -> {
                    }
                }
            }
        } catch (RuntimeException e) {
            // A late runner failure must not leak everything built earlier in this attempt; this
            // method retries next frame and otherwise compounds native resources indefinitely.
            fullscreen.values().forEach(FullscreenPassRunner::close);
            mipchain.values().forEach(MipchainRunner::close);
            compute.values().forEach(ComputePassRunner::close);
            particles.values().forEach(ParticlePassRunner::close);
            FornaxMod.LOGGER.warn("[Fornax] GraphRunner: pass runners not ready yet ({}); retrying next frame", e.toString());
            return;
        }

        fullscreenRunners.putAll(fullscreen);
        mipchainRunners.putAll(mipchain);
        mipchainTargets.putAll(indexMipchainTargets(currentPack.graph(), mipchain));
        computeRunners.putAll(compute);
        particleRunners.putAll(particles);
        temporalRunners.putAll(temporal);
        runnersBuilt = true;
    }

    /**
     * Builds the resource-facing mipchain index from the graph's declared target names. Pass
     * execution continues to use {@code runnersByPass}; texture consumers use this returned map.
     */
    static <T> Map<String, T> indexMipchainTargets(GraphSpec graph, Map<String, T> runnersByPass) {
        Map<String, T> byTarget = new LinkedHashMap<>();
        for (PassSpec pass : graph.passes()) {
            if (pass.type() != PassType.MIPCHAIN || pass.target() == null) {
                continue;
            }
            T runner = runnersByPass.get(pass.name());
            if (runner != null) {
                T previous = byTarget.put(pass.target(), runner);
                if (previous != null && previous != runner) {
                    throw new IllegalStateException(
                            "multiple mipchain passes declare target '" + pass.target() + "'");
                }
            }
        }
        return byTarget;
    }

    /**
     * Whether the ACTIVE pack's graph declares a compile-enabled {@code temporal} pass -- the
     * signal {@code GameRendererMixin} uses under TAA to skip the end-of-frame ACCUMULATION
     * (mid-graph already did it) and present sharpen-only, letting the end-of-frame sceneHistory
     * copy resume. Recomputed per call against live compile values, never cached: whether the
     * pass runs depends on {@code enabledAtCompile}, the same filter the pass loop applies.
     */
    public static boolean activePackHasTemporalPass() {
        PackModel pack = currentPack;
        if (pack == null) {
            return false;
        }
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() == PassType.TEMPORAL && enabledAtCompile(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code graph} declares its own {@code copy} pass writing {@code builtin.sceneDepth} --
     * if so, the pack owns depth copy-back's ordering and {@link #finish} must not also run the
     * hardcoded fallback copy. Extracted as a pure function of {@link GraphSpec} (rather than inlined
     * into {@link #rebuild}) so it's unit-testable without a device-backed {@link PackModel}.
     */
    static boolean computePackDeclaresDepthCopyback(GraphSpec graph) {
        return graph.passes().stream().anyMatch(p -> p.type() == PassType.COPY
                && !p.outputs().isEmpty() && p.outputs().get(0).equals("builtin.sceneDepth"));
    }

    /**
     * Whether a {@code RuntimeShaderPack.reload} completion belonging to {@code completedGeneration}
     * may mark {@link #sourcesReady} true, given the live rebuild counter now reads {@code
     * currentGeneration} -- false when a NEWER {@link #rebuild} has already superseded it (its own
     * {@link #closeCurrent()} call bumped the counter again before this stale future landed).
     * Extracted as a pure function (rather than inlined into the {@code thenRunAsync} lambda in
     * {@link #rebuild}) so the generation-comparison itself -- the one part of the ordering fix
     * genuinely worth pinning against an off-by-one -- is unit-testable without a device-backed
     * pack, mirroring {@link #computePackDeclaresDepthCopyback}'s own extraction rationale.
     */
    static boolean shouldMarkSourcesReady(long completedGeneration, long currentGeneration) {
        return completedGeneration == currentGeneration;
    }

    private static boolean enabledAtCompile(PassSpec p) {
        return isEnabledAtCompile(p, compileValues);
    }

    /** Shared {@code enabled_if} evaluation, factored out of {@link #enabledAtCompile} so {@link
     * #anyEnabledComputePassReadsVoxelGrid(GraphSpec, Map)} can re-check the same live compile values
     * against every pass in a graph without a second, divergent copy of this logic. */
    private static boolean isEnabledAtCompile(PassSpec p, Map<String, Integer> compileValues) {
        if (p.enabledIf() == null) {
            return true;
        }
        return EnabledIfExpr.parse(p.enabledIf()).evaluate(compileValues);
    }

    /**
     * True if any currently-enabled compute pass in {@code graph} reads the brick voxel grid -- the
     * voxel window must stream real data whenever this is true, independent of whether the debug
     * raymarch view happens to also be selected. Checked by name against the buffer target the
     * brick-voxelization milestone injects ({@link BrickGridUpload#OCCUPANCY_TARGET}), not a specific
     * pack option name -- any current or future pack-authored compute pass that reads the voxel grid
     * activates streaming, without this engine code needing to know that pack's option names.
     *
     * <p>Pure function of {@link GraphSpec} + the live compile values (not of {@link #computeRunners},
     * which only holds runners for passes a device already exists to build) -- this keeps it directly
     * unit-testable without a live pack or GPU device, mirroring {@link
     * #computePackDeclaresDepthCopyback}. It re-evaluates each compute pass's {@code enabled_if} via
     * {@link #isEnabledAtCompile}, the exact same check {@link #enabledAtCompile} applies in {@link
     * #finish}, rather than trusting a separately-tracked "currently built" set.
     */
    static boolean anyEnabledComputePassReadsVoxelGrid(GraphSpec graph, Map<String, Integer> compileValues) {
        for (PassSpec p : graph.passes()) {
            // Originally COMPUTE-only (every voxel-grid consumer was a compute dispatch). Widened to
            // also cover FULLSCREEN passes (queue-topology fix): the sun-shadow prototype's DDA
            // moved from a compute pass (sun_shadow_voxel) to a fullscreen fragment
            // pass (sun_shadow) that texelFetches voxelOccupancy directly -- without this widening,
            // enabling that pass alone would never trip this predicate, VoxelDebugRaymarchPass.onFrame
            // would treat the grid as unneeded, and the fragment pass's own texelFetch would read an
            // unallocated/never-streamed buffer (FullscreenPassRunner.run throws "buffer input is not
            // allocated" the instant a buffer-kind input's registry entry is null). Widened again to
            // PARTICLES for the identical reason: a particles pass binds a buffer-kind input as a real
            // STORAGE_BUFFER descriptor, so one declaring voxelOccupancy (a shelter march deciding
            // whether a flake is under cover) would otherwise fail its runner build with "neither an
            // allocated buffer nor texture target" -- and that throw aborts EVERY runner in the
            // attempt, retrying forever. MIPCHAIN/COPY/GEOMETRY passes are still not included: no pack
            // pass of those types has ever declared a voxel-buffer input, and admitting them here
            // would silently broaden this gate's meaning beyond "a pass that actually DDA-marches or
            // otherwise samples the grid this frame".
            if ((p.type() != PassType.COMPUTE && p.type() != PassType.FULLSCREEN
                    && p.type() != PassType.PARTICLES)
                    || !isEnabledAtCompile(p, compileValues)) {
                continue;
            }
            for (String in : p.inputs()) {
                if (in.equals(BrickGridUpload.OCCUPANCY_TARGET)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if any currently-enabled pass reads the per-column precipitation clipmap, which is what
     * decides whether the engine pays to fill it.
     *
     * <p>Same shape and same reasoning as {@link #anyEnabledComputePassReadsVoxelGrid}: keyed on the
     * BUFFER TARGET NAME rather than on any pack's option or pass name, so a pack that grows a second
     * consumer -- ground wetness that dries in a desert, say, alongside snow that only falls where it
     * snows -- turns the fill on without this engine code learning anything about that pack. The same
     * three pass types are admitted for the same reason: all three bind a buffer input as a real
     * descriptor and would fail their runner build against an unallocated target.
     *
     * <p>Pure function of {@link GraphSpec} plus the live compile values, so it is unit-testable with
     * no pack and no device.
     */
    static boolean anyEnabledPassReadsPrecipClipmap(GraphSpec graph, Map<String, Integer> compileValues) {
        for (PassSpec p : graph.passes()) {
            if ((p.type() != PassType.COMPUTE && p.type() != PassType.FULLSCREEN
                    && p.type() != PassType.PARTICLES)
                    || !isEnabledAtCompile(p, compileValues)) {
                continue;
            }
            for (String in : p.inputs()) {
                if (in.equals(PrecipClipmapBuffer.TARGET)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an enabled shader pass binds the engine-owned surface-fluid field.
     *
     * <p>Keyed on the buffer target rather than a pack pass name so this remains a reusable engine
     * capability. Only pass kinds with an actual buffer-input descriptor path participate.
     */
    static boolean anyEnabledPassReadsSurfaceFluidClipmap(
            GraphSpec graph, Map<String, Integer> compileValues) {
        return anyEnabledComputePassReads(graph, compileValues, SurfaceFluidClipmapBuffer.TARGET);
    }

    /** Whether an enabled shader pass binds the engine-owned water-actor set. */
    static boolean anyEnabledPassReadsWaterActors(
            GraphSpec graph, Map<String, Integer> compileValues) {
        return anyEnabledComputePassReads(graph, compileValues, WaterActorBuffer.TARGET);
    }

    private static boolean anyEnabledComputePassReads(
            GraphSpec graph, Map<String, Integer> compileValues, String target) {
        for (PassSpec p : graph.passes()) {
            if (p.type() != PassType.COMPUTE || !isEnabledAtCompile(p, compileValues)) {
                continue;
            }
            for (String in : p.inputs()) {
                if (in.equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@link #computeGraphicsWaitStages} against the live compile values. */
    private static long graphicsWaitStagesFor(PassSpec p, GraphSpec graph) {
        return computeGraphicsWaitStages(p, graph, compileValues);
    }

    /**
     * The graphics-queue pipeline stages that must wait on {@code p}'s compute submission, or 0 for
     * "no cross-queue handoff needed".
     *
     * <p>This closes the one dependency a pipeline barrier structurally cannot: {@code
     * ComputePassRunner} submits to the COMPUTE queue and a {@code ParticlePassRunner} draw is
     * recorded into Blaze3D's GRAPHICS queue, and {@code vkCmdPipelineBarrier} only orders work
     * within one queue. The compute-write to vertex-read edge therefore has to be a semaphore, which
     * is what {@code ComputePassRunner.run}'s stage-mask argument drives -- its signal/wait pair
     * carries both the execution dependency and the memory visibility (compute {@code SHADER_WRITE}
     * becoming visible to vertex {@code SHADER_READ}); the release barrier that runner already
     * records at the end of its own command buffer covers only same-queue compute readers.
     *
     * <p>{@code VERTEX_SHADER}, not {@code FRAGMENT_SHADER}: the flake buffer is read by the
     * BILLBOARD VERTEX stage (each instance fetches its own flake to build a quad), which runs before
     * any fragment work. Waiting at fragment would let vertex invocations read the buffer while the
     * simulation dispatch was still writing it -- a race that would show up as flakes flickering
     * between two positions, not as a validation error.
     *
     * <p>Pure function of the graph + compile values, extracted for the same reason {@link
     * #computePackDeclaresDepthCopyback} was: this is a correctness rule about which passes feed
     * which, and it should be provable without a GPU.
     */
    static long computeGraphicsWaitStages(PassSpec p, GraphSpec graph, Map<String, Integer> compileValues) {
        if (p.type() != PassType.COMPUTE || p.outputs().isEmpty()) {
            return 0;
        }
        long stages = 0;
        for (PassSpec reader : graph.passes()) {
            if (!isEnabledAtCompile(reader, compileValues)) {
                continue;
            }
            for (String in : reader.inputs()) {
                if (p.outputs().contains(in)) {
                    if (reader.type() == PassType.PARTICLES) {
                        stages |= VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT;
                    } else if (reader.type() == PassType.FULLSCREEN || reader.type() == PassType.GEOMETRY
                            || reader.type() == PassType.TEMPORAL || reader.type() == PassType.MIPCHAIN) {
                        stages |= VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
                    }
                }
            }
        }
        return stages;
    }

    /**
     * Whether a compute pass writes a storage image whose physical allocation can still be in use by
     * an enabled graphics-queue pass. Inputs do not count: graph inputs are read-only, so concurrent
     * reads need no dependency. History suffixes intentionally collapse to the base target here:
     * after the end-of-frame swap, next frame's current image is the physical image graphics sampled
     * as history in the prior frame.
     */
    static boolean computeStorageWriteNeedsGraphicsDrain(PassSpec writer, GraphSpec graph,
                                                          Map<String, Integer> compileValues) {
        if (writer.type() != PassType.COMPUTE) {
            return false;
        }
        for (String output : writer.outputs()) {
            String base = targetBaseName(output);
            TargetSpec target = graph.targets().get(base);
            if (target == null || !target.storage()) {
                continue;
            }
            for (PassSpec graphicsPass : graph.passes()) {
                if (graphicsPass.type() == PassType.COMPUTE
                        || !isEnabledAtCompile(graphicsPass, compileValues)) {
                    continue;
                }
                for (String ref : graphicsPass.inputs()) {
                    if (targetBaseName(ref).equals(base)) {
                        return true;
                    }
                }
                for (String ref : graphicsPass.outputs()) {
                    if (targetBaseName(ref).equals(base)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String targetBaseName(String ref) {
        return ref.endsWith(".history")
                ? ref.substring(0, ref.length() - ".history".length()) : ref;
    }

    /** Whether ANY target's or pass's {@code enabled_if} in {@code graph} references {@code
     * FX_COMPUTE} by name -- the gate for {@link #ensureRunnersBuilt}'s FX_COMPUTE self-heal (see its
     * own doc comment), so a pack that never gates anything on {@code FX_COMPUTE} never pays the
     * extra rebuild the self-heal triggers. Mirrors {@link GraphValidator#validate}'s own
     * targets-then-passes {@code enabled_if} iteration order; a pure function of {@link GraphSpec} for
     * the same testability reasons as {@link #computePackDeclaresDepthCopyback}. */
    static boolean graphReferencesEngineCompute(GraphSpec graph) {
        for (TargetSpec t : graph.targets().values()) {
            if (t.enabledIf() != null && EnabledIfExpr.parse(t.enabledIf()).referencedNames().contains("FX_COMPUTE")) {
                return true;
            }
        }
        for (PassSpec p : graph.passes()) {
            if (p.enabledIf() != null && EnabledIfExpr.parse(p.enabledIf()).referencedNames().contains("FX_COMPUTE")) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code currentPack}'s graph declares a pass named {@code name} that is currently
     * compile-enabled -- the same {@link #isEnabledAtCompile}/{@link #compileValues} machinery
     * {@link #anyEnabledComputePassReadsVoxelGrid} uses, generalized to look up one pass by name
     * instead of scanning for a specific input. Used by {@link #ensureRunnersBuilt} to decide whether
     * {@link VoxelWaterReflBuffer} should be (re)allocated or freed this session. */
    private static boolean graphHasEnabledPass(String name) {
        if (currentPack == null) {
            return false;
        }
        for (PassSpec p : currentPack.graph().passes()) {
            if (p.name().equals(name)) {
                return isEnabledAtCompile(p, compileValues);
            }
        }
        return false;
    }

    /** The live render-resolution source {@link #prepare} reads to size every render-basis target
     * this frame ({@code Minecraft.getInstance().gameRenderer.mainRenderTarget()}'s width/height --
     * see {@code prepare()}'s own doc comment for why this, not {@code SsaaManager}'s native size, is
     * the correct RENDER-basis source). Factored out here so {@link #computeDispatchOverride} and
     * {@link #ensureRunnersBuilt}'s buffer-lifecycle branch can read the exact same value without
     * introducing a second size source. */
    private static int currentRenderWidth() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget().width;
    }

    /** @see #currentRenderWidth() */
    private static int currentRenderHeight() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget().height;
    }

    /**
     * Whether the active pack's compile option {@code name} currently evaluates truthy (nonzero) --
     * the engine-side equivalent of a graph {@code enabled_if} check, for the handful of Java call
     * sites (camera jitter, PBR uniform upload) that need to react to a pack's own compile-time
     * toggle rather than an engine {@code FornaxSettings} field. Returns {@code false} when no pack
     * is active (or the pack declares no such option) -- exactly the "no-pack fallback" behavior
     * those call sites want (e.g. no point jittering the camera with nothing to resolve it).
     */
    public static boolean isCompileOptionEnabled(String name) {
        return compileValues.getOrDefault(name, 0) != 0;
    }

    /**
     * True when the active pack claims the sky: pack active AND its SKY_PROCEDURAL compile
     * option resolved non-zero (absent counts as 0 -- same hardened default as the vanilla
     * override registry) AND no competing sky mod is loaded (see {@link SkyModCompat} -- users
     * routinely mix sky mods, so Fornax yields rather than collides). The sky-pass cancellation
     * mixin is this predicate's only consumer; the shader-side paint decision does NOT re-evaluate
     * it -- it reads the committed did-cancel flag instead (see SkyFrameState).
     */
    public static boolean packOwnsSky() {
        return isActive() && isCompileOptionEnabled("SKY_PROCEDURAL") && !SkyModCompat.competingSkyModLoaded();
    }

    /**
     * True when the active pack claims volumetric clouds: pack active AND its CLOUDS_VOLUMETRIC
     * compile option resolved non-zero AND no competing sky mod is loaded -- the exact same
     * yield term as {@link #packOwnsSky()}, since a mod that owns the sky typically also owns
     * (or conflicts with) clouds. The clouds-pass cancellation mixin is this predicate's only
     * consumer; like {@link #packOwnsSky()}, the shader-side paint decision reads the committed
     * did-cancel flag instead of re-evaluating this (see SkyFrameState.commitClouds).
     */
    public static boolean packOwnsClouds() {
        return isActive() && isCompileOptionEnabled("CLOUDS_VOLUMETRIC") && !SkyModCompat.competingSkyModLoaded();
    }

    /**
     * The active pack's compile option {@code name}'s raw integer value, or {@code fallback} when no
     * pack is active or the pack declares no such option -- the read-back counterpart to {@link
     * #isCompileOptionEnabled} for compile options that carry a real magnitude rather than a boolean
     * (e.g. {@code SHADOW_RESOLUTION}), used by {@code SodiumWorldRendererOrchestrationMixin}'s
     * shadow-pass orchestration.
     */
    public static int compileOptionValue(String name, int fallback) {
        return compileValues.getOrDefault(name, fallback);
    }

    /**
     * Whether a fullscreen pass gets the real sun/debug half of {@link PassParams} filled in.
     *
     * <p><b>Failing this test is SILENT and does not read as "no sun".</b> {@code PassParams.reset()}
     * defaults {@code trueSunHeight} to <b>1.0</b> -- the sun at full zenith -- so an unlisted pass
     * that reads {@code u_SunDirection.w} sees permanent noon rather than an obviously broken value.
     * Observed once as a pack's clouds rendering sunset-orange at midnight under a correct night
     * sky: {@code sunVisibility} pinned to 1.0 while {@code noonFactor} (from the global {@code
     * u_SkyState.y}, correctly 0.0 at midnight) drove {@code mix(sunset, noon, 0.0)} to pure sunset
     * at full strength. The sky itself was fine because {@code gbuffer_resolve} is on this list and
     * the clouds pass was not.
     *
     * <p>Extracted from {@link #computeParams} so the invariant can be asserted by a test instead of
     * trusted: any pack shader declaring {@code u_SunDirection} in its {@code u_PassParams} block
     * must be matched here. See {@code GraphRunnerSunParamsTest}.
     *
     * <p>Prefix matches let a pack add more arms to a shader family with no edit here. {@code
     * resolve_hdr*} covers a sun-shadow provider split (resolve_hdr_voxel/resolve_hdr_el_voxel are
     * further mutually-exclusive arms of one shader family). {@code clouds_march*} covers the
     * CLOUD_QUALITY==2 {@code clouds_march_full} arm. {@code resolve} (the LDR pass) is matched
     * exactly, since it does not share the {@code resolve_hdr} prefix.
     *
     * <p>Some listed passes want only part of this block. {@code direct_light_analytic} consumes
     * {@code u_Param3} only to SUPPRESS itself during the resolve-branched instrument views
     * (1-12/16/19): it additively writes real light into sceneHdr AFTER the resolve replaced sceneHdr
     * with an instrument image, contaminating those views wherever an analytic light reached (a
     * lantern neighbourhood is exactly where that instrument must stay exact); {@code u_Param2}/
     * {@code u_SunDirection}/sprite rects are harmless-unused for it, as for {@code ssr_water_fill}.
     * {@code clouds_march} conversely reads only {@code u_SunDirection.w} -- the TRUE sun DIRECTION
     * from {@code u_SkyCelestial}, because the silver lining belongs on the sun's side of the sky
     * even after the moon has taken over lighting.
     *
     * <p>{@code glint_occlusion} is a screen-space raymarch pass for the water glitter's sun/moon
     * occlusion. It originally derived its own light direction from {@code u_SkyCelestial},
     * assuming the moon sits exactly antipodal to the sun, to avoid adding a name here -- wrong for
     * the moon (sun glitter worked, moon glitter never appeared even fully unobstructed), so it now
     * reads the same real {@code u_SunDirection.xyz} every other glitter-relevant site uses, same as
     * {@code water_composite}.
     */
    static boolean wantsSunAndDebugParams(String name) {
        return name.equals("resolve") || name.startsWith("resolve_hdr")
                || name.equals("tonemap") || name.equals("water_composite")
                || name.startsWith("water_volume_march")
                || name.equals("water_volume_scatter_history")
                || name.equals("ssr_water_fill") || name.equals("direct_light_analytic")
                || name.startsWith("clouds_march") || name.equals("glint_occlusion");
    }

    /**
     * The Hi-Z trace family, whose {@code u_Param2} carries the mipchain LEVEL COUNT rather than the
     * render distance. Named rather than inlined so {@link #suppliesParam2} and
     * {@link #computeParams} cannot disagree about the membership of this set -- they are the same
     * call, not two copies of one condition.
     */
    static boolean isHiZTracePass(String name) {
        return name.equals("ssr_trace_fancy") || name.equals("ssr_trace_fast")
                || name.equals("ssr_trace_water");
    }

    /**
     * Whether ANY branch of {@link #computeParams} writes a real {@code u_Param2} for this pass.
     *
     * <p><b>The other half of the silent one-way door.</b> {@link #wantsSunAndDebugParams} guards the
     * sun; nothing guarded this, and it fails exactly as quietly:
     * {@link PassParams#reset()} leaves {@code param2} at <b>0.0</b>, which is a perfectly valid
     * float that no shader can distinguish from a real value. A pack shader dividing by it gets a
     * render distance of zero -- fog that is either absent everywhere or total everywhere, with no
     * error anywhere. {@code resolve} and {@code water_composite} both key border fog on this value;
     * every other {@code u_Param2} consumer is a Hi-Z trace, filled by a different branch.
     *
     * <p>Not asserted in the converse direction, for the same reason the sun predicate is not:
     * filling a pass's params costs nothing, starving one changes what it renders.
     *
     * <p>Mipchain passes are absent here. {@code MipchainRunner} builds its OWN params
     * buffer with a seed flag in this slot and never routes through {@code computeParams} at all,
     * so {@code hiz}-style passes are supplied by a path this predicate does not describe --
     * {@code GraphRunnerSunParamsTest} exempts them by pass TYPE rather than by name.
     */
    static boolean suppliesParam2(String name) {
        return wantsSunAndDebugParams(name) || isHiZTracePass(name);
    }

    private static PassParams computeParams(PassSpec p, int renderWidth, int renderHeight) {
        String outputRef = p.outputs().get(0);
        int w = renderWidth;
        int h = renderHeight;
        if (!outputRef.equals("builtin.output") && registry != null) {
            TargetInstance t = registry.get(outputRef);
            if (t != null) {
                w = t.width();
                h = t.height();
            }
        }
        PassParams base = PassParams.reusable(w, h);
        String name = p.name();
        if (wantsSunAndDebugParams(name)) {
            // Reads fresh from FornaxConfig every frame so flipping the debug view in the UI takes
            // effect immediately. resolve/resolve_hdr consume u_Param3 for their 1-12 debug branches
            // and u_SunDirection for the deferred bump-lighting math they reproduce; tonemap consumes
            // u_Param3 for the HDR-only 13/14/15 views plus the 1-12 sceneHdr passthrough.
            // resolve_hdr_el is the EMITTER_LIGHTS-variant HDR resolve and consumes the same stable
            // debug id + sun direction; a forward no-op today since no pack graph names it yet.
            // (Under the HDR island, resolve/resolve_hdr/resolve_hdr_el are mutually exclusive via
            // enabled_if, so at most one runs per frame -- all are matched here because a rebuild
            // between HDR on/off/EL can make any one of them the active resolve variant.)
            // water_composite needs the SAME u_Param2 terrain-render-distance anchor resolve/tonemap
            // use for its own aerial-fog border term, so distant composited water's fog dissolves at
            // the same screen distance the surrounding opaque terrain's fog does -- a different
            // anchor here would show as a border-fog seam at the water's edge. u_SunDirection feeds
            // its light-normal specular lobe. u_Param3 (debug-view id) is harmless-unused for it.
            // ssr_water_fill needs the exact same u_Param2 anchor as water_composite, for the same
            // reason: its own per-voxel-hit aerial fog border term must dissolve at the identical
            // screen distance, or the voxel fill would show a border-fog seam where render distance
            // ends. u_Param3/u_SunDirection/the sprite rects are harmless-unused for this pass too --
            // only u_Param2 even appears in its u_PassParams block (a documented prefix-truncation --
            // see that block's own comment).
            Vector3f sunDirection = SunDirection.computeSunDirection(PASS_SUN_DIRECTION.get());
            // u_Param2: the TERRAIN render distance in blocks, the anchor the pack's border fog
            // needs. The u_Globals render-fog end this used to key on can sit beyond the real chunk
            // cutoff (it tracks fog attribute distances, not the chunk grid), leaving border fog
            // below 1.0 where geometry actually ends -- edge chunks popped out of a half-veil instead
            // of fading. Zero when no client options exist (headless); the shader falls back to its
            // render-fog estimate then.
            float renderDistanceBlocks = 0.0f;
            Minecraft mcClient = Minecraft.getInstance();
            if (mcClient != null && mcClient.options != null) {
                renderDistanceBlocks = mcClient.options.renderDistance().get() * 16.0f;
            }
            base = base.withParam2(renderDistanceBlocks)
                    .withParam3(FornaxConfig.get().debugView.shaderId())
                    .withSunDirection(sunDirection.x(), sunDirection.y(), sunDirection.z())
                    .withTrueSunHeight(SunDirection.trueSunHeight())
                    .withSunSpriteRect(CelestialSprites.sunRect())
                    // Moon phase from the live probe. It used to read SkyFrameState.moonPhase(),
                    // which only ever held a real value for packs that cancel vanilla's sky --
                    // every other pack drew a full moon on every night of the cycle. See SkyProbe.
                    .withMoonSpriteRect(CelestialSprites.moonPhaseRect((int) SkyProbe.read().moonPhase()));
        } else if (name.startsWith("celestial_shadow")) {
            // Prefix match, not exact equals -- same reasoning as "resolve_hdr".startsWith
            // ("resolve_hdr") above: a pack's half/full-resolution variants (celestial_shadow_half/
            // celestial_shadow_full) are two arms of one shader gated on
            // CELESTIAL_SHADOW_RESOLUTION, so this keeps working for new arm names with no risk of
            // a silent zero-sun-direction regression -- a missed name would NOT fail loudly, it
            // would just leave PassParams' sun direction at its zero default.
            //
            // This pass marches a DDA ray TOWARD the sun/moon (a fullscreen fragment pass; see
            // celestial_shadow.fsh's own header comment), so unlike voxel_water_refl (which samples
            // a shadow map instead) it genuinely needs the resolved direction.
            // SunDirection.computeSunDirection() performs the sun/moon handoff from
            // u_SkyCelestial, computed once here rather than re-derived per-fragment; this branch
            // fills only the direction, not the debug-view/render-distance/sprite-rect fields the
            // branch above also sets, so it stays its own minimal branch. The fullscreen form reads
            // u_Globals.u_VoxelWindow/u_CameraAbs directly for its DDA window geometry, so no
            // ExtraPushConstants plumbing is needed.
            //
            // Keep this SunDirection.computeSunDirection() call distinct from the
            // isEmitterLightPass branch below -- sharing it there once caused a night-glow bug (see
            // that branch's own comment). A shadow march needs whichever body is up, sun by day,
            // moon by night; do not unify the two branches.
            Vector3f sunDirection = SunDirection.computeSunDirection(PASS_SUN_DIRECTION.get());
            base = base.withSunDirection(sunDirection.x(), sunDirection.y(), sunDirection.z())
                    .withTrueSunHeight(SunDirection.trueSunHeight());
            // Telemetry only (no GPU readback): mirrors celestial_shadow.fsh's own
            // `windowVoxels = d * SECTION; maxDistance = min(float(windowVoxels), RAY_LENGTH_CAP_BLOCKS)`
            // exactly (RAY_LENGTH_CAP_BLOCKS = 1024 -- see that shader's own comment for why a bare
            // 512 was too low), so the HUD's celestial_rays_window row shows the same ray-length cap
            // this frame's fragment pass is actually using -- derivable CPU-side from the live voxel
            // window's diameter rather than read back from the shader. allocatedDiameter() is the
            // same authoritative source u_Globals.u_VoxelWindow.w is ultimately published from
            // (VoxelWindow.currentState(), via EmitterFrameState/GlobalUniformsWriteMixin), so this
            // stays consistent with what the shader actually reads.
            int allocatedDiameter = VoxelDebugRaymarchPass.allocatedDiameter();
            int windowDiameter = allocatedDiameter > 0 ? allocatedDiameter : VoxelWindow.currentState().diameter();
            frameProfiler.recordValue("celestial_rays_window", Math.min(windowDiameter * 16, 1024));
        } else if (isEmitterLightPass(p)) {
            // light_inject/light_propagate are compute passes, so unlike every OTHER consumer of
            // PassParams' sun direction above, they receive it through the shared 32-byte
            // push-constant base (PassParams.PUSH_CONSTANT_BASE_SIZE), not a uniform buffer --
            // ComputePassRunner writes params.sunDirX/Y/Z into that push-constant range unconditionally
            // for every compute pass, so this field ALREADY exists on the wire; the only gap was that
            // no branch of this if/else chain ever populated it for these two pass names, leaving
            // PassParams.of()'s zero default. light_inject.comp's own GI_SUN_BOUNCE header comment
            // documents this same gap and fix.
            //
            // This branch does NOT call SunDirection.computeSunDirection() like the
            // celestial_shadow* branch above does, even though both branches once shared that exact
            // call (bug: every sky-exposed block glowed at night). The two branches need DIFFERENT
            // vectors and must stay different:
            //   - celestial_shadow* (shadow march) needs whichever body is actually casting light this
            //     frame -- sun by day, MOON by night -- because a night shadow march that ignored the
            //     moon would have nothing to shadow against. SunDirection.computeSunDirection()'s
            //     sun/moon handoff is correct there. DO NOT change that branch to match this one.
            //   - light_inject.comp's GI_SUN_BOUNCE term is a DAYLIGHT-only gate (indirect bounce off
            //     sun-lit sky-exposed voxels), keyed on push-constant sunDir.y via
            //     `clamp(sunDir.y, 0, 1)`. Feeding it computeSunDirection()'s moon-at-night fallback made
            //     that clamp see the moon's positive y after dusk and inject full "sunlight" into every
            //     sky-exposed block all night. This branch instead reads the TRUE sun direction --
            //     SkyProbe's sunDirX/Y, the exact same source u_SkyCelestial.xyz is populated from
            //     (globals.glsl: "xyz = TRUE sun direction (moon = -xyz)"; GlobalUniformsWriteMixin
            //     writes these same values into that uniform lane) -- which goes negative at night
            //     and stays negative, so `clamp(sunDir.y, 0, 1)` correctly gates to zero.
            //
            //     This read used to come from SkyFrameState, which only held a real value for packs
            //     that cancel vanilla's sky; for every other pack it was the zero vector, and the
            //     clamp gated sun bounce off permanently rather than only at night. SkyProbe reads the
            //     camera's environment attribute probe live, every frame, in every dimension, so it is
            //     valid whenever this compute pass's params are built.
            //
            // This does NOT belong in EmitterLightExtra (computeExtraPushConstants below) even though
            // that record is these two passes' other source of extra push-constant data: EmitterLightExtra
            // is scoped to per-frame VALUES PassParams has no field for (camera position,
            // voxel window geometry) appended immediately after the shared base, whereas sun direction
            // already has a first-class field in PassParams/the shared push-constant base -- adding a
            // second, redundant sun-direction copy into the extra payload would just be two sources of
            // truth for the same three floats. Both compute shaders' own PushConstants block already
            // declares `vec3 sunDir` at the base's offset 16 (previously always zero, documented
            // "unused" in each file's header); only light_inject.comp actually reads it today (its
            // GI_SUN_BOUNCE-gated sunElevation term) -- light_propagate.comp declares the same field for
            // push-constant-layout symmetry with light_inject (both share the base struct) but has no
            // sun-driven term of its own to read it into.
            SkyProbe.Values emitterSky = SkyProbe.read();
            base = applyEmitterSunDirection(base, emitterSky.sunDirX(), emitterSky.sunDirY(),
                    emitterSky.sunDirZ());
        } else if (isHiZTracePass(name)) {
            // Hi-Z level count, replacing the old hardcoded pass's dedicated u_SsrParams member --
            // computed the same way TargetPlan sizes the mipchain target itself, so it always
            // matches whatever the "hiz" target actually built this frame. ssr_trace_water marches
            // the SAME opaque "hiz" pyramid as the opaque trace passes (only
            // its ray ORIGIN differs), so it needs this exact same level count -- without it,
            // ssr_trace_water.fsh's shared body (shaders/include/ssr_trace_body.glsl) would read
            // u_Param2's PassParams-default value (0) as levelCount, corrupting the tile-skip
            // clamp (`level = min(level + 1, levelCount - 1)`) for every water reflection.
            base = base.withParam2((float) TargetPlan.computeLevelCount(renderWidth, renderHeight));
            if (name.equals("ssr_trace_water")) {
                // This pass's own u_Param2 slot is already claimed by the Hi-Z level count above
                // (ssr_trace_body.glsl's tile-skip clamp needs it every frame), so the same
                // terrain-render-distance-in-blocks anchor the other fog sites (resolve/tonemap/
                // water_composite/ssr_water_fill) key their border fog on rides the otherwise-unused
                // u_Param3 slot here instead -- ssr_trace_water.fsh's applyWaterHitAerialFog reads
                // u_Param3 directly (falling back to u_RenderFog.y, floored at 32.0, when
                // unset/<=1.0, same fallback shape as those other fog sites). Without it that
                // function's anchor collapses to vanilla's render-fog-far value, over-fogging water
                // reflections at a ~32-block radius instead of the real render distance.
                // ssr_trace_fancy/ssr_trace_fast (the opaque origin, sharing only the Hi-Z level
                // count above) do NOT get a u_Param3 value here -- ssr_trace.fsh's shared march has
                // no water-hit aerial-fog hook to feed, so their u_Param3 stays at zero, harmlessly.
                float renderDistanceBlocks = 0.0f;
                Minecraft mcClient = Minecraft.getInstance();
                if (mcClient != null && mcClient.options != null) {
                    renderDistanceBlocks = mcClient.options.renderDistance().get() * 16.0f;
                }
                base = base.withParam3(renderDistanceBlocks);
            }
        }
        return base;
    }

    /**
     * The isEmitterLightPass branch's sun-direction write, extracted out of {@link #computeParams} as
     * a pure function for unit-testability without a live {@code Minecraft.getInstance()} client.
     * Unlike this method's earlier shape, which read {@link SkyFrameState}'s plain static fields
     * directly -- the
     * caller pre-resolves the Minecraft-dependent value and passes it in. The source moved to
     * {@link SkyProbe}, whose {@code read()} does dereference {@code Minecraft.getInstance()}, so
     * taking the vector as arguments is what keeps the night-glow regression guard headless: see
     * {@code GraphRunnerTest}'s {@code emitterLightPassReceivesNegativeSunYWhenSunBelowHorizon}.
     *
     * <p>The move is itself a fix. {@code SkyFrameState}'s sun direction was only ever populated for
     * packs that cancel vanilla's sky, so for every other pack this wrote a zero vector and
     * {@code light_inject.comp}'s {@code clamp(sunDir.y, 0, 1)} gate silently disabled indirect sun
     * bounce at all hours -- the night-glow fix's own mechanism turning into a permanent off switch.
     * See this method's comment at its {@code computeParams} call site for why this branch needs the
     * TRUE sun rather than {@code SunDirection.computeSunDirection()}'s sun/moon handoff.
     */
    static PassParams applyEmitterSunDirection(PassParams base, float sunX, float sunY, float sunZ) {
        return base.withSunDirection(sunX, sunY, sunZ);
    }

    /** The two emitter-lights compute passes, recognized by name (the computeParams name-keying
     * precedent): their dispatch is engine-computed from the live voxel window. */
    private static boolean isEmitterLightPass(PassSpec p) {
        return p.name().equals("light_inject") || p.name().equals("light_propagate");
    }

    /**
     * Independent lighting producers that run before opaque rendering. Kept name-scoped rather than
     * moving every compute pass because voxel_water_refl consumes current-frame graphics outputs and
     * must remain at its declared graph position.
     */
    static boolean isPreOpaqueLightingComputePass(PassSpec p) {
        return p.type() == PassType.COMPUTE
                && (isEmitterLightPass(p)
                || p.name().equals("light_list_reset")
                || isLightListBuildPass(p));
    }

    /** The voxel water reflection compute pass, recognized by name (the isEmitterLightPass
     * name-keying precedent): its dispatch is engine-computed from the live render resolution (a
     * screen-space domain, unlike the emitter passes' voxel-window domain) and its submit is
     * synchronously fence-waited before the same-frame debug/composite pass reads the SSBO back. */
    private static boolean isVoxelWaterReflPass(PassSpec p) {
        return p.name().equals("voxel_water_refl");
    }

    /** {@code light_list_build}, the analytic light-list scan/compact compute pass, recognized by
     * name (the isEmitterLightPass name-keying precedent). Its sibling {@code light_list_reset}
     * needs neither an engine-computed dispatch override nor extra push constants -- its
     * TOML-declared {@code dispatch = [1, 1, 1]} and base-only push constants are already correct
     * as-is (it just zeroes one atomic-counter word), so it is NOT recognized here; only {@code
     * light_list_build}'s dispatch domain depends on the live voxel window.
     *
     * <p>{@code light_list_reset} and this pass execute in-order on the compute queue. The last enabled
     * pre-opaque lighting producer signals the single semaphore that exposes the complete chain to
     * graphics; that is usually this pass when analytic lighting is enabled. */
    private static boolean isLightListBuildPass(PassSpec p) {
        return p.name().equals("light_list_build");
    }

    /**
     * Engine-computed dispatch group counts for compute passes whose domain is the voxel window's
     * light-cell volume -- null for every other pass (TOML dispatch/local_size behavior unchanged).
     * cells/axis = diameter x BrickGridUpload.lightCellsPerSectionAxis() (8 Standard / 16 High,
     * selected by the pack's LIGHT_CELL_DETAIL compile option -- see that method's own doc comment);
     * the shaders declare local_size 4x4x4, mirrored here as LIGHT_LOCAL_SIZE (the
     * VoxelDebugRaymarchPass.LOCAL_SIZE hand-lockstep precedent -- doc comments on both sides).
     * diameter*8 and diameter*16 are both always divisible by 4, so ceil-division is exact at either
     * tier: groups = diameter*2 (Standard) or diameter*4 (High) per axis (Standard, rd8 d=17 ->
     * 34^3 groups = 2.5M invocations; ceiling d=25 -> 50^3 = 8.0M).
     *
     * <p>The diameter fed into this MUST be {@link VoxelDebugRaymarchPass#allocatedDiameter()}, never
     * {@link VoxelWindow#currentState()}'s diameter (see I-1 in {@code
     * .superpowers/sdd/el-task-5-review.md}): the window is only recentered at the very END of {@code
     * finish()} (after this pass loop runs), while the light-volume buffer is already resized at
     * frame START in {@code prepare()}. On a render-distance DECREASE, {@code VoxelWindow}'s diameter
     * would still be this frame's stale-LARGER value, so dispatching against it computes cell/slot
     * indices past the just-shrunk buffer -- a one-frame out-of-bounds storage-buffer write (Vulkan
     * UB). {@code allocatedDiameter()} is updated in the exact same call that (re)sizes the buffer, so
     * it is always consistent with what the buffer actually holds this frame. The invariant that makes
     * it safe to read here: this method is only ever reached for a pass whose {@code ComputePassRunner}
     * already exists, and a light-pass runner is only ever built after {@code ensureRunnersBuilt()} has
     * already called {@code VoxelDebugRaymarchPass.ensureGridAllocated()} at least once this session --
     * so {@code allocatedDiameter()} can never be the -1 "never allocated" sentinel here.
     */
    @Nullable
    private static int[] computeDispatchOverride(PassSpec p) {
        if (isVoxelWaterReflPass(p)) {
            // Screen-space domain: one invocation per rendered pixel, ceil(dim/8) groups (the
            // shader's own layout(local_size_x=8, local_size_y=8)). Same render-size source
            // prepare() reads before sizing every render-basis target this frame -- see
            // currentRenderWidth/currentRenderHeight's own doc. (The sun-shadow prototype's own
            // former compute pass, sun_shadow_voxel, shared this exact shape -- retired along with
            // the whole compute-pass form when it became the fullscreen "sun_shadow" pass; a
            // fullscreen pass's dispatch is the ordinary one-thread-per-pixel fragment invocation,
            // no engine override needed.)
            int w = currentRenderWidth();
            int h = currentRenderHeight();
            int gx = (w + 7) / 8;
            int gy = (h + 7) / 8;
            return new int[]{gx, gy, 1};
        }
        if (isLightListBuildPass(p)) {
            // The shader maps a camera-centred scan cube into the toroidal voxel window. Dispatch that
            // cube directly instead of launching over the full light window and discarding almost all
            // invocations after a distance check. Clamped to the REAL streamed window radius:
            // u_LightReach can be set as low as 1 chunk/16 blocks, well under this scan's own
            // 64-block default -- an unclamped scan cube would dispatch threads whose
            // slotOf() addressing wraps the toroidal window onto UNRELATED sections (real data from a
            // different part of the map), picking up phantom lights. Same diameter source as the
            // push-constant site below (allocatedDiameter(), never window.diameter() -- I-1 hazard,
            // see that site's own comment) so dispatch size and the radius the shader is TOLD to scan
            // never disagree.
            int allocatedDiameter = VoxelDebugRaymarchPass.allocatedDiameter();
            int diameter = allocatedDiameter > 0 ? allocatedDiameter : VoxelWindow.currentState().diameter();
            int groups = analyticLightListGroups(clampedAnalyticScanRadiusBlocks(diameter));
            return new int[]{groups, groups, groups};
        }
        if (!isEmitterLightPass(p)) {
            return null;
        }
        int diameter = VoxelDebugRaymarchPass.allocatedDiameter();
        if (diameter <= 0) {
            // Defense-in-depth only -- see the invariant above; should be unreachable in practice. A
            // zero-extent dispatch is a safe no-op rather than computing a garbage negative group count.
            return new int[]{0, 0, 0};
        }
        int cellsPerAxis = diameter * BrickGridUpload.lightCellsPerSectionAxis();
        int groups = (cellsPerAxis + LIGHT_LOCAL_SIZE - 1) / LIGHT_LOCAL_SIZE;
        return new int[]{groups, groups, groups};
    }

    /** Java mirror of light_inject.comp / light_propagate.comp's layout(local_size_x/y/z = 4) --
     * hand-lockstep, doc-commented on both sides (the VoxelDebugRaymarchPass.LOCAL_SIZE precedent). */
    private static final int LIGHT_LOCAL_SIZE = 4;

    /** Pure sizing seam for the Standard-detail analytic-light scan. One light cell spans two blocks;
     * the inclusive camera-centred domain is {@code 2*ceil(radius/2)+1} cells per axis. */
    static int analyticLightListGroups(float scanRadiusBlocks) {
        if (!(scanRadiusBlocks > 0.0f) || !Float.isFinite(scanRadiusBlocks)) {
            return 0;
        }
        int blocksPerCell = 16 / BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD;
        int radiusCells = (int) Math.ceil(scanRadiusBlocks / blocksPerCell);
        int sideCells = radiusCells * 2 + 1;
        return (sideCells + LIGHT_LOCAL_SIZE - 1) / LIGHT_LOCAL_SIZE;
    }

    /** {@link #ANALYTIC_LIGHT_SCAN_RADIUS_BLOCKS} clamped to the real streamed voxel window's own
     * radius -- see the dispatch-size call site's own comment for the hazard this closes.
     * {@code diameter <= 1} (window never activated) leaves the scan radius unclamped;
     * {@link #isEmitterLightPass} elsewhere already treats that state as a zero-extent no-op, and an
     * unclamped value here is harmless since {@link #isLightListBuildPass}'s own dispatch-size branch
     * degrades to whatever {@code analyticLightListGroups} does with it, not a wrap hazard. */
    static float clampedAnalyticScanRadiusBlocks(int diameter) {
        if (diameter <= 1) {
            return ANALYTIC_LIGHT_SCAN_RADIUS_BLOCKS;
        }
        float windowRadiusBlocks = ((diameter - 1) / 2) * 16.0f;
        return Math.min(ANALYTIC_LIGHT_SCAN_RADIUS_BLOCKS, windowRadiusBlocks);
    }

    /** light_list_build.comp's scan radius around the camera, in blocks (analytic-lights milestone,
     * M1 default: 64 blocks / 4 sections, per the plan's own Decision 1 cost/density analysis). Not a
     * hand-mirrored constant -- there is no shader-side {@code const} copy of this value, since it is
     * threaded through {@link LightListExtra}'s push constant every frame rather than baked in as a
     * shader {@code #define}, specifically so it can become a real runtime-adjustable option later
     * without a shader recompile. */
    private static final float ANALYTIC_LIGHT_SCAN_RADIUS_BLOCKS = 64.0f;

    /** Extra push-constant bytes {@link ComputePassRunner#build} must reserve in {@code p}'s pipeline
     * layout, beyond the shared {@code PassParams.BUFFER_SIZE} -- light_inject/light_propagate (see
     * {@link EmitterLightExtra}), voxel_water_refl (see {@link VoxelWaterReflExtra}), and now
     * light_list_build (see {@link LightListExtra}) use extra push constants; light_list_reset does
     * not (see {@link #isLightListBuildPass}'s own doc for why its sibling is excluded). */
    private static int extraPushConstantBytesFor(PassSpec p) {
        if (isVoxelWaterReflPass(p)) {
            return VoxelWaterReflExtra.BYTE_SIZE;
        }
        if (isLightListBuildPass(p)) {
            return LightListExtra.BYTE_SIZE;
        }
        return isEmitterLightPass(p) ? EmitterLightExtra.BYTE_SIZE : 0;
    }

    /**
     * Builds {@code p}'s extra per-frame push-constant payload (see {@link ExtraPushConstants}) --
     * {@code null} for every pass except light_inject/light_propagate, which get the live voxel
     * window's geometry plus the camera position.
     *
     * <p>{@code diameter} comes from {@link VoxelDebugRaymarchPass#allocatedDiameter()}, NOT {@code
     * window.diameter()} -- the same I-1 hazard {@link #computeDispatchOverride} guards against: the
     * dispatch extent and this push-constant's window MUST always agree about how big the
     * light-volume buffer actually is this frame, or the shader's {@code slotOf} can compute a slot
     * index past the buffer (see the shaders' own bounds-guard comments). The CENTER still comes from
     * {@code window.center*}: recentering never resizes anything (toroidal addressing is a pure
     * relabeling of which absolute section a slot holds), so a center that's up to one frame stale is
     * harmless here, unlike a stale diameter.
     */
    @Nullable
    private static ExtraPushConstants computeExtraPushConstants(PassSpec p, ChunkRenderMatrices matrices,
                                                                 double camX, double camY, double camZ) {
        if (isVoxelWaterReflPass(p)) {
            // invProjModelView: UNLIKE u_InvProjModelView (which GlobalUniformsWriteMixin builds from
            // this frame's shared, possibly-jittered `projection` so screen-space passes stay
            // jitter-consistent with the rasterized G-buffer), this kernel's ray is a world-space DDA:
            // sub-pixel TAA/TAAU jitter cyclically flips which voxel a near-silhouette ray hits,
            // reading as a flash, worst on calm water. CameraJitter.currentUnjitteredProjection()
            // is the same per-frame projection captured BEFORE GameRendererMixin.fornax$setProjection
            // applies that jitter -- see its own doc comment. Scope is narrow: ONLY this pass's push
            // constant uses it; every other consumer of matrices.projection() is untouched.
            Matrix4f invProjModelView = CameraJitter.currentUnjitteredProjection().mul(matrices.modelView()).invert();
            Matrix4f sunViewProj = new Matrix4f(ShadowFrameState.current());
            VoxelWindow.WindowState win = VoxelWindow.currentState();
            // diameter MUST be VoxelDebugRaymarchPass.allocatedDiameter(), NOT window.diameter() --
            // the exact same I-1 hazard EmitterLightExtra guards against below: this pass's kernel
            // indexes into voxelOccupancy/voxelPayload/voxelPalette using this diameter for its own
            // slotOf(), and those buffers are sized to allocatedDiameter(), which can lag
            // VoxelWindow's own diameter by up to one frame on a render-distance decrease. Center
            // still comes from window.center* -- recentering never resizes anything, so a center up
            // to one frame stale is harmless (same reasoning as EmitterLightExtra's own).
            int allocatedDiameter = VoxelDebugRaymarchPass.allocatedDiameter();
            int diameter = allocatedDiameter > 0 ? allocatedDiameter : win.diameter();
            return new VoxelWaterReflExtra(invProjModelView, sunViewProj,
                    (float) camX, (float) camY, (float) camZ,
                    diameter, win.centerX(), win.centerY(), win.centerZ());
        }
        if (isLightListBuildPass(p)) {
            // Same diameter/center derivation as EmitterLightExtra below (I-1 hazard: diameter MUST be
            // allocatedDiameter(), never window.diameter() -- see that field's own doc), plus the one
            // field EmitterLightExtra has no room for: the scan radius. ANALYTIC_LIGHT_SCAN_RADIUS_BLOCKS
            // is a compile-time Java constant today (see its own doc) -- threaded through a push
            // constant rather than a shader #define specifically so a later milestone can turn it into
            // a real runtime option with no shader recompile, per the plan's own Decision 1. Clamped
            // to the real window radius (see clampedAnalyticScanRadiusBlocks's own doc) -- MUST use
            // the same diameter this pass's own dispatch-size branch above computed, or the shader
            // would be told to scan a radius its actual thread grid doesn't cover.
            VoxelWindow.WindowState window = VoxelWindow.currentState();
            int allocatedDiameter = VoxelDebugRaymarchPass.allocatedDiameter();
            int diameter = allocatedDiameter > 0 ? allocatedDiameter : window.diameter();
            return new LightListExtra((float) camX, (float) camY, (float) camZ,
                    diameter, window.centerX(), window.centerY(), window.centerZ(),
                    clampedAnalyticScanRadiusBlocks(diameter));
        }
        // The sun-shadow prototype's own former compute pass (sun_shadow_voxel) used to build a
        // SunShadowVoxelExtra push-constant payload here (unjittered invProjModelView + window
        // geometry) -- retired along with the whole compute-pass shape when it became the fullscreen
        // "sun_shadow" pass (queue-topology fix). A fullscreen fragment pass reads
        // u_InvProjModelView/u_VoxelWindow/u_CameraAbs directly off u_Globals (see celestial_shadow.fsh's
        // own header comment on why that is equally correct and needs no push-constant plumbing at
        // all), so no ExtraPushConstants case is needed for it any more.
        if (!isEmitterLightPass(p)) {
            return null;
        }
        VoxelWindow.WindowState window = VoxelWindow.currentState();
        int allocatedDiameter = VoxelDebugRaymarchPass.allocatedDiameter();
        // Fallback only hit in the same unreachable-in-practice case computeDispatchOverride guards
        // (see its comment); dispatch groups are already {0,0,0} there, so no invocation ever reads
        // this value for real work.
        int diameter = allocatedDiameter > 0 ? allocatedDiameter : window.diameter();
        return new EmitterLightExtra((float) camX, (float) camY, (float) camZ,
                diameter, window.centerX(), window.centerY(), window.centerZ());
    }

    /** light_inject/light_propagate's extra per-frame data beyond the shared 32-byte PassParams
     * block: camera position (unused by v1's window-relative iteration but cheap and part of the
     * RtShadowExtra-template shape a later held-item-light follow-on needs) and the voxel window's
     * geometry. 32 bytes: vec4 cameraPos (w unused, 16) + ivec4 diameter/centerX/centerY/centerZ
     * (16) -- total push range with the base 32 is 64, well under Vulkan's guaranteed-minimum 128.
     * All members 16-byte-aligned vec4/ivec4 -- the voxel_debug_raymarch push-block discipline that
     * sidesteps the scalar-after-vec3 law entirely. radius is derivable: (diameter - 1) / 2. */
    private record EmitterLightExtra(float camX, float camY, float camZ,
                                     int diameter, int centerX, int centerY, int centerZ)
            implements ExtraPushConstants {
        static final int BYTE_SIZE = 32;

        @Override
        public int byteSize() {
            return BYTE_SIZE;
        }

        @Override
        public void writeInto(ByteBuffer buffer, int offset) {
            buffer.putFloat(offset, camX);
            buffer.putFloat(offset + 4, camY);
            buffer.putFloat(offset + 8, camZ);
            // offset+12: unused (vec4 w padding)
            buffer.putInt(offset + 16, diameter);
            buffer.putInt(offset + 20, centerX);
            buffer.putInt(offset + 24, centerY);
            buffer.putInt(offset + 28, centerZ);
        }
    }

    /**
     * A pass that should run this frame but has no built runner is a contract violation, never a
     * normal frame state: {@link #ensureRunnersBuilt()} builds every compile-enabled pass's runner
     * or none (its catch retries the whole set), and {@link GraphValidator} refuses graphs whose
     * gating could leave an enabled pass without its targets. Skipping used to be silent -- the
     * exact signature of the deferred chain producing nothing (terrain in the G-buffer, no resolve,
     * empty screen) with zero log evidence. Logged at ERROR once per pass per pack session; the
     * frame still renders without the pass rather than crashing mid-draw.
     */
    private static void logMissingRunnerOnce(String passName) {
        if (missingRunnerLogged.add(passName)) {
            FornaxMod.LOGGER.error("[Fornax] GraphRunner: pass '{}' is enabled but has no built runner -- "
                    + "skipping it; deferred output will be incomplete until the next successful rebuild", passName);
        }
    }

    private static void closeCurrent() {
        // Detach the voxel window from this soon-to-be-closed registry BEFORE freeing its buffers, so a
        // late Sodium-worker onSectionHarvested can't pick up a registry whose buffers are being torn
        // down (TargetRegistry.close and BrickGridUpload.uploadSlot already share SHARED_QUEUE_LOCK, so
        // this is defense-in-depth, not the sole guard). onFrame reattaches next active frame.
        VoxelWindow.attachRegistry(null);
        VoxelDebugRaymarchPass.disable();

        // Device-wide wait-idle before ANY GPU resource below is freed. Two crashes caught live, same
        // call site, both faulting inside libMoltenVK.dylib's vkQueueSubmit2KHR (reached via
        // VulkanQueue$Submission.close() -> VulkanCommandEncoder.submit()) -- i.e. mid-submission on
        // the render thread, seconds after this method's own teardown ran (one from an HDR_ENABLE
        // Apply toggling off 9 GPU-backed targets, one from an ordinary world-join ResourceManager
        // reload racing this method's own target rebuild). Every .close()/.free() call
        // in this method destroys a GpuTexture/GpuTextureView/GpuBuffer with no guarantee the GPU is
        // done with whatever frame(s) still reference them -- Blaze3D's own texture-close path
        // defers actual destruction through a per-GRAPHICS-submission ring, but this mod also
        // dispatches compute-pass work directly against the compute queue via raw vkQueueSubmit,
        // entirely invisible to that ring (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's
        // own doc for the full mechanism). Mirrors that exact guard -- itself mirroring
        // TargetRegistry's buffer-teardown path, hardened after the VoxelDebugRaymarchPass incident
        // -- applied once here at the single point every OTHER GPU resource this method frees
        // (opaqueDepth, WaterSurfaceManager, mipchain/compute runners, the registry itself) funnels
        // through. Rare-path cost only (pack rebuild/unload, or a pack switch); never per-frame.
        VulkanComputeBackend.waitForGpuIdleBeforeDestroy();

        // Engine-owned depth copy (see OpaqueDepth's own doc): freed here, on every pack teardown --
        // "None" unload, a pack switch, or a rebuild() mid-session -- so its GPU texture/view never
        // outlive the registry/runners they're torn down alongside; the next prepare() with a pack
        // active reallocates it fresh via ensureSize (see that method's own doc for the MoltenVK
        // garbage-VRAM clear this guarantees). free() is null-safe and idempotent (see its own doc),
        // so this is harmless on a session where no pack has ever loaded a device yet.
        //
        // Reload-sequencing law: unlike TargetRegistry/fullscreenRunners (whose pipelines/bind groups
        // must not rebuild until RuntimeShaderPack.reload()'s future lands -- see rebuild()'s own
        // javadoc on the sourcesReady gate), OpaqueDepth is pure engine plumbing with no shader text
        // and no compiled RenderPipeline of its own: GraphInputResolver resolves builtin.depth_opaque
        // against this live instance's GpuTextureView every frame, the same way it resolves
        // GBufferManager's attachments, never against a cached bind-group shape that could go stale.
        // It therefore rides prepare()/finish() directly and never needs to gate on -- or chain
        // through -- the async resource-reload future the way ensureRunnersBuilt() must; it cannot
        // fall into rebuild()'s stale-snapshot hazard, so no rebuild()/RendererReload sequencing
        // change is needed for it here or anywhere else.
        opaqueDepth.free();

        // The shadow map is another engine-owned target outside TargetRegistry. closeCurrent's
        // device-idle boundary above is exactly the teardown law its texture/view pair needs;
        // keeping it alive here leaked both the D32 map and its dummy color attachment across
        // every pack switch/unload.
        ShadowMapManager.close();

        // Water pre-pass targets (see WaterSurfaceManager's own doc): unlike opaqueDepth above, these
        // are never allocated unconditionally by prepare() -- they are lazily (re)built per-call from
        // SodiumWorldRendererOrchestrationMixin#fornax$renderWaterPrepass, gated on SSR_WATER_MODE > 1
        // -- but still must be torn down here on every pack teardown so a pack switch or "None" unload
        // never leaves a stale-sized water target's GPU texture/view outliving the registry/runners
        // it's torn down alongside. close() is null-safe and idempotent, so this is harmless even if
        // WaterSurfaceManager was never allocated this session (SSR_WATER_MODE never exceeded 1).
        WaterSurfaceManager.close();

        for (MipchainRunner m : mipchainRunners.values()) {
            m.close();
        }
        mipchainRunners.clear();
        mipchainTargets.clear();
        for (ComputePassRunner c : computeRunners.values()) {
            c.close();
        }
        computeRunners.clear();
        for (ParticlePassRunner p : particleRunners.values()) {
            p.close();
        }
        particleRunners.clear();
        for (FullscreenPassRunner f : fullscreenRunners.values()) {
            f.close();
        }
        fullscreenRunners.clear();
        // No close(): a TemporalPassRunner owns no per-runner native resources -- its pipelines
        // are process-lifetime statics shared across rebuilds and its settings ring is static too.
        temporalRunners.clear();
        runnersBuilt = false;
        // Bumping the generation here (not just in rebuild()) covers unload()'s own closeCurrent()
        // call too: any reload future from a pack that's now being unloaded must never mark
        // sourcesReady for whatever (if anything) becomes active next.
        rebuildGeneration++;
        sourcesReady = false;
        missingRunnerLogged.clear();

        if (registry != null) {
            registry.close();
            registry = null;
        }
        if (packTextureRegistry != null) {
            packTextureRegistry.close();
            packTextureRegistry = null;
        }
        if (optionsBuffer != null) {
            optionsBuffer.close();
            optionsBuffer = null;
        }
        pendingOptionsLayout = null;
        pendingRuntimeDefaults = Map.of();
        currentPack = null;
        // Same reasoning as rebuild()'s invalidate: on teardown the variants reference a pack that no
        // longer exists, and must not survive into whatever loads next.
        dev.icehunter.fornax.pipeline.DeferredGeometryPipelines.invalidate();
        packDeclaresDepthCopyback = false;
        // Drop every rolling per-label sample: a pass whose enabled_if just went permanently false
        // this rebuild (option toggle, pack switch, "None" unload) must not leave its last avg/p95
        // frozen on the HUD forever -- see frameProfiler's own field doc. Losing the
        // frame/geometry-dwell running
        // averages across a rebuild too is an acceptable tradeoff: rebuilds are rare, user-initiated
        // events, and post-rebuild numbers should reflect the CURRENT pack state, not a blend with
        // whatever was active before it.
        frameProfiler.reset();
        // registry.close() above may have just released the very TargetInstance views these still
        // point at -- clear immediately rather than waiting for the next prepare()'s own reset, so no
        // draw landing in the gap between this teardown and the next active frame can bind a closed
        // GPU resource.
        clearGeometryInputViews();
    }

    private static String insertAfterFirstLine(String source, String textToInsert) {
        int newline = source.indexOf('\n');
        if (newline < 0) {
            return source + "\n" + textToInsert;
        }
        return source.substring(0, newline + 1) + textToInsert + source.substring(newline + 1);
    }
}
