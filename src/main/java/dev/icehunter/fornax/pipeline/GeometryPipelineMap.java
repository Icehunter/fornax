package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.icehunter.fornax.pack.GeometrySlot;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps vanilla's {@link RenderPipeline} constants onto the {@link GeometrySlot} a pack shades them
 * with. This is the Fornax equivalent of Iris's {@code IrisPipelines} table, and like it, the map is
 * keyed on pipeline <em>identity</em> -- vanilla's constants are singletons and {@code RenderPipeline}
 * has no value equality, so identity is both correct and cheap.
 *
 * <p><b>What may be mapped: pipelines that write depth, and only those.</b> A deferred slot writes the
 * G-buffer, and the resolve reconstructs each pixel's world position from the depth buffer. Geometry
 * that does not write depth leaves no pixel to reconstruct from, so its G-buffer writes would be lit
 * using whatever surface lies behind it -- wrong position, wrong normal, wrong shadow lookup, in a way
 * that reads as a subtle shading error rather than an obvious failure. Every vanilla pipeline's depth
 * state was measured rather than assumed, and {@code DeferredSlotDepthContractTest} enforces the rule
 * so a future addition cannot quietly break it.
 *
 * <p>Blending is a softer constraint. A deferred variant drops the blend function outright, since
 * {@code RenderPipeline.build()} requires all colour targets to share one and a blended G-buffer would
 * average normals and material IDs into meaningless values. That is right for geometry that is
 * effectively cutout despite being declared translucent -- player skins, capes, armour -- and wrong for
 * genuinely see-through geometry, which wants a forward pass after the resolve instead. Mapping such a
 * slot is therefore allowed but is a decision the pack makes by claiming it.
 *
 * <p><b>Deliberately unmappable slots.</b> {@code spider_eyes}, {@code damaged_block},
 * {@code armor_glint} and the translucent beacon beam all read depth without writing it, by design --
 * they are overlays on geometry that has already been drawn. {@code sky_basic} and {@code sky_textured}
 * go further and carry no depth state at all. Sky in a deferred renderer is not geometry: it is every
 * pixel the resolve finds at the far plane, which is where a pack should shade it. Those slots stay in
 * {@link GeometrySlot} because the pack format names them, but nothing here will ever map to them.
 *
 * <p><b>Why this cannot be a phase-dependent lookup.</b> Iris resolves ambiguous pipelines (is this
 * {@code ENTITY_CUTOUT} draw an entity, a block entity, or the player's hand?) by consulting a
 * "current phase" global at draw time. That does not port to 26.2: rendering is submit/prepare/execute,
 * so the phase live when geometry is *submitted* is long gone by the time it is *drawn*. Worse, the
 * backend pipeline caches are {@code IdentityHashMap}s filled via {@code computeIfAbsent}, so a
 * phase-dependent substitution would be pinned by whatever the first lookup happened to return and
 * then silently served forever. This table is therefore a pure, stable function of the pipeline
 * alone; per-draw context (entity vs block entity vs hand) has to ride on the submit node instead,
 * which is a separate piece of work from this mapping.
 */
public final class GeometryPipelineMap {
    private static final Map<RenderPipeline, GeometrySlot> BY_PIPELINE = new IdentityHashMap<>();

    static {
        // --- Entities -------------------------------------------------------------------------
        // Opaque/cutout entity geometry: the deferred-friendly arm. All share vanilla's core/entity
        // shader and the ENTITY vertex format, which is why one slot covers them.
        put(RenderPipelines.ENTITY_SOLID, GeometrySlot.ENTITIES);
        put(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, GeometrySlot.ENTITIES);
        put(RenderPipelines.ENTITY_CUTOUT, GeometrySlot.ENTITIES);
        put(RenderPipelines.ENTITY_CUTOUT_CULL, GeometrySlot.ENTITIES);
        put(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, GeometrySlot.ENTITIES);
        put(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, GeometrySlot.ENTITIES);

        // Armour rides the same core/entity shader and vertex format as the body it is worn on.
        // ARMOR_DECAL_CUTOUT_NO_CULL is deliberately NOT mapped: it depth-tests EQUAL against the
        // base pass, so it only lines up if the base wrote byte-identical depth. Substituting one
        // and not the other, or changing either's depth output, makes decals z-fight or vanish.
        put(RenderPipelines.ARMOR_CUTOUT_NO_CULL, GeometrySlot.ENTITIES);

        // --- Entities, translucent ------------------------------------------------------------
        // A separate slot because these blend and the deferred variant drops that blend, which a pack
        // must opt into rather than inherit. It is the right trade here: a player's skin renders on
        // ENTITY_TRANSLUCENT but is effectively cutout, and leaving the slot unclaimed keeps the player
        // out of the G-buffer entirely -- no motion vectors, no deferred lighting, no cast shadow.
        // ENTITY_TRANSLUCENT_EMISSIVE is NOT here: it does not write depth.
        put(RenderPipelines.ENTITY_TRANSLUCENT, GeometrySlot.ENTITIES_TRANSLUCENT);
        put(RenderPipelines.ENTITY_TRANSLUCENT_CULL, GeometrySlot.ENTITIES_TRANSLUCENT);
        put(RenderPipelines.ARMOR_TRANSLUCENT, GeometrySlot.ENTITIES_TRANSLUCENT);

        // --- Items ------------------------------------------------------------------------------
        // Dropped items, item frames, and the item in the player's hand -- world geometry like any
        // other, and the last category left unmapped. Leaving them out did not merely render them
        // unshaded, it made them INVISIBLE for any pack claiming a non-terrain slot:
        //
        //   * an unmapped pipeline falls through to vanilla and draws straight to the screen;
        //   * claiming any non-terrain slot defers the graph until after vanilla's solid features
        //     (GraphRunner.deferGraphUntilAfterSolidFeatures -- entity draws land later in the frame,
        //     so resolving at the end of the terrain layer would consume a G-buffer they had not
        //     written to yet);
        //   * so the item drew to the screen and the deferred graph's tonemap then painted over it.
        //
        // It survived only where the pack's tonemap discards, which for a pack that does not own the
        // sky is the sky itself -- a held torch that rendered only against sky and nowhere else. The
        // player's ARM was never affected, because it draws on ENTITY_TRANSLUCENT in a later phase,
        // after the graph. Measured: the post-graph draw census showed entity_translucent at ~1/frame
        // and item_cutout entirely absent.
        //
        // Mapping them fixes the ordering by construction -- they write the G-buffer with everything
        // else and are resolved rather than overpainted -- and gains them deferred lighting, motion
        // vectors and shadow casting on the way.
        //
        // REQUIRES the GUI guard. These same pipelines draw item icons in the inventory via
        // GuiItemAtlas, into 32x32 atlas slots, and redirecting those at the G-buffer crashes on a
        // scissor-out-of-bounds. See DeferredGeometryPipelines.guiPhase; that guard is why this
        // mapping is safe, and is almost certainly what was missing when items were first skipped.
        put(RenderPipelines.ITEM_CUTOUT, GeometrySlot.ENTITIES);
        // Blends, so it goes with the other translucents for the same reason ENTITY_TRANSLUCENT does:
        // the deferred variant drops the blend, and a pack must opt into that rather than inherit it.
        put(RenderPipelines.ITEM_TRANSLUCENT, GeometrySlot.ENTITIES_TRANSLUCENT);

        // --- Block entities -------------------------------------------------------------------
        // Chests, signs, banners, beds -- world geometry Sodium does not own, and the largest visual
        // gap left by mapping terrain and entities alone.
        put(RenderPipelines.SOLID_BLOCK, GeometrySlot.BLOCK_ENTITIES);
        put(RenderPipelines.CUTOUT_BLOCK, GeometrySlot.BLOCK_ENTITIES);
        put(RenderPipelines.TRANSLUCENT_BLOCK, GeometrySlot.BLOCK_ENTITIES_TRANSLUCENT);

        // --- Particles ------------------------------------------------------------------------
        // NOT REACHED FROM THIS FILE'S USUAL CALLER. Particles never pass through
        // PreparedRenderType.drawFromBuffer at all: QuadParticleFeatureRenderer.executeGroup builds
        // its own render pass off the CommandEncoder and its private static drawLayers calls
        // setPipeline and drawIndexed directly. These two lines were therefore dead for their whole
        // life, and a pack claiming 'particles' got nothing -- the same bypass the Plague pack's
        // graph.toml records for weather. QuadParticleDeferredMixin is the second hook that makes
        // them live; it consults this map exactly as the chokepoint does.
        //
        // Both arms write depth. OPAQUE_PARTICLE is PARTICLE_SNIPPET with no colour-target state of
        // its own, so it carries no blend for the deferred variant to drop -- the caveat that makes
        // claiming translucent entities a pack decision simply does not arise for it.
        //
        // TRANSLUCENT_PARTICLE USED TO BE MAPPED HERE AND HAS MOVED TO ForwardPipelineMap. It had to:
        // the two tables are disjoint by construction (ForwardPipelineMap.put throws on an overlap),
        // and the translucent arm now takes the FORWARD route so that campfire smoke gets fog without
        // losing its blend.
        //
        // The reason originally given for keeping it here was CHECKED before removing it, and it was
        // wrong. It said an unmapped pipeline inside a deferred group would bind a one-target pipeline
        // into a five-attachment pass. It cannot: QuadParticleDeferredMixin's head gate walks every
        // layer and returns "no deferral" the moment slotOf() is null, so an unmapped layer makes the
        // WHOLE GROUP stay vanilla -- no pass is rewritten and no variant is bound. The mixed-group
        // case is therefore strictly safer after the move than before it, and
        // ParticleGroupDeferralContractTest asserts that rather than this paragraph.
        put(RenderPipelines.OPAQUE_PARTICLE, GeometrySlot.PARTICLES);

        // --- Weather --------------------------------------------------------------------------
        // Only the depth-writing arm. WEATHER_NO_DEPTH_WRITE is the same rain and snow drawn without
        // depth, and deferring it would light each streak using the world behind it.
        put(RenderPipelines.WEATHER_DEPTH_WRITE, GeometrySlot.WEATHER);

        // --- Beacon beams ---------------------------------------------------------------------
        // The opaque core only; BEACON_BEAM_TRANSLUCENT is the surrounding glow and writes no depth.
        put(RenderPipelines.BEACON_BEAM_OPAQUE, GeometrySlot.BEACON_BEAM);

        // --- Lightning ------------------------------------------------------------------------
        put(RenderPipelines.LIGHTNING, GeometrySlot.LIGHTNING);

        // --- Clouds ---------------------------------------------------------------------------
        // Both cloud pipelines bind no vertex format -- geometry is generated in the shader -- which
        // the deferred builder handles, since it copies whatever bindings the base declares.
        put(RenderPipelines.CLOUDS, GeometrySlot.CLOUDS);
        put(RenderPipelines.FLAT_CLOUDS, GeometrySlot.CLOUDS);

        // --- Lines ----------------------------------------------------------------------------
        // Block-outline and debug geometry. LINES_TRANSLUCENT is excluded: no depth write.
        put(RenderPipelines.LINES, GeometrySlot.LINES);
        put(RenderPipelines.LINES_DEPTH_BIAS, GeometrySlot.LINES);
    }

    private GeometryPipelineMap() {}

    private static void put(RenderPipeline pipeline, GeometrySlot slot) {
        GeometrySlot previous = BY_PIPELINE.put(pipeline, slot);
        if (previous != null && previous != slot) {
            // A constant mapped twice to different slots is an authoring mistake in this file, and
            // would otherwise resolve to whichever line happened to run last.
            throw new IllegalStateException("Fornax: render pipeline " + pipeline.getLocation()
                    + " mapped to both " + previous + " and " + slot);
        }
    }

    /**
     * The slot a pack would shade this pipeline with, or {@code null} if Fornax does not claim it --
     * which is the common case and means "draw exactly as vanilla would".
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
