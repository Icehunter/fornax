package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.icehunter.fornax.pack.GeometrySlot;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps vanilla {@link RenderPipeline} constants onto the {@link GeometrySlot} a pack shades them with
 * on the FORWARD draw -- vanilla's own target, vanilla's own blend, vanilla's own place in the frame.
 *
 * <p><b>A SEPARATE TABLE FROM {@link GeometryPipelineMap}, WITH THE INVERSE INVARIANT.</b> That map
 * holds pipelines that CAN be deferred; this one holds pipelines that must NOT be. Merging them and
 * distinguishing the two by a flag was the obvious alternative and is wrong for one concrete reason:
 * four call sites read {@code GeometryPipelineMap.slotOf(...) != null} as meaning exactly "defer this
 * draw" -- the render-pass wrapper, the pipeline wrapper, the shadow-cast decision, and the shader
 * source substitution. A merged table would make that expression mean "this pipeline is interesting",
 * and every one of those sites would have to re-ask the question correctly. Keeping the tables
 * disjoint means the existing reading stays true and cannot rot; {@code DeferredSlotDepthContractTest}
 * asserts the disjointness rather than trusting this paragraph.
 *
 * <p><b>What belongs here: geometry that draws after the graph has resolved, or that blends in a way
 * a G-buffer cannot carry.</b> Both facts are properties of vanilla's pipeline and its draw order, not
 * of the pack -- see {@link GeometrySlot#rendersForward()} for why that makes them engine facts rather
 * than {@code graph.toml} syntax.
 *
 * <p><b>What does NOT belong here, today, despite fitting the mechanism.</b> {@code ARMOR_GLINT},
 * {@code SPIDER_EYES}, {@code ENTITY_TRANSLUCENT_EMISSIVE}, {@code BEACON_BEAM_TRANSLUCENT} and
 * {@code HAND_TRANSLUCENT} are all reachable through this same hook with no further engine work. Each
 * is also a visible LOOK change to something that renders correctly today, and bundling them would
 * produce a frame that can only be judged as a lump. They are deliberately left out, one round each.
 */
public final class ForwardPipelineMap {
    private static final Map<RenderPipeline, GeometrySlot> BY_PIPELINE = new IdentityHashMap<>();

    static {
        // --- Banner patterns ------------------------------------------------------------------
        // The pattern layers painted over a banner's cloth. Doubly undeferrable, and either fact
        // alone would be enough:
        //
        //   * BANNER_PATTERN is built with depthWrite = false, so GeometryPipelineMap's own rule
        //     refuses it -- a G-buffer write with no depth to reconstruct from would be lit using
        //     whatever surface lies behind the banner.
        //   * it draws in the translucent phase, after GraphRunner.finishDeferred(). Deferring a
        //     draw that lands after the resolve writes a G-buffer nothing will ever read, which is
        //     the exact failure 95fc3b2 reverted when entities_translucent was claimed.
        //
        // The symptom this closes: a banner's post and cloth are ENTITY_SOLID -> ENTITIES, which IS
        // claimed, deferred and fogged -- so a correctly-hazed pole carries vivid, unfogged pattern
        // layers painted straight over it. One banner, two colour spaces.
        put(RenderPipelines.BANNER_PATTERN, GeometrySlot.BANNER_PATTERNS);

        // --- Particles, translucent arm -------------------------------------------------------
        // Campfire smoke, torch flame and smoke, souls, spells, sculk -- everything 30942a6 moved onto
        // Layer.TRANSLUCENT. Undeferrable twice over, and either fact alone would be enough:
        //
        //   * it draws during executeTranslucentAfterTerrain, AFTER GraphRunner.finishDeferred() has
        //     resolved at the return of executeSolid. A deferred write there lands in a G-buffer
        //     nothing will read, which is how geometry goes invisible rather than unshaded.
        //   * TRANSLUCENT_PARTICLE carries BlendFunction.TRANSLUCENT (measured, not assumed --
        //     ParticleGroupDeferralContractTest reads it off the constant) and a deferred variant
        //     drops the blend outright. That is exactly the regression SmokeParticleLayerMixin exists
        //     to prevent: the user's 128x smoke sprites are ~28% partial-alpha texels, and unblended
        //     they read as solid flashing rectangles under TAAU jitter.
        //
        // UNLIKE EVERY OTHER ENTRY IN THIS TABLE, THIS ONE IS NOT REACHED THROUGH THE CHOKEPOINT.
        // Particles never pass PreparedRenderType.drawFromBuffer at all -- QuadParticleFeatureRenderer
        // builds its own render pass and its private static drawLayers calls setPipeline directly.
        // QuadParticleDeferredMixin consults this map from there, exactly as the chokepoint does from
        // PreparedRenderTypeDeferredMixin. The map stays a pure pipeline -> slot function either way;
        // which hook asks is not its business.
        //
        // The symptom this closes: a smoke column 200 blocks off rendered at full vividness in front of
        // terrain hazed all the way to the sky colour. Same complaint as the banner above, different
        // draw path.
        put(RenderPipelines.TRANSLUCENT_PARTICLE, GeometrySlot.PARTICLES_TRANSLUCENT);
    }

    private ForwardPipelineMap() {}

    private static void put(RenderPipeline pipeline, GeometrySlot slot) {
        if (GeometryPipelineMap.slotOf(pipeline) != null) {
            // The two tables must stay disjoint -- see the class comment. A pipeline in both would be
            // deferred and forward-substituted in the same frame, which is a five-attachment pipeline
            // bound into a one-attachment pass.
            throw new IllegalStateException("Fornax: render pipeline " + pipeline.getLocation()
                    + " is mapped as both deferred and forward");
        }
        GeometrySlot previous = BY_PIPELINE.put(pipeline, slot);
        if (previous != null && previous != slot) {
            throw new IllegalStateException("Fornax: render pipeline " + pipeline.getLocation()
                    + " mapped to both " + previous + " and " + slot);
        }
    }

    /**
     * The slot a pack would shade this pipeline with on its forward draw, or {@code null} if Fornax
     * does not claim it -- the common case, meaning "draw exactly as vanilla would".
     */
    @Nullable
    public static GeometrySlot slotOf(@Nullable RenderPipeline pipeline) {
        return pipeline == null ? null : BY_PIPELINE.get(pipeline);
    }

    /** Whether any vanilla pipeline maps to {@code slot} -- used by tests and diagnostics. */
    public static boolean isMapped(GeometrySlot slot) {
        return BY_PIPELINE.containsValue(slot);
    }

    /** Number of mapped pipelines, for diagnostics. */
    public static int size() {
        return BY_PIPELINE.size();
    }
}
