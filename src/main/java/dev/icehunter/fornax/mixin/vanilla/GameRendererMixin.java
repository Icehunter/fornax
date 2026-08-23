package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.metalfx.FrameGenPass;
import dev.icehunter.fornax.metalfx.MetalFxUpscalePass;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.graph.TargetInstance;
import dev.icehunter.fornax.pass.reconstruct.ReconstructPass;
import dev.icehunter.fornax.pass.reconstruct.TemporalInputs;
import dev.icehunter.fornax.pass.ssaa.SsaaDownsamplePass;
import dev.icehunter.fornax.pass.ssaa.SsaaManager;
import dev.icehunter.fornax.pass.taa.CameraJitter;
import dev.icehunter.fornax.pass.debug.GraphTargetDebugPass;
import dev.icehunter.fornax.pass.voxel.VoxelDebugRaymarchPass;
import dev.icehunter.fornax.pass.water.WaterPrepassDebugPass;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import dev.icehunter.fornax.pipeline.SceneHistory;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds SSAA/TAA/TAAU support to vanilla's {@code GameRenderer#renderLevel}: swaps in an off-screen
 * render-scale target (native-sized for TAA, larger for SSAA, smaller for TAAU) for the duration
 * of the level render and resolves it back into the native target afterward -- a real box-filter
 * downsample for SSAA, the engine-owned temporal {@link ReconstructPass} for TAA/TAAU -- and
 * applies {@link CameraJitter}'s current offset to the projection matrix used by both vanilla's
 * own entity/sky/particle rendering and this mod's terrain rendering.
 *
 * <p>Fog and projection-matrix accessors are deliberately not exposed here: official Sodium's own
 * mixins on vanilla {@code GameRenderer}/{@code FogRenderer} already implement the
 * {@code GameRendererStorage}/{@code FogStorage} contracts at
 * {@code net.caffeinemc.mods.sodium.client.util}, so code needing them can cast the vanilla
 * objects to those interfaces directly instead of duplicating the accessors here.
 *
 * <p>Priority is bumped above the default (1000): Sodium's own vendored {@code GameRendererMixin}
 * wraps the exact same {@code ProjectionMatrixBuffer.getBuffer} call to capture its
 * {@code this.projection} (the value {@code GameRendererStorage.sodium$getProjectionMatrix()}
 * returns, which {@code LevelRendererMixin} reads once per frame to build the
 * {@code ChunkRenderMatrices} this mod's terrain draws and {@code PreviousFrameCameraTransform}
 * both consume). Two same-priority {@code @WrapOperation}s on one instruction chain in
 * mixin-application order, which is undefined without an explicit priority -- letting Sodium's
 * wrapper capture first would freeze its stored projection at the pre-jitter matrix while the
 * real GPU buffer still receives the jittered one, permanently decoupling terrain's own
 * projection uniform from the jitter it's supposed to carry. A higher priority here guarantees
 * this mixin applies after (wraps outside) Sodium's, so the jitter translation always lands
 * before Sodium's capture, regardless of mod load order.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public class GameRendererMixin {
    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    private Minecraft minecraft;

    /**
     * Read-only: {@link #fornax$reconstruct} reads {@code getFov()} off it (already public, degrees --
     * the exact per-frame value the projection matrix used, not the raw options setting) to feed
     * {@link FrameGenPass}'s camera-linearization params. {@code depthFar} is Camera-private, reached
     * instead via {@link CameraAccessor}.
     */
    @Shadow
    @Final
    private Camera mainCamera;

    @Unique
    private RenderTarget fornax$ssaaNativeTargetBackup;

    /**
     * Last reason {@link #fornax$reconstruct} skipped, so a repeated skip logs once per distinct
     * cause rather than once per frame. A pack toggle produces a single line; a pack that never
     * allocates its targets produces one line and then stays quiet instead of flooding the log.
     */
    @Unique
    private String fornax$lastReconstructSkipReason;

    /**
     * Set when this frame's reconstruct ran: the reconstruct's accumulation pass renders directly
     * into sceneHistory's write slot (unsharpened, so the presentation sharpen stays out of the
     * temporal feedback loop), making the end-of-frame copy redundant AND wrong -- copying the
     * SHARPENED native target over the accumulation would re-inject sharpening into next frame's
     * history, the exact divergence the two-pass split exists to prevent.
     */
    @Unique
    private boolean fornax$historyWrittenByReconstruct;

    /**
     * Camera jitter is gated on the active {@code aaMethod} wanting it (TAA/TAAU): disabling it
     * while jitter kept running would just add a constant sub-pixel wobble with nothing to resolve
     * it. Applied directly to the projectionMatrix instance passed into original.call() below, not a
     * copy, so vanilla's own entity/sky/particle rendering and this mod's terrain rendering see the
     * identical jittered matrix -- otherwise terrain would visibly misalign from everything else by
     * a sub-pixel amount every frame. Uses translateLocal, not translate: translateLocal
     * left-multiplies (this = T * this), translating the matrix's output in clip space for a
     * frame-constant NDC-space shift; translate right-multiplies and would translate the input
     * instead, giving a depth-dependent result.
     * NOTE: CameraJitter.advanceFrame() is deliberately NOT called here -- see
     * fornax$endFrame below for why. It still runs unconditionally, even when jitter is
     * disabled, so switching back to TAA/TAAU mid-session doesn't produce a discontinuous jitter
     * sequence.
     */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice fornax$setProjection(ProjectionMatrixBuffer instance, Matrix4f projectionMatrix, Operation<GpuBufferSlice> original) {
        // Captured unconditionally, BEFORE any jitter below is applied, regardless of whether jitter
        // ends up applied this frame: consumers that need a jitter-free reconstruction (e.g. the voxel
        // water-reflection compute kernel's world-space DDA ray) read this back via
        // CameraJitter.currentUnjitteredProjection() instead of the shared (possibly jittered) matrix
        // every other consumer correctly keeps using.
        CameraJitter.captureUnjitteredProjection(projectionMatrix);
        // The null-backup check mirrors fornax$ssaaBeginFrame's reconstruct-resources guard:
        // when that guard skips the off-screen swap (shaders disabled / no pack), jittering the
        // projection would shimmer permanently with no reconstruct to resolve it.
        if (FornaxConfig.get().aaMethod.wantsJitter() && this.fornax$ssaaNativeTargetBackup != null) {
            Vector2f jitter = CameraJitter.currentOffsetNdc();
            projectionMatrix.translateLocal(jitter.x(), jitter.y(), 0.0f);
        }

        return original.call(instance, projectionMatrix);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void fornax$ssaaBeginFrame(CallbackInfo ci) {
        SsaaManager.applyCurrentScale();

        // getWidth()/getHeight() return the real physical framebuffer size here, not
        // getScreenWidth()/getScreenHeight() (a separate HiDPI logical-points value). isFrameActive()
        // is still false at this point (set true only at the end of this method), so WindowMixin's
        // override -- which only applies once isFrameActive() is true -- has not kicked in yet, and
        // this read passes through untouched. Captured unconditionally (even under OFF, and before
        // the early return below) so GraphRunner.prepare()'s output-basis target sizing always has
        // this frame's true native size available, regardless of what mainRenderTarget itself gets
        // swapped to a moment later.
        int nativeWidth = this.minecraft.getWindow().getWidth();
        int nativeHeight = this.minecraft.getWindow().getHeight();
        SsaaManager.setNativeSize(nativeWidth, nativeHeight);

        if (!SsaaManager.needsOffscreenTarget()) {
            SsaaManager.deactivate();
            return;
        }

        // TAA/TAAU can only resolve their off-screen target through the reconstruct pass, and
        // METALFX needs the exact same inputs for its ML scaler (plus writes its output through the
        // same sceneHistory slot) even though it skips the engine reconstruct itself -- all three
        // need the pack graph's G-buffer (motion/depth) and the engine sceneHistory target to hold
        // data THIS frame's geometry wrote. With shaders disabled, no pack loaded, or a transient
        // resize/world-join rebuild window that is not the case, so skip the swap entirely and
        // render this frame plain (fornax$setProjection keys jitter off the same signal via the
        // null backup). SSAA is unaffected: its box downsample needs only the color target itself.
        //
        // The question is TemporalInputs' to answer, not a null check's: both handles outlive a
        // shaders-off toggle (GBufferManager never nulls its instance; ShadersEnabledFlip keeps the
        // pack loaded), so an allocation-only guard skipped nothing and ghosted vanilla. See
        // TemporalInputs' own header for the full mechanism and what was rejected.
        if (FornaxConfig.get().aaMethod.needsGraphResources()
                && TemporalInputs.unavailable(GraphRunner.isActive(),
                        GBufferManager.getInstance() != null,
                        GraphRunner.sceneHistoryTarget() != null) != null) {
            SsaaManager.deactivate();
            return;
        }

        this.fornax$ssaaNativeTargetBackup = this.mainRenderTarget;
        this.mainRenderTarget = SsaaManager.ensureScaledTarget(nativeWidth, nativeHeight);
        SsaaManager.setFrameActive(true);
    }

    /**
     * The single end-of-frame injection: off-screen-target restore, then the sceneHistory copy,
     * then the jitter advance, in that order, as EXPLICIT sequential calls from one method body.
     * Deliberately not three sibling {@code @Inject}s at the same RETURN point -- Mixin orders
     * same-priority handlers at one injection point by little more than declaration order, and the
     * sceneHistory copy is only correct AFTER {@link #fornax$restoreNativeTarget} has resolved and
     * swapped {@code mainRenderTarget} back to native (copying first would snapshot the off-screen
     * render-scale target instead: a wrong-basis, and under SSAA a cropped, history). A single
     * injection site makes that ordering a plain-Java guarantee instead of a mixin-application
     * subtlety.
     *
     * <p>Deliberately the RETURN boundary for EVERY method, including TAA/TAAU: an earlier
     * mid-frame variant resolved before vanilla's first-person hand so the hand could draw onto
     * the finished native frame, and three successive placements of it each corrupted some part
     * of vanilla's hand/translucent phase state (live-caught). Vanilla's frame now runs completely
     * untouched from HEAD to RETURN; the first-person ghosting that motivated the mid-frame
     * attempt is instead solved inside the reconstruct shader by responsive-pixel masking (scene
     * depth vs G-buffer depth -- see ReconstructPass/reconstruct.fsh), which needs no injection
     * between vanilla draws at all.
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void fornax$endFrame(CallbackInfo ci) {
        this.fornax$restoreNativeTarget();
        this.fornax$copySceneHistory();
        // VoxelDebugRaymarchPass.presentIfEnabled was called here, DDA-raymarching the brick grid on a
        // compute queue and blitting it over the native frame for the VOXEL_RAYMARCH debug view. REMOVED
        // 2026-07-20: that per-frame dispatch + mapped readback could wedge the GPU, and on macOS a wedged
        // GPU takes WindowServer down with it -- a hard power-off, not a recoverable game crash. It cost
        // the user multiple forced reboots, including from merely cycling PAST the view with F9.
        //
        // Only the PRESENTATION half is gone. VoxelDebugRaymarchPass still owns the voxel grid itself
        // (ensureGridAllocated / allocatedDiameter / onFrame), which the voxel sun-shadow path depends on
        // every frame via GraphRunner -- deleting the class would take the shadows with it. Its remaining
        // work is gated on voxelGridNeededByPack, not on the debug view.
        // Deferred Water Task 1 spike instrumentation: same bypass shape as the voxel debug view
        // above, minus the compute half -- see WaterPrepassDebugPass's own doc comment.
        WaterPrepassDebugPass.presentIfEnabled(this.mainRenderTarget);
        // Generic pack-owned graph-target presentation. Water shaft diagnostics and the archived
        // M1 shadow view route through the same live, no-recompile path.
        GraphTargetDebugPass.presentIfEnabled(this.mainRenderTarget);
        // Unconditional (even when jitter is disabled, so re-enabling TAA/TAAU mid-session doesn't
        // produce a discontinuous jitter sequence), and last -- never from fornax$setProjection,
        // which fires partway through renderLevel BEFORE a later CameraJitter.currentOffsetNdc()
        // read uploads the per-frame jitter uniform; advancing there would hand that read the next
        // frame's offset instead of the one just baked into this frame's projection matrix, a
        // one-frame mismatch that would corrupt motion-vector correction.
        CameraJitter.advanceFrame();
    }

    /**
     * Resolves this frame's off-screen render-scale target back into the native
     * {@code mainRenderTarget}; no-op when no method swapped one in this frame (OFF). SSAA alone
     * gets the real box-filter downsample ({@link SsaaDownsamplePass}, averaging supersampled
     * texels) -- TAA (same-size off-screen target) and TAAU (below-native) both get the
     * engine-owned temporal {@link ReconstructPass} instead: at ratio 1.0 (TAA) it is functionally
     * equivalent to the retired {@code taa_blend} pass, below 1.0 (TAAU) it does real
     * motion-reprojected upscaling reconstruction.
     */
    @Unique
    private void fornax$restoreNativeTarget() {
        if (this.fornax$ssaaNativeTargetBackup == null) {
            return;
        }

        AaMethod method = FornaxConfig.get().aaMethod;
        switch (method) {
            case SSAA -> SsaaDownsamplePass.downsample(this.mainRenderTarget, this.fornax$ssaaNativeTargetBackup,
                    SsaaManager.getScaleFactor());
            case TAA, TAAU, METALFX -> {
                // MetalFX spike M1 (dev-only, -Dfornax.metalfx.passthrough=true): roundtrip the
                // low-res color through an exported MTLTexture pair BEFORE the normal reconstruct
                // -- an identity copy whose pass criterion is "the frame still looks normal."
                // No-op (and self-disabling on any failure) without the flag; see
                // VulkanMetalInterop's own header for the sync model.
                VulkanMetalInterop.passthroughIfEnabled(this.mainRenderTarget);
                // METALFX routes through fornax$reconstruct too: MetalFxUpscalePass.runIfEnabled
                // handles the frame when the method selects it (or the dev flag arms it) and is
                // healthy; otherwise the engine reconstruct runs -- which is also exactly the
                // unsupported-hardware/persisted-config fallback (jitter/scale are TAAU-identical).
                this.fornax$reconstruct();
            }
            case OFF -> {
                // Unreachable: OFF never sets needsOffscreenTarget(), so fornax$ssaaNativeTargetBackup
                // is null and this method already returned above.
            }
        }

        this.mainRenderTarget = this.fornax$ssaaNativeTargetBackup;
        this.fornax$ssaaNativeTargetBackup = null;
        SsaaManager.setFrameActive(false);
    }

    /**
     * TAA/TAAU branch of {@link #fornax$restoreNativeTarget}: gathers the render-res G-buffer
     * motion/depth views, the native {@code sceneHistory} target, and this frame's jitter/blend/
     * sharpen settings, then hands them to {@link ReconstructPass#reconstruct}.
     *
     * <p>CORRECTED 2026-08-04, and the correction is the whole bug: this comment used to assert that
     * "the G-buffer and sceneHistory are absent when {@code shadersEnabled} is off or no pack is
     * loaded". <b>They are not.</b> {@code GBufferManager} never nulls its instance and {@code
     * ShadersEnabledFlip} keeps the pack (and therefore the target registry) loaded, so both handles
     * survive a shaders-off toggle intact and merely stop being WRITTEN. Believing otherwise is what
     * let the guard below pass and fed a frozen G-buffer to the reconstruct -- see {@link
     * TemporalInputs}, which now owns the question. {@code fornax$ssaaBeginFrame} skips the
     * off-screen swap for the whole frame whenever that predicate says no, so arriving here anyway
     * means pack state changed <em>within</em> the frame -- the user enabling, disabling or switching
     * a pack between begin-frame and end-frame. That is an ordinary thing to do from the settings
     * screen, not a corrupt state.
     *
     * <p>This used to throw on that window, which turned a routine pack toggle into a crash to
     * desktop. It now skips reconstruction for the frame instead: {@link #fornax$restoreNativeTarget}
     * restores {@code mainRenderTarget} either way, so the cost is one unreconstructed frame at the
     * moment of the toggle rather than losing the session. A pack whose targets never allocate would
     * hit this every frame, so the skip is logged (rate-limited) rather than silent -- a pack bug has
     * to stay visible in the log without being fatal.
     */
    @Unique
    private void fornax$warnReconstructSkipped(String reason) {
        if (reason.equals(this.fornax$lastReconstructSkipReason)) {
            return;
        }
        this.fornax$lastReconstructSkipReason = reason;
        FornaxMod.LOGGER.warn("[Fornax] Skipping TAA/TAAU reconstruct this frame: {}."
                + " Expected briefly while enabling, disabling or switching a pack;"
                + " if it persists, the active pack's targets are not being allocated.", reason);
    }

    @Unique
    private void fornax$reconstruct() {
        GBuffer gbuffer = GBufferManager.getInstance();
        TargetInstance sceneHistory = GraphRunner.sceneHistoryTarget();
        // Same predicate as fornax$ssaaBeginFrame's, deliberately re-evaluated rather than trusted
        // from begin-frame: the latch cannot move mid-frame, but a pack rebuild landing between the
        // two can still free the registry. Unreachable in the ordinary shaders-off case (begin-frame
        // already declined the swap, so fornax$restoreNativeTarget returns before ever calling this).
        TemporalInputs.Unavailable unavailable = TemporalInputs.unavailable(GraphRunner.isActive(),
                gbuffer != null, sceneHistory != null);
        if (unavailable != null) {
            fornax$warnReconstructSkipped(unavailable.reason());
            return;
        }
        this.fornax$lastReconstructSkipReason = null; // recovered -- let a future skip log again

        // MetalFX spike M2 (dev-only, -Dfornax.metalfx.scaler=true): the ML temporal scaler
        // replaces ReconstructPass for the frame when armed and healthy. It owns its temporal
        // history internally, but its unsharpened native output is copied directly into the same
        // sceneHistory write slot the engine reconstruct uses before presentation sharpening.
        // Marking the frame written skips the later native->history copy, keeping sharpen out of
        // SSR history.
        if (MetalFxUpscalePass.runIfEnabled(this.mainRenderTarget, this.fornax$ssaaNativeTargetBackup,
                gbuffer.getMotionView(), gbuffer.getDepthView(), sceneHistory,
                CameraJitter.currentOffsetNdc())) {
            this.fornax$historyWrittenByReconstruct = true;
            // Frame generation (dev-only, -Dfornax.framegen=true) is only ever reachable behind a
            // successful METALFX upscale for the frame -- see FrameGenPass's own header for why it
            // never touches sceneHistory (that write already happened above). near/fov are already
            // public on Camera; depthFar (the engine's own render-distance/cloud-range-derived far
            // plane, never infinite) is Camera-private, reached via CameraAccessor -- see its own
            // header and MetalFxFrameInterpolator#encode's for why FrameGenPass needs them.
            FrameGenPass.runIfEnabled(this.fornax$ssaaNativeTargetBackup, Camera.PROJECTION_Z_NEAR,
                    ((CameraAccessor) this.mainCamera).fornax$depthFar(), this.mainCamera.getFov(),
                    CameraJitter.currentOffsetNdc());
            return;
        }

        // Mid-graph temporal boundary (a pack `temporal` pass, TAA only -- see TemporalPassRunner):
        // accumulation already happened INSIDE the graph, before bloom, in HDR. Accumulating again
        // here would re-introduce the exact defect the boundary exists to fix (the finished frame's
        // bloom halos hold the clamp open along a bright mover's path -- permanent star trails), so
        // the tail degrades to presentation sharpen only. fornax$historyWrittenByReconstruct stays
        // false ON PURPOSE: the end-of-frame copy then fills sceneHistory from the final native
        // frame, exactly the OFF/SSAA behaviour, so SSR's reflection source keeps working.
        if (FornaxConfig.get().aaMethod == AaMethod.TAA && GraphRunner.activePackHasTemporalPass()) {
            ReconstructPass.presentSharpened(this.mainRenderTarget.getColorTextureView(),
                    this.fornax$ssaaNativeTargetBackup, this.mainRenderTarget.width,
                    this.mainRenderTarget.height, FornaxConfig.get().reconstructSharpen);
            return;
        }

        ReconstructPass.reconstruct(this.mainRenderTarget, this.fornax$ssaaNativeTargetBackup,
                gbuffer.getMotionView(), gbuffer.getDepthView(), sceneHistory,
                CameraJitter.currentOffsetNdc(), FornaxConfig.get().taaBlendFactor, FornaxConfig.get().reconstructSharpen);
        this.fornax$historyWrittenByReconstruct = true;
    }

    /**
     * Writes {@link SceneHistory}'s engine-guaranteed target from the final NATIVE frame, once per
     * frame -- directly under OFF/SSAA; under TAA/TAAU the reconstruct's accumulation pass has
     * already written the slot (unsharpened, with the age in alpha) and this copy is skipped. Runs only after {@link
     * #fornax$restoreNativeTarget} (see {@link #fornax$endFrame}'s ordering note), so
     * {@code mainRenderTarget} is always the native target here: the resolved off-screen target
     * (downsampled under SSAA, blitted under TAA/TAAU) when one was active this frame, or the
     * never-swapped native target the pack graph's own resolve pass wrote straight into under OFF.
     * No-ops with no pack active (nothing yet allocates a {@link TargetInstance} to copy into) and
     * is otherwise unconditional -- see {@link GraphRunner#sceneHistoryTarget()}.
     */
    @Unique
    private void fornax$copySceneHistory() {
        // TAA/TAAU: the reconstruct's accumulation pass already wrote this frame's sceneHistory
        // (unsharpened + age). Copying the sharpened presented frame over it would feed sharpening
        // back into the accumulator -- see fornax$historyWrittenByReconstruct. Flag, not a live
        // aaMethod re-read: the method could change between the restore and this call.
        if (this.fornax$historyWrittenByReconstruct) {
            this.fornax$historyWrittenByReconstruct = false;
            return;
        }

        TargetInstance sceneHistory = GraphRunner.sceneHistoryTarget();
        if (sceneHistory == null) {
            return;
        }

        RenderTarget target = this.mainRenderTarget;
        GpuTextureView finalColor = target.getColorTextureView();

        // Defensive min-size, exactly like CopyRunner's own copy-pass convention: sceneHistory is
        // OUTPUT-basis (always native resolution) but this and the native target can still
        // transiently disagree in size (e.g. a resize race, or a rebuild landing mid-frame), so
        // this clamp keeps that window from ever producing a GPU out-of-bounds copy.
        int width = Math.min(target.width, sceneHistory.width());
        int height = Math.min(target.height, sceneHistory.height());
        SceneHistory.copyFinalColor(finalColor, sceneHistory, width, height);
    }
}
