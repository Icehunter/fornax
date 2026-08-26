package dev.icehunter.fornax.pack;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The geometry program slots a pack may declare, one per kind of geometry the engine can route to a
 * pack-supplied program. A {@code [[pass]]} of {@code type = "geometry"} names one via its {@code
 * slot} key; the pass's {@code program} then supplies that slot's {@code .vsh}/{@code .fsh} pair.
 *
 * <p>Only {@link #TERRAIN} is wired to actual rendering today -- it is Sodium's chunk geometry, the
 * one thing Fornax has ever drawn through a pack. Every other constant is declared but inert: a pack
 * may name it, the loader will validate and resolve its program, and nothing will draw through it
 * until the render-layer interception that fills it in lands. They are enumerated now rather than
 * added one at a time so the vocabulary (and therefore every pack's {@code graph.toml}) is stable
 * before packs start depending on it.
 *
 * <p>Naming follows the geometry being drawn rather than the vanilla {@code RenderType} that happens
 * to carry it, because that mapping is many-to-one and drifts between Minecraft versions -- the
 * mapping table belongs with the interception code, not in this vocabulary.
 */
public enum GeometrySlot {
    /**
     * Sodium chunk geometry: the SOLID/CUTOUT deferred sub-draws and the TRANSLUCENT forward one,
     * all sharing a single compiled program. The only slot that renders today, and the only one
     * whose program path is also reachable through the engine's hardcoded fallback.
     */
    TERRAIN("terrain"),

    /** Living entities, item frames, and other non-block-entity world geometry. */
    ENTITIES("entities"),
    /** Entities under the glowing effect, drawn to the outline buffer. */
    ENTITIES_GLOWING("entities_glowing"),
    /** Entity geometry on a translucent render type. */
    ENTITIES_TRANSLUCENT("entities_translucent"),
    /** Block entities (chests, signs, beds) -- vanilla's block-entity renderer path. */
    BLOCK_ENTITIES("block_entities"),
    /** Block entities on a translucent render type. */
    BLOCK_ENTITIES_TRANSLUCENT("block_entities_translucent"),

    /** First-person held item and arm geometry, solid pass. */
    HAND("hand"),
    /** First-person held item and arm geometry, translucent pass. */
    HAND_TRANSLUCENT("hand_translucent"),

    /** Particles -- vanilla's textured/lit particle render types, SOLID arm only. */
    PARTICLES("particles"),
    /**
     * The TRANSLUCENT particle arm -- a FORWARD slot (see {@link #rendersForward()}), and the second
     * one after {@link #BANNER_PATTERNS}.
     *
     * <p>Separate from {@link #PARTICLES} because the two arms are different draws with opposite
     * rules, not two programs for one draw. Vanilla submits particles twice: a solid arm during
     * {@code executeSolid}, which the graph resolves at the RETURN of, and a translucent arm during
     * {@code executeTranslucentAfterTerrain}, which lands after that resolve. So the solid arm can be
     * deferred (and must be, or the tonemap paints over it) while the translucent arm can never be --
     * deferring it writes a G-buffer nothing will read. Independently, {@code TRANSLUCENT_PARTICLE}
     * carries {@code BlendFunction.TRANSLUCENT}, and a deferred variant drops the blend, turning
     * high-resolution partial-alpha smoke into solid flashing rectangles under TAAU jitter.
     *
     * <p>Campfire smoke, torch flame and smoke, souls, spells and sculk all ride this arm; deferring
     * it would draw them at full vividness in front of terrain that has been hazed to the sky colour.
     */
    PARTICLES_TRANSLUCENT("particles_translucent"),
    /** Rain and snow geometry. */
    WEATHER("weather"),

    /** The untextured sky dome, horizon and void plane. */
    SKY_BASIC("sky_basic"),
    /** Textured celestial geometry: sun, moon, and custom skyboxes. */
    SKY_TEXTURED("sky_textured"),
    /** Vanilla's cloud layer, when the pack has not cancelled it in favour of its own. */
    CLOUDS("clouds"),

    /** Beacon beams. */
    BEACON_BEAM("beacon_beam"),
    /** Lightning bolts. */
    LIGHTNING("lightning"),
    /** The block-breaking crack overlay. */
    DAMAGED_BLOCK("damaged_block"),
    /** Enchantment glint overlay geometry. */
    ARMOR_GLINT("armor_glint"),
    /** Eye-layer geometry drawn additively over entities (spider eyes, enderman eyes). */
    SPIDER_EYES("spider_eyes"),
    /** Untextured line and quad geometry: selection outline, hitboxes, debug rendering. */
    LINES("lines"),

    /**
     * The pattern layers painted over a banner's cloth -- the first FORWARD slot (see
     * {@link #rendersForward()}).
     *
     * <p>Separate from {@link #BLOCK_ENTITIES} because it is a different draw with different rules,
     * not a different program for the same one. A banner's post and cloth are {@code ENTITY_SOLID}
     * and are deferred and shaded like any other entity; its patterns draw on {@code BANNER_PATTERN},
     * which is built with {@code depthWrite = false} and is therefore structurally unmappable under
     * {@link dev.icehunter.fornax.pipeline.GeometryPipelineMap}'s "only pipelines that write depth"
     * rule -- and which draws AFTER the graph has resolved, so deferring it would make it invisible
     * even if the depth rule allowed it. A correctly-fogged pole carrying vivid unfogged pattern
     * layers is what that combination looks like from outside.
     */
    BANNER_PATTERNS("banner_patterns"),

    /**
     * Terrain rendered from the sun/moon's view into the shadow map. Distinct from {@link #TERRAIN}
     * because it is the same geometry drawn through a different projection into different
     * attachments, and a pack wants to shade it far more cheaply (depth, and at most an alpha test
     * for cutouts) than the main camera pass.
     *
     * <p>Reserved ahead of the shadow pass becoming pack-overridable: today the engine owns
     * {@code fornax:blocks/shadow} outright and this slot draws nothing.
     */
    SHADOW("shadow"),
    /** Entity geometry in the shadow pass, so entities can cast shadows. */
    SHADOW_ENTITIES("shadow_entities");

    /** The default slot for a geometry pass that omits {@code slot}. */
    public static final GeometrySlot DEFAULT = TERRAIN;

    private static final Map<String, GeometrySlot> BY_TOKEN = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(GeometrySlot::token, Function.identity()));

    private final String token;

    GeometrySlot(String token) {
        this.token = token;
    }

    /** The lowercase name used in {@code graph.toml}'s {@code slot} key. */
    public String token() {
        return token;
    }

    /**
     * Whether geometry actually routes through this slot today, as opposed to being reserved.
     *
     * <p>This answers "does declaring this slot do anything", the question a pack author asks
     * before spending a round on a pass. Nothing in production reads it, so getting it wrong here
     * costs nothing directly to the engine -- but it is exactly how an inert pass ships unnoticed,
     * as the {@link #WEATHER} case below shows.
     *
     * <p>"Renders" means A HOOK ROUTES TO IT, not "a pipeline maps to it", and the two differ in
     * exactly one place that matters:
     *
     * <ul>
     *   <li>{@link #WEATHER} IS mapped in {@code GeometryPipelineMap} and still does not render.
     *       {@code WeatherEffectRenderer.render} builds its own buffer and render pass and calls
     *       {@code setPipeline}/{@code drawIndexed} directly, so it never reaches
     *       {@code PreparedRenderType.drawFromBuffer} -- the only place that map is consulted.
     *   <li>{@link #SHADOW} is claimable in the pack format but the engine owns
     *       {@code fornax:blocks/shadow} outright; nothing routes to it.
     *   <li>{@link #PARTICLES} renders only because a SECOND hook ({@code QuadParticleDeferredMixin})
     *       exists for it, and only its solid arm -- the same bypass weather has, closed rather than
     *       inherited.
     *   <li>{@link #BANNER_PATTERNS} renders through a THIRD route, the forward branch of the
     *       chokepoint. See {@link #rendersForward()}.
     *   <li>{@link #PARTICLES_TRANSLUCENT} is BOTH of those at once -- the forward mechanism,
     *       delivered through the particle hook rather than the chokepoint, because the translucent
     *       arm bypasses the chokepoint exactly as the solid arm does.
     * </ul>
     *
     * <p>Kept as an explicit list rather than derived from {@code GeometryPipelineMap.isMapped}
     * precisely because that derivation would say weather renders.
     */
    public boolean isRendered() {
        return switch (this) {
            case TERRAIN, ENTITIES, ENTITIES_TRANSLUCENT, BLOCK_ENTITIES, BLOCK_ENTITIES_TRANSLUCENT,
                 PARTICLES, PARTICLES_TRANSLUCENT, BEACON_BEAM, LIGHTNING, CLOUDS, LINES,
                 SHADOW_ENTITIES, BANNER_PATTERNS -> true;
            default -> false;
        };
    }

    /**
     * Whether this slot's geometry is drawn FORWARD -- into vanilla's own target, with vanilla's own
     * blend, in vanilla's own place in the frame -- rather than deferred into the G-buffer.
     *
     * <p><b>This is an ENGINE FACT, not a pack choice, and that is why it is a predicate here rather
     * than a key in {@code graph.toml}.</b> Whether a draw can be deferred is decided by three things
     * the pack has no say in: when in the frame vanilla issues it, whether it blends, and whether it
     * writes depth. A {@code type = "forward"} key would let a pack assert something false about
     * vanilla's own render pipeline, and the engine would then have to either ignore it or break. The
     * codebase settled this same argument for shadow casting -- see {@link #castsShadow()}, which is
     * a property of the slot for the identical reason -- and this follows it deliberately.
     *
     * <p>The consequences of being forward, all of which the draw site depends on:
     *
     * <ul>
     *   <li>The render pass is NOT rewritten. Vanilla's single colour target and its depth attachment
     *       are kept exactly as issued, so blend, ordering and depth test are preserved by
     *       construction rather than reproduced.
     *   <li>The pipeline IS substituted, but its colour target states are copied verbatim from the
     *       base rather than replaced with the five G-buffer formats.
     *   <li>The fragment program composites into an ALREADY-TONEMAPPED, display-referred target. Its
     *       output is a screen colour, not scene light. A pack program that writes linear HDR here is
     *       not slightly wrong, it is in the wrong space.
     *   <li>It must NOT cast a shadow, and must NOT be in {@code GeometryPipelineMap}. Both are
     *       asserted by tests rather than left to this comment.
     * </ul>
     *
     * <p>Being forward says nothing about WHICH hook delivers the substitution.
     * {@link #BANNER_PATTERNS} arrives through the draw chokepoint; {@link #PARTICLES_TRANSLUCENT}
     * arrives through {@code QuadParticleDeferredMixin}, because particles reach no chokepoint at all.
     * Everything in the list above is true of both, which is the point of keeping this a property of
     * the slot rather than of the hook.
     */
    public boolean rendersForward() {
        return this == BANNER_PATTERNS || this == PARTICLES_TRANSLUCENT;
    }

    /**
     * Whether geometry in this slot should be replayed into the shadow map.
     *
     * <p>Solid things that sit in the world cast; atmosphere and UI do not. Rain, snow, clouds,
     * particles, lightning, beacon beams and block outlines are all either volumetric, transient or
     * screen furniture, and replaying them produces a shadow map full of streaks and billboards that
     * darkens the whole scene through geometry the eye never reads as solid.
     *
     * <p>This is a property of the slot rather than of the pipeline, and it must stay that way:
     * deriving cast-shadow from "this pipeline is mapped at all" would silently enlist every slot
     * added to {@link dev.icehunter.fornax.pipeline.GeometryPipelineMap} as a shadow caster --
     * costing a shadow-map replay and a compiled pipeline variant per slot, and corrupting the
     * shadow map with clouds and weather.
     */
    public boolean castsShadow() {
        return switch (this) {
            case TERRAIN, ENTITIES, ENTITIES_TRANSLUCENT, BLOCK_ENTITIES, BLOCK_ENTITIES_TRANSLUCENT,
                 SHADOW, SHADOW_ENTITIES -> true;
            default -> false;
        };
    }

    /**
     * Resolves a {@code slot} token, or throws {@link FornaxPackError} naming the offending pass and
     * listing the accepted tokens -- the loader is strict everywhere else, and a typo'd slot would
     * otherwise silently produce a geometry pass that never draws.
     */
    public static GeometrySlot parse(String token, String passName, String file) {
        GeometrySlot slot = BY_TOKEN.get(token.toLowerCase(Locale.ROOT));
        if (slot == null) {
            throw new FornaxPackError(file, "pass." + passName + ".slot",
                    "unknown geometry slot '" + token + "' -- expected one of " + tokens());
        }
        return slot;
    }

    /** Every accepted token, in declaration order, for use in error messages. */
    public static String tokens() {
        return Stream.of(values()).map(GeometrySlot::token).collect(Collectors.joining(", "));
    }
}
