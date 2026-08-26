package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.GeometrySlot;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Builds and caches deferred variants of vanilla {@link RenderPipeline}s: faithful clones that write
 * Fornax's five G-buffer attachments instead of a single colour target, so a pack program bound to a
 * geometry slot can participate in deferred shading the same way terrain does.
 *
 * <p>Clones rather than mutations. {@code RenderPipeline}'s fields are final and its constants are
 * shared singletons, so editing one in place would change it for every consumer, including vanilla
 * paths Fornax never claimed. Cloning also keeps the variants under a {@code fornax:} location, which
 * matters beyond tidiness: {@code VulkanRenderPipelineMixin} keys its push-constant handling on the
 * namespace, and a variant registered under {@code minecraft:} would silently miss it.
 *
 * <p>Every non-target property is copied verbatim from the base pipeline -- shader defines, bind group
 * layouts, vertex bindings, depth state, cull, polygon mode, topology. Anything that diverges from the
 * base is a mismatch between what vanilla submits and what the pipeline expects, and those fail as
 * corrupt geometry or validation errors rather than anything legible.
 */
public final class DeferredGeometryPipelines {
    /**
     * The G-buffer attachment formats, in layout order, matching {@code GBufferManager}'s own
     * attachments and the {@code layout(location = N) out} declarations a deferred terrain fragment
     * shader already writes. A slot's program writes the same five, so one G-buffer serves terrain and
     * every other geometry kind without a second resolve path.
     */
    /**
     * gMaterial MUST stay {@code RGBA8_UNORM}: it carries the LabPBR {@code _s} categorical bytes
     * unsigned-normalised 8-bit-per-channel, and a downstream pack shader recovers those codes by
     * scaling the sampled float back to an integer -- Plague's {@code brdf.glsl} does
     * {@code int f0Byte = int(f0Raw * 255.0 + 0.5);} and then {@code m.conductor = f0Byte >= 230;}
     * to read the 230-255 metal-index range. An sRGB or floating-point target would reach that same
     * shader code with a different byte-to-float mapping and silently hand back the wrong metal/
     * dielectric classification -- no error, just a materially wrong render. Plague cannot see this
     * declaration from its own repo, so the guarantee has to be held here; kept in sync with
     * {@link GBufferManager#ensureSize}'s texture creation by {@code GBufferFormatLockTest}, which
     * fails the build if the two declaration sites ever disagree.
     */
    private static final GpuFormat[] GBUFFER_FORMATS = {
            GpuFormat.RGBA16_SNORM, // gNormal:   world normal + face index
            GpuFormat.RGBA8_UNORM,  // gAlbedo:   albedo + sky light
            GpuFormat.RGBA8_UNORM,  // gMaterial: LabPBR _s + packed block light/emissive
            GpuFormat.RGBA8_UNORM,  // gAo:       AO in .r, pack-defined in .gba
            GpuFormat.RG16_FLOAT,   // gMotion:   screen-space motion delta
    };

    private static final Map<RenderPipeline, RenderPipeline> CACHE = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> SHADOW_CACHE = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> SHADOW_WORLD_SPACE_CACHE = new IdentityHashMap<>();

    /**
     * True while solid features are being re-executed into the shadow map rather than the G-buffer.
     *
     * <p>Entity geometry is submitted once per frame with the camera's transforms already folded into
     * each draw, and vanilla offers no way to re-submit it from another viewpoint. Rather than
     * duplicating the submit machinery, the same prepared draws are simply executed a second time
     * with this flag raised: the draw site then binds the shadow map instead of the G-buffer and
     * swaps in a pipeline whose vertex stage reprojects through the light's matrix. The vertex
     * buffers, textures and per-draw state are all reused exactly as they are.
     */
    /**
     * True while vanilla's GUI is rendering, so item draws made for the INVENTORY are never deferred.
     *
     * <p>Item pipelines are shared between the world and the GUI: the same {@code ITEM_CUTOUT} that
     * draws a dropped torch also draws its hotbar icon, via {@code GuiItemAtlas}, into a 32x32 atlas
     * slot. Redirecting those draws at the G-buffer crashes outright -- the GUI's own scissor is out
     * of bounds for the G-buffer's render area:
     *
     * <pre>Scissor at 0, 480 with size 32x32 is out of bounds for RenderArea[0, 0, 854, 480]</pre>
     *
     * <p>That shared use is almost certainly why the item pipelines were left unmapped in the first
     * place. This flag is what makes mapping them safe: world item draws defer, GUI item draws do not.
     *
     * <p>Deliberately NOT expressed as "has the graph finished this frame", which was tried and failed
     * twice -- a boolean cleared by {@code GraphRunner.prepare()} (which runs per terrain draw, not
     * per frame) and then a frame-counter comparison that the GUI still slipped past. Asking "are we
     * drawing the GUI" is answerable exactly, at the one call site that knows, instead of being
     * inferred from frame phase.
     */
    private static boolean guiPhase;

    /** See {@link #guiPhase}. */
    public static boolean isGuiPhase() {
        return guiPhase;
    }

    /** See {@link #guiPhase}. Set around vanilla's GUI render, in a finally. */
    public static void setGuiPhase(boolean value) {
        guiPhase = value;
    }

    private static boolean shadowPhase;

    /** Whether the current draw is part of the shadow-casting re-execution. */
    public static boolean isPlayerCastPhase() {
        return playerCastPhase;
    }

    public static boolean isShadowPhase() {
        return shadowPhase;
    }

    /** Raises or lowers the shadow-phase flag. Render thread only. */
    public static void setShadowPhase(boolean value) {
        shadowPhase = value;
        if (value && !shadowPhaseReported) {
            shadowPhaseReported = true;
            FornaxMod.LOGGER.info("[Fornax][diag] entity shadow-casting phase entered for the first time");
        }
    }

    private DeferredGeometryPipelines() {}

    /**
     * Whether a prepared particle group may be routed into the G-buffer instead of the colour target
     * vanilla opens for it. Pure, so the rule below can be exercised exhaustively rather than only
     * observed in a frame.
     *
     * <p><b>The translucent arm must never be deferred, and that is the whole reason this predicate
     * exists.</b> Vanilla submits particles twice: a solid arm during {@code executeSolid}, and a
     * translucent arm during {@code executeTranslucentAfterTerrain}. The graph resolves at the return
     * of {@code executeSolid}, so the solid arm draws BEFORE the resolve (deferring it is legal and
     * is what makes it visible at all) while the translucent arm draws AFTER it -- deferring that one
     * writes a G-buffer nothing will ever read, which is exactly how the player loses geometry.
     * Second, independently: {@code TRANSLUCENT_PARTICLE} blends and a deferred variant drops the
     * blend, which is the regression {@code SmokeParticleLayerMixin} was written to end. Either fact
     * alone is disqualifying.
     *
     * <p>{@code anyLayerTranslucent} is checked alongside {@code groupTranslucent} rather than instead
     * of it. The group flag is a summary taken from the first submit; the layer scan is what the group
     * actually holds. Requiring both to be false means a mixed group -- which vanilla's phase split
     * does not produce today, and which nothing here should depend on it never producing -- simply
     * stays vanilla.
     *
     * <p>{@code shadowPhase} excludes the shadow-casting replay of {@code executeSolid}. Particles do
     * not cast ({@code GeometrySlot.PARTICLES.castsShadow()} is false), so their draws during that
     * replay have nothing to contribute and re-entering the G-buffer would only rewrite what the real
     * pass already put there.
     *
     * <p>Deliberately excludes the "does a deferred variant exist for every layer's pipeline" test,
     * even though the caller also requires it: resolving a variant COMPILES a pipeline and logs, and
     * doing that for a group about to be rejected would build the very translucent variant this rule
     * exists to prevent.
     */
    public static boolean wantsDeferredParticleGroup(boolean groupTranslucent, boolean anyLayerTranslucent,
                                                     boolean packActive, boolean shadowPhase,
                                                     boolean gBufferPresent) {
        return !groupTranslucent && !anyLayerTranslucent && packActive && !shadowPhase && gBufferPresent;
    }

    /**
     * Whether the translucent particle arm should take the FORWARD route: vanilla's render pass,
     * vanilla's blend, vanilla's place in the frame, with only the PIPELINE substituted so the pack's
     * program can fog the sprite in place. The sibling of
     * {@link #wantsDeferredParticleGroup(boolean, boolean, boolean, boolean, boolean)}, and pure for
     * the same reason: the rule is swept exhaustively rather than observed in a frame.
     *
     * <p><b>Mutually exclusive with deferral by construction</b> -- that rule requires
     * {@code !groupTranslucent} and this one requires {@code groupTranslucent}, so no group can satisfy
     * both and the three-valued decision at the call site cannot be ambiguous. That is why this is a
     * second predicate rather than a widening of the first: the failure this project keeps paying for
     * is "which branch fired" becoming unattributable, and two disjoint rules over the same inputs stay
     * legible where one rule with a mode flag does not.
     *
     * <p><b>{@code allLayersTranslucent}, not {@code anyLayerTranslucent}.</b> The deferred rule refuses
     * a group holding ANY translucent layer; the mirror image of that is requiring EVERY layer here.
     * A mixed group -- which vanilla's phase split does not produce today, and which nothing should
     * depend on it never producing -- therefore satisfies neither rule and simply stays vanilla, in
     * both directions. The caller computes it over the group's real layer map rather than trusting
     * the group flag, which {@code prepareGroup} takes from the first submit only.
     *
     * <p><b>No G-buffer term, and that is the whole economy of the forward path.</b> A forward draw
     * writes vanilla's own colour target and reads no attachment Fornax owns, so a frame with no
     * G-buffer is not a reason to refuse it. Nor is a missing per-layer variant: the render pass is
     * left exactly as vanilla opened it, so a layer whose pipeline resolves to nothing falls back to
     * vanilla's own pipeline with no attachment mismatch possible. The deferred path can afford
     * neither fallback, which is why its gate is all-or-nothing over the whole group and this one is
     * not.
     *
     * <p><b>{@code separateParticlesTarget} is vanilla's own ternary, read rather than assumed.</b>
     * {@code executeGroup} draws a translucent group into {@code LevelRenderer.particlesTarget()} when
     * that is non-null and into {@code mainRenderTarget} otherwise -- bytecode 45-63 of
     * {@code executeGroup}, {@code particlesTarget != null && group.translucent}.
     *
     * <p><b>WHAT MAKES IT NON-NULL IS THE {@code improvedTransparency} OPTION, NOT "Fabulous".</b> 26.2
     * has no {@code graphicsMode} FABULOUS any more; the transparency post chain moved onto its own
     * boolean, surfaced as "Improved Transparency" in Video Settings and stored as
     * {@code improvedTransparency} in {@code options.txt}. The chain is
     * {@code Options.improvedTransparency()} -> {@code OptionsRenderState.improvedTransparency} ->
     * {@code GameRenderState.useShaderTransparency()} -> {@code LevelRenderer.getTransparencyChain()},
     * which is what allocates {@code LevelTargetBundle.particles}. Recorded at this length because the
     * previous wording said "Fabulous graphics", and a diagnosis was built on the reasoning "there is
     * no {@code graphicsMode} line in {@code options.txt}, so this input cannot be the cause" -- which
     * was true about {@code graphicsMode} and false about the frame.
     *
     * <p><b>The refusal is CONSERVATIVE, and its stated premise has since been measured and found
     * WRONG.</b> The premise was that the pack's display transform would be "applied against the wrong
     * background". It is not applied against a background at all:
     * {@code plagueCompositeLinearOverDisplay} is a pure function of the SPRITE's own display-referred
     * colour and the fog terms, and reads no destination texel. The particles target is
     * {@code RGBA8_UNORM} cleared to {@code (0,0,0,0)} -- the same 8-bit display space as {@code main},
     * not a linear HDR buffer -- and {@code post/transparency.fsh} composites it with
     * {@code dst*(1-src.a) + src.rgb}, a plain premultiplied "over" in that same space. So the fragment
     * this program writes, and the value that reaches the screen, are the same either way. Lifting the
     * term is therefore a live option; it is left in place pending the user's call rather than removed
     * on the strength of this paragraph, and the log now names the setting so the choice is theirs.
     */
    public static boolean wantsForwardParticleGroup(boolean groupTranslucent, boolean allLayersTranslucent,
                                                    boolean packActive, boolean shadowPhase,
                                                    boolean separateParticlesTarget) {
        return groupTranslucent && allLayersTranslucent && packActive && !shadowPhase && !separateParticlesTarget;
    }

    /**
     * The deferred variant of {@code base} for {@code slot}, built on first use and cached by base
     * pipeline identity thereafter. Returns {@code null} when the pack does not supply a fragment
     * program for the slot -- there is nothing to defer to, so the caller keeps vanilla's pipeline.
     *
     * <p>Cached on the <em>base</em> pipeline rather than rebuilt per draw because pipeline
     * construction compiles shaders; doing that per frame would be catastrophic. The cache is cleared
     * on pack change ({@link #invalidate()}), which is the only time the answer can differ.
     */
    @Nullable
    public static synchronized RenderPipeline deferredVariantOf(RenderPipeline base, GeometrySlot slot) {
        RenderPipeline cached = CACHE.get(base);
        if (cached != null) {
            return cached;
        }
        RenderPipeline variant = build(base, slot);
        // Only successes are cached. A failure here is usually TRANSIENT -- the commonest cause is a
        // draw landing before the pack's shader sources finish publishing, which happens on nearly
        // every fresh start. Caching that null pinned the pipeline as "unshaded" for the rest of the
        // session, so entities rendered only if you happened to switch packs after startup and never
        // if you launched straight into one. Retrying costs a map lookup and a source probe; getting
        // it wrong costs an entire category of geometry, silently.
        if (variant != null) {
            CACHE.put(base, variant);
        }
        return variant;
    }

    /**
     * The FORWARD variant of {@code base} for {@code slot}: the pack's program, drawn into vanilla's
     * own target with vanilla's own blend, depth state and write mask.
     *
     * <p>Built from {@link #build}'s body with ONE line changed -- {@code base.getColorTargetStates()}
     * is copied verbatim instead of the five G-buffer formats being synthesised. That is the whole
     * difference between deferred and forward on the pipeline side, and copying rather than rebuilding
     * is what makes it right: the blend function, the format and the write mask arrive together and
     * cannot be got individually wrong. A hand-written {@code ColorTargetState} would have to name
     * {@code BANNER_PATTERN}'s exact blend, which is a fact about vanilla that would silently rot.
     *
     * <p>Additionally declares {@code u_PackOptions} alongside {@code u_Globals}. A forward program
     * composites into a display-referred target and has to reproduce the pack's own display transform
     * to do it -- exposure, tonemap and grade are all runtime options, so without this block the
     * program cannot land in the frame's colour space at all. Deferred variants do not get it: they
     * write scene-referred G-buffer values and the resolve applies the transform once, later.
     */
    @Nullable
    public static synchronized RenderPipeline forwardVariantOf(RenderPipeline base, GeometrySlot slot) {
        RenderPipeline cached = FORWARD_CACHE.get(base);
        if (cached != null) {
            return cached;
        }
        RenderPipeline variant = buildForward(base, slot);
        // Only successes are cached, for the same reason deferredVariantOf does it: the commonest
        // failure is a draw landing before the pack's shader sources finish publishing, and caching
        // that null pins the pipeline as unshaded for the whole session.
        if (variant != null) {
            FORWARD_CACHE.put(base, variant);
        }
        return variant;
    }

    /** Drops every cached variant, so the next draw rebuilds against the newly active pack. */
    public static synchronized void invalidate() {
        CACHE.clear();
        FORWARD_CACHE.clear();
        SHADOW_CACHE.clear();
        SHADOW_WORLD_SPACE_CACHE.clear();
        NO_GBUFFER_REPORTED.clear();
        SEEN.clear();
        DEFERRED_PASS_REPORTED.clear();
        NO_PROGRAM_REPORTED.clear();
        SHADOW_MISS_REPORTED.clear();
        SHADOW_DREW_REPORTED.clear();
        SHADOW_SKIPPED_REPORTED.clear();
        FORWARD_REPORTED.clear();
        FORWARD_DECLINED_REPORTED.clear();
        SlotReachabilityCensus.reset();
    }

    private static final Map<RenderPipeline, RenderPipeline> FORWARD_CACHE = new IdentityHashMap<>();

    private static final Map<RenderPipeline, Boolean> NO_GBUFFER_REPORTED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> SEEN = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> SHADOW_MISS_REPORTED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> SHADOW_DREW_REPORTED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> SHADOW_SKIPPED_REPORTED = new IdentityHashMap<>();
    private static boolean shadowPhaseReported;

    /**
     * Raised only while the player is being submitted as a caster, so the draw site can report whether
     * that submission produced any draws at all. "The player made no geometry" and "the player made
     * geometry that landed somewhere wrong" look identical from outside and need opposite fixes.
     */
    /** [TEMPORARY DIAGNOSTIC] Draw the player caster into the G-buffer instead of the shadow map. */
    public static final boolean PLAYER_CAST_TO_GBUFFER = false;

    private static boolean playerCastPhase;
    private static boolean playerDrawReported;

    public static void setPlayerCastPhase(boolean value) {
        playerCastPhase = value;
        if (!value && !playerDrawReported) {
            playerDrawReported = true;
            FornaxMod.LOGGER.info("[Fornax][diag] player caster submitted but produced NO draws");
        }
    }

    /** Called from the draw site; cancels the "no draws" report for this session. */
    public static void notePlayerDraw(RenderPipeline pipeline) {
        if (playerCastPhase && !playerDrawReported) {
            playerDrawReported = true;
            FornaxMod.LOGGER.info("[Fornax][diag] player caster DREW via {}", pipeline.getLocation());
        }
    }
    private static final Map<RenderPipeline, Boolean> NO_PROGRAM_REPORTED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> DEFERRED_PASS_REPORTED = new IdentityHashMap<>();

    /** Logs, once per pipeline, that a draw genuinely received a G-buffer render pass and at what size. */
    public static synchronized void noteDeferredPass(RenderPipeline pipeline, int width, int height) {
        if (DEFERRED_PASS_REPORTED.putIfAbsent(pipeline, Boolean.TRUE) == null) {
            FornaxMod.LOGGER.info("[Fornax][diag] deferred G-buffer pass applied to {} at {}x{}",
                    pipeline.getLocation(), width, height);
        }
    }

    /** Logs, once per pipeline, that a draw actually rendered into the shadow map. */
    public static synchronized void noteShadowDraw(RenderPipeline pipeline) {
        if (SHADOW_DREW_REPORTED.putIfAbsent(pipeline, Boolean.TRUE) == null) {
            FornaxMod.LOGGER.info("[Fornax][diag] shadow caster DREW: {}", pipeline.getLocation());
        }
    }

    /** Logs, once per pipeline, that a draw reached the shadow phase and was skipped as non-casting. */
    public static synchronized void noteShadowSkip(RenderPipeline pipeline) {
        if (SHADOW_SKIPPED_REPORTED.putIfAbsent(pipeline, Boolean.TRUE) == null) {
            FornaxMod.LOGGER.info("[Fornax][diag] shadow phase SKIPPED (no caster): {}", pipeline.getLocation());
        }
    }

    /** Logs each pipeline reaching the draw chokepoint once, so absence from the log is evidence. */
    public static synchronized void notePipelineSeen(RenderPipeline pipeline) {
        GeometrySlot deferred = GeometryPipelineMap.slotOf(pipeline);
        GeometrySlot forward = ForwardPipelineMap.slotOf(pipeline);
        if (deferred != null) {
            SlotReachabilityCensus.noteSlotReached(deferred);
        }
        if (forward != null) {
            SlotReachabilityCensus.noteSlotReached(forward);
        }
        if (SEEN.putIfAbsent(pipeline, Boolean.TRUE) == null) {
            String claim = deferred != null ? deferred.token() + " (deferred)"
                    : forward != null ? forward.token() + " (forward)" : "none";
            FornaxMod.LOGGER.info("[Fornax][diag] draw chokepoint saw pipeline {} (claimed slot: {})",
                    pipeline.getLocation(), claim);
        }
    }

    private static final Map<RenderPipeline, Boolean> FORWARD_REPORTED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> FORWARD_DECLINED_REPORTED = new IdentityHashMap<>();

    /**
     * Logs, once per pipeline, that the forward hook actually substituted the pack's program.
     *
     * <p>Mirrors {@link #noteDeferredPass} for the same reason it exists: "the hook ran and declined"
     * and "the hook never saw this draw" are indistinguishable from outside and have completely
     * different fixes. A forward slot has no G-buffer pass to show up in, so this line is the ONLY
     * evidence that the substitution happened at all.
     */
    public static synchronized void noteForwardSubstitution(RenderPipeline pipeline, GeometrySlot slot) {
        SlotReachabilityCensus.noteSlotSubstituted(slot);
        if (FORWARD_REPORTED.putIfAbsent(pipeline, Boolean.TRUE) == null) {
            FornaxMod.LOGGER.info("[Fornax][diag] forward program substituted on {} for slot '{}'",
                    pipeline.getLocation(), slot.token());
        }
    }

    /** Logs, once per pipeline, that the forward hook saw a claimed draw and declined it, with why. */
    public static synchronized void noteForwardDeclined(RenderPipeline pipeline, GeometrySlot slot, String reason) {
        if (FORWARD_DECLINED_REPORTED.putIfAbsent(pipeline, Boolean.TRUE) == null) {
            FornaxMod.LOGGER.warn("[Fornax][diag] forward hook DECLINED {} for slot '{}': {}",
                    pipeline.getLocation(), slot.token(), reason);
        }
    }

    /**
     * Records that a claimed pipeline was drawn with no G-buffer to defer into, once per pipeline.
     * Without this the case is indistinguishable from an unclaimed slot, which is precisely the
     * confusion to avoid when an entire category of entity is silently missing.
     */
    public static synchronized void noteNoGBuffer(RenderPipeline pipeline, GeometrySlot slot) {
        if (NO_GBUFFER_REPORTED.putIfAbsent(pipeline, Boolean.TRUE) == null) {
            FornaxMod.LOGGER.warn("[Fornax] {} is claimed by slot '{}' but drew with no G-buffer built"
                    + " -- rendering it as vanilla.", pipeline.getLocation(), slot.token());
        }
    }

    /**
     * The shadow-casting variant of {@code base}: depth-only, projected through the light's matrix.
     * {@code null} when the pack ships no program for the shadow-entities slot, in which case those
     * draws are simply skipped during the shadow phase and the entity casts nothing.
     */
    @Nullable
    public static synchronized RenderPipeline shadowVariantOf(RenderPipeline base) {
        return shadowVariantOf(base, false);
    }

    /**
     * @param worldSpaceInput geometry submitted with an identity view rotation, so {@code ModelViewMat}
     *                        already yields a camera-relative WORLD position and must not have a
     *                        camera view undone from it. True only for geometry Fornax submits itself.
     */
    @Nullable
    public static synchronized RenderPipeline shadowVariantOf(RenderPipeline base, boolean worldSpaceInput) {
        Map<RenderPipeline, RenderPipeline> cache = worldSpaceInput ? SHADOW_WORLD_SPACE_CACHE : SHADOW_CACHE;
        RenderPipeline cached = cache.get(base);
        if (cached != null) {
            return cached;
        }
        RenderPipeline variant = buildShadow(base, worldSpaceInput);
        if (variant != null) {
            cache.put(base, variant);
        }
        return variant;
    }

    @Nullable
    private static RenderPipeline buildShadow(RenderPipeline base, boolean worldSpaceInput) {
        Identifier fragment = GeometryProgramSource.replacementIdentifierFor(
                base, com.mojang.blaze3d.shaders.ShaderType.FRAGMENT, GeometrySlot.SHADOW_ENTITIES);
        Identifier vertex = GeometryProgramSource.replacementIdentifierFor(
                base, com.mojang.blaze3d.shaders.ShaderType.VERTEX, GeometrySlot.SHADOW_ENTITIES);
        if (fragment == null || vertex == null) {
            // Both stages are required here, unlike the main deferred variant: vanilla's vertex shader
            // projects through the CAMERA, and a shadow caster must project through the light.
            if (SHADOW_MISS_REPORTED.putIfAbsent(base, Boolean.TRUE) == null) {
                FornaxMod.LOGGER.warn("[Fornax] No shadow-entities program resolved for {} (vsh={}, fsh={})"
                        + " -- entities drawn with it cast no shadow. The pack must declare a geometry pass"
                        + " with slot = \"shadow_entities\" and ship BOTH stages.",
                        base.getLocation(), vertex, fragment);
            }
            return null;
        }

        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("fornax",
                        "pipeline/shadow_" + (worldSpaceInput ? "ws_" : "")
                                + base.getLocation().getPath().replace('/', '_')))
                .withVertexShader(vertex)
                .withFragmentShader(fragment)
                .withCull(base.isCull())
                .withPolygonMode(base.getPolygonMode())
                .withPrimitiveTopology(base.getPrimitiveTopology())
                // Forward-Z, matching the shadow camera's zero-to-one ortho projection. The main
                // camera's reversed-Z GREATER_THAN_OR_EQUAL would reject every real caster against
                // the shadow map's clear value.
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                // One dummy colour target, matching the unread RGBA8 attachment the shadow map keeps
                // so that a zero-colour-target pipeline is never constructed.
                .withColorTargetState(0, new ColorTargetState(
                        java.util.Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));

        if (worldSpaceInput) {
            // Tells the shadow program its input is already camera-relative world space, so it must
            // NOT undo a camera view that was never applied.
            b.withShaderDefine("FORNAX_WORLD_SPACE_INPUT");
        }

        ShaderDefines defines = base.getShaderDefines();
        for (String flag : defines.flags()) {
            b.withShaderDefine(flag);
        }
        for (Map.Entry<String, String> e : defines.values().entrySet()) {
            String raw = e.getValue();
            try {
                if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                    b.withShaderDefine(e.getKey(), Float.parseFloat(raw));
                } else {
                    b.withShaderDefine(e.getKey(), Integer.parseInt(raw));
                }
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
        for (var layout : base.getBindGroupLayouts()) {
            b.withBindGroupLayout(layout);
        }
        b.withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
                .build());

        VertexFormat[] bindings = base.getVertexFormatBindings();
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] != null) {
                b.withVertexBinding(i, bindings[i]);
            }
        }

        try {
            RenderPipeline built = b.build();
            if (!com.mojang.blaze3d.systems.RenderSystem.getDevice().precompilePipeline(built).isValid()) {
                FornaxMod.LOGGER.error("[Fornax] Shadow-caster pipeline for {} failed to compile --"
                        + " entities drawn with it will cast no shadow.", base.getLocation());
                return null;
            }
            FornaxMod.LOGGER.info("[Fornax] Built shadow-caster pipeline {} (from {})",
                    built.getLocation(), base.getLocation());
            return built;
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax] Could not build a shadow-caster pipeline from {}: {}",
                    base.getLocation(), e.toString());
            return null;
        }
    }

    @Nullable
    private static RenderPipeline buildForward(RenderPipeline base, GeometrySlot slot) {
        return build(base, slot, true);
    }

    @Nullable
    private static RenderPipeline build(RenderPipeline base, GeometrySlot slot) {
        return build(base, slot, false);
    }

    @Nullable
    private static RenderPipeline build(RenderPipeline base, GeometrySlot slot, boolean forward) {
        // The EXPLICIT-SLOT overload, always, and it is load-bearing for the forward path. The
        // one-argument form resolves the slot through GeometryPipelineMap, which by construction does
        // NOT contain a forward pipeline (the two tables are disjoint -- see ForwardPipelineMap), so
        // it would return null here and every forward draw would silently stay vanilla. The
        // shadow-caster builder takes the same overload for the same shape of reason.
        Identifier fragment = GeometryProgramSource.replacementIdentifierFor(
                base, com.mojang.blaze3d.shaders.ShaderType.FRAGMENT, slot);
        if (fragment == null) {
            // Logged because this is the one silent way a claimed slot ends up unshaded: every other
            // refusal below is an explicit error, so an unexplained gap here reads as "the hook never
            // ran" when it actually means "the pack program did not resolve".
            // Rate-limited: this path retries rather than caches, so an unclaimed slot would
            // otherwise warn every frame forever.
            if (NO_PROGRAM_REPORTED.putIfAbsent(base, Boolean.TRUE) == null) {
                FornaxMod.LOGGER.warn("[Fornax] No pack fragment program resolved for slot '{}' while building a"
                        + " deferred variant of {} -- leaving it vanilla (will retry).", slot.token(), base.getLocation());
            }
            return null;
        }
        // A pack may ship only the fragment stage; vanilla's vertex shader then still supplies the
        // varyings this fragment stage reads, so the vertex format and bind groups stay in agreement
        // with nothing to keep in sync by hand.
        Identifier vertex = GeometryProgramSource.replacementIdentifierFor(
                base, com.mojang.blaze3d.shaders.ShaderType.VERTEX, slot);

        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("fornax",
                        "pipeline/" + (forward ? "forward_" : "deferred_") + slot.token() + "_"
                                + base.getLocation().getPath().replace('/', '_')))
                .withVertexShader(vertex != null ? vertex : base.getVertexShader())
                .withFragmentShader(fragment)
                .withCull(base.isCull())
                .withPolygonMode(base.getPolygonMode())
                .withPrimitiveTopology(base.getPrimitiveTopology());

        // Depth state is copied, never synthesised: several vanilla pipelines depth-test EQUAL or
        // disable depth writes, and a variant that disagreed with its base would z-fight or vanish.
        if (base.getDepthStencilState() != null) {
            b.withDepthStencilState(base.getDepthStencilState());
        }

        // Defines carry the pipeline's variant behaviour (ALPHA_CUTOUT thresholds, EMISSIVE,
        // NO_OVERLAY, PER_FACE_LIGHTING, DISSOLVE); the pack's fragment shader branches on exactly
        // these, so the variant has to reproduce them precisely.
        ShaderDefines defines = base.getShaderDefines();
        for (String flag : defines.flags()) {
            b.withShaderDefine(flag);
        }
        for (Map.Entry<String, String> e : defines.values().entrySet()) {
            // ShaderDefines stores values as strings but the builder only takes int or float, so the
            // value has to be parsed back. The int/float distinction is load-bearing rather than
            // cosmetic: it decides whether the generated directive reads `#define ALPHA_CUTOUT 0` or
            // `#define ALPHA_CUTOUT 0.1`, i.e. whether alpha testing happens at all.
            String raw = e.getValue();
            try {
                if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                    b.withShaderDefine(e.getKey(), Float.parseFloat(raw));
                } else {
                    b.withShaderDefine(e.getKey(), Integer.parseInt(raw));
                }
            } catch (NumberFormatException nfe) {
                // Abandon the variant rather than build one missing a define. A dropped ALPHA_CUTOUT
                // or EMISSIVE renders confidently and wrongly, which is far worse to debug than
                // simply keeping vanilla's pipeline and saying so.
                FornaxMod.LOGGER.error("[Fornax] Cannot reproduce shader define {}={} from {} as int or"
                        + " float -- keeping vanilla's pipeline for slot '{}' rather than building a"
                        + " variant that silently drops it.", e.getKey(), raw, base.getLocation(), slot.token());
                return null;
            }
        }

        for (var layout : base.getBindGroupLayouts()) {
            b.withBindGroupLayout(layout);
        }

        // Fornax's per-frame uniforms, appended to whatever bind groups the vanilla pipeline already
        // carries. Without this a slot program can see only what vanilla hands its own shaders --
        // enough to texture geometry, but not enough to participate in deferred rendering: no
        // previous-frame matrices (so no motion vectors, so entities smear under TAA), no sun
        // direction, no sky or water state.
        //
        // Contract, and the reason this is unconditional: a geometry slot program MUST declare the
        // block via #moj_import <fornax:globals.glsl>. Declaring the layout without the shader using
        // it, or vice versa, is a bind-group mismatch -- so this is a requirement of the slot rather
        // than something detected per pack.
        // A forward program composites into an ALREADY-TONEMAPPED target, so it has to reproduce
            // the pack's own display transform to land in the same colour space as the pixels it is
            // blending with -- and exposure, tonemap contrast and the grade are all RUNTIME options.
            // A deferred program never needs this: it writes scene-referred G-buffer values and the
            // resolve applies the transform once, downstream.
            //
            // Declared unconditionally for the same reason u_Globals is: declaring the layout without
            // the shader using it, or the reverse, is a bind-group mismatch. It is a requirement of a
            // forward slot rather than something detected per pack. Blaze3D's dev-only validation
            // (GlRenderPass.VALIDATION) throws on a DECLARED uniform that was never set, so the draw
        // site must bind both every time -- it does.
        b.withBindGroupLayout(labPbrBindGroupLayout(slot, forward));

        VertexFormat[] bindings = base.getVertexFormatBindings();
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] != null) {
                b.withVertexBinding(i, bindings[i]);
            }
        }

        if (forward) {
            // THE ONE LINE THAT MAKES IT FORWARD. Copied verbatim, never synthesised: blend function,
            // format and write mask arrive together and so cannot be individually got wrong. Writing
            // a ColorTargetState by hand would mean naming BANNER_PATTERN's exact blend in Fornax's
            // source, which is a fact about vanilla that would rot silently across versions -- and a
            // dropped blend on pattern layers is opaque rectangles over the cloth, the same class of
            // regression SmokeParticleLayerMixin exists to prevent.
            ColorTargetState[] targets = base.getColorTargetStates();
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null) {
                    b.withColorTargetState(i, targets[i]);
                }
            }
        } else {
            // The actual point of the deferred variant. Note there is no BlendFunction on any target:
            // RenderPipeline.build() requires every non-null colour target to share one, and a blended
            // G-buffer would average normals and material IDs into meaningless values rather than
            // compositing anything. Blended base pipelines DO reach here -- a player's skin renders on
            // ENTITY_TRANSLUCENT and is effectively cutout -- so dropping the blend is correct for
            // them. GeometryPipelineMap decides which blended pipelines are eligible; genuinely
            // see-through geometry is left unmapped, and lands in ForwardPipelineMap instead.
            for (int i = 0; i < GBUFFER_FORMATS.length; i++) {
                b.withColorTargetState(i, new ColorTargetState(java.util.Optional.empty(), GBUFFER_FORMATS[i], ColorTargetState.WRITE_ALL));
            }
        }

        try {
            RenderPipeline built = b.build();
            // Compile now rather than at first draw, and verify: this is the only substitution path
            // for a claimed slot, so nothing else is left to catch a pack whose GLSL does not build.
            // An invalid pipeline reaching a draw renders garbage or nothing at all, with the cause
            // far from the symptom -- returning null instead keeps vanilla's own pipeline, which is
            // wrong-looking but correct and legible.
            if (!com.mojang.blaze3d.systems.RenderSystem.getDevice().precompilePipeline(built).isValid()) {
                FornaxMod.LOGGER.error("[Fornax] {} pipeline for slot '{}' (from {}) failed to compile"
                        + " -- keeping vanilla's pipeline. The pack's GLSL error is logged above.",
                        forward ? "Forward" : "Deferred", slot.token(), base.getLocation());
                return null;
            }
            FornaxMod.LOGGER.info("[Fornax] Built {} pipeline {} for slot '{}' (from {})",
                    forward ? "forward" : "deferred", built.getLocation(), slot.token(), base.getLocation());
            return built;
        } catch (RuntimeException e) {
            // build() enforces its own invariants (matching blend functions, attribute count limits).
            // A pack that trips one must not take the frame down -- keep vanilla's pipeline instead.
            FornaxMod.LOGGER.error("[Fornax] Could not build a {} pipeline for slot '{}' from {}:"
                    + " {} -- keeping vanilla's pipeline for it.",
                    forward ? "forward" : "deferred", slot.token(), base.getLocation(), e.toString());
            return null;
        }
    }

    /** Executable layout seam used by both pipeline construction and the eligibility contract. */
    static BindGroupLayout labPbrBindGroupLayout(GeometrySlot slot, boolean forward) {
        BindGroupLayout.Builder layout = BindGroupLayout.builder()
                .withUniform("u_Globals", UniformType.UNIFORM_BUFFER);
        if (!forward && dev.icehunter.fornax.atlas.LabPbrGeometryBindings
                .hasLabPbrSidecars(slot)) {
            layout.withSampler("u_NormalTex");
            layout.withSampler("u_MaterialTex");
        }
        if (forward) {
            layout.withUniform("u_PackOptions", UniformType.UNIFORM_BUFFER);
        }
        return layout.build();
    }
}
