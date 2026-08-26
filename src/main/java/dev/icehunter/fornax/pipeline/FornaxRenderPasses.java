package dev.icehunter.fornax.pipeline;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/**
 * External (non-Sodium-internal) equivalent of an {@code isDeferred()} predicate for {@code
 * TerrainRenderPass}, plus Fornax's own additional shadow-depth passes. The real {@code
 * TerrainRenderPass} has only {@code isTranslucent()}/{@code supportsFragmentDiscard()} -- no {@code
 * isDeferred()}/{@code isShadow()} of its own, and Sodium's {@code DefaultTerrainRenderPasses} only
 * ever constructs SOLID/CUTOUT/TRANSLUCENT. Rather than mixing new methods into {@code
 * TerrainRenderPass} itself, every consumer that needs either predicate calls the static helpers
 * below instead.
 *
 * <p>Four-way classification every terrain draw falls into, in the order every consumer checks
 * them:
 * <ul>
 *   <li><b>Shadow (depth-only)</b>: {@link #SHADOW} or {@link #SHADOW_CUTOUT} (see {@link
 *   #isShadow}). Neither is one of Sodium's three registered passes -- they exist so the terrain
 *   mixins can key a depth-only render target/pipeline variant off them the same way they key
 *   deferred/translucent off SOLID/CUTOUT/TRANSLUCENT. TWO distinct instances are required, not
 *   one, purely because of how Sodium selects PER-SECTION GEOMETRY for a draw -- see {@link #SHADOW}
 *   and {@link #sourceGeometryPass}'s javadoc for the decompile evidence.</li>
 *   <li><b>Water pre-pass (1-color+depth)</b>: {@link #WATER_PREPASS} (see {@link
 *   #isWaterPrepass}). Also not one of Sodium's three registered passes; also non-translucent (so it
 *   routes like SOLID/CUTOUT for pipeline selection) but excluded from {@link #isDeferred} the same
 *   way the shadow passes are, and its geometry is likewise redirected -- to {@code
 *   DefaultTerrainRenderPasses.TRANSLUCENT} rather than SOLID/CUTOUT, since water only ever exists in
 *   the translucent chunk list. See {@link #WATER_PREPASS}'s own javadoc.</li>
 *   <li><b>Deferred (G-buffer/MRT)</b>: {@link #isDeferred}, true for the non-translucent,
 *   non-shadow, non-water-prepass passes -- SOLID and CUTOUT, the only two {@code TerrainRenderPass}
 *   instances left once shadow, water pre-pass, and translucent are excluded.</li>
 *   <li><b>Translucent (forward)</b>: {@code pass.isTranslucent()}, unchanged from upstream --
 *   TRANSLUCENT alone, drawn through the single-attachment path exactly as official Sodium does.
 *   </li>
 * </ul>
 */
public final class FornaxRenderPasses {
    /**
     * Fornax's own depth-only shadow-caster pass for SOLID-equivalent geometry, constructed with the
     * same {@code TerrainRenderPass} constructor and {@code isTranslucent}/{@code fragmentDiscard}
     * argument values SOLID uses ({@code new TerrainRenderPass(ChunkSectionLayer.SOLID, false,
     * false)} -- see {@code DefaultTerrainRenderPasses}'s {@code <clinit>}). Reusing {@code
     * ChunkSectionLayer.SOLID} as the {@code renderType} argument is deliberate, not a placeholder:
     * vanilla's {@code ChunkSectionLayer} enum has exactly three values (SOLID/CUTOUT/TRANSLUCENT,
     * it cannot be extended), and {@code renderType} only ever feeds {@code
     * TerrainRenderPass.getPipeline()} (used solely to derive a debug/registry label string) and
     * {@code getTarget()} (irrelevant here -- {@code
     * DefaultChunkRendererRenderPassMixin}'s shadow branch never reads the color/depth views {@code
     * getTarget()} would have supplied, since it builds its own {@code RenderPassDescriptor} against
     * {@link dev.icehunter.fornax.pass.shadow.ShadowMapManager#getView()} instead). {@code SOLID}'s
     * {@code false, false} makes {@link #SHADOW} non-translucent, same as SOLID/CUTOUT.
     *
     * <p>{@code SHADOW}/{@code SHADOW_CUTOUT} are deliberately their OWN {@code TerrainRenderPass}
     * instances (not reuses of {@code DefaultTerrainRenderPasses.SOLID}/{@code CUTOUT}): {@code
     * ShaderChunkRenderer}'s pipeline cache ({@code programs}, a {@code TerrainRenderPass ->
     * RenderPipeline} map with no {@code equals()}/{@code hashCode()} override on the key type, i.e.
     * identity-keyed) needs its own pipeline entry per shadow variant, and {@code RenderRegion}'s
     * per-pass {@code cachedBatches} map (also identity-keyed) needs its own {@code MultiDrawBatch}
     * cache slot per shadow variant too -- reusing SOLID's/CUTOUT's own identity for the shadow draw
     * would collide with (and silently reuse stale, main-camera-face-culled) their own cached batch.
     *
     * <p><b>Decompile evidence a single shared shadow instance cannot work at all (Sodium
     * mc26.2-0.9.0, bf93ed83):</b> {@code RenderRegionManager}'s per-section mesh upload loop (the
     * only code that ever calls {@code RenderRegion.createStorage(TerrainRenderPass)}, populating
     * the {@code Map<TerrainRenderPass, SectionRenderDataStorage>} {@code
     * DefaultChunkRenderer.render} later reads via {@code RenderRegion.getStorage}) iterates ONLY
     * {@code DefaultTerrainRenderPasses.ALL} ({@code {SOLID, CUTOUT, TRANSLUCENT}}) -- there is no
     * extension point for a fourth, engine-added {@code TerrainRenderPass} to ever receive its own
     * storage. Geometry selection is therefore NOT keyed off {@code TerrainRenderPass.renderType}
     * (a {@code @Deprecated(forRemoval=true)}, effectively unused field -- its only read is {@code
     * TerrainRenderPass.getPipeline()}, a debug-label lookup) but off the {@code TerrainRenderPass}
     * object's OWN IDENTITY as a map key ({@code sectionRenderData}/{@code cachedBatches} are both
     * {@code Reference2ReferenceOpenHashMap}, i.e. identity-hashed). A {@code
     * region.getStorage(FornaxRenderPasses.SHADOW)} call therefore ALWAYS returns {@code null} for
     * every region (nothing ever populated that map entry), and {@code DefaultChunkRenderer.render}
     * treats {@code null} storage as "this region has nothing to draw for this pass" ({@code if
     * (storage == null) continue;}) -- i.e. a naive shadow draw against {@link #SHADOW} alone would
     * silently rasterize ZERO geometry, forever, with no error. {@code
     * DefaultChunkRendererGeometryStorageMixin} closes this gap by redirecting {@code
     * RenderRegion.getStorage} calls for a shadow-pass identity to the already-built vanilla pass's
     * storage (see {@link #sourceGeometryPass}) -- the pipeline/render-pass/shader routing stays
     * keyed on {@link #SHADOW}/{@link #SHADOW_CUTOUT}'s own identity (via {@link #isShadow}), only
     * the GEOMETRY lookup is redirected.
     */
    public static final TerrainRenderPass SHADOW =
            new TerrainRenderPass(ChunkSectionLayer.SOLID, false, false);

    /**
     * Fornax's own depth-only shadow-caster pass for CUTOUT-equivalent geometry (leaves, glass,
     * foliage -- anything with alpha-tested holes), constructed with the same constructor argument
     * values {@code DefaultTerrainRenderPasses.CUTOUT} uses ({@code new
     * TerrainRenderPass(ChunkSectionLayer.CUTOUT, false, true)} -- {@code fragmentDiscard = true},
     * verified against the real {@code DefaultTerrainRenderPasses.<clinit>} decompile). Required as
     * its own instance, separate from {@link #SHADOW}, so CUTOUT geometry (leaf canopies, etc.)
     * actually casts shadows -- a spec requirement -- rather than being silently skipped: see {@link
     * #SHADOW}'s javadoc for why one shared shadow instance cannot draw both SOLID's and CUTOUT's
     * geometry (each needs its own {@code cachedBatches}/{@code getStorage} redirect target).
     * {@code shadow.fsh}'s own hard {@code alpha < 0.1} discard applies uniformly to whichever of
     * these two draws runs it, so no separate shader variant is needed for CUTOUT's alpha holes.
     */
    public static final TerrainRenderPass SHADOW_CUTOUT =
            new TerrainRenderPass(ChunkSectionLayer.CUTOUT, false, true);

    /**
     * Fornax's own water-surface pre-pass identity. MUST be constructed translucent
     * (isTranslucent=true): {@code DefaultChunkRenderer.render}'s {@code useIndexedTessellation =
     * renderPass.isTranslucent() && indexedRenderingEnabled} decides which INDEX BUFFER the stored
     * per-section element offsets are read against. Translucent sections' offsets are only valid
     * against the per-region dynamically-sorted local index arena; a non-translucent identity made
     * the renderer read those same offsets against its unrelated {@code SharedQuadIndexBuffer},
     * decoding garbage vertex indices and producing sparse wedge-shaped partial water
     * coverage (most of a lake missing from waterNormal/waterDepth).
     * Pipeline/color-target/render-pass shape is unaffected by the flag: every lockstep mixin
     * (constants, CTS, attachments) special-cases this pass by IDENTITY ({@link #isWaterPrepass})
     * before any {@code isTranslucent()} branch. fragmentDiscard=true because the pre-pass shader
     * discards every non-water translucent fragment. Its geometry is redirected to
     * DefaultTerrainRenderPasses.TRANSLUCENT (see sourceGeometryPass), exactly the mechanism SHADOW
     * uses to draw already-built geometry it never gets its own storage for. One CTS / one color
     * attachment (waterNormal) + one depth attachment (waterDepth) -- the minimum LOCKSTEP
     * footprint, structurally identical to SHADOW's 1-color+depth shape.
     */
    public static final TerrainRenderPass WATER_PREPASS =
            new TerrainRenderPass(ChunkSectionLayer.TRANSLUCENT, true, true);

    public static boolean isWaterPrepass(TerrainRenderPass pass) {
        return pass == WATER_PREPASS;
    }

    private FornaxRenderPasses() {
    }

    /**
     * True for {@link #SHADOW} or {@link #SHADOW_CUTOUT} (identity check -- {@code TerrainRenderPass}
     * has no {@code equals()} override, so {@code ==} and {@code .equals()} agree here anyway;
     * identity is used to match the pipeline cache's own keying semantics documented on {@link
     * #SHADOW}). Every terrain mixin gated on this predicate (shader constants, color-target-state,
     * render-pass descriptor) therefore automatically treats both shadow variants identically --
     * same shader, same single-default-color-target pipeline, same depth-primary render pass (plus
     * its one real, unread dummy color attachment -- see {@code ShadowMapManager}'s javadoc for why
     * a true zero-color-attachment pipeline/pass is not achievable against this Blaze3D version) --
     * with no changes needed in those mixins beyond this method covering both identities.
     */
    public static boolean isShadow(TerrainRenderPass pass) {
        return pass == SHADOW || pass == SHADOW_CUTOUT;
    }

    /**
     * The already-built vanilla {@code TerrainRenderPass} whose {@code SectionRenderDataStorage} a
     * shadow-pass draw should source real per-section geometry from -- {@code
     * DefaultTerrainRenderPasses.SOLID} for {@link #SHADOW}, {@code
     * DefaultTerrainRenderPasses.CUTOUT} for {@link #SHADOW_CUTOUT}, or {@code pass} itself
     * unchanged for every non-shadow pass (identity passthrough, so this method is safe to call
     * unconditionally). Consumed exclusively by {@code DefaultChunkRendererGeometryStorageMixin} --
     * see {@link #SHADOW}'s javadoc for why this redirect exists and the decompile evidence that
     * motivates it.
     */
    public static TerrainRenderPass sourceGeometryPass(TerrainRenderPass pass) {
        if (pass == SHADOW) {
            return DefaultTerrainRenderPasses.SOLID;
        }
        if (pass == SHADOW_CUTOUT) {
            return DefaultTerrainRenderPasses.CUTOUT;
        }
        if (pass == WATER_PREPASS) {
            return DefaultTerrainRenderPasses.TRANSLUCENT;
        }
        return pass;
    }

    /**
     * Deferred (G-buffer/MRT) terrain passes are the non-translucent, non-shadow ones -- SOLID and
     * CUTOUT, the only two {@code TerrainRenderPass} instances left once {@link #isShadow} and
     * TRANSLUCENT are excluded. {@link #SHADOW}/{@link #SHADOW_CUTOUT} are non-translucent too
     * ({@code isTranslucent() == false}, see {@link #SHADOW}'s javadoc), so they have to be excluded
     * explicitly here or they would misroute into the 5-attachment G-buffer path instead of the
     * depth-only one.
     */
    public static boolean isDeferred(TerrainRenderPass pass) {
        return !pass.isTranslucent() && !isShadow(pass) && !isWaterPrepass(pass);
    }
}
