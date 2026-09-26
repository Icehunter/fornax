package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pipeline.DeferredGeometryPipelines;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the pack's render graph after vanilla's solid feature draws, for packs that claim a non-terrain
 * geometry slot.
 *
 * <p>Deferred shading requires every G-buffer writer to have drawn before anything resolves it.
 * Terrain-only packs satisfy that at the end of the opaque terrain layer, which is where the graph has
 * always run; a pack that also shades entities does not, because those draw here -- after that point.
 *
 * <p><b>After solid, before translucent, and nowhere else.</b> That boundary is the single point where
 * all opaque geometry has drawn and no blended geometry has. Moving the resolve later, to catch
 * geometry that only draws during the translucent pass, is the tempting mistake and it breaks blended
 * geometry outright: the full-screen resolve overwrites whatever the translucent pass already drew, so
 * layered blended geometry such as banner patterns loses its colour and comes out blank white.
 *
 * <p>When something needed in the G-buffer has not drawn by this point, the answer is to draw that
 * thing explicitly before resolving -- never to move the resolve. Iris settles on the same boundary and
 * renders the hand by hand right here for exactly this reason; {@code PlayerShadowCaster} is the same
 * idea applied to the shadow map.
 *
 * <p>Targets {@code PreparedFrame.executeSolid} rather than the {@code LevelRenderer.addMainPass}
 * call site: the call there lives inside a frame-graph lambda, whose synthetic method name shifts with
 * unrelated edits to the surrounding method, while this one is a real named method on a public type.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class FeatureSolidFeaturesGraphMixin {

    @org.spongepowered.asm.mixin.Unique
    private boolean fornax$reportedShadowSkip;

    @Inject(method = "executeSolid", at = @At("RETURN"))
    private void fornax$runGraphAfterSolidFeatures(CallbackInfo ci) {
        // The shadow phase below re-executes these same solid draws, which re-enters this method.
        // Without this guard the nested pass would start its own shadow replay and resolve the graph
        // midway through building the shadow map.
        //
        // The mirror phase needs the same guard for a different reason: PlayerMirrorCaster.cast()'s
        // renderAllFeatures call reaches a second PreparedFrame.executeSolid internally (the caster's
        // frame, not the level's main one), which re-enters this hook. isPlayerMirrorPhase() is true
        // for the whole call to cast(), set before renderAllFeatures and cleared in a finally right
        // after, so it stays raised for the window the reentrant call falls inside. Without this
        // check, the reentrant call would see wantPlayerMirror() still true and call cast() a second
        // time while the first call's PreparedFrame is still marked in use, throwing "PreparedFrame
        // already in use" and disabling the mirror for the rest of the session.
        if (DeferredGeometryPipelines.isShadowPhase() || DeferredGeometryPipelines.isPlayerMirrorPhase()) {
            return;
        }

        // Cast entity shadows by running the very same prepared draws a second time, aimed at the
        // shadow map. Vanilla bakes each draw's transforms at submit time and offers no way to
        // re-submit from another viewpoint, so rather than duplicating the submit machinery the draws
        // are replayed and the pack's shadow program reprojects them through the light's matrix.
        //
        // Must happen BEFORE the graph runs: the resolve samples the shadow map, so a caster added
        // afterwards would not appear until the following frame.
        //
        // Either shadow tier wants this replay: the traced tier only ever reaches terrain (see
        // RayRouter's caster capture), so an entity occluder can only ever reach the independent
        // entity depth target through this same raster replay, whether or not the raster map's own
        // terrain draws are also running this frame.
        boolean wantShadowCasters = FornaxRenderState.isActive()
                && (GraphRunner.isCompileOptionEnabled("SHADOWS")
                        || GraphRunner.isCompileOptionEnabled("RT_SHADOWS"))
                && GraphRunner.shadowsEnabledThisFrame()
                && ShadowMapManager.getView() != null;
        if (!wantShadowCasters && !fornax$reportedShadowSkip) {
            fornax$reportedShadowSkip = true;
            dev.icehunter.fornax.FornaxMod.LOGGER.info(
                    "[Fornax][diag] entity shadow casting inactive: packActive={} SHADOWS={} RT_SHADOWS={} map={}",
                    FornaxRenderState.isActive(), GraphRunner.isCompileOptionEnabled("SHADOWS"),
                    GraphRunner.isCompileOptionEnabled("RT_SHADOWS"),
                    ShadowMapManager.getView() != null);
        }
        if (wantShadowCasters) {
            DeferredGeometryPipelines.setShadowPhase(true);
            try {
                FeatureRenderDispatcher.PreparedFrame frame = (FeatureRenderDispatcher.PreparedFrame) (Object) this;
                // Solid draws only. Vanilla has not executed the translucent set yet at this point,
                // and running it early here would consume it before it ever reaches the screen. The
                // player -- whose skin is translucent -- is submitted separately below, so the one
                // caster that would otherwise be missed is covered.
                frame.executeSolid();
                dev.icehunter.fornax.pass.shadow.PlayerShadowCaster.cast();
            } finally {
                // Cleared in a finally so a throwing draw cannot strand the flag raised, which would
                // send every subsequent frame's entities into the shadow map instead of the screen.
                DeferredGeometryPipelines.setShadowPhase(false);
            }
        }

        // The player's reflection draws here, independent of shadows: unlike the block above, this
        // runs whether or not wantShadowCasters was true. It must happen before the graph resolves,
        // since player_mirror_resolve (and its _x/_z siblings) sample
        // builtin.mirror{,X,Z}{Albedo,Normal,Material,Depth}, so each slot's caster must draw into its
        // MRT first. One slot finishes fully, submit, draw, endFrame, phase lowered, before the next
        // slot's wantPlayerMirror/cast runs at all: never interleaved, never nested, so
        // PlayerMirrorCaster's per-instance RenderBuffers never serves two overlapping
        // renderAllFeatures calls at once.
        for (GeometrySlot mirrorSlot : dev.icehunter.fornax.pipeline.PlayerMirrorTargets.slots()) {
            boolean wantMirror = wantPlayerMirror(mirrorSlot);
            if (wantMirror) {
                DeferredGeometryPipelines.setActiveMirrorSlot(mirrorSlot);
                try {
                    dev.icehunter.fornax.pass.mirror.PlayerMirrorCaster.forSlot(mirrorSlot).cast();
                } finally {
                    // Cleared in a finally for the same reason as the shadow phase flag: a throwing
                    // draw must not strand it raised and misroute every later frame's entities.
                    DeferredGeometryPipelines.setActiveMirrorSlot(null);
                }
            } else if (DeferredGeometryPipelines.shouldClearMirrorOnFallingEdge(
                    wantMirror, fornax$mirrorDrewLastFrame.getOrDefault(mirrorSlot, false))) {
                // See shouldClearMirrorOnFallingEdge's doc: the player left the water or wall, the
                // pack unloaded, or the probe stopped finding a plane. Nothing else clears this
                // slot's builtin.mirror* when that happens, so this clears the stale frame here.
                // This is a full clear, colour and depth, not the per-frame depth-only trim: see
                // PlayerMirrorTargets.clearFullOnFallingEdge's doc for why this call stays full.
                dev.icehunter.fornax.pipeline.PlayerMirrorTargets.forSlot(mirrorSlot).clearFullOnFallingEdge();
            }
            fornax$mirrorDrewLastFrame.put(mirrorSlot, wantMirror);
        }

        if (GraphRunner.deferGraphUntilAfterSolidFeatures()) {
            GraphRunner.finishDeferred();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.Map<GeometrySlot, Boolean> fornax$mirrorDrewLastFrame =
            new java.util.EnumMap<>(GeometrySlot.class);

    /**
     * Reads this frame's live state for {@code slot} and hands it to {@link DeferredGeometryPipelines
     * #wantsPlayerMirrorPhase}, the pure predicate that decides. {@link
     * GraphRunner#wantsPlayerMirrorConsumed(GeometrySlot)} is the consumer-driven half of the gate:
     * whether some compile-enabled pass reads that slot's {@code builtin.mirror*} input, rather than
     * a hardcoded option name or threshold; see that method's doc. The validity check differs by
     * family: the floor slot asks {@code WaterPlaneProbe}'s valid flag, while each wall slot asks
     * {@code WallPlaneProbe}'s per-axis facing lane (0 means no wall found on that axis, the same
     * "0 means invalid" convention every enum lane in {@code u_Globals} uses).
     */
    private static boolean wantPlayerMirror(GeometrySlot slot) {
        boolean probeValid = switch (slot) {
            case PLAYER_MIRROR -> dev.icehunter.fornax.pipeline.WaterPlaneProbe.current().valid() != 0.0f;
            case PLAYER_MIRROR_X -> dev.icehunter.fornax.pipeline.WallPlaneProbe.current().xFacing() != 0.0f;
            case PLAYER_MIRROR_Z -> dev.icehunter.fornax.pipeline.WallPlaneProbe.current().zFacing() != 0.0f;
            default -> false;
        };
        return DeferredGeometryPipelines.wantsPlayerMirrorPhase(
                FornaxRenderState.isActive(),
                GraphRunner.packClaimsMirrorSlot(slot),
                GraphRunner.wantsPlayerMirrorConsumed(slot),
                probeValid);
    }
}
