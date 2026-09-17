package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.RayTracingMode;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.rt.BufferQuery;
import dev.icehunter.fornax.rt.CelestialFill;
import dev.icehunter.fornax.rt.RayProvider;
import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayReadiness;
import dev.icehunter.fornax.rt.RayTier;

/**
 * Celestial visibility at {@link RayTier#HARDWARE_VOXEL}: hardware traversal of the brick-grid
 * geometry the engine exports into a finite window around the camera.
 *
 * <p>Approximate where the mesh tier is exact, and wider: the window reaches past the mesh tier's
 * receiving cylinder, so this is what answers a texel whose blocker is too far away for tier 3. It
 * writes only texels no higher tier has answered, which is the whole of its contract.
 *
 * <p>Thin on purpose. {@link MetalRtShadowPass} still owns the acceleration structures, the shared
 * timeline and the interop images, and this drives them; that class is where the celestial fill is
 * encoded, alongside the dispatches that already share its command buffer. Splitting the ownership
 * before the legacy screen-space path is removed would mean two owners of one structure set on two
 * timelines in a single frame.
 */
public final class VoxelMetalProvider implements RayProvider {

    /** This frame's G-buffer, handed over on the render thread. Cleared every frame. */
    private GBuffer screen;

    /** Buffer-form queries, lazily built: a pack that declares none never allocates any of this. */
    private RayQueryInterop rayQueries;

    @Override
    public RayTier tier() {
        return RayTier.HARDWARE_VOXEL;
    }

    /** Visibility only, the same as the mesh tier: closest hit needs the buffer-form query. */
    @Override
    public boolean answers(RayQueryKind kind) {
        return kind == RayQueryKind.VISIBILITY;
    }

    /**
     * The window this traces is maintained by the voxel streaming pass, and the structures are
     * built inside the same call that fills, so readiness here is only about whether the backend
     * can run at all. A frame with no built structure yet declines inside the fill and leaves every
     * texel to the tier below, which is the same answer by a different route.
     */
    @Override
    public RayReadiness readiness() {
        // Deliberately says nothing about the captured G-buffer. Readiness is evaluated at the top
        // of the frame and the capture happens later, so consulting it here reports "not ready"
        // every frame and the router never calls this tier at all. The fill guards on it instead,
        // where the answer is actually known.
        if (FornaxConfig.get().rayTracing == RayTracingMode.OFF) {
            return RayReadiness.notReady("ray tracing is off");
        }
        return MetalRtSupport.isAvailable()
                ? RayReadiness.answering()
                : RayReadiness.notReady("no supported Metal ray-tracing device");
    }

    /**
     * Hands over this frame's G-buffer, at the point the G-buffer is complete. The trace runs
     * later in the frame, so the voxel window it reads is this frame's, not the one before.
     */
    public void captureScreen(GBuffer gbuffer) {
        this.screen = gbuffer;
    }

    @Override
    public void beginFrame() {
        screen = null;
    }

    /**
     * Traces without a G-buffer. This runs before the terrain draw, where no G-buffer exists yet,
     * and the celestial fill needs none: only the screen-space scene debug does, and it runs later
     * once one has been captured.
     */
    @Override
    public void fillCelestialVisibility(CelestialFill request) {
        MetalRtShadowPass.runIfEnabled(screen, FornaxConfig.get().rayTracing != RayTracingMode.OFF, request);
    }

    @Override
    public boolean publishCelestialVisibility() {
        return MetalRtShadowPass.publishPendingCelestial();
    }

    /**
     * Answers a batch against the window structure the celestial fill already built this frame.
     * Approximate geometry, but present wherever the window reaches, which is further than the
     * mesh tier's caster set.
     */
    @Override
    public void answer(BufferQuery query) {
        long structure = MetalRtAcceleration.instanceStructure();
        if (structure == 0) {
            return;
        }
        if (rayQueries == null) {
            rayQueries = new RayQueryInterop();
        }
        rayQueries.answer(query, tier().ordinal(), structure,
                MetalRtAcceleration.residentResources(), MetalRtShadowPass.atlasTexture());
    }

    @Override
    public void close() {
        screen = null;
        if (rayQueries != null) {
            rayQueries.close();
            rayQueries = null;
        }
        MetalRtShadowPass.shutdown();
    }
}
