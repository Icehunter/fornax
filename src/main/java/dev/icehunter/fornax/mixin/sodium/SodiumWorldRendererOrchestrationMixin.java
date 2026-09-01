package dev.icehunter.fornax.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pass.shadow.ShadowCamera;
import dev.icehunter.fornax.pass.shadow.ShadowCasterLists;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.water.WaterSurfaceManager;
import dev.icehunter.fornax.pipeline.CameraMotionState;
import dev.icehunter.fornax.pipeline.ChunkRenderContextHolder;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;
import dev.icehunter.fornax.pipeline.LocalActorFrameState;
import dev.icehunter.fornax.pipeline.UniformBufferManagerExtension;
import dev.icehunter.fornax.util.SunDirection;
import dev.icehunter.fornax.voxel.EmitterFrameState;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.joml.Vector3f;

import java.util.Iterator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link GraphRunner#prepare()}/{@link GraphRunner#finish} around the opaque (SOLID/CUTOUT)
 * chunk draws inside the official {@code SodiumWorldRenderer.drawChunkLayer(...)}, running the
 * clear-&gt;ssao-&gt;hiz-&gt;ssr-&gt;resolve-&gt;taa sequence around opaque terrain without any of it
 * running for the translucent group. {@link GraphRunner#isActive()} itself no-ops both calls with no
 * pack active (shaders off, or no pack selected), so opaque terrain then renders as plain, undeferred
 * vanilla Sodium (see {@code ShaderChunkRendererShaderLocationMixin} and its sibling deferred-pipeline
 * mixins). The same HEAD hook also drives the sun/moon shadow pass (see {@link
 * #fornax$renderShadowPass}) immediately after {@code GraphRunner.prepare()}, so this frame's shadow
 * map is always ready before its own resolve pass (later in {@link GraphRunner#finish}) samples it.
 *
 * <p>{@code drawChunkLayer} has a single overload -- {@code public void
 * drawChunkLayer(ChunkSectionLayerGroup, ChunkRenderMatrices, double, double, double,
 * GpuSampler)} -- and the official (unmodified) method body's OPAQUE branch consists of nothing
 * but the two {@code renderLayer(SOLID)}/{@code renderLayer(CUTOUT)} calls (the official class has
 * no {@code computeSsao}/{@code buildHiZ}/{@code traceSsr}/{@code resolveGBuffer}/{@code
 * applyTaa}/{@code prepareGBufferForOpaquePass} methods at all). Bracketing the whole method call
 * with {@code @Inject(HEAD)}/{@code @Inject(RETURN)}, both gated on {@code group ==
 * ChunkSectionLayerGroup.OPAQUE}, therefore lands in exactly the same place a {@code
 * @WrapOperation} around the two internal {@code renderLayer} invocations would -- without needing
 * to target a private same-class method call by descriptor+ordinal, which is more brittle for no
 * behavioral difference here. Plain vanilla Mixin (no MixinExtras) is sufficient for this one.
 */
@Mixin(SodiumWorldRenderer.class)
public class SodiumWorldRendererOrchestrationMixin {
    /** {@code SHADOW_RESOLUTION}'s fallback when a pack declares no such compile option (today's
     * deployed pack, pending Task 6) or before {@link GraphRunner#optionsBuffer()} exists. */
    private static final int FALLBACK_SHADOW_RESOLUTION = 2048;
    /** {@code u_ShadowDistance}'s fallback, same circumstances as {@link #FALLBACK_SHADOW_RESOLUTION}
     * -- BLOCKS, the option's own unit (the unit contract lives at {@link
     * ShadowCamera#shadowDistanceOptionBlocks}: the raw option value is BLOCKS, not chunks, and
     * must not be scaled at the read site). 96 blocks is the {@code sample_pack} fixture's declared
     * default (bias 0.733 at the 2048 default map). */
    private static final float FALLBACK_SHADOW_DISTANCE_BLOCKS = 96.0f;

    /**
     * Shadows {@code SodiumWorldRenderer}'s private {@code lastFogParameters} field (javap-verified
     * against sodium-fabric-0.9.0: {@code private FogParameters lastFogParameters = FogParameters.NONE;},
     * assigned in {@code setupTerrain} before {@code drawChunkLayer} runs, so it already holds this
     * frame's real fog by the time {@link #fornax$renderShadowPass} reads it). Unlike {@code matrices}
     * (a {@code drawChunkLayer} parameter, threaded directly), fog is not a parameter of {@code
     * drawChunkLayer} at all -- only this instance field carries it -- so there is no local to reuse
     * and a {@code @Shadow} field is required to read it directly. See {@link
     * #fornax$renderShadowPass}'s javadoc for why the shadow draws must carry the real value here
     * rather than {@link FogParameters#NONE}.
     */
    @Shadow(remap = false)
    private FogParameters lastFogParameters;

    /**
     * Shadows {@code SodiumWorldRenderer}'s private {@code renderSectionManager} field
     * (javap-verified: {@code private RenderSectionManager renderSectionManager;}, no getter).
     * {@link #fornax$renderShadowPass} reads this directly (rather than going through {@code
     * self.renderLayer}) to reach {@code getChunkRenderer()} and to build {@link ShadowCasterLists}'
     * radius-based caster list -- see that class's javadoc for why the player-frustum-culled {@code
     * getRenderLists()} this field would otherwise expose is exactly the bug being fixed here.
     */
    @Shadow(remap = false)
    private RenderSectionManager renderSectionManager;

    /**
     * Shadows {@code SodiumWorldRenderer}'s private {@code uniformBufferManager} field
     * (javap-verified: {@code private UniformBufferManager uniformBufferManager;}, no getter).
     * Bypassing {@code self.renderLayer} for the shadow draws (see {@link #fornax$renderShadowPass})
     * also bypasses the {@code update(...)} call {@code renderLayer}'s own body makes -- this field lets
     * {@link #fornax$renderShadowPass} make that same guarded call explicitly, so it stays this
     * frame's real (non-no-op) write. See {@link #fornax$renderShadowPass}'s "Matrix delivery" doc
     * for the full guard model.
     */
    @Shadow(remap = false)
    private UniformBufferManager uniformBufferManager;

    @Inject(method = "drawChunkLayer", at = @At("HEAD"))
    private void fornax$prepareOpaque(ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler terrainSampler, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            EmitterFrameState.commit(x, y, z); // before the frame's first update() -- see EmitterFrameState
            // Same window as the line above, and narrower than it looks: this READS
            // PreviousFrameCameraTransform, which GraphRunner.finish() re-commits at the very end of
            // this same frame. It must run before that AND before the first update() writes
            // u_Globals. See CameraMotionState for why a full-screen pass cannot derive the delta.
            CameraMotionState.commit(x, y, z);
            LocalActorFrameState.commitFromClient();
            GraphRunner.prepare(matrices, x, y, z);
            fornax$renderShadowPass(matrices, x, y, z, terrainSampler);
            fornax$renderWaterPrepass(matrices, x, y, z, terrainSampler);
        }
    }

    /**
     * Sun/moon shadow-map orchestration: (re)builds/clears {@link ShadowMapManager}'s depth target,
     * computes this frame's light view/proj matrices ({@link ShadowCamera#compute}, fed {@link
     * SunDirection#computeSunDirection()}), commits the combined {@code viewProj} to {@link
     * ShadowFrameState} for {@code UniformBufferManagerMixin}'s {@code u_SunViewProj} append, then
     * draws SOLID- and CUTOUT-equivalent shadow-caster geometry into it via two direct {@code
     * chunkRenderer.render(...)} calls ({@link FornaxRenderPasses#SHADOW}/{@link
     * FornaxRenderPasses#SHADOW_CUTOUT} -- see that class's javadoc for why two distinct pass
     * instances, not one, are required for both geometry classes to actually cast shadows).
     *
     * <p><b>Why direct {@code render(...)}, not {@code self.renderLayer(...)}:</b> {@code
     * renderLayer} always draws {@code renderSectionManager.getRenderLists()} -- Sodium's own
     * player-frustum-culled list, rebuilt once per frame from an octree traversal seeded by the
     * PLAYER camera. A section outside the player's current view never enters that list, so it never
     * casts a shadow either, even though the light doesn't care which way the player is facing --
     * exactly the mismatch {@link ShadowCasterLists#build} exists to close. {@link
     * ShadowCasterLists#build} replaces that list with a scan over every loaded region, testing each
     * candidate section's own world-space AABB against the light's shadow ortho volume ({@code
     * lightMatrices.viewProj()}, the same camera-relative matrix committed to {@link ShadowFrameState}
     * below) -- correct at every sun angle and camera height, unlike a world-XZ-radius proxy, which is
     * exact only at noon. The two draws below call {@code
     * renderSectionManager.getChunkRenderer().render(...)} directly against it, bypassing {@code
     * renderLayer} (and therefore {@code getRenderLists()}) entirely.
     *
     * <p>Gated on {@link GraphRunner#isActive()} AND the pack's {@code SHADOWS} compile option
     * ({@link GraphRunner#isCompileOptionEnabled}, which returns {@code false} whenever the active
     * pack declares no such option -- today's deployed pack, pending Task 6). With the option off,
     * this method skips the clear/camera/draw work below entirely, but still calls {@link
     * ShadowMapManager#ensureSize} with a minimal fallback resolution: a resolve pass can declare
     * {@code sunShadowMap} as an unconditional graph input regardless of {@code SHADOWS} (only
     * the shader-side sampling is behind {@code #ifdef SHADOWS}, not the descriptor binding), so
     * leaving {@link ShadowMapManager} entirely unallocated makes {@code GraphInputResolver.resolveView}
     * throw on the very first frame. See {@link #fornax$renderShadowPass}'s own doc comment for the
     * fallback's sizing/clear rationale. {@link ShadowFrameState} still stays at its identity-matrix
     * default whenever this gate is closed, which {@code UniformBufferManagerMixin}'s unconditional
     * per-frame append still safely uploads as an unused value (see that mixin's own doc comment).
     *
     * <p><b>Matrix delivery (the true {@code update()} model):</b>
     * {@code UniformBufferManager.update()} is guarded by a {@code hasUpdatedThisFrame} flag reset
     * once per frame -- only the FIRST {@code update()} call each frame actually writes {@code
     * u_ProjectionMatrix}/{@code u_ModelViewMatrix} (and, via {@code UniformBufferManagerMixin}'s
     * append, {@code u_SunViewProj}); every later call this frame is a no-op against those slots.
     * The only other place that calls {@code update()} is inside {@code renderLayer}'s own body,
     * and this method bypasses {@code renderLayer} entirely for the shadow draws (see above), so
     * nothing would call {@code update()} before those draws unless this method does so itself --
     * so it does, explicitly, immediately below, via the shadowed {@link #uniformBufferManager}
     * field: {@code uniformBufferManager.update(matrices, realFog)} runs BEFORE either direct
     * {@code render(...)} call, preserving the same "single guarded update per frame, main
     * matrices + fog only" model {@code renderLayer} itself follows. A single guarded, single-slot
     * buffer cannot hold two different matrix sets in one frame, so this call is given {@code
     * matrices} -- the MAIN camera's {@link ChunkRenderMatrices}, the same instance {@code
     * drawChunkLayer} received and will pass to its own SOLID/CUTOUT/TRANSLUCENT draws later this
     * frame -- never the light's. The light transform instead rides {@code u_SunViewProj}, the
     * frame-constant extension member {@code UniformBufferManagerMixin} appends to every {@code
     * update()} call regardless of which matrices that call's classic slots carry; {@code
     * shadow.vsh} reads {@code u_SunViewProj} directly and never touches {@code
     * u_ProjectionMatrix}/{@code u_ModelViewMatrix}. {@code ShadowCamera#compute} is called here
     * for {@link ShadowCamera.LightMatrices#viewProj()}, committed to {@link ShadowFrameState}
     * below; its {@code proj}/{@code view} split is not threaded into any render call. The base
     * method body's own later SOLID/CUTOUT/TRANSLUCENT {@code renderLayer(...,
     * this.lastFogParameters, ...)} calls still exist unmodified and remain guard-suppressed
     * no-ops against {@code update()}.
     *
     * <p><b>Ordering guarantee:</b> {@link ShadowFrameState#commit} runs below, before the explicit
     * {@code update()} call -- i.e. before this frame's first {@code update()}/append. {@link
     * GraphRunner#prepare()} (called just before this method, from the same HEAD inject) touches no
     * uniform buffer, so nothing between "frame start" and here can consume this frame's first
     * {@code update()} early. The append therefore always reads this frame's freshly committed light
     * matrix, never a stale one from last frame.
     *
     * <p>{@code x}/{@code y}/{@code z} stay the MAIN/PLAYER camera's position (not the light's):
     * every vertex this engine draws is expressed camera-relative to the player (see {@link
     * ShadowCamera}'s own class javadoc), so {@code CameraTransform} must stay player-relative
     * regardless of which matrix now projects the result. {@link ShadowCamera#compute} still uses
     * this same position to place the light frustum around the player.
     *
     * <p>{@code SHADOW_RESOLUTION} is a compile option (read via {@link
     * GraphRunner#compileOptionValue}); {@code u_ShadowDistance} is a runtime option (read via {@link
     * GraphRunner#optionsBuffer()}'s live value, the same path {@code UniformBufferManagerMixin}
     * already uses for {@code u_BumpStrength}/{@code u_AOStrength}).
     *
     * <p><b>Fog delivery (same guarded-single-write mechanic as the matrices above):</b> fog IS
     * irrelevant to the shadow draw's own depth-only output -- {@code shadow.fsh} declares no
     * fog-dependent branch -- but {@code UniformBufferManager.update(matrices, fogParameters)} writes
     * BOTH the classic matrix slots AND the fog fields (color/environmental/render-distance) in the
     * same guarded, once-per-frame call. Since this method's explicit {@code update(...)} call is
     * that frame's first (and only effective) one, whatever {@code fogParameters} it carries is the
     * ONLY fog the whole frame's {@code u_Globals} ever sees -- the base body's later
     * SOLID/CUTOUT/TRANSLUCENT {@code renderLayer(..., this.lastFogParameters, ...)} calls are
     * guard-suppressed no-ops, exactly like their matrices. Passing {@link FogParameters#NONE} here
     * would silence fog world-wide for the whole frame; the shadowed {@link #lastFogParameters}
     * field reads the SAME real fog the main draw would have written, sourced directly from the
     * private {@code SodiumWorldRenderer.lastFogParameters} field (assigned in {@code setupTerrain},
     * before {@code drawChunkLayer} runs, so it already holds this frame's live value here -- unlike
     * {@code matrices}, fog is not a {@code drawChunkLayer} parameter, so there is no local to reuse
     * and the {@code @Shadow} field is required).
     */
    private void fornax$renderShadowPass(ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler terrainSampler) {
        if (!GraphRunner.isActive()) {
            return;
        }
        if (!GraphRunner.isCompileOptionEnabled("SHADOWS")) {
            // SHADOWS off: skip the clear/camera/draw work below, but a resolve pass can still
            // unconditionally declare "sunShadowMap" as a graph input -- the #ifdef SHADOWS in
            // the resolve shader only compiles out the SAMPLING, not the descriptor binding, so
            // GraphInputResolver.resolveView still runs against it every frame regardless of this
            // option. Leaving ShadowMapManager entirely unallocated therefore throws "Fornax graph:
            // input 'sunShadowMap' resolved to no allocated target" (GraphInputResolver.resolveView)
            // on the very first frame. A minimal 64x64 (~16KB) fallback keeps the descriptor valid;
            // ensureSize's allocation-time clear-to-1.0 makes it permanently read as "no occluder
            // anywhere", which is semantically correct for shadows-off and is never actually sampled
            // since the shader-side #ifdef compiles that read out.
            ShadowMapManager.ensureSize(64);
            return;
        }

        int resolution = GraphRunner.compileOptionValue("SHADOW_RESOLUTION", FALLBACK_SHADOW_RESOLUTION);
        PackOptionsBuffer options = GraphRunner.optionsBuffer();
        float shadowDistance = options != null
                // The pack declares this runtime option as u_ShadowDistance (runtime u_ prefix
                // convention); reading the bare name would silently pin the ortho extent at the
                // fallback for every slider value above it. The raw option value is BLOCKS and
                // passes through unconverted -- shadowDistanceOptionBlocks IS that unit contract
                // (see its doc for why no chunks-to-blocks conversion factor applies here).
                ? ShadowCamera.shadowDistanceOptionBlocks(
                        options.get("u_ShadowDistance", FALLBACK_SHADOW_DISTANCE_BLOCKS))
                : FALLBACK_SHADOW_DISTANCE_BLOCKS;

        ShadowMapManager.ensureSize(resolution);
        ShadowMapManager.clear();

        Vector3f lightDir = SunDirection.computeSunDirection();
        ShadowCamera.LightMatrices lightMatrices = ShadowCamera.compute(lightDir, x, y, z, shadowDistance, resolution);
        // The shared radial-distortion bias, computed once per frame from the SAME shadowDistance
        // and resolution locals just fed into ShadowCamera.compute -- committed alongside viewProj
        // below so they can never drift apart (see ShadowFrameState.commit's own doc comment).
        float shadowMapBias = ShadowCamera.shadowMapBias(shadowDistance, resolution);
        // Must land before the explicit update() call below -- see "Ordering guarantee" above.
        ShadowFrameState.commit(lightMatrices.view(), lightMatrices.proj(),
                lightMatrices.viewProj(), shadowMapBias);

        // MAIN camera matrices and MAIN fog, not the light's -- see "Matrix delivery" and
        // "Fog delivery" above for why: both land in the frame's single guarded update() write.
        FogParameters realFog = this.lastFogParameters;

        // Light-frustum caster list, independent of the player frustum -- see ShadowCasterLists'
        // javadoc and this method's "Why direct render(...)" doc above. Built once and reused for
        // both draws below: the two passes source different geometry storage (via
        // DefaultChunkRendererGeometryStorageMixin's redirect) but the same set of nearby sections
        // is spatially relevant to both. lightMatrices.viewProj() is the SAME matrix just committed
        // to ShadowFrameState above, so the caster-list test and the actual rasterization can never
        // disagree about what the light volume is.
        ChunkRenderListIterable casterLists = ShadowCasterLists.build(this.renderSectionManager, x, y, z, lightMatrices.viewProj());

        // Explicit trigger for the frame's single guarded update() -- see "Matrix delivery" above
        // for why this must run here rather than inside self.renderLayer(...).
        this.uniformBufferManager.update(matrices, realFog);

        // SodiumWorldRendererRenderLayerMixin normally does this at the HEAD of every renderLayer
        // call (updatePbrSettings() -- guarded once-per-frame, same model as update() above -- then
        // ChunkRenderContextHolder.set(...), which DefaultChunkRendererRenderMixin reads mid-render
        // to bind u_PbrSettings). Bypassing renderLayer for the shadow draws bypasses that HEAD
        // inject too, and these two direct render() calls are this frame's FIRST calls into
        // DefaultChunkRenderer.render(...) -- so without this, DefaultChunkRendererRenderMixin would
        // read last frame's stale ChunkRenderContextHolder contents during the shadow draws. Mirror
        // the same two calls here; the base body's later SOLID/CUTOUT/TRANSLUCENT renderLayer calls
        // still run this same sequence themselves afterward, idempotently (updatePbrSettings()'s
        // guard is already tripped, and getPbrSettingsBuffer()/getUniformBuffer() return the same
        // instances until next frame), exactly as when multiple renderLayer calls repeated it before.
        UniformBufferManagerExtension pbrExtension = (UniformBufferManagerExtension) (Object) this.uniformBufferManager;
        pbrExtension.updatePbrSettings();
        ChunkRenderContextHolder.set(this.uniformBufferManager.getUniformBuffer(), pbrExtension.getPbrSettingsBuffer());

        CameraTransform cameraTransform = new CameraTransform(x, y, z);
        this.renderSectionManager.getChunkRenderer().render(matrices, casterLists, FornaxRenderPasses.SHADOW,
                cameraTransform, realFog, false, terrainSampler,
                this.uniformBufferManager.getUniformBuffer(), this.uniformBufferManager.getSectionTimeInfo());
        this.renderSectionManager.getChunkRenderer().render(matrices, casterLists, FornaxRenderPasses.SHADOW_CUTOUT,
                cameraTransform, realFog, false, terrainSampler,
                this.uniformBufferManager.getUniformBuffer(), this.uniformBufferManager.getSectionTimeInfo());
    }

    /**
     * Water-surface pre-pass orchestration (Deferred Water Task 1 spike): re-draws the TRANSLUCENT
     * chunk list under the {@link FornaxRenderPasses#WATER_PREPASS} identity into {@link
     * WaterSurfaceManager}'s {@code waterNormal}/{@code waterDepth} targets, immediately after {@link
     * #fornax$renderShadowPass} in the same HEAD inject -- byte-for-byte the same "direct {@code
     * chunkRenderer.render(...)}, bypass {@code self.renderLayer}" shape that method already
     * documents in full (see its own javadoc for why: player-frustum-culled {@code
     * getRenderLists()} would be wrong for a LIGHT-relative shadow draw, but water is drawn from the
     * SAME main camera as every other terrain pass, so unlike the shadow pass this draw uses {@code
     * renderSectionManager.getRenderLists()} directly -- the identical list Sodium's own later
     * TRANSLUCENT {@code renderLayer} call would use, just re-submitted early under a different pass
     * identity via the {@code sourceGeometryPass} storage redirect (see {@link FornaxRenderPasses
     * #sourceGeometryPass}).
     *
     * <p>Gated on {@link GraphRunner#isActive()} AND the pack's {@code SSR_WATER_MODE} compile value
     * being above 1. Opaque {@code SSR_QUALITY} deliberately does not participate: the resolve,
     * cloud composite, underwater refraction and tonemap all consume {@code builtin.waterDepth}
     * while opaque SSR is off, so gating allocation on {@code SSR_QUALITY != 0} would make those
     * ungated passes fail input resolution and skip permanently, leaving only the clear-colour
     * frame. Water modes 0/1 still keep the cheap forward arm and pay no pre-pass allocation or draw.
     *
     * <p><b>update()/PBR/context guard:</b> {@link #fornax$renderShadowPass} already ran this same
     * HEAD inject, immediately before this call. If {@code SHADOWS} is on, it already made this
     * frame's single guarded {@code uniformBufferManager.update(...)} call plus {@code
     * updatePbrSettings()}/{@code ChunkRenderContextHolder.set(...)} -- repeating them here is a safe,
     * idempotent no-op (both are internally once-per-frame guarded, exactly like {@code
     * fornax$renderShadowPass}'s own javadoc documents for its later SOLID/CUTOUT/TRANSLUCENT
     * callers). If {@code SHADOWS} is off, {@code fornax$renderShadowPass} returned early WITHOUT
     * making those calls (see its own doc) -- meaning THIS call would otherwise be the frame's first
     * {@code DefaultChunkRenderer.render(...)} with no matrices/PBR/context ever set up. So this
     * method makes the same three calls itself, unconditionally, before its own draw -- mirroring
     * {@code fornax$renderShadowPass}'s own "Matrix delivery"/context block exactly, just guarded by
     * the same once-per-frame flags rather than duplicated logic.
     */
    private void fornax$renderWaterPrepass(ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler terrainSampler) {
        if (!WaterSurfaceManager.shouldAllocateTargets(GraphRunner.isActive())) {
            return;
        }

        int width = Minecraft.getInstance().gameRenderer.mainRenderTarget().width;
        int height = Minecraft.getInstance().gameRenderer.mainRenderTarget().height;
        WaterSurfaceManager.ensureSize(width, height);
        WaterSurfaceManager.clear();

        // Allocation and the clear sit above this gate, not inside it. builtin.waterDepth is an
        // input to ungated fullscreen passes (resolve, clouds_composite, both underwater blurs,
        // tonemap); a name that resolves to no allocated target disables those passes for the
        // session. A cleared target is the right content when nothing draws, so below Traced the
        // graph gets valid empty targets and only the geometry is skipped.
        if (!WaterSurfaceManager.shouldRenderPrepass(
                GraphRunner.isActive(), GraphRunner.compileOptionValue("SSR_WATER_MODE", 0))) {
            return;
        }

        // MAIN camera matrices/fog, exactly like fornax$renderShadowPass -- see "update()/PBR/context
        // guard" above for why these three calls are safe to repeat even when fornax$renderShadowPass
        // already made them this frame.
        FogParameters realFog = this.lastFogParameters;
        this.uniformBufferManager.update(matrices, realFog);
        UniformBufferManagerExtension pbrExtension = (UniformBufferManagerExtension) (Object) this.uniformBufferManager;
        pbrExtension.updatePbrSettings();
        ChunkRenderContextHolder.set(this.uniformBufferManager.getUniformBuffer(), pbrExtension.getPbrSettingsBuffer());

        CameraTransform cameraTransform = new CameraTransform(x, y, z);
        // The SAME list Sodium's own later TRANSLUCENT renderLayer call would use -- see this
        // method's own javadoc for why (unlike the shadow pass) no ShadowCasterLists-style
        // un-culled radius scan is needed here: water is drawn from the main camera, not a light.
        ChunkRenderListIterable translucentLists = this.renderSectionManager.getRenderLists();

        // Per-frame batch invalidation, the ShadowCasterLists C1-fix precedent (see its class
        // javadoc): RenderRegionManager's upload-time invalidation only clears Sodium's OWN passes'
        // cachedBatches slots -- WATER_PREPASS is invisible to it. A block edit that compacts a
        // region's index arena rewrites every OTHER section's byte offsets in that region; with the
        // camera stationary (no clearAllCachedBatches from prepareForRender), this pass would keep
        // replaying a frozen batch of stale offsets, so an unrelated section-sized patch of open
        // water can vanish the moment a block is broken hundreds of blocks away.
        // Clearing for exactly the regions this frame's list draws forces a fresh fillCommandBuffer,
        // the same per-frame rebuild the main passes already do on movement.
        for (Iterator<ChunkRenderList> it = translucentLists.iterator(false); it.hasNext(); ) {
            it.next().getRegion().clearCachedBatchFor(FornaxRenderPasses.WATER_PREPASS);
        }

        // indexedRenderingEnabled=true, matching Sodium's own TRANSLUCENT renderLayer call: together
        // with WATER_PREPASS.isTranslucent() == true this selects useIndexedTessellation, the ONLY
        // index-buffer path whose bound buffer matches translucent sections' stored element offsets
        // (see FornaxRenderPasses.WATER_PREPASS's javadoc for the garbage-indices partial-coverage
        // failure a false/false combination produces).
        this.renderSectionManager.getChunkRenderer().render(matrices, translucentLists,
                FornaxRenderPasses.WATER_PREPASS, cameraTransform, realFog, true,
                terrainSampler, this.uniformBufferManager.getUniformBuffer(),
                this.uniformBufferManager.getSectionTimeInfo());
    }

    @Inject(method = "drawChunkLayer", at = @At("RETURN"))
    private void fornax$finishOpaque(ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler terrainSampler, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            if (GraphRunner.deferGraphUntilAfterSolidFeatures()) {
                // A pack claiming a non-terrain geometry slot needs those draws in the G-buffer before
                // anything resolves it, and they happen later in the frame -- see
                // GraphRunner.deferGraphUntilAfterSolidFeatures. Stash and let
                // FeatureSolidFeaturesGraphMixin run the graph once they are done.
                GraphRunner.stashDeferredFinish(matrices, x, y, z);
            } else {
                GraphRunner.finish(matrices, x, y, z);
            }
        }
    }
}
